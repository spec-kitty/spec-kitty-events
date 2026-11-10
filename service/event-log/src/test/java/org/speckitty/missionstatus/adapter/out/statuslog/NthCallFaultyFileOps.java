package org.speckitty.missionstatus.adapter.out.statuslog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Wraps {@link JdkFileOps} and counts every mutating call in order: open, write, force, truncate and directory sync, on the
 * channels it hands out too. It fails the calls whose 1-based number is in {@code failAt}. A clean run (empty {@code failAt}) lists the
 * calls, so a test can enumerate N = 1..k.
 */
final class NthCallFaultyFileOps implements FileOps {

  /** How a failing call behaves. */
  enum Mode {
    /** The call throws and has no effect. */
    BEFORE,
    /** The call takes effect, then throws (the worst case for a caller that trusts a throw to mean nothing happened). */
    AFTER,
    /** A write stores half of its bytes and reports that count; every other call behaves as {@link #BEFORE}. */
    SHORT_WRITE
  }

  private final FileOps real = new JdkFileOps();
  private final Set<Integer> failAt;
  private final Mode mode;
  private final List<String> calls = new ArrayList<>();

  NthCallFaultyFileOps(Set<Integer> failAt, Mode mode) {
    this.failAt = failAt;
    this.mode = mode;
  }

  static NthCallFaultyFileOps clean() {
    return new NthCallFaultyFileOps(Set.of(), Mode.BEFORE);
  }

  /** The mutating calls so far, as {@code "<n>:<name>"}. */
  List<String> calls() {
    return List.copyOf(calls);
  }

  private boolean next(String name) {
    calls.add((calls.size() + 1) + ":" + name);
    return failAt.contains(calls.size());
  }

  private IOException injected() {
    return new IOException("injected fault at call " + calls.size() + " (" + calls.get(calls.size() - 1) + ")");
  }

  @Override
  public boolean exists(Path path) {
    return real.exists(path);
  }

  @Override
  public byte[] read(Path path) throws IOException {
    return real.read(path);
  }

  @Override
  public boolean regularOrAbsent(Path path) throws IOException {
    return real.regularOrAbsent(path);
  }

  @Override
  public AppendChannel openForAppend(Path path) throws IOException {
    boolean fail = next("open");
    if (fail && mode != Mode.AFTER) {
      throw injected();
    }
    AppendChannel channel = real.openForAppend(path);
    if (fail) {
      channel.close();
      throw injected();
    }
    return new Counting(channel);
  }

  @Override
  public void syncDirectory(Path directory) throws IOException {
    boolean fail = next("sync-directory");
    if (fail && mode != Mode.AFTER) {
      throw injected();
    }
    real.syncDirectory(directory);
    if (fail) {
      throw injected();
    }
  }

  private final class Counting implements AppendChannel {
    private final AppendChannel inner;

    private Counting(AppendChannel inner) {
      this.inner = inner;
    }

    @Override
    public long size() throws IOException {
      return inner.size();
    }

    @Override
    public int write(ByteBuffer bytes) throws IOException {
      boolean fail = next("write");
      if (!fail) {
        return inner.write(bytes);
      }
      if (mode == Mode.SHORT_WRITE) {
        return inner.write(bytes.slice(0, bytes.remaining() / 2));
      }
      if (mode == Mode.AFTER) {
        inner.write(bytes);
      }
      throw injected();
    }

    @Override
    public void force() throws IOException {
      boolean fail = next("force");
      if (fail && mode != Mode.AFTER) {
        throw injected();
      }
      inner.force();
      if (fail) {
        throw injected();
      }
    }

    @Override
    public void truncate(long size) throws IOException {
      boolean fail = next("truncate");
      if (fail && mode != Mode.AFTER) {
        throw injected();
      }
      inner.truncate(size);
      if (fail) {
        throw injected();
      }
    }

    @Override
    public void close() throws IOException {
      inner.close();
    }
  }
}
