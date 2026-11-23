pub mod config;
pub mod crypto;
pub mod framing;
pub mod messages;
pub mod session;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use uuid::Uuid;

use pumpkin_plugin_api::{
    Context, EventHandler, Plugin, PluginMetadata,
    events::player::{PlayerCustomPayloadEvent, PlayerJoinEvent, PlayerLeaveEvent},
    events::EventPriority,
    wit::pumpkin::plugin::event::{
        PlayerCustomPayloadEventData, PlayerJoinEventData, PlayerLeaveEventData,
    },
    wit::pumpkin::plugin::player::{JavaKickOptions, SocketTeardownPolicy},
    wit::pumpkin::plugin::text::TextComponent,
    register_plugin,
};

use crate::config::HaikyoConfig;
use crate::crypto::{parse_attestation_token, KeyStore};
use crate::framing::{encode_unfragmented, DecodeResult};
use crate::messages::{
    C2SHelloAck, S2CAttestChallenge, S2CAttestResult, S2CHello, S2CModsRules,
};
use crate::session::{PlayerSession, TrustLevel};

pub const CHANNEL_CONTROL: &str = "haikyo:control";
pub const CHANNEL_ATTEST: &str = "haikyo:attest";
pub const CHANNEL_MODS: &str = "haikyo:mods";
pub const CHANNEL_AC: &str = "haikyo:ac";
pub const CHANNEL_ADMIN: &str = "haikyo:admin";
pub const CHANNEL_VOICE: &str = "haikyo:voice";

#[derive(Default)]
pub struct WasmPluginState {
    pub config: HaikyoConfig,
    pub keystore: KeyStore,
    pub sessions: HashMap<Uuid, PlayerSession>,
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
    fn handle(&self, _server: pumpkin_plugin_api::Server, event: PlayerJoinEventData) -> PlayerJoinEventData {
        let player = &event.player;
        let uuid_raw = player.get_id();
        let uuid = Uuid::from_u128(((uuid_raw.high as u128) << 64) | (uuid_raw.low as u128));
        let username = player.get_name();

        let mut s = self.state.lock().unwrap();
        s.sessions.insert(
            uuid,
            PlayerSession::new(uuid, username.clone(), "127.0.0.1".into(), 0),
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
            plugin_version: "0.1.0".into(),
            require_haikyo: s.config.require_haikyo,
            channels,
            mod_channels: vec!["waypoints@1".into()],
            consent_scopes: vec![
                "version".into(),
                "integrity".into(),
                "mods".into(),
                "inputs".into(),
                "environment".into(),
                "screenshot".into(),
            ],
            download_url: "https://haikyo.conquister.net".into(),
        };

        let body = serde_json::to_vec(&hello).unwrap_or_default();
        let frame = encode_unfragmented(0x01, &body);

        let nonce_hex = if let Some(sess) = s.sessions.get(&uuid) {
            hex::encode(sess.nonce)
        } else {
            String::new()
        };
        let srv_addr = s.config.server_address.clone();
        drop(s);

        if let Some(java_player) = player.as_java() {
            // Enviar payload de controle
            java_player.send_custom_payload(CHANNEL_CONTROL, &frame);

            // Enviar desafio de atestado
            let challenge = S2CAttestChallenge {
                nonce: nonce_hex,
                server_address: srv_addr,
            };
            let challenge_body = serde_json::to_vec(&challenge).unwrap_or_default();
            let challenge_frame = encode_unfragmented(0x01, &challenge_body);
            java_player.send_custom_payload(CHANNEL_ATTEST, &challenge_frame);
        }

        event
    }
}

struct LeaveHandler {
    state: Arc<Mutex<WasmPluginState>>,
}

