// Step expressions the jvm reader refuses (UNSUPPORTED_EXPRESSION), one per line below.
package fixtures;

public class Steps {
    private static final String TEXT = "a constant";

    @Given("a " + "concatenation")
    public void concatenated() {}

    @Given(TEXT)
    public void constant() {}

    @Given("""
        a text block""")
    public void textBlock() {}

    @Given("a custom {color}")
    public void customParameterType(Color color) {}

    @Given("^an unbalanced (regex$")
    public void badRegex() {}

    @Given("an unmatched } brace")
    public void unmatchedBrace() {}

    @Given("an alternation of {int}/{float}")
    public void parameterInAlternation(int a) {}
}
