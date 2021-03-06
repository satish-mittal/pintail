package com.inmobi.messaging.consumer.databus;

/*
 * #%L
 * messaging-client-databus
 * %%
 * Copyright (C) 2012 - 2014 InMobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.inmobi.databus.partition.PartitionCheckpoint;
import com.inmobi.databus.partition.PartitionCheckpointList;
import com.inmobi.databus.partition.PartitionId;
import com.inmobi.databus.partition.PartitionReader;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.consumer.databus.mapred.DatabusInputFormat;
import com.inmobi.messaging.consumer.util.DatabusUtil;
import com.inmobi.messaging.metrics.CollectorReaderStatsExposer;
import com.inmobi.messaging.metrics.PartitionReaderStatsExposer;

/**
 * Consumes data from the configured databus stream topic.
 *
 * Initializes the databus configuration from the configuration file specified
 * by the configuration {@value DatabusConsumerConfig#databusConfigFileKey},
 * the default value is
 * {@value DatabusConsumerConfig#DEFAULT_DATABUS_CONFIG_FILE}
 *
 * Consumer can specify a comma separated list of clusters from which the stream
 * should be streamed via configuration
 * {@value DatabusConsumerConfig#databusClustersConfig}. If no
 * such configuration exists, it will stream from all the source clusters of the
 * stream.
 *
 * This consumer supports mark and reset. Whenever user calls mark, the current
 * consumption will be check-pointed in a directory configurable via
 * {@value DatabusConsumerConfig#checkpointDirConfig}. The default value for
 * value for checkpoint
 * directory is {@value DatabusConsumerConfig#DEFAULT_CHECKPOINT_DIR}. After
 * reset(), consumer will start reading
 * messages from last check-pointed position.
 *
 * Maximum consumer buffer size is configurable via
 * {@value DatabusConsumerConfig#queueSizeConfig}.
 * The default value is {@value DatabusConsumerConfig#DEFAULT_QUEUE_SIZE}.
 *
 * If consumer is reading from the file that is currently being written by
 * producer, consumer will wait for flush to happen on the file. The wait time
 * for flush is configurable via
 * {@value DatabusConsumerConfig#waitTimeForFlushConfig}, and default
 * value is {@value DatabusConsumerConfig#DEFAULT_WAIT_TIME_FOR_FLUSH}
 *
 * Initializes partition readers for each active collector on the stream.
 * TODO: Dynamically detect if new collectors are added and start readers for
 *  them
 */
