# Configuration Reference (`config.yml`)

This file documents all configuration keys supported by `haikyo-paper` (Paper & Folia), `haikyo-velocity` (Velocity), and `haikyo-fabric` (Fabric).

## General

| Key | Type | Default | Description |
|---|---|---|---|
| `language` | string | `en_us` | Default language for console logs and fallback messages (`en_us`, `pt_br`, `es_es`). Players see messages in their own client locale when supported. |
| `role` | string | `auto` | `auto`, `standalone`, `backend`, or `proxy`. In `auto` mode, Paper/Folia inspects `config/paper-global.yml` for Velocity forwarding and uses `backend` if `link.shared-secret` is set, otherwise safely falls back to `standalone` with a warning. |
| `server-address` | string | `""` | Canonical public address (e.g. `play.example.com:25565`) validated against signed attestation tokens. Leave empty to accept any address. |
| `server-id` | string | `"haikyo-server"` | Identifier advertised to clients in `haikyo:control` `hello` (used by clients to scope per-server/per-network consent and default HWID salt). |
| `download-url` | string | `"https://haikyo.conquister.net"` | URL included in kick messages when Haikyo is required. |
| `require-haikyo` | boolean | `false` | When `true`, all players must complete Haikyo attestation within `attestation-timeout-seconds`. |
| `attestation-timeout-seconds` | int | `15` | Grace period before kicking a player who is required to use Haikyo (globally or via `requires-haikyo.json`). |
| `consent-scopes` | list | `[version, integrity, mods, inputs]` | Scopes requested on the client's first-join consent screen. Optional admin scopes: `environment`, `screenshot`. |

## Attestation (`attestation.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `attestation.public-keys` | map | `{}` | Map of `key_id -> Base64 Ed25519 public key` (accepts raw 32-byte keys or X.509 SPKI DER keys). Never place private keys here. |
| `attestation.revoked-versions` | list | `[]` | Client version strings explicitly rejected by this server. |
| `attestation.require-no-agent` | boolean | `true` | Marks a player `SUSPECT` if their integrity report indicates Agent Control (`--control`) is active. |
| `attestation.report-interval-min-seconds` | int | `15` | Minimum randomized interval between client integrity reports. |
| `attestation.report-interval-max-seconds` | int | `45` | Maximum randomized interval between client integrity reports. Missing reports beyond `2 * max + 10s` mark the player `SUSPECT`. |

## Proxy Link (`link.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `link.shared-secret` | string | `""` | Shared HMAC-SHA256 secret between Velocity (`proxy`) and Paper/Folia (`backend`). Must be identical on both sides. If empty or invalid on a backend, the backend rejects forged proxy claims and falls back to `standalone`. |
| `link.session-ttl-seconds` | int | `60` | Validity duration of signed `haikyo:link` transfer frames. |
| `link.sync-requires-to-proxy` | boolean | `true` | On backend (Paper/Folia): propagates `requires-haikyo.json` promotions (`/haikyo suspect` and `always`-check anticheat flags) and `/haikyo pardon` to Velocity over signed `haikyo:link` (`0x03`). Set `false` to keep `requires-haikyo.json` strictly local to the backend. |
| `link.accept-backend-requires-sync` | boolean | `true` | On Velocity (`proxy`): controls whether the proxy accepts signed `haikyo:link` (`0x03`) require/pardon sync frames from backend servers. |

## Mod Rules (`mods.*`)

```yaml
mods:
  rules:
    zoom:
      action: limit          # allow | limit | block
      params:
        max_factor: 2.0      # arbitrary mod-declared parameters
      reason: "PvP zoom cap"
    minimap:
      action: block
      reason: "Disabled on this server"
  waypoints:
    enabled: true
    markers:
      - id: spawn
        name: Spawn
        world: minecraft:overworld
        x: 0.0
        y: 64.0
        z: 0.0
        color: "#FF6B35"
```

Clients apply `mods.rules` locally and report active mod states in signed integrity reports. Any mismatch emits a `ModSignalEvent` and transitions the player to `SUSPECT`.

