package ai.speckitty.events.cloudevents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;

/**
 * Cucumber-JVM steps of the {@code tests/scenarios/upcast/} area (spec-kitty-events#199): the same
 * step texts as the pytest-bdd reference binding, {@code tests/scenarios/upcast/conftest.py}, bound
 * against the Java {@link Upcaster}. They read the corpus the Python package ships
 * ({@code producer_contract/fixtures/upcast/cases}, passed as {@code -Dupcast.cases}) and check each
 * result against what its case pins. Keep both bindings and the area's {@code README.md} steps
 * table in step; {@code scripts/ci/scenario_contract.py} fails when a {@code @jvm} step has no
 * definition here. Cucumber makes a new instance per scenario, so the fields hold one scenario's state.
 */
public class UpcastSteps {

    private static final String CORPUS_SUFFIX = "spec_kitty_events/producer_contract/fixtures/upcast/cases";
    private static final List<String> OUTCOMES = List.of("mapped", "passthrough");

    private record UpcastCase(String relPath, JsonNode data) {

        String line() {
            return data.get("line").asString();
        }

        byte[] lineBytes() {
            return line().getBytes(StandardCharsets.UTF_8);
        }

        JsonNode expected() {
            return data.get("expected");
        }

        UpcastContext context() {
            JsonNode context = data.get("context");
            return new UpcastContext(text(context, "missionid"), text(context, "missionslug"), text(context, "projectuuid"));
        }

        List<String> codes() {
            List<String> codes = new ArrayList<>();
            expected().get("codes").forEach(code -> codes.add(code.asString()));
            return codes;
        }

        private static String text(JsonNode node, String name) {
            JsonNode value = node.get(name);
            return value == null || value.isNull() ? null : value.asString();
        }
    }

    private record Upcast(UpcastCase upcastCase, UpcastResult result) {}

    /** The code strict use raised, or {@code null} when it upcast the row. */
    private record StrictUse(UpcastCase upcastCase, String raised) {}

    private Path corpus;
    private List<UpcastCase> cases = List.of();
    private List<Upcast> upcasts = List.of();
    private List<StrictUse> strictUses = List.of();

    @Given("the upcast corpus shipped with the package")
    public void theUpcastCorpusShippedWithThePackage() {
        String property = System.getProperty("upcast.cases");
        assertNotNull(property, "-Dupcast.cases is not set (java/build.gradle.kts passes it)");
        corpus = Path.of(property);
        assertTrue(Files.isDirectory(corpus), corpus + " is not a directory");
        assertTrue(corpus.toAbsolutePath().normalize().toString().replace('\\', '/').endsWith(CORPUS_SUFFIX),
                corpus + " is not the corpus the Python package ships");
    }

    @Given("every case of the group {string}")
    public void everyCaseOfTheGroup(String group) throws IOException {
        cases = list(group, null);
        assertFalse(cases.isEmpty(), "the corpus group " + group + " holds no case");
    }

    @Given("every case of the group {string} pinned as {string}")
    public void everyCaseOfTheGroupPinnedAs(String group, String outcome) throws IOException {
        assertTrue(OUTCOMES.contains(outcome), outcome + " is not an upcast outcome: " + OUTCOMES);
        cases = list(group, outcome);
        assertFalse(cases.isEmpty(), "no case of the corpus group " + group + " is pinned as " + outcome);
    }

