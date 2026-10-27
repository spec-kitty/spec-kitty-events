package ai.speckitty.events.cloudevents;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed contract files (registry, envelope and composed row schemas), read from the
 * classpath under {@code spec_kitty_events/producer_contract/}: the same files the Python
 * binding loads.
 */
public final class Contract {

    static final String ROOT = "spec_kitty_events/producer_contract/";
    /** The deepest nesting a line may have (root container = 1), as the Python binding's MAX_DEPTH. */
    public static final int MAX_DEPTH = 256;

    static final JsonMapper MAPPER = JsonMapper.builder(
                    JsonFactory.builder()
                            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
                            .build())
            .build();
    private static final ConcurrentHashMap<String, JsonNode> CACHE = new ConcurrentHashMap<>();

    private Contract() {}

    /** A contract file, parsed (cached; treat as read-only). */
    public static JsonNode file(String relative) {
        return CACHE.computeIfAbsent(relative, Contract::load);
    }

    /** The type registry. */
    public static JsonNode registry() {
        return file("registry.json");
    }

    /** The registry entry of a full type string or its short name, or null. */
    public static JsonNode entry(String typeOrName) {
        for (JsonNode entry : registry().get("types")) {
            if (typeOrName.equals(entry.get("type").asString()) || typeOrName.equals(entry.get("name").asString())) {
                return entry;
            }
        }
        return null;
    }

    private static JsonNode load(String relative) {
        try (InputStream in = Contract.class.getClassLoader().getResourceAsStream(ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("contract file not on the classpath: " + ROOT + relative);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
