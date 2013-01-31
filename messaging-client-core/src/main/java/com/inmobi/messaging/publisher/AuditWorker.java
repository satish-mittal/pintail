package com.inmobi.messaging.publisher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.inmobi.audit.thrift.AuditMessage;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;

class AuditWorker implements Runnable {

  private String hostname;
  private String tier;
  private int windowSizeInMins;
  private static final String AUDIT_STREAM_TOPIC_NAME = "audit";
  private ClientConfig config;
  private AbstractMessagePublisher publisher;
  private static final Logger LOG = LoggerFactory.getLogger(AuditWorker.class);
  private final TSerializer serializer = new TSerializer();

  AuditWorker(String hostname, String tier, int windowSizeInMins,
      ClientConfig config) {
    this.hostname = hostname;
    this.tier = tier;
    this.windowSizeInMins = windowSizeInMins;
    this.config = config;
  }

  @Override
  public void run() {
    try {
      LOG.info("Running the AuditWorker");
      for (Entry<String, AuditCounterAccumulator> entry : AuditService.topicAccumulatorMap
          .entrySet()) {
        String topic = entry.getKey();
        AuditCounterAccumulator accumulator = entry.getValue();
        Map<Long, AtomicLong> received = accumulator.getReceived();
        Map<Long, AtomicLong> sent = accumulator.getSent();
        accumulator.reset(); // resetting before creating packet to make sure
                             // that during creation of packet no more writes
                             // should occur to previous counters
        AuditMessage packet = createPacket(topic, received, sent);
        publishPacket(packet);

      }
    } catch (Throwable e) {// catching general exception so that thread should
                           // not get aborted
      LOG.error("Error while publishing the audit message", e);
    }

  }

  void close() {
    if (publisher != null) {
      publisher.close();
    }
  }

  private void publishPacket(AuditMessage packet) {
    if (publisher == null) {
      try {
        publisher = (AbstractMessagePublisher) MessagePublisherFactory
            .create(config);
      } catch (IOException e) {
        LOG.error(
            "Cannot create publisher to publish audit package;Audit packet would be dropped",
            e);
        return;
      }
    }

    try {
        LOG.debug("Publishing audit packet" + packet);
        publisher.publish(AUDIT_STREAM_TOPIC_NAME,
            new Message(ByteBuffer.wrap(serializer.serialize(packet))));
      } catch (TException e) {
      LOG.error("Error while serializing the audit packet " + packet, e);
      }
  }

  private AuditMessage createPacket(String topic,
      Map<Long, AtomicLong> received, Map<Long, AtomicLong> sent) {
    Map<Long, Long> finalReceived = new HashMap<Long, Long>();
    Map<Long, Long> finalSent = new HashMap<Long, Long>();

    // TODO find a better way of converting Map<Long,AtomicLong> to
    // Map<Long,Long>;if any
    for (Entry<Long, AtomicLong> entry : received.entrySet()) {
      finalReceived.put(entry.getKey(), entry.getValue().get());
    }

    for (Entry<Long, AtomicLong> entry : sent.entrySet()) {
      finalSent.put(entry.getKey(), entry.getValue().get());
    }
    long currentTime = new Date().getTime();
    System.out.println("Generating audit packet at " + currentTime);
    AuditMessage packet = new AuditMessage(currentTime, topic,
        tier, hostname, windowSizeInMins, finalReceived, finalSent);
    return packet;
  }

}
