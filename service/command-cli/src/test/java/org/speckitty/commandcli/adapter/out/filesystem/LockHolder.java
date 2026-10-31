package org.speckitty.commandcli.adapter.out.filesystem;

import java.nio.file.Path;
import java.util.Optional;

/** A child process for {@link ServiceLockTest}: tries the lock, prints the outcome, and in "hold" mode keeps it until stdin closes. */
public final class LockHolder {

  private LockHolder() {}

  public static void main(String[] args) throws Exception {
    Path gcd = Path.of(args[0]).getParent();
    Optional<ServiceLock> lock = ServiceLock.tryAcquire(StateDirectory.prepare(gcd));
    System.out.println(lock.isPresent() ? "ACQUIRED" : "REFUSED");
    System.out.flush();
    if (lock.isPresent() && "hold".equals(args[1])) {
      while (System.in.read() >= 0) {
        // hold until the parent closes stdin
      }
    }
    if (lock.isPresent()) {
      lock.get().close();
    }
  }
}
