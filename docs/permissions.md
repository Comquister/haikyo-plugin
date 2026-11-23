# Commands & Permissions

## Paper, Folia & Fabric (`/haikyo`)

| Command | Permission (Paper/Folia) | Default / Fabric Level | Description |
|---|---|---|---|
| `/haikyo status [player]` | `haikyo.command.status` | `op` / level 2 | Displays connected players' Haikyo status, client version, trust level (`NONE` / `ATTESTED` / `SUSPECT`), sampling flag, and signal count. |
| `/haikyo info <player>` | `haikyo.admin.info` | `op` / level 2 | Requests client build, OS/CPU/GPU/RAM/display, average FPS, salted HWID, active mods, and guard signals. Logged in `audit.log`. |
| `/haikyo hwid <player>` | `haikyo.admin.info` | `op` / level 2 | Requests the player's salted per-server SHA-256 HWID. Logged in `audit.log`. |
| `/haikyo screenshot <player>` | `haikyo.admin.screenshot` | `op` / level 2 | Requests the next rendered in-game frame (game-only, mandatory HUD notice to player) delivered privately to the requesting admin via temporary link or file. Logged in `audit.log` (no image). |
| `/haikyo voice <mute\|unmute\|forcemute> <player> [seconds] [reason]` | `haikyo.admin.voice` | `op` / level 2 | Moderates a player's Simple Voice Chat audio (cancels mic packets server-side and pushes `haikyo:voice` state to the Haikyo client). Logged in `audit.log`. |
| `/haikyo pardon <player\|uuid>` | `haikyo.command.pardon` | `op` / level 2 | Removes a player from `requires-haikyo.json` so they are no longer individually required to join with Haikyo. |
| `/haikyo suspect <player> [reason]` | `haikyo.command.suspect` | `op` / level 2 | Marks an online player as `SUSPECT` (disabling relaxed anticheat checks) and adds them to `requires-haikyo.json` if `promote-suspect-to-require` is enabled. |
| `/haikyo reload` | `haikyo.command.reload` | `op` / level 2 | Reloads `config.yml`, localization overrides, mod rules, screenshot delivery settings, and pushes refreshed rules/voice config to online Haikyo clients. |

### Parent Permission (Paper/Folia)

- `haikyo.admin` (`default: op`) grants all `haikyo.command.*` and `haikyo.admin.*` permissions.

## Velocity (`/haikyoproxy`, alias `/hkp`)

| Command | Permission | Description |
|---|---|---|
| `/haikyoproxy status` | `haikyo.command.status` | Displays proxy-wide Haikyo session status across all connected players. |
| `/haikyoproxy pardon <player\|uuid>` | `haikyo.command.pardon` | Removes a player from the proxy's `requires-haikyo.json`. |
| `/haikyoproxy reload` | `haikyo.command.reload` | Reloads proxy `config.yml` and attestation keys/revocations. |
