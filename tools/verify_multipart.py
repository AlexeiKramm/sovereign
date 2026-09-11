#!/usr/bin/env python3
"""Parse the app's real upload body with the server's own multipart implementation.

Why this exists: the Kotlin unit tests assert the multipart format against RFC 7578 as *we*
understand it. The oracle is the parser the server actually runs (FastAPI -> python-multipart),
and a body it rejects shows up on the phone as `bad_input`, which looks like a model or audio
problem instead of a transport bug. This closes that gap without a device or a network.

Usage:
    cd ~/git-repos/local-voice-ime
    ./gradlew :app:testDebugUnitTest          # writes app/build/wire/*
    <stack venv>/bin/python tools/verify_multipart.py

Exits non-zero on any mismatch.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

WIRE = Path(__file__).resolve().parent.parent / "app" / "build" / "wire"

try:
    from fastapi import FastAPI, File, Form, UploadFile
    from fastapi.testclient import TestClient
except ImportError as exc:  # pragma: no cover
    sys.exit(f"FastAPI is required to run this check: {exc}")


def main() -> int:
    body_path = WIRE / "transcribe-request.bin"
    audio_path = WIRE / "expected-audio.bin"
    ctype_path = WIRE / "content-type.txt"

    for p in (body_path, audio_path, ctype_path):
        if not p.exists():
            print(f"missing {p} — run './gradlew :app:testDebugUnitTest' first")
            return 2

    body = body_path.read_bytes()
    expected_audio = audio_path.read_bytes()
    content_type = ctype_path.read_text().strip()

    app = FastAPI()

    @app.post("/v1/transcribe")
    async def transcribe(
        audio: UploadFile = File(...),
        cleanup: str = Form("off"),
        language: str = Form("auto"),
        hotwords: list[str] = Form(default=[]),
    ) -> dict:
        received = await audio.read()
        return {
            "filename": audio.filename,
            "content_type": audio.content_type,
            "audio_sha256": hashlib.sha256(received).hexdigest(),
            "audio_len": len(received),
            "cleanup": cleanup,
            "language": language,
            "hotwords": hotwords,
        }

    client = TestClient(app)
    response = client.post("/v1/transcribe", content=body, headers={"Content-Type": content_type})

    print(f"boundary from body : {content_type}")
    print(f"status             : {response.status_code}")
    if response.status_code != 200:
        print(response.text[:2000])
        return 1

    got = response.json()
    expected_hash = hashlib.sha256(expected_audio).hexdigest()

    checks = [
        ("audio field parsed", got["audio_len"] > 0),
        ("filename preserved", got["filename"] == "clip.wav"),
        ("content type preserved", got["content_type"] == "audio/wav"),
        ("audio bytes identical", got["audio_sha256"] == expected_hash),
        ("cleanup field", got["cleanup"] == "full"),
        ("language field", got["language"] == "auto"),
        ("hotwords repeated field", got["hotwords"] == ["Umacut", "Parakeet"]),
    ]

    print(f"audio len          : {got['audio_len']} bytes (sent {len(expected_audio)})")
    print(f"audio sha256       : {got['audio_sha256']}")
    print(f"expected sha256    : {expected_hash}")
    print(f"hotwords           : {got['hotwords']}")
    print()

    failed = False
    for name, ok in checks:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
        failed |= not ok

    print()
    if failed:
        print("RESULT: the server's parser did NOT accept the app's body as intended.")
        return 1

    print("RESULT: FastAPI/python-multipart parsed the app's body exactly as the contract expects.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
