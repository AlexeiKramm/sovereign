# Smart Voice

An Android app (`fi.ak.smartvoice`) that turns speech into text in any app. The dictation
surface is a **floating pill** — an overlay plus an accessibility service that inserts the text
at the cursor. The main app is a launcher/configurator/dashboard: **Home · Profiles · Settings**,
plus a first-run onboarding wizard. No cloud speech service is involved at any point.

Two engines, chosen in Settings (`engine/TierPolicy.kt`):

- **On-device (default):** streaming ASR via sherpa-onnx (Nemotron 3.5, 320/560 ms) plus an LLM
  cleanup pass via **LiteRT-LM** (Gemma 4 E2B/E4B, Qwen 3.5 0.8B).
- **Server (explicit choice):** `local-voice-stack`'s `POST /v1/transcribe` on the user's home
  PC. The authoritative contract lives in the server repo, not here.

Kotlin + Jetpack Compose (Material 3). `minSdk 30`, `targetSdk`/`compileSdk 36`, **arm64-v8a only**.

## Start here

**`AGENTS.md` is the operating manual** — what this is, how it builds on each host, how work is
organised, and the rules. Read it before touching anything.

## Where things are

| Path | What |
|---|---|
| `AGENTS.md` | Operating manual (read first) |
| `V1.md` | The release definition and the pre-release checklist — read this second |
| `docs/work/` | Active and archived feature work orders (`PLAN.md` + `CHUNKS.md` per feature) |
| `docs/DECISIONS.md` | Append-only decision log, newest at the bottom |
| `docs/archive/` | History only: the pre-2026-09-16 decision log and the shipped UI plan |
| `docs/contract.md` | How the app uses the server contract (the contract itself is in the server repo) |
| `app/src/main/java/fi/ak/smartvoice/` | The code; `AGENTS.md` has the package map |

The GenieX/NPU runtime, the old IME keyboard and the Hermes build host were removed. Do not
resurrect them; treat any mention of them as stale.
