pub mod audit;
pub mod commands;
pub mod config;
pub mod crypto;
pub mod framing;
pub mod link;
pub mod messages;
pub mod session;

// Aliases para os macros de plugin do Pumpkin que esperam `pumpkin::plugin::...`
pub use pumpkin_core as pumpkin;


use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;
use uuid::Uuid;

use pumpkin_api_macros::{plugin_impl, plugin_method};
use pumpkin_core::entity::player::Player;
use pumpkin_core::plugin::Context;
use pumpkin_core::plugin::api::EventPriority;
use pumpkin_core::plugin::api::events::player::{
    PlayerCustomPayloadEvent, PlayerJoinEvent, PlayerLeaveEvent,
};
use pumpkin_util::text::TextComponent;

use crate::audit::AuditLogger;
use crate::config::HaikyoConfig;
use crate::crypto::{KeyStore, parse_attestation_token};
use crate::framing::{DecodeResult, encode_unfragmented};
use crate::link::{RequireSync, encode_signed_require_sync};
use crate::messages::{
    C2SAdminHwidResponse, C2SAdminScreenshotResponse, C2SHelloAck, S2CAdminScreenshotRequest,
    S2CAttestChallenge, S2CAttestResult, S2CHello, S2CModsRules,
};
use crate::session::{PlayerSession, TrustLevel};

pub const CHANNEL_CONTROL: &str = "haikyo:control";
pub const CHANNEL_ATTEST: &str = "haikyo:attest";
pub const CHANNEL_MODS: &str = "haikyo:mods";
pub const CHANNEL_AC: &str = "haikyo:ac";
pub const CHANNEL_ADMIN: &str = "haikyo:admin";
pub const CHANNEL_LINK: &str = "haikyo:link";

pub async fn send_payload_to_player(player: &Player, channel: &str, data: &[u8]) {
    if let Some(java) = player.as_java() {
        java.send_custom_payload(channel, data).await;
    }
}

#[derive(Default)]
pub struct HaikyoPluginState {
    pub config: HaikyoConfig,
    pub keystore: KeyStore,
    pub sessions: HashMap<Uuid, PlayerSession>,
    pub required_players: HashSet<String>,
}

#[plugin_impl]
pub struct HaikyoPlugin {
    state: Arc<RwLock<HaikyoPluginState>>,
    audit: Arc<AuditLogger>,
}

impl HaikyoPlugin {
    pub fn new() -> Self {
        let config = HaikyoConfig::default();
        let mut keystore = KeyStore::new();
        for (k_id, b64) in &config.public_keys {
            let _ = keystore.insert_base64(k_id.clone(), b64);
        }

        let state = HaikyoPluginState {
            config,
            keystore,
            sessions: HashMap::new(),
            required_players: HashSet::new(),
        };

        Self {
            state: Arc::new(RwLock::new(state)),
            audit: Arc::new(AuditLogger::new("haikyo_audit.log")),
        }
    }
}

impl Default for HaikyoPlugin {
    fn default() -> Self {
        Self::new()
    }
}

