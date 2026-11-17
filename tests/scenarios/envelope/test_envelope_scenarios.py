"""Binds every ``envelope/`` feature to the pytest-bdd steps in ``conftest.py``."""

import pytest
from pytest_bdd import scenarios

# pytest-bdd 8.1 still registers step fixtures through pytest's deprecated nodeid/baseid
# arguments; the warning is about pytest-bdd internals, not these scenarios.
pytestmark = [
    pytest.mark.filterwarnings("ignore:Passing nodeid to _register_fixture is deprecated"),
    pytest.mark.filterwarnings("ignore:Passing baseid to FixtureDef is deprecated"),
]

scenarios(".")
