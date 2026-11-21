#!/usr/bin/env python3
"""Check a release tag and say what the build should call itself.

The workflow calls this before a release build, so a tag that would produce a
version the app cannot compare, or a version code that is not newer than the last
one, stops the run rather than publishing something odd.

    python3 tools/check_tag.py v1.4.0            # a release
    python3 tools/check_tag.py v1.4.0-beta.2     # a beta

It prints the values the workflow reads, as GitHub step outputs when
GITHUB_OUTPUT is set, and as plain lines otherwise:

    version=1.4.0        the version name (-PappVersionName)
    code=1040099         the version code (-PappVersionCode)
    beta=false           whether this is a pre-release
    channel=release      "release" or "beta"

The version code is major*1000000 + minor*10000 + patch*100 + beta, where a
release carries 99 and a beta carries its own number. That keeps every version
comparable, and a release always newer than its own betas:

    1.4.0-beta.1 -> 1040001      1.4.0 -> 1040099      1.4.1 -> 1040199

This file is part of Kuroka (GPL-3.0, see ../LICENSE).
"""

from __future__ import annotations

import os
import re
import sys

# v1.4.0, v1.4.0-beta.2 — nothing else is a release tag.
TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$")


def parse(tag: str) -> tuple[str, int, bool] | None:
    """The version, its code and whether it is a beta, or None when the tag is not one of ours."""
    found = TAG.match(tag.strip())
    if not found:
        return None
    major, minor, patch, beta = found.groups()
    major, minor, patch = int(major), int(minor), int(patch)
    if major > 99 or minor > 99 or patch > 99:
        return None
    if beta is None:
        return f"{major}.{minor}.{patch}", major * 1_000_000 + minor * 10_000 + patch * 100 + 99, False
    number = int(beta)
    if number < 1 or number > 98:
        return None
    return (
        f"{major}.{minor}.{patch}-beta.{number}",
        major * 1_000_000 + minor * 10_000 + patch * 100 + number,
        True,
    )


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] in ("-h", "--help"):
        print(__doc__.strip(), file=sys.stderr)
        return 2

    tag = argv[1]
    parsed = parse(tag)
    if parsed is None:
        print(
            f"'{tag}' is not a Kuroka release tag: it has to be vX.Y.Z or vX.Y.Z-beta.N, "
            "with N between 1 and 98.",
            file=sys.stderr,
        )
        return 1

    version, code, beta = parsed
    values = {
        "version": version,
        "code": str(code),
        "beta": "true" if beta else "false",
        "channel": "beta" if beta else "release",
    }

    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")
    for key, value in values.items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
