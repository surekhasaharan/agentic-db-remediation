package adr.broker;

import adr.domain.Json;

/** Strict Jackson, then the record's own explicit checks. Broker step 3. */
public final class Inputs {
  private Inputs() {}

  public static <T> T parse(Object args, Class<T> type) {
    return Json.convert(Json.SNAKE, args == null ? java.util.Map.of() : args, type);
  }
}
