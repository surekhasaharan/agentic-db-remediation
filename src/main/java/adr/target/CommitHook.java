package adr.target;

/** How chaos reaches the two seams inside apply without the target knowing about demos. */
public interface CommitHook {
  void beforeCommit();
  void afterCommit();

  CommitHook NONE = new CommitHook() {
    @Override public void beforeCommit() {}
    @Override public void afterCommit() {}
  };
}