#[plugin_method]
async fn on_load(&self, context: Arc<Context>) -> Result<(), String> {
    tracing::info!("Haikyo Plugin para PumpkinMC inicializando...");

    // Carregar requires-haikyo.json se existir
    if let Ok(content) = std::fs::read_to_string("requires-haikyo.json") {
        if let Ok(list) = serde_json::from_str::<Vec<String>>(&content) {
            let mut state = self.state.write().await;
            for entry in list {
                state.required_players.insert(entry.to_lowercase());
            }
            tracing::info!("Carregados {} registros em requires-haikyo.json", state.required_players.len());
        }
    }

    let state_join = Arc::clone(&self.state);
    let state_leave = Arc::clone(&self.state);
    let state_payload = Arc::clone(&self.state);
    let audit_payload = Arc::clone(&self.audit);

    // 0. Registrar permissão e comando /haikyo
    let _ = context.register_permission(pumpkin_util::permission::Permission::new(
        "haikyo-pumpkin:haikyo.admin",
        "Permite usar os comandos administrativos /haikyo",
        pumpkin_util::permission::PermissionDefault::Op(pumpkin_util::permission::PermissionLvl::Two),
    ));
    let haikyo_cmd = commands::build_haikyo_command(Arc::clone(&self.state), Arc::clone(&self.audit));
    context.register_command(haikyo_cmd, "haikyo-pumpkin:haikyo.admin");

    // 1. Handler para PlayerJoinEvent
    context.register_event(
        Arc::new(move |server, event: &mut PlayerJoinEvent| {
            let player = Arc::clone(&event.player);
            let state = Arc::clone(&state_join);

            tokio::spawn(async move {
                let uuid = player.gameprofile.id;
                let username = player.gameprofile.name.clone();
                let address = player.client.address.lock().unwrap().to_string();
                let now = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs();

                tracing::info!("Jogador conectado: {} ({})", username, uuid);

                let mut s = state.write().await;
                s.sessions.insert(uuid, PlayerSession::new(uuid, username, address, now));

                // Montar S2C Hello
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
                drop(s);

                send_payload_to_player(&player, CHANNEL_CONTROL, &frame).await;

                // Enviar desafio de atestado
                let (nonce_hex, srv_addr) = {
                    let s = state.read().await;
                    if let Some(session) = s.sessions.get(&uuid) {
                        (hex::encode(session.nonce), s.config.server_address.clone())
                    } else {
                        return;
                    }
                };

                let challenge = S2CAttestChallenge {
                    nonce: nonce_hex,
                    server_address: srv_addr,
                };

                let challenge_body = serde_json::to_vec(&challenge).unwrap_or_default();
                let challenge_frame = encode_unfragmented(0x01, &challenge_body);
                send_payload_to_player(&player, CHANNEL_ATTEST, &challenge_frame).await;
            });

            pumpkin_core::plugin::api::EventResult::Continue
        }),
        EventPriority::Normal,
        false,
    );

    // 2. Handler para PlayerLeaveEvent
    context.register_event(
        Arc::new(move |_server, event: &mut PlayerLeaveEvent| {
            let uuid = event.player.gameprofile.id;
            let state = Arc::clone(&state_leave);
            tokio::spawn(async move {
                let mut s = state.write().await;
                s.sessions.remove(&uuid);
            });
            pumpkin_core::plugin::api::EventResult::Continue
        }),
        EventPriority::Normal,
        false,
    );

    // 3. Handler para PlayerCustomPayloadEvent
    context.register_event(
        Arc::new(move |_server, event: &mut PlayerCustomPayloadEvent| {
            let channel = event.channel.clone();
            let data = event.data.clone();
            let player = Arc::clone(&event.player);
            let state = Arc::clone(&state_payload);
            let audit = Arc::clone(&audit_payload);

            tokio::spawn(async move {
                let uuid = player.gameprofile.id;
                let username = player.gameprofile.name.clone();

                let decode_res = {
                    let mut s = state.write().await;
                    if let Some(sess) = s.sessions.get_mut(&uuid) {
                        sess.reassembler.process_incoming(&data)
                    } else {
                        return;
                    }
                };

                match decode_res {
                    Ok(DecodeResult::Complete(frame)) => {
                        handle_frame(
                            channel,
                            frame.pkt_type,
                            frame.body,
                            player,
                            state,
                            audit,
                        ).await;
                    }
                    Ok(DecodeResult::Pending) => {}
                    Ok(DecodeResult::Ignored(msg)) => {
                        tracing::debug!("Quadro ignorado de {}: {}", username, msg);
                    }
                    Err(err) => {
                        tracing::warn!("Erro no framing de {}: {}", username, err);
                    }
                }
            });

            pumpkin_core::plugin::api::EventResult::Continue
        }),
        EventPriority::Normal,
        false,
    );

    tracing::info!("Haikyo Plugin registrado com sucesso no PumpkinMC!");
    Ok(())
}

