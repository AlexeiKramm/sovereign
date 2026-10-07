package io.github.alexeikramm.sovereign.server

import io.github.alexeikramm.sovereign.dictation.SessionArbiter

/**
 * Maps the process-wide [SessionArbiter]'s current holder to the wire `SERVER_STATE` frame
 * (PROTOCOL.md §6, CHUNK-8). The arbiter is the one source of truth for "is the pipeline taken", so
 * both the on-connect advertisement and the change broadcast use this single mapping.
 *
 * The arbiter knows only *which kind* of surface holds the lease, so a `SERVER` holder (a desktop
 * session) cannot be named per-connection here — it reads `"another desktop"`, matching
 * `SovereignServerEngine.busyPayload`. A `RESERVED` holder is a phone-local capture, so the phone
 * itself is the holder.
 *
 * Pure JVM: no Android, so the mapping is unit-tested.
 */
internal fun serverStatePayload(owner: SessionArbiter.Owner?, identity: ServerIdentity): ServerStatePayload =
    when (owner) {
        null -> ServerStatePayload(ServerState.IDLE, "", "")
        SessionArbiter.Owner.PILL,
        SessionArbiter.Owner.TRANSCRIBE,
        -> ServerStatePayload(ServerState.RESERVED, identity.deviceId, identity.serverName)

        SessionArbiter.Owner.SERVER -> ServerStatePayload(ServerState.BUSY, "", "another desktop")
    }
