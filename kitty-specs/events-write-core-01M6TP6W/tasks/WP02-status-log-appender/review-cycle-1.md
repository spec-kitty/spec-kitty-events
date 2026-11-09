---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-07T13:23:00Z'
reviewer_agent: claude
wp_id: WP02
---

# WP02 review (Opus): REJECT, cycle 1

## [MAJOR] The appender follows a symlinked status.events.jsonl
`JdkFileOps.java:34` opens the log with CREATE, WRITE, APPEND, and `:23` reads it, both without NOFOLLOW_LINKS. A committed `kitty-specs/x/status.events.jsonl -> ~/anything` makes the only status writer append outside the repository. Core refuses this case (`store.py:407-421`).

Fix:
- add `LinkOption.NOFOLLOW_LINKS` to both opens (read through `FileChannel.open(path, READ, NOFOLLOW_LINKS)`);
- map the error to a typed refusal (PersistenceFailedException or a dedicated subtype);
- add a POSIX-only test with a symlinked log, and one with a dangling symlink.

## [MINOR] findings
1. `LockedStatusLogSession.java:76`: the sync after the rollback truncate is never checked. Add a test asserting truncate, then force.
2. A failed directory sync on a newly created file (`:67`) is never injected. Add a test: the file rolls back to 0 bytes and the call throws PersistenceFailedException.
3. `:37` and `:40` read the whole file again on every append and never compare `sizeBefore` with the bytes just checked. Refuse the append when the two differ.
4. `:43` rolls back only on IOException. Also roll back on RuntimeException.
5. `FileWriteApis.java:19-28` misses `Files.newByteChannel`, `Files.copy`, `Files.move`, `Files.createTempFile`, `AsynchronousFileChannel.open` and the `FileWriter`/`PrintWriter` constructors. Add them.
