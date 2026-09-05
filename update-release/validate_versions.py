#!/usr/bin/env python3
"""Validate the unified client product version and platform build identities."""
import argparse
from pathlib import Path
import sys

from client_release import validate_source


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--version", required=True)
    parser.add_argument("--android-version-code")
    parser.add_argument("--ios-build-number")
    args = parser.parse_args()
    try:
        validate_source(
            args.repository.resolve(),
            args.version,
            args.android_version_code,
            args.ios_build_number,
        )
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(f"client release version validation failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
