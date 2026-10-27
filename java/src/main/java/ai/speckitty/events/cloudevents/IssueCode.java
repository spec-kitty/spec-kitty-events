package ai.speckitty.events.cloudevents;

/** The stable issue codes of the upcaster contract; the names are the wire strings. */
public enum IssueCode {
    NOT_JSON,
    NOT_AN_OBJECT,
    ALREADY_CLOUDEVENT,
    UNRECOGNISED_ROW,
    UNSUPPORTED_SCHEMA_VERSION,
    UNREGISTERED_TYPE,
    UNEXPECTED_MEMBER,
    MISSING_MEMBER,
    INVALID_MEMBER,
    INVALID_ID,
    INVALID_TIME,
    CLOCK_OUT_OF_RANGE,
    MISSION_ID_CONFLICT,
    MISSION_SLUG_CONFLICT,
    SUBJECT_CONFLICT,
    AGGREGATE_UNMAPPABLE,
    MISSION_ID_MISSING
}
