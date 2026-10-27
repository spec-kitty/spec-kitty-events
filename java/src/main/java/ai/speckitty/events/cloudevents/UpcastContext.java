package ai.speckitty.events.cloudevents;

/**
 * What the caller knows about the log being read (the Mission's {@code meta.json}). Context
 * fills a gap a row leaves; it never overrides a value the row carries. Any member may be null.
 */
public record UpcastContext(String missionid, String missionslug, String projectuuid) {

    /** No context. */
    public static final UpcastContext NONE = new UpcastContext(null, null, null);
}
