# Local Voice IME

An Android keyboard (IME) that turns speech into text in any app, backed by the
user's own machines — the [Local Voice Stack](../local-voice-stack) server when it
is reachable, an on-device pipeline when it is not. No cloud speech service is
involved at any point.

**State: v1 shipped 2026-09-11, verified on the device.** One APK with two entry
points: a settings/diagnostics screen and a dictation panel (IME) that posts audio to
`local-voice-stack` and commits the cleaned text at the cursor. `docs/v1-report.md` has the
evidence; `STATUS.md` is what is true now, `PLAN.md` is intent, `DECISIONS.md` is why.

## How this repo is organised

| File | Answers | Goes stale? |
|---|---|---|
| `HANDOFF.md` | Where does a new session start, and what is the first job? | Rewritten at the end of each working session. |
| `PLAN.md` | What are we building, how does it work, in what order? | Yes — intent drifts. Re-read before each chunk. |
| `STATUS.md` | What is true right now? | Must be current. Single screen. |
| `DECISIONS.md` | Why is it this way, and what did we reject? | Append-only. Never edited, only superseded. |
| `docs/contract.md` | How do the app and the server talk, and where are the gaps? | Updated whenever either side changes an interface. |
| `docs/build.md` | How is this built and deployed without Android Studio, and what version pitfalls exist? | Update when the toolchain or a dependency forces a change. |
| `docs/spikes.md` | How does a time-boxed experiment get run, so its number can be re-taken? | Append per spike; update the status line when one is run. |
| `docs/v1-report.md` | What did v1 ship, what was verified, what broke, what could v2 be? | Closed out at the end of v1; new evidence goes to the appendix. |
| `docs/prior-art.md` | What other projects and the research line actually do, with numbers. | Append as new evidence arrives; never rewritten. |
| `README.md` | This file. Orientation only. | Rarely. |

Three rules that keep these documents honest:

1. **The code is the source of truth for reality; the plan is the source of truth
   for intent.** When they disagree, the code wins and the plan gets fixed.
2. **Any claim about code cites `file:line`.** If it cannot be cited, it does not
   belong in `STATUS.md`.
3. **Nothing is "implemented" until it is verified by real output.** Written is
   not working.

## Relationship to the server repo

`../local-voice-stack` is a separate project with its own docs and its own
**frozen** transport contract (`../local-voice-stack/docs/CONTRACT.md`, version
`1.0`, hash-guarded). This app is a *consumer* of that contract. It does not
redefine it, and it does not keep a copy — see `docs/contract.md` for how the two
stay in sync.
