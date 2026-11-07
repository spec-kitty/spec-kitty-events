package org.speckitty.missionstatus.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;

/** The one shared FR-010 predicate for calls that open or modify files through JDK write APIs. */
public final class FileWriteApis {

  private FileWriteApis() {}

  /** Matches the write-call list shared by event-log and command-cli's architecture rules. */
  public static DescribedPredicate<JavaCall<?>> jdkOpenForWriteApi() {
    return DescribedPredicate.describe("calls a JDK open-for-write API", FileWriteApis::matches);
  }

  private static boolean matches(JavaCall<?> call) {
    String owner = call.getTargetOwner().getName();
    String method = call.getName();
    if (owner.equals("java.nio.channels.FileChannel")) {
      return method.equals("open") || method.equals("write") || method.equals("truncate");
    }
    if (owner.equals("java.nio.file.Files")) {
      return method.startsWith("write")
          || method.equals("newByteChannel")
          || method.equals("newOutputStream")
          || method.equals("newBufferedWriter")
          || method.equals("createFile")
          || method.equals("createTempFile")
          || method.equals("copy")
          || method.equals("move");
    }
    if (owner.equals("java.nio.channels.AsynchronousFileChannel")) {
      return method.equals("open");
    }
    return (owner.equals("java.io.FileOutputStream")
            || owner.equals("java.io.FileWriter")
            || owner.equals("java.io.PrintWriter")
            || owner.equals("java.io.RandomAccessFile"))
        && method.equals("<init>");
  }
}
