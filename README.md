# Haikyo Plugin

[![License: AGPL v3+](https://img.shields.io/badge/License-AGPLv3%2B-blue.svg)](LICENSE)

Official server-side plugin and mod suite for integrating Minecraft servers (**Paper**, **Folia**, **Velocity**, and **Fabric**) with the **Haikyo Client**.

- **Protocol negotiation (`haikyo:*`)**: vanilla and non-Haikyo players continue playing normally unless `require-haikyo` is enabled.
- **Ed25519 client attestation**: verifies short-lived attestation tokens and signed rules packages (`HKRL`) using built-in JDK Ed25519 (zero external crypto dependencies) with key rotation and version revocation.
- **Server-side mod control (`mods.rules`)**: `allow`, `limit` (with arbitrary mod-declared parameters such as `zoom.max_factor`), or `block` any Haikyo mod, audited via signed integrity reports.
- **Integrated anticheat trust engine**: assigns per-player trust levels (`NONE`, `ATTESTED`, `SUSPECT`) with configurable `always` vs `relaxed` check tables, randomized unrelaxed sampling (default 5%), `haikyo:ac` gameplay input corroboration, and adapters for **GrimAC**, **Vulcan**, and **Generic** anticheats.
- **Native Paper, Folia & Fabric support**:
  - Single Paper/Folia JAR (`folia-supported: true`) using only Folia entity/region/async schedulers and thread-safe state.
  - Native **Fabric 26.3** server mod (`haikyo-fabric`) sharing the exact same `haikyo-core` engine.
  - **Velocity 4.x** proxy plugin (`haikyo-velocity`) authenticating once at the proxy and forwarding HMAC-SHA256 signed sessions over `haikyo:link`.
- **Admin tools (`haikyo:admin`)**: `/haikyo info`, `/haikyo hwid` (salted per-server SHA-256), and `/haikyo screenshot` (game-only capture with mandatory player HUD banner, temporary 128-bit token links or file/upload delivery, and 30-day `audit.log` without image bytes).
- **Simple Voice Chat Mode A (`haikyo:voice`)**: soft-depend integration with Simple Voice Chat applying server distance/falloff overrides and mute/force-mute moderation while keeping native SVC UDP transport and zero audio recording by default.
- **Public Java API (`haikyo-api`)**: lets third-party plugins and server mods register custom `haikyo:mod/<id>` channels and react to trust/signal events.
- **3 Languages out of the box**: English (`en_us`), Brazilian Portuguese (`pt_br`), and Spanish (`es_es`).

---

## Resumo em Português (PT-BR)

Plugin e mod oficial do servidor para integração com o **Haikyo Client** em **Paper**, **Folia**, **Velocity** e **Fabric**:

- **Compatível com servidores comuns**: jogadores sem o Haikyo continuam jogando normalmente (exceto quando `require-haikyo: true` ou listados em `requires-haikyo.json`).
- **Atestado Ed25519** sem dependências externas de criptografia, com rotação de chaves e revogação por versão.
- **Controle dinâmico de mods (`mods.rules`)**: `allow`, `limit` (com parâmetros declarados por cada mod) e `block`, conferido pelo relatório assinado do cliente.
- **Anticheat integrado**: níveis de confiança (`NONE`, `ATTESTED`, `SUSPECT`), tabela de checks `always` vs `relaxed`, amostragem aleatória de 5% sem relaxamento e adaptadores para **GrimAC**, **Vulcan** e modo **Genérico**.
- **Comandos de admin (`haikyo:admin`)**: `/haikyo info`, `/haikyo hwid` (sal por servidor) e `/haikyo screenshot` (captura só do jogo, aviso obrigatório no HUD, link temporário de 1h com token de 128 bits e `audit.log` sem imagens).
- **Voice chat integrado (Modo A)**: integração opcional ao **Simple Voice Chat** com alcance/queda definidos pelo servidor e moderação (`mute`/`forcemute`), sem gravação de áudio por padrão.
- **Paper + Folia no mesmo JAR** (sem `Bukkit.getScheduler()`), **Velocity** (`haikyo:link` HMAC-SHA256) e **Fabric 26.3** compartilhando o mesmo `haikyo-core`.
- **Licença AGPL-3.0-or-later** (copyleft forte também para código em rede).

---

## Modules

| Module | Platform | Description |
|---|---|---|
| `haikyo-api` | All (Java) | Public Java API for third-party plugins & server mods (`HaikyoApi`, `TrustLevel`, `PlayerSession`, listeners). |
| `haikyo-core` | All (Java) | Pure-Java core (wire framing & fragmentation, Ed25519 attestation & `HKRL` rules verifier, `haikyo:link` HMAC codec, `mods.rules`, `TrustManager`, `AuditLogger`, `ScreenshotDeliveryService`, `VoiceModerationManager`, localization, `requires-haikyo.json`). Zero platform imports. |
| `haikyo-paper` | Paper & Folia 26.3+ | Self-contained Paper/Folia plugin JAR (`folia-supported: true`), strict Folia region/entity scheduler compatibility, full `/haikyo` commands, GrimAC / Vulcan / Generic anticheat adapters, Simple Voice Chat Mode A adapter, and `waypoints` sync. |
| `haikyo-velocity` | Velocity 4.3.1+ | Self-contained Velocity proxy plugin JAR implementing the `proxy` role and `haikyo:link` session forwarding. |
| `haikyo-fabric` | Fabric 26.3 | Self-contained Fabric server mod JAR reusing `haikyo-core`, Brigadier `/haikyo` commands, `waypoints`, `haikyo:admin`, and Simple Voice Chat Mode A entrypoint. |
| `haikyo-pumpkin` | PumpkinMC (Rust Native) | High-performance native Rust cdylib plugin for PumpkinMC servers (Minecraft 26.3). Wire framing, Ed25519 offline verification, `/haikyo` commands, and `haikyo:link` (`HKRQ`) suspect synchronization with Velocity. |
| `haikyo-pumpkin-wasm` | PumpkinMC (Wasm Component) | Universal WebAssembly Component plugin targeting `pumpkin-plugin-api` (Wasm/WIT). Sandboxed execution across operating systems. |

## Platform Feature Matrix

Comparison of features across server implementations:

| Feature / Subsystem | Paper & Folia (`haikyo-paper`) | Fabric (`haikyo-fabric`) | Velocity (`haikyo-velocity`) | PumpkinMC (`haikyo-pumpkin`) |
|---|:---:|:---:|:---:|:---:|
| **Language & Runtime** | Java 25 | Java 25 | Java 25 | Rust (Native cdylib / Wasm) |
| **Folia Compatibility** | ✅ Full (`folia-supported: true`) | N/A | N/A | N/A (Rust multithreaded) |
| **Wire Framing & Defrag** | ✅ Yes (64 frags, SHA-256) | ✅ Yes | ✅ Yes | ✅ Yes |
| **Ed25519 Client Attestation** | ✅ Yes (`HKAT` v1) | ✅ Yes | ✅ Yes (at edge) | ✅ Yes |
| **Dynamic Mod Control (`mods.rules`)** | ✅ Yes | ✅ Yes | N/A (transparent) | ✅ Yes (basic allowed rules) |
| **Anticheat Trust Engine** | ✅ Full (GrimAC, Vulcan, Generic) | ⚠️ Generic only | N/A | ⚠️ Generic / Command only |
| **Admin Commands** | ✅ `info`, `hwid`, `screenshot`, `suspect`, `pardon` | ✅ Full | ✅ `reload`, `info`, `find` | ✅ `info`, `suspect`, `pardon` |
| **Screenshot Delivery** | ✅ Embedded HTTP Server (60s token) | ⚠️ File save | N/A | ⚠️ Console / Audit log |
| **Proxy Link (`haikyo:link`)** | ✅ Receives `HKLK`, sends `HKRQ` | ✅ Receives `HKLK`, sends `HKRQ` | ✅ Issues `HKLK`, receives `HKRQ` | ⚠️ Sends `HKRQ` (Suspect sync); `HKLK` intake planned |
| **Periodic Integrity Sweeps** | ✅ 30s async task | ✅ 30s server tick task | ✅ Periodic sweep | ⚠️ Handshake/attest only |
| **Voice Chat (`haikyo:voice`)** | ✅ Simple Voice Chat Mode A | ✅ Simple Voice Chat Mode A | N/A | ❌ Planned |
| **LuckPerms Contexts** | ✅ Yes (`haikyo:attested`) | ✅ Yes | ✅ Yes | ❌ Not available on Pumpkin |
| **Official Waypoints Mod** | ✅ Yes (`haikyo:mod/waypoints`) | ✅ Yes | N/A | ❌ Planned |


## Requirements

- **Java 25** (required by Paper 26.3 / Folia 26.3 / Velocity 4.3.1 / Fabric 26.3)
- **Paper 26.3+**, **Folia 26.3+**, **Velocity 4.3.1+**, or **Fabric 26.3** (with Fabric API)

## Quick Start

1. Build all JARs (or download from Releases):
   ```bash
   ./gradlew build
   ```
   Output JARs:
   - Paper / Folia: `haikyo-paper/build/libs/haikyo-paper-<version>.jar`
   - Velocity: `haikyo-velocity/build/libs/haikyo-velocity-<version>.jar`
   - Fabric: `haikyo-fabric/build/libs/haikyo-fabric-<version>.jar`
   - Public API: `haikyo-api/build/libs/haikyo-api-<version>.jar`
2. Place the matching JAR in `plugins/` (Paper/Folia/Velocity) or `mods/` (Fabric) and start the server to generate `config.yml`.
3. Configure your attestation public key(s) under `attestation.public-keys` and (if using Velocity) set the same `link.shared-secret` on both Velocity and Paper/Folia backends.
4. Run `/haikyo reload` (or `/haikyoproxy reload` on Velocity).

## Documentation

- [Configuration Reference & Anticheat Check Table](docs/configuration.md)
- [Commands & Permissions](docs/permissions.md)
- [Public API, LuckPerms Contexts & Dynamic Permissions](docs/api.md)
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
