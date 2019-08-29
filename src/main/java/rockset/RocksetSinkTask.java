package rockset;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.jcustenborder.kafka.connect.utils.VersionUtil;

public class RocksetSinkTask extends SinkTask {
  private static Logger log = LoggerFactory.getLogger(RocksetSinkTask.class);
  private RocksetClientWrapper rc;
  private ExecutorService executorService;
  private Map<TopicPartition, List<CompletableFuture>> futureMap;
  private RocksetConnectorConfig config;
  private RecordParser recordParser;


  public static final int RETRIES_COUNT = 5;
  public static final int INITIAL_DELAY = 250;

  private RecordParser getRecordParser(String format) {
    switch (format) {
      case "json":
        return new JsonParser();
      case "avro":
        return new AvroParser();
      default:
        throw new ConnectException(String.format("Format %s not supported", format));
    }
  }

  @Override
  public void start(Map<String, String> settings) {
    this.config = new RocksetConnectorConfig(settings);
    this.rc = new RocksetClientWrapper(
        this.config.getRocksetApikey(),
        this.config.getRocksetApiServerUrl());
    this.executorService = Executors.newFixedThreadPool(this.config.getRocksetTaskThreads());
    this.futureMap = new HashMap<>();
    this.recordParser = getRecordParser(config.getFormat());

  }

  // used for testing
  public void start(Map<String, String> settings, RocksetClientWrapper rc,
                    ExecutorService executorService) {
    this.config = new RocksetConnectorConfig(settings);
    this.rc = rc;
    this.executorService = executorService;
    this.futureMap = new HashMap<>();
    this.recordParser = getRecordParser(config.getFormat());
  }

  @Override
  public void put(Collection<SinkRecord> records) {
    String collection = this.config.getRocksetCollection();
    String workspace = this.config.getRocksetWorkspace();
    handleRecords(records, workspace, collection);
  }

  private void handleRecords(Collection<SinkRecord> records,
                             String workspace, String collection) {
    log.debug("Adding {} documents to collection {} in workspace {}",
        records.size(), collection, workspace);
    if (records.size() == 0) {
      return;
    }

    submitForProcessing(workspace, collection, records);
  }

  private Map<TopicPartition, Collection<SinkRecord>> partitionRecordsByTopic(
      Collection<SinkRecord> records) {
    Map<TopicPartition, Collection<SinkRecord>> topicPartitionedRecords = new HashMap<>();
    for (SinkRecord record: records) {
      TopicPartition key = new TopicPartition(record.topic(), record.kafkaPartition());
      topicPartitionedRecords.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
    }

    return topicPartitionedRecords;
  }

  private void submitForProcessing(String workspace,  String collection,
                                   Collection<SinkRecord> records) {

    Map<TopicPartition, Collection<SinkRecord>> partitionedRecords =
        partitionRecordsByTopic(records);

    for (Map.Entry<TopicPartition, Collection<SinkRecord>> tpe : partitionedRecords.entrySet()) {
      TopicPartition tp = tpe.getKey();
      checkForFailures(tp, false);
      futureMap.computeIfAbsent(tp, k -> new ArrayList<>()).add(
          addWithRetries(workspace, collection, tpe.getValue()));
    }
  }

  private boolean isRetriableException(Throwable e) {
   return (e.getCause() != null && e.getCause() instanceof RetriableException);
  }

  private void checkForFailures(TopicPartition tp, boolean wait) {
    if (futureMap.get(tp) == null) {
      return;
    }

    List<CompletableFuture> futures = futureMap.get(tp);
    Iterator<CompletableFuture> futureIterator = futures.iterator();
    while (futureIterator.hasNext()) {
      CompletableFuture future = futureIterator.next();
      // this is blocking only if wait is true
      if (wait || future.isDone()) {
        try {
          future.get();
        } catch (Exception e) {
          if (isRetriableException(e)) {
            throw new RetriableException(
                String.format("Unable to write document for topic: %s, partition: %s, in Rockset,"
                    + " should retry, cause: %s", tp.topic(), tp.partition(), e.getMessage()), e);
          }

          throw new RuntimeException(
              String.format("Unable to write document for topic: %s, partition: %s, in Rockset,"
                  + " cause: %s", tp.topic(), tp.partition(), e.getMessage()), e);
        } finally {
          futureIterator.remove();
        }
      }
    }
  }

  // TODO improve this logic
  private CompletableFuture addWithRetries(String workspace, String collection,
                              Collection<SinkRecord> records) {
    return CompletableFuture.runAsync(() -> {
      boolean success = this.rc.addDoc(workspace, collection, records, recordParser);
      int retries = 0;
      int delay = INITIAL_DELAY;
      while (!success && retries < RETRIES_COUNT) {
        try {
          Thread.sleep(delay);
        }
        catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        // addDoc throws ConnectException if it's not Internal Error
        success = this.rc.addDoc(workspace, collection, records, recordParser);
        retries += 1;
        delay *= 2;
      }
      if (!success) {
        throw new RetriableException(String.format("Add document request timed out "
            + "for document with collection %s and workspace %s", collection, workspace));
      }
    }, executorService);
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> map) {
    for (Map.Entry<TopicPartition, OffsetAndMetadata> toe : map.entrySet()) {
      checkForFailures(toe.getKey(), true);
    }
  }

  @Override
  public void stop() {
    executorService.shutdown();
  }

  @Override
  public String version() {
    return VersionUtil.version(this.getClass());
  }
}
