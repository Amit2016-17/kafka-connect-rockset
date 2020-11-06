package rockset;

import static com.google.common.base.Preconditions.checkArgument;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.avro.AvroData;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface RecordParser {

  Map<String, Object> parseValue(SinkRecord record);

  /**
   * Parse key from a sink record.
   * If key is struct type convert to Java Map type
   * else return what is in the key
   */
  default Object parseKey(SinkRecord record) throws IOException {
    if (record.key() instanceof Struct) {
      AvroData keyData = new AvroData(1);
      Object key = keyData.fromConnectData(record.keySchema(), record.key());
      // For struct types convert to a Java Map object
      return toMap(key);
    }
    return record.key();
  }

  static Object toMap(Object key) throws IOException {
    return new ObjectMapper().readValue(key.toString(), new TypeReference<Map<String, Object>>() {});
  }
}

class JsonParser implements RecordParser {
  private static Logger log = LoggerFactory.getLogger(JsonParser.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  @Override
  public Map<String, Object> parseValue(SinkRecord record) {
    Exception cause;
    // First try to deserialize as map
    try {
      return toMap(record.value());
    } catch (Exception e) {
      cause = e;
    }

    // Then try to deserialize as list
    List<Map<String, Object>> maps;
    try {
      maps = toMapList(record.value());
    } catch (Exception e) {

      // Could not deserialize to as map and list. Throw
      String name = record.value() == null ? "null" : record.value().getClass().getName();
      String message = String.format("Cannot deserialize the record of type %s as Map or List", name);
      log.warn(message, cause);
      throw new RuntimeException(message, e);
    }

    int size = maps.size();
    checkArgument(size == 1, "Only 1 object allowed in list type messages. Found %s", size);
    return maps.get(0);
  }

  private static Map<String, Object> toMap(Object value) throws IOException {
    return mapper.readValue(value.toString(), new TypeReference<Map<String, Object>>() {});
  }

  private List<Map<String, Object>> toMapList(Object value) throws IOException {
    return mapper.readValue(value.toString(), new TypeReference<List<Map<String, Object>>>() {});
  }
}
