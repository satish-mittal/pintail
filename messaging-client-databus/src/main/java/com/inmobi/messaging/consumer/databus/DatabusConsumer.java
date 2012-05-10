package com.inmobi.messaging.consumer.databus;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.inmobi.databus.CheckpointProvider;
import com.inmobi.databus.Cluster;
import com.inmobi.databus.DatabusConfig;
import com.inmobi.databus.DatabusConfigParser;
import com.inmobi.databus.FSCheckpointProvider;
import com.inmobi.databus.SourceStream;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;
import com.inmobi.messaging.consumer.AbstractMessageConsumer;

/**
 * Consumes data from the configured databus stream topic.
 * 
 * Max consumer buffer size is configurable via databus.consumer.buffer.size. 
 * The default value is 1000.
 *
 * Initializes partition readers for each active collector on the stream.
 * TODO: Dynamically detect if new collectors are added and start readers for
 *  them 
 */
public class DatabusConsumer extends AbstractMessageConsumer {
  private static final Log LOG = LogFactory.getLog(DatabusConsumer.class);


  public static final String DEFAULT_CHK_PROVIDER = FSCheckpointProvider.class
      .getName();
  public static final int DEFAULT_QUEUE_SIZE = 1000;
  public static final long DEFAULT_WAIT_TIME_FOR_FLUSH = 1000; // 1 second

  private DatabusConfig databusConfig;
  private BlockingQueue<QueueEntry> buffer;
  private String databusCheckpointDir;

  private final Map<PartitionId, PartitionReader> readers = 
      new HashMap<PartitionId, PartitionReader>();

  private CheckpointProvider checkpointProvider;
  private Checkpoint currentCheckpoint;
  private long waitTimeForFlush;
  private int bufferSize;

  @Override
  protected void init(ClientConfig config) {
    super.init(config);
    initializeConfig(config);
    start();
  }

  void initializeConfig(ClientConfig config) {
    bufferSize = config.getInteger("databus.consumer.buffer.size",
        DEFAULT_QUEUE_SIZE);
    buffer = new LinkedBlockingQueue<QueueEntry>(bufferSize);
    databusCheckpointDir = config.getString("databus.checkpoint.dir", ".");
    waitTimeForFlush = config.getLong("databus.stream.waittimeforflush",
        DEFAULT_WAIT_TIME_FOR_FLUSH);
    this.checkpointProvider = new FSCheckpointProvider(databusCheckpointDir);

    try {
      byte[] chkpointData = checkpointProvider.read(getChkpointKey());
      if (chkpointData != null) {
        this.currentCheckpoint = new Checkpoint(chkpointData);
      } else {
        Map<PartitionId, PartitionCheckpoint> partitionsChkPoints = 
            new HashMap<PartitionId, PartitionCheckpoint>();
        this.currentCheckpoint = new Checkpoint(partitionsChkPoints);
      }
      DatabusConfigParser parser = new DatabusConfigParser(null);
      databusConfig = parser.getConfig();
    } catch (Exception e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }
    LOG.info("Databus consumer initialized with streamName:" + topicName +
        " consumerName:" + consumerName + " startTime:" + startTime +
        " queueSize:" + bufferSize + " checkPoint:" + currentCheckpoint);
  }

  Map<PartitionId, PartitionReader> getPartitionReaders() {
    return readers;
  }

  Checkpoint getCurrentCheckpoint() {
    return currentCheckpoint;
  }

  DatabusConfig getDatabusConfig() {
    return databusConfig;
  }

  CheckpointProvider getCheckpointProvider() {
    return checkpointProvider; 
  }

  int getBufferSize() {
    return bufferSize;
  }

  @Override
  public synchronized Message next() {
    QueueEntry entry;
    try {
      entry = buffer.take();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    currentCheckpoint.set(entry.partitionId, entry.partitionChkpoint);
    return entry.message;
  }

  private synchronized void start() {
    createPartitionReaders();
    for (PartitionReader reader : readers.values()) {
      reader.start();
    }
  }

  private void createPartitionReaders() {
    Map<PartitionId, PartitionCheckpoint> partitionsChkPoints = 
        currentCheckpoint.getPartitionsCheckpoint();
    SourceStream sourceStream = databusConfig.getSourceStreams().get(topicName);
    LOG.debug("Stream name: " + sourceStream.getName());
    for (String c : sourceStream.getSourceClusters()) {
      Cluster cluster = databusConfig.getClusters().get(c);
      try {
        FileSystem fs = FileSystem.get(cluster.getHadoopConf());
        Path path = new Path(cluster.getDataDir(), topicName);
        LOG.debug("Stream dir: " + path);
        FileStatus[] list = fs.listStatus(path);
        if (list == null || list.length == 0) {
          LOG.warn("No collector dirs available in stream directory");
          return;
        }
        for (FileStatus status : list) {
          String collector = status.getPath().getName();
          LOG.debug("Collector is " + collector);
          PartitionId id = new PartitionId(cluster.getName(), collector);
          if (partitionsChkPoints.get(id) == null) {
            partitionsChkPoints.put(id, null);
          }
          PartitionReader reader = new PartitionReader(id,
              partitionsChkPoints.get(id), cluster, buffer, topicName,
              startTime, waitTimeForFlush);
          readers.put(id, reader);
          LOG.info("Created partition " + id);
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private String getChkpointKey() {
    return consumerName + "_" + topicName;
  }

  @Override
  public synchronized void reset() {
    // restart the service, consumer will start streaming from the last saved
    // checkpoint
    close();
    try {
      this.currentCheckpoint = new Checkpoint(
          checkpointProvider.read(getChkpointKey()));
      LOG.info("Resetting to checkpoint:" + currentCheckpoint);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    // reset to last marked position, ignore start time
    startTime = null;
    start();
  }

  @Override
  public synchronized void mark() {
    try {
      checkpointProvider.checkpoint(getChkpointKey(),
          currentCheckpoint.toBytes());
      LOG.info("Committed checkpoint:" + currentCheckpoint);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized void close() {
    for (PartitionReader reader : readers.values()) {
      reader.close();
    }
    readers.clear();
    buffer.clear();
    buffer = new LinkedBlockingQueue<QueueEntry>(bufferSize);
  }

  @Override
  public boolean isMarkSupported() {
    return true;
  }

}
