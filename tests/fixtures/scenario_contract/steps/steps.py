"""Step definitions for the contract-check fixtures (read by ast, never imported)."""

from pytest_bdd import given, parsers, then, when


@given("a thing")
def a_thing() -> None: ...


@given(parsers.parse('the value "{value:w}"'))
def the_value(value: str) -> None: ...


@when("it happens")
def it_happens() -> None: ...


@then("it holds")
def it_holds() -> None: ...


@then("it is bound twice")
def bound_once() -> None: ...


@then("it is bound twice")
def bound_twice() -> None: ...
