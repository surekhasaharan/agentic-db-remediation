package adr.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Two strict mappers. Strictness is the second net: unknown properties and null primitives are refused
 * here, while missing, null and blank values are refused by explicit code in each record's constructor.
 */
public final class Json {
  private Json() {}

  /** snake_case: API, events, recordings, tool arguments, decisions. */
  public static final ObjectMapper SNAKE = build(true);
  /** camelCase: seed files. */
  public static final ObjectMapper CAMEL = build(false);

  private static ObjectMapper build(boolean snake) {
    JsonMapper.Builder b = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    if (snake) b.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    return b.build();
  }

  /** Reads with the mapper and unwraps a constructor's InvalidInput so callers always see the same type. */
  public static <T> T read(ObjectMapper m, String json, Class<T> type) {
    try {
      return m.readValue(json, type);
    } catch (ValueInstantiationException e) {
      throw unwrap(e);
    } catch (IOException e) {
      throw new InvalidInput("$", "malformed JSON: " + brief(e.getMessage()));
    }
  }

  public static <T> T read(ObjectMapper m, InputStream in, Class<T> type) {
    try {
      return m.readValue(in, type);
    } catch (ValueInstantiationException e) {
      throw unwrap(e);
    } catch (IOException e) {
      throw new InvalidInput("$", "malformed JSON: " + brief(e.getMessage()));
    }
  }

  public static <T> T convert(ObjectMapper m, Object tree, Class<T> type) {
    try {
      return m.convertValue(tree, type);
    } catch (IllegalArgumentException e) {
      if (e.getCause() instanceof ValueInstantiationException v) throw unwrap(v);
      throw new InvalidInput("$", brief(e.getMessage()));
    }
  }

  public static String write(ObjectMapper m, Object o) {
    try {
      return m.writeValueAsString(o);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static RuntimeException unwrap(ValueInstantiationException e) {
    Throwable c = e.getCause();
    if (c instanceof InvalidInput ii) return ii;
    return new InvalidInput("$", brief(e.getOriginalMessage()));
  }

  private static String brief(String s) {
    if (s == null) return "invalid";
    int nl = s.indexOf('\n');
    String one = nl > 0 ? s.substring(0, nl) : s;
    return one.length() > 160 ? one.substring(0, 160) : one;
  }
}
