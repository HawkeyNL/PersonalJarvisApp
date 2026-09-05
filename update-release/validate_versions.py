#!/usr/bin/env python3
"""Validate desktop-only product versions and the immutable client-core pin.

The server repository retains mobile version validation. This compatibility
entry point delegates to the canonical desktop release validator.
"""
import argparse
from pathlib import Path
import sys

from desktop_release import validate_source


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        validate_source(args.repository.resolve(), args.version)
    except (OSError, KeyError, TypeError, ValueError) as error:
        print(f"desktop release version validation failed: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
