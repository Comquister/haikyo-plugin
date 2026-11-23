# Privacy & Data Protection Notice (LGPD / GDPR)

This document explains what data the **Haikyo Server Plugin** processes and what server owners should disclose to their players under applicable privacy laws such as **LGPD (Brazil)** and **GDPR (EU/EEA)**.

## Player Consent First

The Haikyo Client never sends attestation tokens, integrity reports, mod state lists, or gameplay input telemetry (`haikyo:ac`) before the player explicitly accepts the server's requested scopes on the first-join consent screen.

- Consent is scoped per server or proxy identity (`server-id`).
- If a server adds new consent scopes later, the client re-prompts the player.
- Players may decline and disconnect at any time.

## Data Processed by This Plugin (Phases 1–4)

| Data | Purpose | Persistence |
|---|---|---|
| Player UUID, client version, release channel | Protocol handshake & attestation verification | In-memory session only (cleared on disconnect) |
| Signed Ed25519 attestation token & nonce | Verifying official client build authenticity | Verified in-memory; tokens are not stored on disk |
| Active Haikyo mod list & declared parameters | Auditing compliance with server `mods.rules` | In-memory session only |
| Integrity signals & trust level (`NONE`/`ATTESTED`/`SUSPECT`) | Determining anticheat check relaxation | In-memory session only |
| Gameplay input ticks (`haikyo:ac`: WASD, jump, sneak, sprint, attack, use, look deltas) | Corroborating anticheat movement/combat flags | Rolling ~10-second in-memory ring buffer only — **never written to disk**, and **never captures typed chat or text keys** |
| `requires-haikyo.json` (UUID, username, reason, timestamp, actor) | Requiring previously flagged accounts to join with attested Haikyo | Stored locally in `plugins/Haikyo/requires-haikyo.json` until removed via `/haikyo pardon` |

## What This Plugin Does NOT Collect

- No typed text, chat keystrokes, or OS-level keylogging.
- No scanning of other running processes or unrelated files on the player's device.
- No global ban list or third-party telemetry sharing.

## Responsibilities of Server Owners

As the server operator, you are the **data controller** for any player data stored on your server (including `requires-haikyo.json` and server logs):

1. Inform players in your server rules / privacy policy if you enable `require-haikyo` or gameplay input telemetry (`consent-scopes: [..., inputs]`).
2. Only request the `consent-scopes` you actually use.
3. Protect `link.shared-secret` in `config.yml` and restrict administrative permissions (`haikyo.admin`) to trusted staff.
4. Honor player data deletion requests (e.g. removing entries via `/haikyo pardon <player>` and rotating logs).
