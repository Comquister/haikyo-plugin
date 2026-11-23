# Haikyo Public API, LuckPerms Contexts & Dynamic Permissions

This guide covers how server owners grant dynamic permissions to Haikyo players (with or without LuckPerms) and how third-party plugin developers integrate with `haikyo-api`.

---

## 1. LuckPerms Dynamic Contexts (Paper, Folia, Velocity, Fabric)

When **LuckPerms** is installed, Haikyo automatically registers a `ContextCalculator` exposing four live per-player contexts:

| Context Key | Values | Description |
|---|---|---|
| `haikyo-client` | `true` \| `false` | Whether the player completed the Haikyo handshake. |
| `haikyo-attested` | `true` \| `false` | Whether the player's current trust level is `ATTESTED`. |
| `haikyo-trust` | `none` \| `attested` \| `suspect` | Exact Haikyo trust level. |
| `haikyo-channel` | e.g. `release`, `stable`, `beta`, `dev` | Client release channel (only present when connected with Haikyo). |

Whenever a player completes the handshake or transitions trust levels (`NONE` ↔ `ATTESTED` ↔ `SUSPECT`), Haikyo immediately signals LuckPerms to refresh cached contexts.

### Example LuckPerms Commands

```text
# Grant a cosmetics permission only to attested Haikyo players
/lp group default permission set myserver.cosmetics.cape true haikyo-attested=true

# Grant a special queue priority to any Haikyo client user
/lp group default permission set queue.priority.haikyo true haikyo-client=true

# Restrict an arena command if a player becomes SUSPECT
/lp group default permission set arena.ranked.join false haikyo-trust=suspect

# Allow beta testers into a test world
/lp group default permission set server.beta.access true haikyo-channel=beta
```

---

## 2. Built-in Extra Permissions (No LuckPerms Required)

On Paper & Folia, you can grant dynamic permissions directly in `plugins/Haikyo/config.yml` without installing any permissions plugin:

```yaml
permissions:
  on-haikyo:
    - "myserver.haikyo.badge"
  on-attested:
    - "myserver.ranked.play"
    - "myserver.cosmetics.trail"
```

- `on-haikyo` nodes are granted as soon as the Haikyo handshake completes.
- `on-attested` nodes are granted while `TrustLevel == ATTESTED` and are **immediately revoked** if trust drops to `SUSPECT` or `NONE`, or when the player disconnects.
- All `PermissionAttachment` updates run on each player's Folia entity scheduler (`player.getScheduler()`).

---

## 3. Third-Party Java API (`haikyo-api`)

Add `haikyo-api` as a `compileOnly` dependency and declare `softdepend: [Haikyo]` (or `depend: [Haikyo]`) in your `plugin.yml`.

### Obtaining `HaikyoApi`

```java
import dev.haikyo.api.HaikyoApi;
import dev.haikyo.api.HaikyoApiProvider;
import org.bukkit.Bukkit;

// Option A: Bukkit / Paper ServicesManager
HaikyoApi api = Bukkit.getServicesManager().load(HaikyoApi.class);

// Option B: Platform-independent provider (Paper, Folia, Velocity, Fabric)
HaikyoApiProvider.get().ifPresent(haikyo -> {
    if (haikyo.isAttested(player.getUniqueId())) {
        // Player is verified and clean
    }
});
```

### Integrating a Custom Anticheat

```java
HaikyoApiProvider.get().ifPresent(api -> {
    // 1. Ask Haikyo whether a noisy heuristic check may be relaxed for this player
    //    (honors config.yml check table, sampling fraction, and haikyo:ac input telemetry)
    if (api.shouldRelaxCheck(playerId, "myanticheat", "AimHeuristicA")) {
        event.setCancelled(true);
        return;
    }

    // 2. Emit a custom external signal (transitions player to SUSPECT)
    api.addExternalSignal(playerId, "MyAnticheat", "impossible_packet_sequence");

    // 3. Flag a player as SUSPECT and promote to requires-haikyo.json
    //    (automatically syncs over signed haikyo:link 0x03 to Velocity when configured!)
    api.flagSuspect(playerId, "MyAnticheat", "Repeated Reach violations");
});
```

### Inspecting Client Integrity Reports

```java
HaikyoApiProvider.get().ifPresent(api -> {
    // Pull latest verified integrity report on demand
    api.getLastIntegritySnapshot(playerId).ifPresent(snap -> {
        boolean agentActive = snap.agentControl();
        var zoomState = snap.mods().get("zoom");
        // Inspect snap.signals(), snap.sequence(), snap.reportTimestamp()
    });

    // Or subscribe to every verified integrity_report in real time
    api.registerIntegrityListener(snapshot -> {
        if (!snapshot.signals().isEmpty()) {
            logger.info("Player " + snapshot.playerId() + " signals: " + snapshot.signals());
        }
    });
});
```

### Registering a Custom Server Mod Channel (`haikyo:mod/<id>`)

```java
import java.nio.charset.StandardCharsets;

HaikyoApiProvider.get().ifPresent(api -> {
    api.registerModEndpoint("waypoints", "1", (playerId, modId, payload) -> {
        String json = new String(payload, StandardCharsets.UTF_8);
        if (json.contains("request_sync")) {
            byte[] reply = "{\"op\":\"sync\",\"markers\":[]}".getBytes(StandardCharsets.UTF_8);
            api.sendToPlayer(playerId, "waypoints", reply);
        }
    });
});
```