impl EventHandler<PlayerLeaveEvent> for LeaveHandler {
    fn handle(&self, _server: pumpkin_plugin_api::Server, event: PlayerLeaveEventData) -> PlayerLeaveEventData {
        let player = &event.player;
        let uuid_raw = player.get_id();
        let uuid = Uuid::from_u128(((uuid_raw.high as u128) << 64) | (uuid_raw.low as u128));

        let mut s = self.state.lock().unwrap();
        s.sessions.remove(&uuid);

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
            let mut s = self.state.lock().unwrap();
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

        // Dispatch de canal
        match channel.as_str() {
            CHANNEL_CONTROL => {
                if let Ok(ack) = serde_json::from_slice::<C2SHelloAck>(&frame.body) {
                    let mut s = self.state.lock().unwrap();
                    if let Some(sess) = s.sessions.get_mut(&uuid) {
                        sess.is_haikyo = true;
                        sess.client_version = Some(ack.client_version);
                        sess.channel = Some(ack.channel);
                        sess.consented_scopes.extend(ack.consented_scopes);
                    }
                }
            }
            CHANNEL_ATTEST => {
                let mut s = self.state.lock().unwrap();
                let server_addr = s.config.server_address.clone();
                let expected_nonce = s.sessions.get(&uuid).map(|sess| sess.nonce).unwrap_or([0u8; 32]);
                let challenge_sent = s.sessions.get(&uuid).map(|sess| sess.challenge_sent_at).unwrap_or(0);

                let mut kick_reason: Option<String> = None;
                let parsed_token = parse_attestation_token(&frame.body);

                match parsed_token {
                    Ok(tok) => {
                        match s.keystore.verify_token(
                            &tok,
                            &uuid,
                            &expected_nonce,
                            &server_addr,
                            challenge_sent,
                        ) {
                            Ok(()) => {
                                if let Some(sess) = s.sessions.get_mut(&uuid) {
                                    sess.trust_level = TrustLevel::Attested;
                                    sess.attest_token = Some(tok);
                                }

                                if let Some(java_player) = player.as_java() {
                                    let res = S2CAttestResult {
                                        success: true,
                                        trust_level: "Attested".into(),
                                        message: Some("Atestado verificado com sucesso".into()),
                                    };
                                    let res_bytes = serde_json::to_vec(&res).unwrap_or_default();
                                    java_player.send_custom_payload(
                                        CHANNEL_ATTEST,
                                        &encode_unfragmented(0x01, &res_bytes),
                                    );

                                    // Enviar regras de mods
                                    let rules = S2CModsRules {
                                        rules_blob: "{}".into(),
                                    };
                                    let rules_bytes = serde_json::to_vec(&rules).unwrap_or_default();
                                    java_player.send_custom_payload(
                                        CHANNEL_MODS,
                                        &encode_unfragmented(0x01, &rules_bytes),
                                    );
                                }
                            }
                            Err(e) => {
                                if let Some(sess) = s.sessions.get_mut(&uuid) {
                                    sess.trust_level = TrustLevel::Suspect;
                                }
                                if s.config.require_haikyo {
                                    kick_reason = Some(format!("Falha no atestado Haikyo: {}", e));
                                }
                            }
                        }
                    }
                    Err(e) => {
                        if let Some(sess) = s.sessions.get_mut(&uuid) {
                            sess.trust_level = TrustLevel::Suspect;
                        }
                        if s.config.require_haikyo {
                            kick_reason = Some(format!("Token de atestado inválido: {}", e));
                        }
                    }
                }

                if let Some(reason) = kick_reason {
                    if let Some(java_player) = player.as_java() {
                        java_player.kick(JavaKickOptions {
                            reason: TextComponent::text(&reason),
                            log_to_console: true,
                            teardown_policy: SocketTeardownPolicy::Graceful,
                        });
                    }
                }
            }
            CHANNEL_MODS => {}
            CHANNEL_AC => {}
            CHANNEL_ADMIN => {}
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
            version: "0.1.0".into(),
            authors: vec!["Comquister".into()],
            description: "Haikyo Client official universal WebAssembly plugin for PumpkinMC".into(),
            dependencies: vec![],
            permissions: vec![],
        }
    }

    fn on_load(&self, context: Context) -> Result<(), String> {
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

        Ok(())
    }
}

register_plugin!(HaikyoWasmPlugin);
