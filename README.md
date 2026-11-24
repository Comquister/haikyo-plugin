# Haikyo Plugin

[![License: AGPL v3+](https://img.shields.io/badge/License-AGPLv3%2B-blue.svg)](LICENSE)

Official server-side plugin and mod suite for integrating Minecraft servers (**Paper**, **Folia**, **Velocity**, **Fabric**, and **PumpkinMC**) with the **Haikyo Client**.

- **Protocol negotiation (`haikyo:*`)**: vanilla and non-Haikyo players continue playing normally unless `require-haikyo` is enabled.
- **Ed25519 client attestation**: verifies short-lived attestation tokens and signed rules packages (`HKRL`) using built-in JDK Ed25519 on Java 15+ and a zero-dependency RFC 8032 pure-Java fallback on Java 8–14, with key rotation and version revocation.
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

Plugin e mod oficial do servidor para integração com o **Haikyo Client** em **Paper**, **Folia**, **Velocity**, **Fabric** e **PumpkinMC**:

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
| `haikyo-paper` | Spigot, Paper, Purpur & Folia 1.8 – 26.3 | Self-contained Java 8+ multi-version plugin JAR (`api-version: '1.13'`, `folia-supported: true`), automatic Folia/Paper regionized scheduler detection with legacy Bukkit fallback, Adventure & legacy text/kick/locale compatibility, full `/haikyo` commands, GrimAC / Vulcan / Generic anticheat adapters, Simple Voice Chat Mode A adapter, and `waypoints` sync. |
| `haikyo-velocity` | Velocity 4.3.1+ | Self-contained Velocity proxy plugin JAR implementing the `proxy` role and `haikyo:link` session forwarding. |
| `haikyo-fabric` | Fabric 26.3 | Self-contained Fabric server mod JAR reusing `haikyo-core`, Brigadier `/haikyo` commands, `waypoints`, `haikyo:admin`, and Simple Voice Chat Mode A entrypoint. |
| `haikyo-pumpkin-wasm` | PumpkinMC (Wasm Component) | Universal WebAssembly Component plugin (`haikyo-pumpkin.wasm`) targeting `pumpkin-plugin-api` (`wasm32-wasip2`). Runs sandboxed across Linux, Windows, and macOS with wire framing, Ed25519 attestation, and `/haikyo` commands. |

## Platform Feature Matrix

Comparison of features across server implementations:

| Feature / Subsystem | Paper & Folia (`haikyo-paper`) | Fabric (`haikyo-fabric`) | Velocity (`haikyo-velocity`) | PumpkinMC (`haikyo-pumpkin-wasm`) |
|---|:---:|:---:|:---:|:---:|
| **Language & Runtime** | Java 8 – 25 (MC 1.8 – 26.3) | Java 25 | Java 25 | Rust (WebAssembly / WASI P2) |
| **Folia Compatibility** | ✅ Full (`folia-supported: true`) | N/A | N/A | N/A (Pumpkin Wasm runtime) |
| **Wire Framing & Defrag** | ✅ Yes (64 frags, SHA-256) | ✅ Yes | ✅ Yes | ✅ Yes |
| **Ed25519 Client Attestation** | ✅ Yes (`HKAT` v1) | ✅ Yes | ✅ Yes (at edge) | ✅ Yes |
| **Dynamic Mod Control (`mods.rules`)** | ✅ Yes | ✅ Yes | N/A (transparent) | ✅ Yes (basic allowed rules) |
| **Anticheat Trust Engine** | ✅ Full (GrimAC, Vulcan, Generic) | ⚠️ Generic only | N/A | ⚠️ Generic / Command only |
| **Admin Commands** | ✅ `info`, `hwid`, `screenshot`, `suspect`, `pardon` | ✅ Full | ✅ `reload`, `info`, `find` | ✅ `info`, `suspect`, `pardon` |
| **Screenshot Delivery** | ✅ Embedded HTTP Server (60s token) | ⚠️ File save | N/A | ❌ Planned |
| **Proxy Link (`haikyo:link`)** | ✅ Receives `HKLK`, sends `HKRQ` | ✅ Receives `HKLK`, sends `HKRQ` | ✅ Issues `HKLK`, receives `HKRQ` | ❌ Planned |
| **Periodic Integrity Sweeps** | ✅ 30s async task | ✅ 30s server tick task | ✅ Periodic sweep | ⚠️ Handshake/attest only |
| **Voice Chat (`haikyo:voice`)** | ✅ Simple Voice Chat Mode A | ✅ Simple Voice Chat Mode A | N/A | ❌ Planned |
| **LuckPerms Contexts** | ✅ Yes (`haikyo:attested`) | ✅ Yes | ✅ Yes | ❌ Not available on Pumpkin |
| **Official Waypoints Mod** | ✅ Yes (`haikyo:mod/waypoints`) | ✅ Yes | N/A | ❌ Planned |


## Requirements

- **Paper / Spigot / Purpur / CraftBukkit / Folia (`haikyo-paper`)**: Minecraft **1.8 – 26.3**, **Java 8+**
- **Velocity (`haikyo-velocity`)**: **Velocity 4.3.1+**, **Java 21/25**
- **Fabric (`haikyo-fabric`)**: **Fabric 26.3** (with Fabric API), **Java 25**
- **PumpkinMC (`haikyo-pumpkin-wasm`)**: **PumpkinMC** (Minecraft **26.3**), single universal `haikyo-pumpkin.wasm` binary (Linux, Windows, macOS)

## Quick Start

1. Build all Java JARs (or download from Releases):
   ```bash
   ./gradlew build
   ```
   Output JARs:
   - Paper / Folia: `haikyo-paper/build/libs/haikyo-paper-<version>.jar`
   - Velocity: `haikyo-velocity/build/libs/haikyo-velocity-<version>.jar`
   - Fabric: `haikyo-fabric/build/libs/haikyo-fabric-<version>.jar`
   - Public API: `haikyo-api/build/libs/haikyo-api-<version>.jar`

   For **PumpkinMC**, download `haikyo-pumpkin.wasm` from Releases (or build with `cargo build --target wasm32-wasip2 --release` inside `haikyo-pumpkin-wasm/`) and place it in `plugins/haikyo-pumpkin.wasm`.
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
