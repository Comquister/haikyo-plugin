# Privacy & Data Protection Notice (LGPD / GDPR)

This document explains what data the **Haikyo Server Plugin / Mod** processes and what server owners should disclose to their players under applicable privacy laws such as **LGPD (Brazil)** and **GDPR (EU/EEA)**.

## Player Consent & Transparency First

The Haikyo Client never sends attestation tokens, integrity reports, mod state lists, environment info, HWID, screenshots, or gameplay input telemetry (`haikyo:ac`) before the player explicitly accepts the server's requested scopes on the first-join consent screen.

- Consent is scoped per server or proxy identity (`server-id`).
- If a server adds new consent scopes later, the client re-prompts the player.
- Players may decline and disconnect at any time.
- **Mandatory HUD Notification**: Whenever an administrator executes `/haikyo screenshot <player>` (or requests sensitive data), the Haikyo Client displays an un-disableable HUD banner notifying the player (e.g. *"An admin requested an in-game screenshot"*).

## Data Processed by This Plugin

| Data | Purpose | Persistence & Retention |
|---|---|---|
| Player UUID, client version, release channel | Protocol handshake & attestation verification | In-memory session only (cleared on disconnect) |
| Signed Ed25519 attestation token & nonce | Verifying official client build authenticity | Verified in-memory; tokens are not stored on disk |
| Active Haikyo mod list & declared parameters | Auditing compliance with server `mods.rules` | In-memory session only |
| Integrity signals & trust level (`NONE`/`ATTESTED`/`SUSPECT`) | Determining anticheat check relaxation | In-memory session only |
| Gameplay input ticks (`haikyo:ac`: WASD, jump, sneak, sprint, attack, use, look deltas) | Corroborating anticheat movement/combat flags | Rolling ~10-second in-memory ring buffer only — **never written to disk**, and **never captures typed chat or text keys** |
| Salted per-server HWID (`/haikyo hwid`, `/haikyo info`) | Anti-alt / ban-evasion checks | Derived client-side with a per-server salt (`SHA-256`). Different servers receive different hashes; the attestation service never sees it. Displayed to the requesting admin only. |
| In-game screenshots (`/haikyo screenshot`) | Visual verification of game rendering only (no desktop/OS overlays) | Stored temporarily under `screens/<128-bit-token>.jpg` (or owner's `upload` endpoint) and **automatically deleted after `ttl-seconds` (default 1 hour)**; expired links return HTTP `404`. |
| `audit.log` (timestamp, admin actor, target UUID/name, action) | Accountability for admin tool usage | Stored locally in `audit.log` (**never includes image bytes**); entries older than `audit-retention-days` (default 30 days) are automatically pruned. |
| `requires-haikyo.json` (UUID, username, reason, timestamp, actor) | Requiring previously flagged accounts to join with attested Haikyo | Stored locally until removed via `/haikyo pardon`. |
| Voice moderation state (`haikyo:voice`) | Enforcing mute/force-mute and distance overrides with Simple Voice Chat | In-memory only (cleared on disconnect). **Zero audio recording by default.** |

## What This Plugin Does NOT Collect

- No typed text, chat keystrokes, or OS-level keylogging.
- No desktop screenshots outside the game's own framebuffer.
- No scanning of other running processes or unrelated files on the player's device.
- No audio recording by default.
- No global ban list or third-party telemetry sharing.

## Responsibilities of Server Owners

As the server operator, you are the **data controller** for any player data stored on your server (including `requires-haikyo.json`, `audit.log`, and temporary screenshots):

1. Inform players in your server rules / privacy policy about which `consent-scopes` your server requests (especially `inputs`, `environment`, and `screenshot`).
2. Keep `admin.screenshot.ttl-seconds` and `admin.audit-retention-days` as short as operationally reasonable.
3. Protect `link.shared-secret`, `admin.screenshot.upload.auth-header`, and restrict `haikyo.admin.*` permissions to trusted staff.
4. Use HTTPS (via reverse proxy or TLS endpoint) when enabling `admin.screenshot.builtin.public-url` or `upload`.
5. Honor player data access and deletion requests (e.g. `/haikyo pardon <player>` and log rotation).
