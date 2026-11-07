#!/usr/bin/env python3
"""Differential-test Java PyIsoDateTime against the running CPython 3.11."""

from __future__ import annotations

import argparse
import json
import os
import random
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path

from generate_fromisoformat_vectors import INPUTS

ROOT = Path(__file__).resolve().parents[2]
JAVA_SOURCE = (
    ROOT
    / "service/domain-model/src/main/java/org/speckitty/missionstatus/domain/model/PyIsoDateTime.java"
)
PACKAGE = "org.speckitty.missionstatus.domain.model"
RUNNER = """package org.speckitty.missionstatus.domain.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class Diff {
  public static void main(String[] args) throws Exception {
    for (String line : Files.readAllLines(Path.of(args[0]))) {
      StringBuilder text = new StringBuilder();
      if (!line.equals("-")) {
        for (String hex : line.split(" ")) {
          text.append((char) Integer.parseInt(hex, 16));
        }
      }
      Optional<PyIsoDateTime> parsed = PyIsoDateTime.parse(text.toString());
      System.out.println(
          parsed.map(value -> "T " + value.aware() + " " + value.micros()).orElse("F"));
    }
  }
}
"""
BASES = (
    "2026-01-02T03:04:05.123456+05:30",
    "20260102T030405",
    "2026-W01-3T12:30",
    "2026W013 130Z",
    "2021-W01-1T03.5",
    "2027-W01-1T0304.5Z",
    "2010-W52-7T23:59:59.999999-23:59:59.999999",
    "0001-01-01T00:00:00+00:01",
    "9999-12-31T23:59:59-01:00",
)
REVIEW_FORMS = (
    "2026-01-02T",
    "2026-01-02 ",
    "2026-01-02T0",
    "2026-01-02T03",
    "2026-01-02T3",
    "2026-01-02é03:04",
    "2026-01-02é03",
    "2026-01-02🦀",
    "2026-01-02🦀03",
    "2026-01-02\ud80003:04",
    "2026-01-02\udc0003",
    "2026-01\ud80002",
    "20260102\ud80003",
    "2026W011\ud80003",
    "2026-W01\ud80003",
    "2026-01-02T03:04Z05",
    "2026-01-02T03:04Z",
    "2026-01-02T03:04ZZ",
    "2026-01-02T03:04:05Z00:00",
    "2026-01-02T03:04+05:99",
    "2026-01-02T03:04+0599",
    "2026-01-02T03:04+23:59:59.999999",
    "2026-01-02T03:04-23:59:59.999999",
    "2026-01-02T03:04+24",
    "2026-01-02T03:04-00",
    "2026-01-02T03:04+00:00:00.000001",
    "2026-01-02T03:04+05:30:15.1",
    "2026-01-02T03:04+05:30:1",
    "2026-01-02T03:04+05:3",
    "2026-01-02T03:04+053",
    "2026-01-02T03:04+05:30:",
    "2026-01-02T03:04+0530:15",
    "2026-01-02T03:04+05:3015",
    "2026-01-02T03:04+05.5",
    "2026-01-02T03:04+05,5",
    "2026-01-02T03:04+050",
    "2026-01-02T03:04+05:30.5",
    "2026-01-02T03:04:05.123456789012345",
    "2026-01-02T03:04:05.1234567x",
    "2026-01-02T03:04:05,123",
    "2026-01-02T03:04:05.-1",
    "2026-01-02T03:04:05.+1",
    "2026-01-02T03:04:05. 1",
    "2026-01-02T03:04:5",
    "2026-01-02T03:4",
    "2026-01-02T03:04:",
    "2026-01-02T03:",
    "2026-01-02T0304:05",
    "2026-01-02T03:04:05:06",
    "2026-01-02T030405.5",
    "2026-01-02T0304.5",
    "2026-01-02T03.5",
    "2026-01-02T03,5",
    "2026-01-02T00:00",
    "2026-01-02T23:59:59.999999",
    "2026-01-02T12:00:00+12:00",
    "2026-01-02T12:00:00-12:00",
    "2026-W53-1",
    "2015-W53-7",
    "2020-W53",
    "2026W53",
    "2026-W1-1",
    "2026-W011",
    "2026W01-1",
    "2026-W01-1T03",
    "2026W011T03:04",
    "2026-W01T03",
    "2026W01T03",
    "2026-W011T03",
    "2026W0112",
    "2026W01123",
    "2026W011234",
    "2026-W01-12",
    "2026-W01-1-",
    "2026-W",
    "2026-W0",
    "2026W0",
    "2026W",
    "2026-W01-",
    "2026-01-02T03:04:05+05:30 ",
    " 2026-01-02",
    "2026-01-02\n",
    "2026-01-02T03:04:05\0",
    "2026-01-0203:04",
    "20260102T",
    "20260102T03",
    "2026010203",
    "202601020304",
    "2026-01",
    "2026-0",
    "202601",
    "2026-01-",
    "2026-01-2",
    "2026-1-2",
    "+2026-01-02",
    "-026-01-02",
    "2026/01/02",
    "2026-01/02",
    "0001-01-01",
    "9999-12-31T23:59:59.999999-23:59",
    "0001-01-01T00:00:00+00:01",
    "9999-12-31T23:59:59-01:00",
    "2024-02-29",
    "2100-02-29",
    "2000-02-29",
    "1900-02-29",
    "1970-01-01T00:00:00Z",
    "1969-12-31T23:59:59.999999Z",
    "2026-01-02T03:04:05+0000",
    "2026-01-02T03:04:05-0000",
    "2026-01-02T03:04:05+00",
    "2026-01-02T03:04:05−05:00",
    "2026-01-02T03:04:05＋05:00",
    "2026-01-02T０３",
    "2026-01-02T03:04:05.١",
    "2026-01-02T03:04:05.123456١",
    "2026-01-02T03:04:05.1234567١",
    "2026-01-02TT03",
    "2026-01-02T03:04-",
    "2026-01-02T03:04+-05",
    "2026-01-02T03:04:05.999999999",
    "2026-01-02T03:04:05.0000001",
    "2026-01-02T24:00:00",
    "2026-01-02T23:59:60.5",
    "2026-01-02T99",
    "2026-01-02T03:99",
    "2026-02-30",
    "2026-04-31",
    "2026-12-31T23:59:59+23:59",
    "2026-01-02T03:04:05.1+05:30",
    "2026-01-02T03:04:05,1Z",
    "20260102T030405.123+0530",
    "20260102T0304+05",
    "2026-01-02x",
    "2026-01-02xx03",
    "2026-01-02T03:04:05+05:30:15.1234567",
    "2026-01-02T03:04:05+05:30:15,5",
    "2026-01-02T03:04:05+05:30:15.",
    "2026-01-02T03:04:05+05:30:15.x",
    "٢026-01-02",
    "2026-01-02\u0085",
    "2026-01-02T03:04:05Z ",
    "2026-01-02T03Z",
    "2026-01-02T0Z",
    "2026-01-02TZ",
    "2026-01-02T03:04-05:30:15.123456789",
)
ALPHABET = "0123456789-:T.,+Z Wz\0\n١０５é🦀"


