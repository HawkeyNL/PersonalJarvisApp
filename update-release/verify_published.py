"""Check redownloaded assets byte-for-byte before publishing a draft."""
import hashlib
import json
from pathlib import Path
import sys
from client_release import expected_assets
from manifest import validate_manifest


def digest(path: Path) -> bytes:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").digest()


def verify(final: Path, staged: Path, manifest: Path, version: str, revision: str) -> None:
    expected = expected_assets(version) | {"latest.json", "latest.json.sig"}
    if {p.name for p in final.iterdir()} != expected:
        raise ValueError("published draft has unexpected or missing assets")
    for name in expected:
        path = final / name
        source = manifest.parent / name if name.startswith("latest.json") else staged / name
        if (source.is_symlink() or not source.is_file() or path.is_symlink()
                or not path.is_file() or path.stat().st_size != source.stat().st_size
                or digest(path) != digest(source)):
            raise ValueError("redownloaded asset differs from tested bytes")
    value = validate_manifest(json.loads((final / "latest.json").read_bytes()))
    if value["release"]["version"] != version or value["release"]["source_revision"] != revision:
        raise ValueError("release identity changed")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4], sys.argv[5])
