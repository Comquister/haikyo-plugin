# Haikyo Plugin

[![License: AGPL v3+](https://img.shields.io/badge/License-AGPLv3%2B-blue.svg)](LICENSE)

Official server-side plugin for integrating Minecraft servers (**Paper**, **Folia**, and **Velocity**) with the **Haikyo Client**.

- **Protocol negotiation (`haikyo:*`)**: vanilla and non-Haikyo players continue playing normally unless `require-haikyo` is enabled.
- **Ed25519 client attestation**: verifies short-lived attestation tokens using built-in JDK Ed25519 (zero external crypto dependencies) and supports key rotation and version revocation.
- **Server-side mod control (`mods.rules`)**: `allow`, `limit` (with arbitrary mod-declared parameters such as `zoom.max_factor`), or `block` any Haikyo mod, audited via signed integrity reports.
- **Integrated anticheat trust engine**: assigns per-player trust levels (`NONE`, `ATTESTED`, `SUSPECT`) with configurable `always` vs `relaxed` check tables, randomized unrelaxed sampling (default 5%), `haikyo:ac` gameplay input corroboration, and adapters for **GrimAC**, **Vulcan**, and **Generic** anticheats.
- **Native Folia support**: single Paper/Folia JAR (`folia-supported: true`) using only Folia entity/region/async schedulers and thread-safe state.
- **Velocity proxy support**: authenticates players once at the proxy and forwards HMAC-SHA256 signed sessions over `haikyo:link` to backend servers.
- **Public Java API (`haikyo-api`)**: lets third-party plugins and server mods register custom `haikyo:mod/<id>` channels and react to trust/signal events.
- **3 Languages out of the box**: English (`en_us`), Brazilian Portuguese (`pt_br`), and Spanish (`es_es`).

---

## Resumo em Português (PT-BR)

Plugin oficial do servidor para integração com o **Haikyo Client** em **Paper**, **Folia** e **Velocity**:

- **Compatível com servidores comuns**: jogadores sem o Haikyo continuam jogando normalmente (exceto quando `require-haikyo: true` ou listados em `requires-haikyo.json`).
- **Atestado Ed25519** sem dependências externas de criptografia, com rotação de chaves e revogação por versão.
- **Controle dinâmico de mods (`mods.rules`)**: `allow`, `limit` (com parâmetros declarados por cada mod) e `block`, conferido pelo relatório assinado do cliente.
- **Anticheat integrado**: níveis de confiança (`NONE`, `ATTESTED`, `SUSPECT`), tabela de checks `always` vs `relaxed`, amostragem aleatória de 5% sem relaxamento e adaptadores para **GrimAC**, **Vulcan** e modo **Genérico**.
- **Paper + Folia no mesmo JAR** (sem uso de `Bukkit.getScheduler()`) e **Velocity** com transferência de sessão assinada via HMAC-SHA256 (`haikyo:link`).
- **Licença AGPL-3.0-or-later** (copyleft forte, no mesmo espírito do Linux e do GrimAC).

---

## Modules

| Module | Description |
|---|---|
| `haikyo-api` | Public Java API for third-party plugins & server mods (`HaikyoApi`, `TrustLevel`, `PlayerSession`, listeners). |
| `haikyo-core` | Pure-Java core (wire framing & fragmentation, Ed25519 attestation verifier, `haikyo:link` HMAC codec, `mods.rules` engine, `TrustManager`, localization, `requires-haikyo.json` store). Zero platform imports. |
| `haikyo-paper` | Self-contained Paper & Folia plugin JAR (`folia-supported: true`), `/haikyo` command, GrimAC / Vulcan / Generic adapters, and example `waypoints` server mod. |
| `haikyo-velocity` | Self-contained Velocity 4.x proxy plugin JAR implementing the `proxy` role and `haikyo:link` session forwarding. |

## Requirements

- **Java 25** (required by Paper 26.3 / Folia 26.3 / Velocity 4.3.1)
- **Paper 26.3+**, **Folia 26.3+**, or **Velocity 4.3.1+**

## Quick Start

1. Build the JARs (or download from Releases):
   ```bash
   ./gradlew build
   ```
   Output JARs:
   - Paper / Folia: `haikyo-paper/build/libs/haikyo-paper-<version>.jar`
   - Velocity: `haikyo-velocity/build/libs/haikyo-velocity-<version>.jar`
2. Drop the JAR into your server's `plugins/` directory and start the server to generate `plugins/Haikyo/config.yml`.
3. Configure your attestation public key(s) under `attestation.public-keys` and (if using Velocity) set the same `link.shared-secret` on both Velocity and Paper/Folia backends.
4. Run `/haikyo reload` (or `/haikyoproxy reload` on Velocity).

## Documentation

- [Configuration Reference](docs/configuration.md)
- [Commands & Permissions](docs/permissions.md)
- [Privacy & LGPD/GDPR Notice for Server Owners](docs/privacy.md)

## Public API Example (`haikyo-api`)

```java
HaikyoApiProvider.get().ifPresent(api -> {
    api.registerModEndpoint("waypoints", "1", (playerId, modId, payload) -> {
        // Handle opaque bytes sent by the client on haikyo:mod/waypoints
        api.sendToPlayer(playerId, "waypoints", "{\"op\":\"clear\"}".getBytes(StandardCharsets.UTF_8));
    });

    api.addTrustChangeListener(event -> {
        if (event.current() == TrustLevel.SUSPECT) {
            // React to suspect transition
        }
    });
});
```

## License

This project is licensed under the **GNU Affero General Public License v3.0 or later (AGPL-3.0-or-later)** — see [LICENSE](LICENSE) for the full text.