async fn handle_frame(
    channel: String,
    pkt_type: u8,
    body: Vec<u8>,
    player: Arc<Player>,
    state: Arc<RwLock<HaikyoPluginState>>,
    audit: Arc<AuditLogger>,
) {
    let uuid = player.gameprofile.id;
    let username = player.gameprofile.name.clone();

    if channel == CHANNEL_CONTROL && pkt_type == 0x02 {
        // C2S hello_ack
        if let Ok(ack) = serde_json::from_slice::<C2SHelloAck>(&body) {
            let mut s = state.write().await;
            if let Some(sess) = s.sessions.get_mut(&uuid) {
                sess.is_haikyo = true;
                sess.client_version = Some(ack.client_version.clone());
                sess.channel = Some(ack.channel.clone());
                sess.consented_scopes.extend(ack.consented_scopes.into_iter());
                tracing::info!(
                    "Haikyo Client reconhecido: {} (versão={}, canal={})",
                    username,
                    ack.client_version,
                    ack.channel
                );
            }
        }
    } else if channel == CHANNEL_ATTEST && pkt_type == 0x02 {
        // C2S attest_response (token HKAT)
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let token_res = parse_attestation_token(&body);
        let s = state.read().await;
        let mut success = false;
        let mut reason = String::new();

        if let Ok(token) = token_res {
            if let Some(sess) = s.sessions.get(&uuid) {
                match s.keystore.verify_token(
                    &token,
                    &uuid,
                    &sess.nonce,
                    &s.config.server_address,
                    now,
                ) {
                    Ok(()) => {
                        success = true;
                        tracing::info!("Atestado Ed25519 válido para o jogador: {}", username);
                    }
                    Err(e) => {
                        reason = format!("Verificação de assinatura falhou: {e}");
                        tracing::warn!("Atestado inválido para {}: {}", username, e);
                    }
                }
            }
        } else {
            reason = "Formato de token HKAT inválido".into();
        }
        drop(s);

        let mut s = state.write().await;
        if let Some(sess) = s.sessions.get_mut(&uuid) {
            if success {
                sess.trust_level = TrustLevel::Attested;
                audit.log_action("haikyo", "attest_success", &username, "Ed25519 verified");
            } else {
                audit.log_action("haikyo", "attest_failure", &username, &reason);
            }
        }
        drop(s);

        // Enviar resultado do atestado
        let result_msg = S2CAttestResult {
            success,
            trust_level: if success { "ATTESTED".into() } else { "NONE".into() },
            message: if success { None } else { Some(reason) },
        };
        let res_bytes = serde_json::to_vec(&result_msg).unwrap_or_default();
        let frame = encode_unfragmented(0x03, &res_bytes);
        send_payload_to_player(&player, CHANNEL_ATTEST, &frame).await;

        if success {
            // Enviar regras de mods (mods.rules)
            let rules = S2CModsRules {
                rules_blob: "{\"rules\":{\"allowed\":[\"*\"],\"blocked\":[]}}".into(),
            };
            let rules_bytes = serde_json::to_vec(&rules).unwrap_or_default();
            let rules_frame = encode_unfragmented(0x01, &rules_bytes);
            send_payload_to_player(&player, CHANNEL_MODS, &rules_frame).await;
        }
    } else if channel == CHANNEL_AC {
        // Lote binário de telemetria de inputs do anticheat
        tracing::trace!("Recebido frame de anticheat de {}: {} bytes", username, body.len());
    } else if channel == CHANNEL_ADMIN {
        if pkt_type == 0x02 {
            // Resposta de screenshot
            if let Ok(resp) = serde_json::from_slice::<C2SAdminScreenshotResponse>(&body) {
                tracing::info!(
                    "Recebida confirmação de screenshot de {}: sucesso={:?}",
                    username, resp.success
                );
                audit.log_action("haikyo", "screenshot_response", &username, &format!("ok={}", resp.success));
            }
        } else if pkt_type == 0x04 {
            // Resposta de HWID
            if let Ok(resp) = serde_json::from_slice::<C2SAdminHwidResponse>(&body) {
                let mut s = state.write().await;
                if let Some(sess) = s.sessions.get_mut(&uuid) {
                    sess.hwid = Some(resp.hwid.clone());
                    tracing::info!("HWID registrado para {}: {}", username, resp.hwid);
                    audit.log_action("haikyo", "hwid_received", &username, &resp.hwid);
                }
            }
        }
    }
}
