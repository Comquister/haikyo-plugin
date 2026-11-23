# Commands & Permissions

## Paper & Folia (`/haikyo`)

| Command | Permission | Default | Description |
|---|---|---|---|
| `/haikyo status [player]` | `haikyo.command.status` | `op` | Displays connected players' Haikyo status, client version, trust level (`NONE` / `ATTESTED` / `SUSPECT`), sampling flag, and signal count. |
| `/haikyo pardon <player\|uuid>` | `haikyo.command.pardon` | `op` | Removes a player from `requires-haikyo.json` so they are no longer individually required to join with Haikyo. |
| `/haikyo suspect <player> [reason]` | `haikyo.command.suspect` | `op` | Marks an online player as `SUSPECT` (disabling relaxed anticheat checks) and adds them to `requires-haikyo.json` if `promote-suspect-to-require` is enabled. |
| `/haikyo reload` | `haikyo.command.reload` | `op` | Reloads `config.yml`, localization overrides, mod rules, and pushes updated rules to online Haikyo clients. |

### Parent Permission

- `haikyo.admin` (`default: op`) grants all `haikyo.command.*` permissions.

## Velocity (`/haikyoproxy`, alias `/hkp`)

| Command | Permission | Description |
|---|---|---|
| `/haikyoproxy status` | `haikyo.command.status` | Displays proxy-wide Haikyo session status across all connected players. |
| `/haikyoproxy pardon <player\|uuid>` | `haikyo.command.pardon` | Removes a player from the proxy's `requires-haikyo.json`. |
| `/haikyoproxy reload` | `haikyo.command.reload` | Reloads proxy `config.yml` and attestation keys/revocations. |
