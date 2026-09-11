# Local Voice IME

An Android keyboard (IME) that turns speech into text in any app, backed by the
user's own machines — the [Local Voice Stack](../local-voice-stack) server when it
is reachable, an on-device pipeline when it is not. No cloud speech service is
involved at any point.

**State: pre-code.** Nothing is implemented. See `STATUS.md` for what exists,
`PLAN.md` for intent and architecture, `DECISIONS.md` for why the choices were
made, `docs/contract.md` for the interface with the server.

## How this repo is organised

| File | Answers | Goes stale? |
|---|---|---|
| `PLAN.md` | What are we building, how does it work, in what order? | Yes — intent drifts. Re-read before each chunk. |
| `STATUS.md` | What is true right now? | Must be current. Single screen. |
| `DECISIONS.md` | Why is it this way, and what did we reject? | Append-only. Never edited, only superseded. |
| `docs/contract.md` | How do the app and the server talk, and where are the gaps? | Updated whenever either side changes an interface. |
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
