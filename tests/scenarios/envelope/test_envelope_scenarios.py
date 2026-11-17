"""Binds every ``envelope/`` feature to the pytest-bdd steps in ``conftest.py``."""

from pytest_bdd import scenarios

scenarios(".")