    private List<UpcastCase> list(String group, String outcome) throws IOException {
        assertNotNull(corpus, "the corpus is not arranged");
        Path directory = corpus.resolve(group);
        assertTrue(Files.isDirectory(directory), "no corpus group " + group);
        List<UpcastCase> selected = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                UpcastCase upcastCase = new UpcastCase(
                        corpus.relativize(path).toString().replace('\\', '/'), Contract.MAPPER.readTree(path.toFile()));
                if (outcome == null || outcome.equals(upcastCase.expected().get("outcome").asString())) {
                    selected.add(upcastCase);
                }
            }
        }
        return selected;
    }

    @When("each case's line is upcast with its caller context")
    public void eachCasesLineIsUpcast() {
        upcasts = cases.stream()
                .map(c -> new Upcast(c, Upcaster.upcastLine(c.lineBytes(), c.context(), false)))
                .toList();
    }

    @When("each case's golden is upcast again with its caller context")
    public void eachCasesGoldenIsUpcastAgain() {
        List<Upcast> again = new ArrayList<>();
        for (UpcastCase c : cases) {
            JsonNode golden = c.expected().get("golden");
            assertTrue(golden != null && golden.isString(), c.relPath() + " pins no golden");
            byte[] bytes = golden.asString().getBytes(StandardCharsets.UTF_8);
            again.add(new Upcast(c, Upcaster.upcastLine(bytes, c.context(), false)));
        }
        upcasts = again;
    }

    @When("each case's line is upcast in strict use")
    public void eachCasesLineIsUpcastInStrictUse() {
        List<StrictUse> uses = new ArrayList<>();
        for (UpcastCase c : cases) {
            String raised = null;
            try {
                Upcaster.upcastLine(c.lineBytes(), c.context(), true);
            } catch (UpcastException error) {
                raised = error.code().name();
            }
            uses.add(new StrictUse(c, raised));
        }
        strictUses = uses;
    }

    @Then("every result has the outcome, form and codes its case pins")
    public void everyResultHasThePinnedOutcomeFormAndCodes() {
        for (Upcast upcast : upcasts) {
            UpcastCase c = upcast.upcastCase();
            JsonNode expected = c.expected();
            assertEquals(expected.get("outcome").asString(), upcast.result().outcome(), c.relPath() + ": outcome");
            assertEquals(expected.get("form").asString(), upcast.result().form(), c.relPath() + ": form");
            assertEquals(c.codes(), upcast.result().codes(), c.relPath() + ": codes");
            assertArrayEquals(c.lineBytes(), upcast.result().originalLine(), c.relPath() + ": original line bytes");
        }
    }

    @Then("every mapped row's canonical bytes equal its golden")
    public void everyMappedRowsCanonicalBytesEqualItsGolden() {
        for (Upcast upcast : upcasts) {
            UpcastCase c = upcast.upcastCase();
            assertTrue(upcast.result().mapped(), c.relPath() + " was not mapped");
            assertEquals(c.expected().get("golden").asString(), CanonicalJson.text(upcast.result().row()),
                    c.relPath() + ": golden bytes");
        }
    }

    @Then("every golden's read-profile validity is the one its case pins")
    public void everyGoldensValidityIsAsPinned() {
        for (Upcast upcast : upcasts) {
            UpcastCase c = upcast.upcastCase();
            JsonNode golden = Contract.MAPPER.readTree(c.expected().get("golden").asString());
            boolean valid = CloudEventValidator.validate(golden, CloudEventValidator.Profile.READ).isEmpty();
            assertEquals(c.expected().get("valid").asBoolean(), valid, c.relPath() + ": read-profile validity");
        }
    }

    @Then("every passed-through line is left as it was")
    public void everyPassedThroughLineIsLeftAsItWas() {
        for (Upcast upcast : upcasts) {
            UpcastCase c = upcast.upcastCase();
            assertFalse(upcast.result().mapped(), c.relPath() + " was mapped");
            assertTrue(c.expected().get("golden").isNull() && c.expected().get("valid").isNull(),
                    c.relPath() + " pins a golden for a pass-through");
            JsonNode row = upcast.result().row();
            if (row != null) {
                assertEquals(CanonicalJson.text(Contract.MAPPER.readTree(c.line())), CanonicalJson.text(row),
                        c.relPath() + ": the passed-through row was rewritten");
            }
        }
    }

    @Then("every result is a pass-through of its golden with the same bytes")
    public void everyResultIsAPassThroughOfItsGolden() {
        for (Upcast upcast : upcasts) {
            UpcastCase c = upcast.upcastCase();
            assertEquals("passthrough", upcast.result().outcome(), c.relPath() + ": outcome");
            assertEquals(List.of(IssueCode.ALREADY_CLOUDEVENT.name()), upcast.result().codes(), c.relPath() + ": codes");
            assertEquals(c.expected().get("golden").asString(), CanonicalJson.text(upcast.result().row()),
                    c.relPath() + ": reading the golden again changed its bytes");
        }
    }

    @Then("strict use refuses every case with the first code it pins and upcasts every other case")
    public void strictUseRefusesAsPinned() {
        for (StrictUse use : strictUses) {
            List<String> codes = use.upcastCase().codes();
            boolean refuses = !codes.isEmpty() && !codes.get(0).equals(IssueCode.ALREADY_CLOUDEVENT.name());
            assertEquals(refuses ? codes.get(0) : null, use.raised(), use.upcastCase().relPath() + ": strict use");
        }
    }
}
