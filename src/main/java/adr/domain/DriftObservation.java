package adr.domain;

import java.util.Map;

/** What the poller saw: the sealed fingerprint, the live one, and the snapshot difference. */
public record DriftObservation(String sealedFingerprint, String liveFingerprint, Map<String, Object> diff, String observedAt) {
  public boolean hasDifference() {
    return !sealedFingerprint.equals(liveFingerprint) && !diff.isEmpty();
  }
}
