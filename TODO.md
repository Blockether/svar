# Stateful Codex session TODO

Svar owns provider transport semantics and observability. Vis may keep an opaque session handle and render Svar's public data, but it must not decide WebSocket, cursor, replay, cache, turn-state, or rotation policy.

- [x] Keep one opaque Svar session across equivalent credential hydration; add the real Codex regression in Vis and leave only generic handle lifecycle there.
- [x] Implement `x-codex-turn-state` in Svar: capture it from WebSocket metadata/handshake, echo it only within one logical tool turn, and reset it at the next user turn.
- [x] Make Svar rotate Responses WebSockets before the provider's 60-minute limit while preserving canonical replay and prompt-cache identity.
- [x] Expose Svar-owned session transport telemetry: prewarm, delta continuation, full replay, reconnect, cursor reset, history reset, fallback, connection age, and rotation.
- [x] Correct the cache metric contract: distinguish provider prompt-cache reads from WebSocket continuation, report freshness and request/token views in Svar, and make Vis a thin renderer that does not seed stale cache samples after restart.
- [x] Abort every terminal Responses stream before retry/replay; recover a forgotten cursor only on a fresh socket.
- [x] Apply caller cancellation to quiet Responses reads and abort, rather than gracefully close, an active session.
- [x] Run focused and full verification, release Svar, bump Vis, and prove one prewarm plus delta continuation in a real session.
- [x] Keep oversized cursorless replays off the uncompressed JDK WebSocket; treat close status 1009 as a deterministic one-turn HTTP bridge and preserve later WebSocket re-entry.
- [ ] Verify, release, bump Vis, and prove oversized HTTP plus compact-history WebSocket re-entry with the real provider.
