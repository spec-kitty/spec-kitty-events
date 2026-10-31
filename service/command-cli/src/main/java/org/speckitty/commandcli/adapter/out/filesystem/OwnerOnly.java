package org.speckitty.commandcli.adapter.out.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Owner-only access for the service's state directory and files (core ADR 2026-10-27-1 D-2: mode 0600 in a 0700 directory, an
 * owner-only ACL on Windows). Core's endpoint reader refuses a file or directory that group or others can reach.
 */
final class OwnerOnly {

  static final Set<PosixFilePermission> DIRECTORY = PosixFilePermissions.fromString("rwx------");
  static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

  private OwnerOnly() {}

  static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** The creation attribute for a new file: 0600 on POSIX; none elsewhere ({@link #restrict} applies the ACL after creation). */
  static FileAttribute<?>[] fileAttributes(Path path) {
    return posix(path) ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE)} : new FileAttribute<?>[0];
  }

  static FileAttribute<?>[] directoryAttributes(Path path) {
    return posix(path) ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY)} : new FileAttribute<?>[0];
  }

  /** Sets owner-only access on an existing file or directory: POSIX mode bits, or an ACL granting the owner alone. */
  static void restrict(Path path, boolean directory) throws IOException {
    if (posix(path)) {
      Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setPermissions(directory ? DIRECTORY : FILE);
      return;
    }
    AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) {
      return;
    }
    UserPrincipal owner = acl.getOwner();
    AclEntry ownerOnly =
        AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
    acl.setAcl(List.of(ownerOnly));
  }

  /** On POSIX, the group and other bits a path carries (empty when there are none or the file system is not POSIX). */
  static Optional<Set<PosixFilePermission>> exposure(Path path) throws IOException {
    if (!posix(path)) {
      return Optional.empty();
    }
    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> exposed = EnumSet.noneOf(PosixFilePermission.class);
    for (PosixFilePermission permission : permissions) {
      if (!permission.name().startsWith("OWNER_")) {
        exposed.add(permission);
      }
    }
    return exposed.isEmpty() ? Optional.empty() : Optional.of(exposed);
  }
}