## Integrated Anticheat (`anticheat.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `anticheat.sampling-fraction` | double | `0.05` | Fraction of `ATTESTED` players (0.0–1.0) randomly selected to run ALL checks unrelaxed for ongoing false-positive measurement and integrity auditing. |
| `anticheat.promote-suspect-to-require` | boolean | `true` | Automatically adds players flagged by `always` checks or `/haikyo suspect` to `requires-haikyo.json`. |
| `anticheat.use-input-telemetry` | boolean | `true` | Verifies signed `haikyo:ac` per-tick gameplay inputs (movement/look/attack/use only — never chat/text keys) to corroborate relaxed flags. |
| `anticheat.checks.<ac>.<check>` | `always` \| `relaxed` | `always` | Classifies checks per anticheat (`grim`, `vulcan`, `generic`). Supports trailing `*` wildcards (e.g. `aim*`). Unlisted checks ALWAYS default to `always`. |
| `anticheat.generic-adapter.enabled` | boolean | `false` | Enables command-based flag filtering for other anticheats. |
| `anticheat.generic-adapter.command-patterns` | list | `[]` | Command prefixes to filter (`"<prefix>"` or `"<prefix>|<checkName>"`). |

### Default Anticheat Check Table

| Anticheat | `always` (NEVER relaxed) | `relaxed` (cancelled for non-sampled `ATTESTED`) |
|---|---|---|
| **GrimAC** | `reach`, `timer`, `badpackets*`, `fly*`, `speed*`, all unlisted checks | `simulation`, `aim*`, `post*`, `groundspoof` |
| **Vulcan** | `reach*`, `flight*`, `speed*`, `timer*`, `badpackets*`, all unlisted checks | `aim*`, `strafe*`, `groundspoof*` |
| **Generic** | `reach*`, `fly*`, `speed*`, all unlisted checks | `aim*`, `simulation*` |

- **GrimAC**: Automatically hooked when GrimAC is installed (soft-depend). Cancels `FlagEvent` only for `relaxed` checks on non-sampled `ATTESTED` players.
- **Vulcan**: Hooked via runtime reflection on `me.frep.vulcan.api.event.VulcanFlagEvent` (no proprietary JAR bundled). Ensure `settings.enable-api: true` is set in Vulcan's config.
- **Generic**: Filters configured commands via `anticheat.generic-adapter` and accepts external suspect reports via `/haikyo suspect <player> [reason]`.

## Admin Tools (`admin.*`)

| Key | Type | Default | Description |
|---|---|---|---|
| `admin.hwid-salt` | string | `""` | Per-server salt mixed into the client's SHA-256 HWID derivation (`defaults to server-id` when empty). Two servers always see different HWIDs for the same PC. |
| `admin.audit-retention-days` | int | `30` | Days before entries in `audit.log` are automatically pruned. `audit.log` stores who/whom/when only — NEVER screenshot images. |
| `admin.screenshot.delivery` | string | `builtin` | `builtin`, `upload`, or `file`. |
| `admin.screenshot.ttl-seconds` | int | `3600` | Link & file expiry (default 1 hour). Expired tokens return HTTP `404` and delete the file from disk. |
| `admin.screenshot.max-width` / `max-height` | int | `1280` / `720` | Maximum downscaled dimensions requested from the client. |
| `admin.screenshot.jpeg-quality` | int | `75` | JPEG compression quality (`20..95`). |
| `admin.screenshot.max-bytes` | int | `1048576` | Maximum JPEG size (`<= 1 MiB`). |
| `admin.screenshot.builtin.bind-host` / `port` | string / int | `"0.0.0.0"` / `8765` | Embedded HTTP server bind address for serving `/haikyo/s/<128-bit-token>`. |
| `admin.screenshot.builtin.public-url` | string | `""` | Public base URL (e.g. `https://screens.example.com:8765`). If empty, automatically falls back safely to `file` mode (`plugins/Haikyo/screens/`). |
| `admin.screenshot.upload.url` / `auth-header` | string | `""` | Owner-controlled HTTP `PUT` endpoint (supports `{token}` placeholder) and optional `Authorization` header. |

## Voice Chat (`voice.*`, Mode A — Simple Voice Chat)

| Key | Type | Default | Description |
|---|---|---|---|
| `voice.mode` | string | `auto` | `auto` (enables Mode A if Simple Voice Chat is installed), `svc`, or `off`. |
| `voice.max-distance` | double | `48.0` | Server-enforced normal voice range in blocks. |
| `voice.whisper-distance` | double | `16.0` | Server-enforced whisper voice range in blocks. |
| `voice.falloff-mode` | string | `natural` | Attenuation curve sent to Haikyo clients (`natural`, `linear`, `inverse`, `exponential`). |
| `voice.falloff-factor` | double | `1.0` | Attenuation multiplier. |
| `voice.allow-groups` | boolean | `true` | Whether players may join SVC groups (enforced via `JoinGroupEvent`). |
| `voice.recording-allowed` | boolean | `false` | Recording is disabled by default; this plugin never records audio. |
