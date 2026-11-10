package org.speckitty.missionstatus.adapter.out.statuslog;

/**
 * Decides, for a torn status log, whether the unterminated fragment is exactly one complete status row.
 *
 * <p>This package carries no codec: the caller supplies the decision. The repair calls it at most once per session, inside the
 * Mission lock, and only when the log is non-empty and does not end in LF. Both arguments are private copies the callee may keep.
 */
@FunctionalInterface
public interface TailClassifier {

  /**
   * @param prefix the log up to and including its last LF (empty when the log holds no LF at all)
   * @param fragment the bytes after that last LF, without a terminator; never empty
   * @return true iff {@code fragment} is exactly one status row that folds after {@code prefix}, so that appending one LF to the log
   *     leaves it readable. It must answer false, never throw, for bad input; a thrown {@link RuntimeException} is treated as false.
   */
  boolean isCompleteRow(byte[] prefix, byte[] fragment);
}
