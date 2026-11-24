pub mod commands;
pub mod config;
pub mod crypto;
pub mod framing;
pub mod messages;
pub mod session;

use base64::Engine;
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

use pumpkin_plugin_api::{
    Context, EventHandler, Plugin, PluginMetadata,
    events::player::{PlayerCustomPayloadEvent, PlayerJoinEvent, PlayerLeaveEvent},
    events::server::ServerListPingEvent,
    events::EventPriority,
    wit::pumpkin::plugin::event::{
        PlayerCustomPayloadEventData, PlayerJoinEventData, PlayerLeaveEventData,
        ServerListPingEventData,
    },
    wit::pumpkin::plugin::permission::{Permission, PermissionDefault, PermissionLevel},
    wit::pumpkin::plugin::player::{JavaKickOptions, SocketTeardownPolicy},
    wit::pumpkin::plugin::text::TextComponent,
    register_plugin,
};

use crate::config::HaikyoConfig;
use crate::crypto::{parse_attestation_token, KeyStore};
use crate::framing::{encode_unfragmented, DecodeResult};
use crate::messages::{
    C2SAttestResponse, C2SHelloAck, S2CAttestChallenge, S2CAttestResult, S2CHello, S2CModsRules,
};
use crate::session::{PlayerSession, TrustLevel};

pub const CHANNEL_CONTROL: &str = "haikyo:control";
pub const CHANNEL_ATTEST: &str = "haikyo:attest";
pub const CHANNEL_MODS: &str = "haikyo:mods";
pub const CHANNEL_AC: &str = "haikyo:ac";
pub const CHANNEL_ADMIN: &str = "haikyo:admin";
pub const CHANNEL_VOICE: &str = "haikyo:voice";

pub const PKT_CONTROL_HELLO: u8 = 0x01;
pub const PKT_CONTROL_HELLO_ACK: u8 = 0x02;

pub const PKT_ATTEST_CHALLENGE: u8 = 0x01;
pub const PKT_ATTEST_RESPONSE: u8 = 0x02;
pub const PKT_ATTEST_RESULT: u8 = 0x03;

pub const PKT_MODS_RULES: u8 = 0x01;

fn now_unix_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

#[derive(Default)]
pub struct WasmPluginState {
    pub config: HaikyoConfig,
    pub keystore: KeyStore,
    pub sessions: HashMap<Uuid, PlayerSession>,
    pub required_players: HashSet<String>,
}

impl WasmPluginState {
    pub fn is_haikyo_required_for(&self, uuid: &Uuid, username: &str) -> bool {
        self.config.require_haikyo
            || self.required_players.contains(&username.to_lowercase())
            || self
                .required_players
                .contains(&uuid.to_string().to_lowercase())
    }
}

pub struct HaikyoWasmPlugin {
    state: Arc<Mutex<WasmPluginState>>,
}

impl HaikyoWasmPlugin {
    pub fn new() -> Self {
        let config = HaikyoConfig::default();
        let mut keystore = KeyStore::new();
        for (k_id, b64) in &config.public_keys {
            let _ = keystore.insert_base64(k_id.clone(), b64);
        }

        Self {
            state: Arc::new(Mutex::new(WasmPluginState {
                config,
                keystore,
                sessions: HashMap::new(),
                required_players: HashSet::new(),
            })),
        }
    }
}

impl Default for HaikyoWasmPlugin {
    fn default() -> Self {
        Self::new()
    }
}

struct JoinHandler {
    state: Arc<Mutex<WasmPluginState>>,
}

