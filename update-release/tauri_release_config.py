"""Build the public-only bundler overlay; update origins remain native/runtime."""
import base64
import binascii
import json
import os
import sys


def release_config(public_key: str) -> dict:
    try:
        if not public_key or len(public_key) > 16384:
            raise ValueError
        lines = base64.b64decode(public_key, validate=True).decode('ascii').splitlines()
        if len(lines) != 2 or not lines[0].startswith('untrusted comment: '):
            raise ValueError
        raw = base64.b64decode(lines[1], validate=True)
        if len(raw) != 42 or raw[:2] != b'Ed':
            raise ValueError
    except (ValueError, binascii.Error, UnicodeError):
        raise ValueError('A valid Tauri signing PUBLIC key is required') from None
    return {
        'build': {'beforeBuildCommand': ''},
        'plugins': {'updater': {'pubkey': public_key, 'endpoints': []}},
    }


if __name__ == '__main__':
    try:
        print(json.dumps(release_config(os.environ.get('JARVIS_TAURI_UPDATER_PUBKEY', '')), separators=(',', ':')))
    except ValueError as error:
        sys.exit(str(error))