public class DatabusConsumer extends AbstractMessagingDatabusConsumer
    implements DatabusConsumerConfig {
  private static final Log LOG = LogFactory.getLog(DatabusConsumer.class);

  private long waitTimeForFlush;
  private Path[] rootDirs;
  private StreamType streamType;
  public static String clusterNamePrefix = "databusCluster";
  private Boolean readFromLocalStream;
  private int numList = 0;

  protected void initializeConfig(ClientConfig config) throws IOException {
    String type = config.getString(databusStreamType, DEFAULT_STREAM_TYPE);
    streamType = StreamType.valueOf(type);
    super.initializeConfig(config);
    waitTimeForFlush = config.getLong(waitTimeForFlushConfig,
        DEFAULT_WAIT_TIME_FOR_FLUSH);
    String rootDirsStr = config.getString(databusRootDirsConfig);
    readFromLocalStream = config.getBoolean(readFromLocalStreamConfig,
        DEFAULT_READ_LOCAL_STREAM);
    String[] rootDirSplits;
    if (rootDirsStr != null) {
      rootDirSplits = rootDirsStr.split(",");
    } else {
      throw new IllegalArgumentException("Databus root directory not specified");
    }
    clusterNames = new String[rootDirSplits.length];
    rootDirs = new Path[rootDirSplits.length];
    for (int i = 0; i < rootDirSplits.length; i++) {
      rootDirs[i] = new Path(rootDirSplits[i]);
      clusterNames[i] = getDefaultClusterName(i);
    }
    if (streamType.equals(StreamType.MERGED)) {
      if (rootDirs.length > 1) {
        throw new IllegalArgumentException("Multiple directories are not"
            + " allowed for merge stream");
      }
    }
    /*
     * Parse the clusterNames config string and
     * migrate to new checkpoint if required
     */
    if (streamType.equals(StreamType.COLLECTOR)) {
      getClusterNames(config, rootDirSplits);
    } else {
      parseClusterNamesAndMigrateCheckpoint(config, rootDirSplits);
    }
    LOG.info("Databus consumer initialized with streamName:" + topicName
        + " consumerName:" + consumerName + " startTime:" + startTime
        + " queueSize:" + bufferSize + " checkPoint:" + currentCheckpoint
        + " streamType:" + streamType);
  }

  private void getClusterNames(ClientConfig config, String[] rootDirSplits) {
    String clusterNameStr = config.getString(clustersNameConfig);
    if (clusterNameStr != null) {
      String [] clusterNameStrs = clusterNameStr.split(",");
      if (clusterNameStrs.length != rootDirSplits.length) {
        throw new IllegalArgumentException("Cluster names were not specified for all root dirs."
            + " Mismatch between number of root dirs and number of user specified cluster names");
      }
      for (int i = 0; i < clusterNameStrs.length; i++) {
        clusterNames[i] = clusterNameStrs[i];
      }
    } else {
      LOG.info("using default cluster names as clustersName config is missing");
    }
  }

  private List<String> getCollectors(FileSystem fs, Path baseDir)
      throws IOException {
    List<String> collectors = new ArrayList<String>();
    LOG.debug("Stream dir: " + baseDir);
    FileStatus[] list = fs.listStatus(baseDir);
    numList++;
    if (list != null && list.length > 0) {
      for (FileStatus status : list) {
        collectors.add(status.getPath().getName());
      }
    } else {
      LOG.warn("No collector dirs available in " + baseDir);
    }
    return collectors;
  }

  protected void createPartitionReaders() throws IOException {
    for (int i = 0; i < rootDirs.length; i++) {
      LOG.debug("Creating partition readers for rootDir:" + rootDirs[i]);
      FileSystem fs = rootDirs[i].getFileSystem(conf);
      String fsuri = fs.getUri().toString();
      Path streamDir = DatabusUtil.getStreamDir(streamType, rootDirs[i],
          topicName);
      String clusterName;
      if (clusterNames != null) {
        clusterName = clusterNames[i];
      } else {
        clusterName = getDefaultClusterName(i);
      }
      if (streamType.equals(StreamType.COLLECTOR)) {
        Map<PartitionId, PartitionCheckpoint> partitionsChkPoints =
            ((Checkpoint) currentCheckpoint).getPartitionsCheckpoint();
        LOG.info("Creating partition readers for all the collectors");
        for (String collector : getCollectors(fs, streamDir)) {
          PartitionId id = new PartitionId(clusterName, collector);
          PartitionCheckpoint pck = partitionsChkPoints.get(id);
          /*
           * Migration of checkpoint required in this case
           * If user provides a cluster name and partition checkpoint is null
           */
          if (!clusterName.equals(getDefaultClusterName(i)) && pck == null) {
            PartitionId defaultPid = new PartitionId(getDefaultClusterName(i),
                collector);
            pck = partitionsChkPoints.get(defaultPid);
            /*
             * Migrate to new checkpoint
             */
            ((Checkpoint) currentCheckpoint).migrateCheckpoint(pck, defaultPid, id);
          }
          Date partitionTimestamp = getPartitionTimestamp(id, pck);
          LOG.debug("Creating partition " + id);
          PartitionReaderStatsExposer collectorMetrics = new
              CollectorReaderStatsExposer(topicName, consumerName,
                  id.toString(), consumerNumber, fsuri);
          addStatsExposer(collectorMetrics);
          Path streamsLocalDir = null;
          if (readFromLocalStream) {
            streamsLocalDir = DatabusUtil.getStreamDir(StreamType.LOCAL,
                rootDirs[i], topicName);
          }
          for (int c = 0; c < numList; c++) {
            collectorMetrics.incrementListOps();
          }
          readers.put(id, new PartitionReader(id, pck, conf, fs,
              new Path(streamDir, collector), streamsLocalDir, buffer, topicName,
              partitionTimestamp, waitTimeForFlush, waitTimeForFileCreate,
              collectorMetrics, stopTime));
          messageConsumedMap.put(id, false);
          numList = 0;
        }
      } else {
        LOG.info("Creating partition reader for cluster");
        PartitionId id = new PartitionId(clusterName, null);
        PartitionCheckpointList partitionCheckpointList = 
        ((CheckpointList) currentCheckpoint).preaprePartitionCheckPointList(id);
        Date partitionTimestamp = getPartitionTimestamp(id,
            partitionCheckpointList);
        LOG.debug("Creating partition " + id);
        PartitionReaderStatsExposer clusterMetrics =
            new PartitionReaderStatsExposer(topicName, consumerName,
                id.toString(), consumerNumber, fsuri);
        addStatsExposer(clusterMetrics);
        readers.put(id, new PartitionReader(id,
            partitionCheckpointList, fs, buffer, streamDir, conf,
            DatabusInputFormat.class.getCanonicalName(), partitionTimestamp,
            waitTimeForFileCreate, true, clusterMetrics, partitionMinList,
            stopTime));
        messageConsumedMap.put(id, false);
      }
    }
  }

  private String getDefaultClusterName(int i) {
    return clusterNamePrefix + i;
  }

  Path[] getRootDirs() {
    return rootDirs;
  }

  @Override
  protected void createCheckpoint() {
    if (streamType.equals(StreamType.COLLECTOR)) {
      currentCheckpoint = new Checkpoint();
    } else {
      currentCheckpoint = new CheckpointList(partitionMinList);
    }
  }
}
