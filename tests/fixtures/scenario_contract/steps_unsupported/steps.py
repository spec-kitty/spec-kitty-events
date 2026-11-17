"""Step expressions the contract check does not model (read by ast, never imported)."""

from pytest_bdd import given, parsers, then, when

TEXT = "a thing"


@given(parsers.cfparse("a {count:Number} things"))
def cfparse_expression() -> None: ...


@when(parsers.parse("it weighs {weight:f}"))
def float_field() -> None: ...


@then(TEXT)
def not_a_literal() -> None: ...
