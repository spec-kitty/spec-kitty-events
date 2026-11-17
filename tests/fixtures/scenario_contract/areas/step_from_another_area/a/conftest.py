from pytest_bdd import given


@given("a step only area a defines")
def only_in_a() -> None: ...


@given("a step both areas define")
def shared_in_a() -> None: ...
