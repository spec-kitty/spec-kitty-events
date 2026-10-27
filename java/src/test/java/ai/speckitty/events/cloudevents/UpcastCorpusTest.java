package ai.speckitty.events.cloudevents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

/**
 * Runs the upcast corpus ({@code producer_contract/fixtures/upcast/cases}) through the Java
 * binding with the same assertions as the Python gate {@code conformance/test_upcast_corpus.py}:
 * outcome, form, codes, the golden bytes, read-profile validity, idempotence and strict use.
 */
class UpcastCorpusTest {

    private static final Path CASES = Path.of(System.getProperty("upcast.cases"));

    private static List<Path> cases() throws IOException {
        try (Stream<Path> files = Files.walk(CASES)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static UpcastContext context(JsonNode node) {
        return new UpcastContext(text(node, "missionid"), text(node, "missionslug"), text(node, "projectuuid"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }

    @Test
    void theCorpusIsTheOnePythonRuns() throws IOException {
        List<Path> cases = cases();
        assertTrue(cases.size() > 150, "corpus has " + cases.size() + " cases");
        List<String> codes = new ArrayList<>();
        for (Path path : cases) {
            Contract.MAPPER.readTree(path.toFile()).get("expected").get("codes").forEach(c -> codes.add(c.asString()));
        }
        for (IssueCode code : IssueCode.values()) {
            assertTrue(codes.contains(code.name()), "no case for " + code);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyCase() throws IOException {
        return cases().stream().map(path -> DynamicTest.dynamicTest(CASES.relativize(path).toString(), () -> run(path)));
    }

    private static void run(Path path) throws IOException {
        JsonNode test = Contract.MAPPER.readTree(path.toFile());
        JsonNode expected = test.get("expected");
        UpcastContext context = context(test.get("context"));
        byte[] line = test.get("line").asString().getBytes(StandardCharsets.UTF_8);
        UpcastResult result = Upcaster.upcastLine(line, context, false);
        assertEquals(expected.get("outcome").asString(), result.outcome(), "outcome");
        assertEquals(expected.get("form").asString(), result.form(), "form");
        List<String> codes = new ArrayList<>();
        expected.get("codes").forEach(c -> codes.add(c.asString()));
        assertEquals(codes, result.codes(), "codes");
        assertArrayEquals(line, result.originalLine(), "original line");
        if (result.mapped()) {
            String golden = expected.get("golden").asString();
            assertEquals(golden, CanonicalJson.text(result.row()), "golden bytes");
            boolean valid = CloudEventValidator.validate(Contract.MAPPER.readTree(golden), CloudEventValidator.Profile.READ).isEmpty();
            assertEquals(expected.get("valid").asBoolean(), valid, "read-profile validity");
            UpcastResult again = Upcaster.upcastLine(golden.getBytes(StandardCharsets.UTF_8), context, false);
            assertEquals("passthrough", again.outcome());
            assertEquals(List.of("ALREADY_CLOUDEVENT"), again.codes());
            assertEquals(golden, CanonicalJson.text(again.row()));
        } else {
            assertTrue(expected.get("golden").isNull());
        }
        boolean raises = !codes.isEmpty() && !codes.get(0).equals("ALREADY_CLOUDEVENT");
        if (raises) {
            UpcastException error = assertThrows(UpcastException.class, () -> Upcaster.upcastLine(line, context, true));
            assertEquals(codes.get(0), error.code().name());
        } else {
            Upcaster.upcastLine(line, context, true);
        }
    }

    @Test
    void canonicalFloatsFollowPythonRepr() {
        assertEquals("1e-05", CanonicalJson.pythonFloat(1e-05));
        assertEquals("0.0001", CanonicalJson.pythonFloat(0.0001));
        assertEquals("1e+16", CanonicalJson.pythonFloat(1e16));
        assertEquals("1234567890123456.0", CanonicalJson.pythonFloat(1234567890123456.0));
        assertEquals("1.5e+300", CanonicalJson.pythonFloat(1.5e300));
        assertEquals("-0.0", CanonicalJson.pythonFloat(-0.0));
        assertEquals("0.1", CanonicalJson.pythonFloat(0.1));
        assertEquals("2.0", CanonicalJson.pythonFloat(2.0));
        assertEquals("123.456", CanonicalJson.pythonFloat(123.456));
        assertEquals("5e-324", CanonicalJson.pythonFloat(Double.MIN_VALUE));
        assertEquals("1e-323", CanonicalJson.pythonFloat(1e-323));
        assertEquals("2.2250738585072014e-308", CanonicalJson.pythonFloat(Double.MIN_NORMAL));
        assertEquals("1.7976931348623157e+308", CanonicalJson.pythonFloat(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.pythonFloat(Double.NaN));
    }

    @Test
    void validatorNamesTheProblem() throws IOException {
        JsonNode row = Contract.MAPPER.readTree(
                "{\"specversion\":\"1.0\",\"type\":\"ai.spec-kitty.nope.v1\"}");
        assertEquals("UNREGISTERED_TYPE", CloudEventValidator.validate(row, CloudEventValidator.Profile.READ).get(0).code());
        assertEquals("NOT_AN_OBJECT",
                CloudEventValidator.validate(Contract.MAPPER.readTree("[1]"), CloudEventValidator.Profile.READ).get(0).code());
        assertNull(Contract.entry("ai.spec-kitty.nope.v1"));
    }
}
