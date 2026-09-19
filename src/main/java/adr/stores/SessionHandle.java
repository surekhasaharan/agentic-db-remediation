package adr.stores;

/**
 * What the lifecycle needs from a session without depending on the app package: the command lock and
 * whether the session is still the registered one for its id (false after reset or eviction).
 */
public interface SessionHandle {
  Object lock();
  boolean isCurrent();
}