impl EventHandler<PlayerJoinEvent> for JoinHandler {
    fn handle(
        &self,
        _server: pumpkin_plugin_api::Server,
        event: PlayerJoinEventData,
    ) -> PlayerJoinEventData {
        let player = &event.player;
        let uuid_raw = player.get_id();
        let uuid = Uuid::from_u128(((uuid_raw.high as u128) << 64) | (uuid_raw.low as u128));
        let username = player.get_name();
        let now = now_unix_secs();

        let frame = {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let require_haikyo = s.is_haikyo_required_for(&uuid, &username);
            s.sessions.insert(
                uuid,
                PlayerSession::new(uuid, username, "127.0.0.1".into(), now),
            );

            let mut channels = HashMap::new();
            channels.insert("control".into(), 1);
            channels.insert("attest".into(), 1);
            channels.insert("mods".into(), 1);
            channels.insert("ac".into(), 1);
            channels.insert("admin".into(), 1);
            channels.insert("voice".into(), 1);

            let hello = S2CHello {
                protocol_version: 1,
                server_role: s.config.server_role.clone(),
                server_id: s.config.server_id.clone(),
                plugin_version: env!("CARGO_PKG_VERSION").into(),
                require_haikyo,
                channels,
                mod_channels: vec![],
                consent_scopes: vec![
                    "version".into(),
                    "integrity".into(),
                    "mods".into(),
                    "inputs".into(),
                ],
                download_url: "https://haikyo.conquister.net".into(),
            };

            let body = serde_json::to_vec(&hello).unwrap_or_default();
            encode_unfragmented(PKT_CONTROL_HELLO, &body)
        };

        if let Some(java_player) = player.as_java() {
            java_player.send_custom_payload(CHANNEL_CONTROL, &frame);
        }

        event
    }
}

struct LeaveHandler {
    state: Arc<Mutex<WasmPluginState>>,
}

impl EventHandler<PlayerLeaveEvent> for LeaveHandler {
    fn handle(
        &self,
        _server: pumpkin_plugin_api::Server,
        event: PlayerLeaveEventData,
    ) -> PlayerLeaveEventData {
        let player = &event.player;
        let uuid_raw = player.get_id();
        let uuid = Uuid::from_u128(((uuid_raw.high as u128) << 64) | (uuid_raw.low as u128));

        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        s.sessions.remove(&uuid);

        event
    }
}

struct ServerListPingHandler {
    state: Arc<Mutex<WasmPluginState>>,
}

impl EventHandler<ServerListPingEvent> for ServerListPingHandler {
    fn handle(
        &self,
        _server: pumpkin_plugin_api::Server,
        mut event: ServerListPingEventData,
    ) -> ServerListPingEventData {
        let insertion = {
            let s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let payload = serde_json::json!({
                "v": 1,
                "server_id": s.config.server_id,
                "require_haikyo": s.config.require_haikyo,
                "scopes": ["version", "integrity", "mods", "inputs"],
            });
            let json = serde_json::to_vec(&payload).unwrap_or_default();
            let b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(json);
            format!("haikyo:v1:{b64}")
        };

        let marker = TextComponent::text("").insertion(&insertion);
        event.motd = event.motd.add_child(marker);
        event
    }
}

struct CustomPayloadHandler {
    state: Arc<Mutex<WasmPluginState>>,
}