def original_review_mutations() -> list[str]:
    result = []
    randomizer = random.Random(7)
    alphabet = "0123456789-:T.,+Z W"
    bases = (
        "2026-01-02T03:04:05.123456+05:30",
        "20260102T030405",
        "2026-W01-3T12:30",
        "2026W013 1230Z",
    )
    for _ in range(400):
        characters = list(randomizer.choice(bases))
        for _ in range(randomizer.randint(1, 3)):
            operation = randomizer.random()
            index = randomizer.randrange(len(characters) + 1)
            if operation < 0.4 and characters:
                del characters[min(index, len(characters) - 1)]
            elif operation < 0.7:
                characters.insert(index, randomizer.choice(alphabet))
            elif characters:
                characters[min(index, len(characters) - 1)] = randomizer.choice(alphabet)
        result.append("".join(characters))
    return result


def forms() -> list[str]:
    result = list(REVIEW_FORMS)
    result.extend(original_review_mutations())
    result.extend(text for text, _branch in INPUTS)
    result.extend(BASES)
    randomizer = random.Random(7)
    while len(result) < 1_500:
        characters = list(randomizer.choice(BASES))
        for _ in range(randomizer.randint(1, 3)):
            operation = randomizer.random()
            index = randomizer.randrange(len(characters) + 1)
            if operation < 0.4 and characters:
                del characters[min(index, len(characters) - 1)]
            elif operation < 0.7:
                characters.insert(index, randomizer.choice(ALPHABET))
            elif characters:
                characters[min(index, len(characters) - 1)] = randomizer.choice(ALPHABET)
        candidate = "".join(characters)
        if candidate not in result:
            result.append(candidate)
    return list(dict.fromkeys(result))


