# Changelog

## v0.1.0 — 2026-10-16

First public release.

- Dictation into any app from a floating pill: an overlay plus an accessibility
  service that inserts the text at your cursor.
- Fully on-device: streaming speech recognition (sherpa-onnx) and an LLM cleanup
  pass (LiteRT-LM). No cloud, no accounts, no telemetry.
- The phone can act as a dictation server for a Windows desktop, over an
  encrypted local-network link.
- Profiles, custom dictionary, per-app cleanup profiles, dictation history, and
  JSON export/import.
- Models download on demand — nothing is bundled in the APK.
- arm64-v8a only.