impl EventHandler<PlayerCustomPayloadEvent> for CustomPayloadHandler {
    fn handle(
        &self,
        _server: pumpkin_plugin_api::Server,
        event: PlayerCustomPayloadEventData,
    ) -> PlayerCustomPayloadEventData {
        let channel = event.channel.clone();
        if !channel.starts_with("haikyo:") {
            return event;
        }

        let player = &event.player;
        let uuid_raw = player.get_id();
        let uuid = Uuid::from_u128(((uuid_raw.high as u128) << 64) | (uuid_raw.low as u128));

        let frame = {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let sess = match s.sessions.get_mut(&uuid) {
                Some(sess) => sess,
                None => return event,
            };

            match sess.reassembler.process_incoming(&event.data) {
                Ok(DecodeResult::Complete(f)) => f,
                Ok(DecodeResult::Pending) => return event,
                Ok(DecodeResult::Ignored(msg)) => {
                    eprintln!("[Haikyo-Wasm] Frame ignorado de {}: {}", uuid, msg);
                    return event;
                }
                Err(e) => {
                    eprintln!("[Haikyo-Wasm] Erro no framing de {}: {}", uuid, e);
                    return event;
                }
            }
        };

        match (channel.as_str(), frame.pkt_type) {
            (CHANNEL_CONTROL, PKT_CONTROL_HELLO_ACK) => {
                let Ok(ack) = serde_json::from_slice::<C2SHelloAck>(&frame.body) else {
                    eprintln!("[Haikyo-Wasm] Falha ao decodificar C2SHelloAck de {}", uuid);
                    return event;
                };
                if ack.protocol_version == 0 || ack.protocol_version > 1 {
                    return event;
                }

                let challenge_payload = {
                    let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    let server_addr = s.config.server_address.clone();
                    let Some(sess) = s.sessions.get_mut(&uuid) else {
                        return event;
                    };
                    sess.is_haikyo = true;
                    if !ack.client_version.is_empty() {
                        sess.client_version = Some(ack.client_version);
                    }
                    if !ack.channel.is_empty() {
                        sess.channel = Some(ack.channel);
                    }
                    sess.consented_scopes.clear();
                    sess.consented_scopes.extend(ack.consented_scopes);

                    if !sess.challenge_sent && sess.attest_token.is_none() {
                        sess.challenge_sent = true;
                        sess.challenge_sent_at = now_unix_secs();
                        let challenge = S2CAttestChallenge {
                            nonce: hex::encode(sess.nonce),
                            server_address: server_addr,
                            session_key: hex::encode(sess.session_key),
                            report_interval_min_s: 15,
                            report_interval_max_s: 45,
                        };
                        let challenge_body = serde_json::to_vec(&challenge).unwrap_or_default();
                        let rules = S2CModsRules {
                            rules_blob: "{}".into(),
                        };
                        let rules_body = serde_json::to_vec(&rules).unwrap_or_default();
                        Some((
                            encode_unfragmented(PKT_ATTEST_CHALLENGE, &challenge_body),
                            encode_unfragmented(PKT_MODS_RULES, &rules_body),
                        ))
                    } else {
                        None
                    }
                };

                if let Some((challenge_frame, rules_frame)) = challenge_payload
                    && let Some(java_player) = player.as_java()
                {
                    java_player.send_custom_payload(CHANNEL_MODS, &rules_frame);
                    java_player.send_custom_payload(CHANNEL_ATTEST, &challenge_frame);
                }
            }
            (CHANNEL_ATTEST, PKT_ATTEST_RESPONSE) => {
                let Ok(resp) = serde_json::from_slice::<C2SAttestResponse>(&frame.body) else {
                    eprintln!("[Haikyo-Wasm] Falha ao decodificar C2SAttestResponse de {}", uuid);
                    return event;
                };

                let now = now_unix_secs();
                let (result_frame, kick_reason) = {
                    let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    let server_addr = s.config.server_address.clone();
                    let (username, expected_nonce, current_trust) = match s.sessions.get(&uuid) {
                        Some(sess) => (sess.username.clone(), sess.nonce, sess.trust_level),
                        None => return event,
                    };
                    let require_for_player = s.is_haikyo_required_for(&uuid, &username);

                    if let Some(err_msg) = resp.error.filter(|e| !e.trim().is_empty()) {
                        eprintln!(
                            "[Haikyo-Wasm] Cliente {} reportou erro de atestado: {}",
                            username, err_msg
                        );
                        let res = S2CAttestResult {
                            accepted: false,
                            trust_level: current_trust.wire_name().into(),
                            reason: Some(err_msg.clone()),
                        };
                        let res_bytes = serde_json::to_vec(&res).unwrap_or_default();
                        let kick = if require_for_player {
                            Some(format!("Falha no atestado Haikyo: {}", err_msg))
                        } else {
                            None
                        };
                        (encode_unfragmented(PKT_ATTEST_RESULT, &res_bytes), kick)
                    } else {
                        let verification = resp
                            .token
                            .as_deref()
                            .filter(|t| !t.trim().is_empty())
                            .ok_or_else(|| "missing_token".to_string())
                            .and_then(|b64| {
                                base64::engine::general_purpose::STANDARD
                                    .decode(b64.trim())
                                    .map_err(|e| format!("invalid_token_base64: {e}"))
                            })
                            .and_then(|raw| parse_attestation_token(&raw))
                            .and_then(|tok| {
                                s.keystore
                                    .verify_token(&tok, &uuid, &expected_nonce, &server_addr, now)
                                    .map(|()| tok)
                            });

                        match verification {
                            Ok(tok) => {
                                let new_trust = if let Some(sess) = s.sessions.get_mut(&uuid) {
                                    sess.is_haikyo = true;
                                    if !tok.version.is_empty() {
                                        sess.client_version = Some(tok.version.clone());
                                    }
                                    if !tok.channel.is_empty() {
                                        sess.channel = Some(tok.channel.clone());
                                    }
                                    if sess.trust_level != TrustLevel::Suspect {
                                        sess.trust_level = TrustLevel::Attested;
                                    }
                                    sess.attest_token = Some(tok);
                                    sess.trust_level
                                } else {
                                    TrustLevel::Attested
                                };

                                let res = S2CAttestResult {
                                    accepted: true,
                                    trust_level: new_trust.wire_name().into(),
                                    reason: None,
                                };
                                let res_bytes = serde_json::to_vec(&res).unwrap_or_default();
                                (encode_unfragmented(PKT_ATTEST_RESULT, &res_bytes), None)
                            }
                            Err(e) => {
                                eprintln!(
                                    "[Haikyo-Wasm] Falha ao verificar atestado de {}: {}",
                                    username, e
                                );
                                let res = S2CAttestResult {
                                    accepted: false,
                                    trust_level: current_trust.wire_name().into(),
                                    reason: Some(e.clone()),
                                };
                                let res_bytes = serde_json::to_vec(&res).unwrap_or_default();
                                let kick = if require_for_player {
                                    Some(format!("Falha no atestado Haikyo: {}", e))
                                } else {
                                    None
                                };
                                (encode_unfragmented(PKT_ATTEST_RESULT, &res_bytes), kick)
                            }
                        }
                    }
                };

                if let Some(java_player) = player.as_java() {
                    java_player.send_custom_payload(CHANNEL_ATTEST, &result_frame);
                    if let Some(reason) = kick_reason {
                        java_player.kick(JavaKickOptions {
                            reason: TextComponent::text(&reason),
                            log_to_console: true,
                            teardown_policy: SocketTeardownPolicy::Graceful,
                        });
                    }
                }
            }
            _ => {}
        }

        event
    }
}