def encode(text: str) -> str:
    encoded = text.encode("utf-16-be", errors="surrogatepass")
    units = (
        int.from_bytes(encoded[index : index + 2], "big") for index in range(0, len(encoded), 2)
    )
    return " ".join(f"{unit:04x}" for unit in units) or "-"


def python_result(text: str) -> str:
    try:
        parsed = datetime.fromisoformat(text)
    except (ValueError, OverflowError):
        return "F"
    aware = parsed.tzinfo is not None
    delta = parsed.replace(tzinfo=None) - datetime(1970, 1, 1)
    micros = (delta.days * 86_400 + delta.seconds) * 1_000_000 + delta.microseconds
    if aware:
        offset = parsed.utcoffset()
        micros -= (offset.days * 86_400 + offset.seconds) * 1_000_000 + offset.microseconds
    return f"T {str(aware).lower()} {micros}"


def java_results(inputs: list[str], java_home: str) -> list[str]:
    java = Path(java_home) / "bin/java"
    javac = Path(java_home) / "bin/javac"
    if not java.is_file() or not javac.is_file():
        raise ValueError(f"JAVA_HOME has no java/javac pair: {java_home}")
    with tempfile.TemporaryDirectory(prefix="fromisoformat-diff-") as directory:
        work = Path(directory)
        source = work / "src" / Path(*PACKAGE.split("."))
        source.mkdir(parents=True)
        shutil.copy(JAVA_SOURCE, source / JAVA_SOURCE.name)
        (source / "Diff.java").write_text(RUNNER, encoding="utf-8")
        encoded = work / "inputs.txt"
        encoded.write_text("\n".join(encode(text) for text in inputs) + "\n", encoding="ascii")
        classes = work / "classes"
        subprocess.run(
            [
                str(javac),
                "-d",
                str(classes),
                str(source / JAVA_SOURCE.name),
                str(source / "Diff.java"),
            ],
            check=True,
        )
        completed = subprocess.run(
            [str(java), "-cp", str(classes), f"{PACKAGE}.Diff", str(encoded)],
            check=True,
            capture_output=True,
            text=True,
        )
    return completed.stdout.splitlines()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    if sys.version_info[:2] != (3, 11):
        parser.error(f"run with core's Python 3.11, not {sys.version.split()[0]}")
    java_home = os.environ.get("JAVA_HOME") or shutil.which("javac")
    if java_home and java_home.endswith("/bin/javac"):
        java_home = java_home[: -len("/bin/javac")]
    if not java_home:
        parser.error("set JAVA_HOME to a JDK")
    inputs = forms()
    expected = [python_result(text) for text in inputs]
    actual = java_results(inputs, java_home)
    mismatches = [
        {"text": text, "python": wanted, "java": got}
        for text, wanted, got in zip(inputs, expected, actual, strict=True)
        if wanted != got
    ]
    accepted = sum(result != "F" for result in expected)
    print(f"differential: {len(inputs)} forms, {accepted} accepted, {len(mismatches)} mismatches")
    if mismatches:
        print(json.dumps(mismatches[:20], ensure_ascii=True, indent=2))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
