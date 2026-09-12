"""Resolve a release identity without secrets, mutation, or GitHub access."""
import argparse
import json
from pathlib import Path
import re

from client_release import VERSION, validate_source


def resolve(root, event, ref, version="", android_code=""):
    if event == "push" and ref.startswith("refs/tags/app-v"):
        version = ref.removeprefix("refs/tags/app-v")
        if not VERSION.fullmatch(version):
            raise ValueError("application tags must use app-vMAJOR.MINOR.PATCH")
        gradle = (root / "android/app/build.gradle.kts").read_text()
        codes = re.findall(r'JARVIS_ANDROID_VERSION_CODE"\)\.orNull\?\.toIntOrNull\(\) \?: ([0-9]+)', gradle)
        if len(codes) != 1:
            raise ValueError("Android checked-in versionCode is ambiguous")
        android_code = codes[0]
    elif event != "workflow_dispatch" or ref != "refs/heads/main":
        raise ValueError("release requires manual main dispatch or a stable application tag")
    validate_source(root, version, android_code)
    return {"app_version": version, "android_version_code": str(int(android_code))}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--event", required=True)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--version", default="")
    parser.add_argument("--android-version-code", default="")
    args = parser.parse_args()
    try:
        print(json.dumps(resolve(Path(__file__).resolve().parents[1], args.event, args.ref,
                                 args.version, args.android_version_code)))
    except (ValueError, OSError) as error:
        parser.error(str(error))
