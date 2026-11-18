// Cucumber-JVM step definitions for the contract-check fixtures (read as text, never compiled).
package fixtures;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;

public class Steps {

    @Given("a thing")
    public void aThing() {}

    @When("it happens")
    public void itHappens() {}

    @io.cucumber.java.en.Then(value = "it holds")
    public void itHolds() {}

    // A commented-out definition binds nothing:
    // @Given("the value {string}")
    /* @Given("the value {string}") */
    public void notAStep() {
        String text = "// @Given(\"the value {string}\") inside a literal is not a comment either";
    }
}