impl Plugin for HaikyoWasmPlugin {
    fn new() -> Self {
        Self::new()
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "haikyo-pumpkin".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Comquister".into()],
            description: "Haikyo Client official universal WebAssembly plugin for PumpkinMC".into(),
            dependencies: vec![],
            permissions: vec![],
        }
    }

    fn on_load(&self, context: Context) -> Result<(), String> {
        let _ = context.register_permission(&Permission {
            node: "haikyo-pumpkin:haikyo.admin".into(),
            description: "Permite usar os comandos administrativos /haikyo".into(),
            default: PermissionDefault::Op(PermissionLevel::Two),
            children: vec![],
        });

        let haikyo_cmd = commands::build_haikyo_command(Arc::clone(&self.state));
        context.register_command(haikyo_cmd, "haikyo-pumpkin:haikyo.admin");

        context
            .register_event_handler::<PlayerJoinEvent, _>(
                JoinHandler {
                    state: Arc::clone(&self.state),
                },
                EventPriority::Normal,
                false,
            )
            .map_err(|e| e.to_string())?;

        context
            .register_event_handler::<PlayerLeaveEvent, _>(
                LeaveHandler {
                    state: Arc::clone(&self.state),
                },
                EventPriority::Normal,
                false,
            )
            .map_err(|e| e.to_string())?;

        context
            .register_event_handler::<PlayerCustomPayloadEvent, _>(
                CustomPayloadHandler {
                    state: Arc::clone(&self.state),
                },
                EventPriority::Normal,
                false,
            )
            .map_err(|e| e.to_string())?;

        context
            .register_event_handler::<ServerListPingEvent, _>(
                ServerListPingHandler {
                    state: Arc::clone(&self.state),
                },
                EventPriority::Normal,
                true,
            )
            .map_err(|e| e.to_string())?;

        Ok(())
    }
}

register_plugin!(HaikyoWasmPlugin);
