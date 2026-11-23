use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::RwLock;
use uuid::Uuid;

use pumpkin_core::command::argument_builder::{ArgumentBuilder, argument, command, literal};
use pumpkin_core::command::argument_types::core::string::StringArgumentType;
use pumpkin_core::command::context::command_context::CommandContext;
use pumpkin_core::command::node::{CommandExecutor, CommandExecutorResult};
use pumpkin_util::text::TextComponent;

use crate::HaikyoPluginState;
use crate::audit::AuditLogger;
use crate::framing::encode_unfragmented;
use crate::link::{RequireSync, encode_signed_require_sync};
use crate::session::TrustLevel;

struct HaikyoInfoExecutor {
    state: Arc<RwLock<HaikyoPluginState>>,
}

impl CommandExecutor for HaikyoInfoExecutor {
    fn execute(&self, context: &CommandContext) -> CommandExecutorResult {
        let state = self.state.clone();
        tokio::spawn(async move {
            let s = state.read().await;
            let total = s.sessions.len();
            let haikyo_count = s.sessions.values().filter(|sess| sess.is_haikyo).count();
            let attested_count = s
                .sessions
                .values()
                .filter(|sess| sess.trust_level == TrustLevel::Attested)
                .count();

            tracing::info!(
                "[Haikyo Info] Online: {} | Haikyo: {} | Attested: {}",
                total, haikyo_count, attested_count
            );
        });

        let reply = TextComponent::text("§6[Haikyo]§r Informações solicitadas. Verifique o console.");
        context.source.send_message(reply);
        Ok(1)
    }
}

struct HaikyoSuspectExecutor {
    state: Arc<RwLock<HaikyoPluginState>>,
    audit: Arc<AuditLogger>,
}

impl CommandExecutor for HaikyoSuspectExecutor {
    fn execute(&self, context: &CommandContext) -> CommandExecutorResult {
        let target_name = match StringArgumentType::get(context, "target") {
            Ok(name) => name.to_string(),
            Err(_) => {
                context.source.send_message(TextComponent::text("§c[Haikyo] Jogador alvo não especificado."));
                return Ok(0);
            }
        };

        let reason = match StringArgumentType::get(context, "reason") {
            Ok(r) => r.to_string(),
            Err(_) => "manual_suspect".to_string(),
        };

        let state = self.state.clone();
        let audit = self.audit.clone();
        let target_name_cloned = target_name.clone();
        let reason_cloned = reason.clone();

        tokio::spawn(async move {
            let mut s = state.write().await;
            let target_lower = target_name_cloned.to_lowercase();

            // Encontrar jogador pelo nome
            let found_uuid_and_player = s.sessions.iter_mut().find_map(|(uuid, sess)| {
                if sess.username.to_lowercase() == target_lower {
                    sess.trust_level = TrustLevel::Suspect;
                    Some((*uuid, sess.username.clone()))
                } else {
                    None
                }
            });

            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as i64;

            let (target_uuid, real_name) = match found_uuid_and_player {
                Some((uuid, name)) => (uuid, name),
                None => (Uuid::nil(), target_name_cloned.clone()),
            };

            // Adicionar à lista de required_players
            s.required_players.insert(target_lower.clone());
            if target_uuid != Uuid::nil() {
                s.required_players.insert(target_uuid.to_string().to_lowercase());
            }

            audit.log_action("haikyo", "suspect_marked", &real_name, &format!("reason={} by=Console/Admin", reason_cloned));
            tracing::info!("[Haikyo] Jogador {} marcado como SUSPECT ({})", real_name, reason_cloned);

            // Sincronizar com Proxy (Velocity) se configurado
            if s.config.publish_requires_to_proxy && !s.config.link_shared_secret.is_empty() && target_uuid != Uuid::nil() {
                let sync = RequireSync {
                    require: true,
                    player_uuid: target_uuid,
                    player_name: real_name.clone(),
                    reason: reason_cloned.clone(),
                    issued_at: now,
                };

                match encode_signed_require_sync(&sync, &s.config.link_shared_secret) {
                    Ok(body) => {
                        let _frame = encode_unfragmented(0x03, &body);
                        tracing::info!(
                            "[Haikyo Link] Pacote HKRQ (0x03) gerado para o Proxy via conexão de {}",
                            real_name
                        );
                    }
                    Err(e) => {
                        tracing::error!("[Haikyo Link] Falha ao codificar HKRQ: {}", e);
                    }
                }
            }
        });

        let msg = format!(
            "§6[Haikyo]§r Jogador §e{}§r marcado como §cSUSPEITO§r ({})",
            target_name, reason
        );
        context.source.send_message(TextComponent::text(msg));
        Ok(1)
    }
}

struct HaikyoPardonExecutor {
    state: Arc<RwLock<HaikyoPluginState>>,
    audit: Arc<AuditLogger>,
}

impl CommandExecutor for HaikyoPardonExecutor {
    fn execute(&self, context: &CommandContext) -> CommandExecutorResult {
        let target_name = match StringArgumentType::get(context, "target") {
            Ok(name) => name.to_string(),
            Err(_) => {
                context.source.send_message(TextComponent::text("§c[Haikyo] Jogador alvo não especificado."));
                return Ok(0);
            }
        };

        let state = self.state.clone();
        let audit = self.audit.clone();
        let target_name_cloned = target_name.clone();

        tokio::spawn(async move {
            let mut s = state.write().await;
            let target_lower = target_name_cloned.to_lowercase();

            s.required_players.remove(&target_lower);

            let found = s.sessions.iter_mut().find_map(|(uuid, sess)| {
                if sess.username.to_lowercase() == target_lower {
                    sess.trust_level = if sess.is_haikyo { TrustLevel::Attested } else { TrustLevel::None };
                    Some((*uuid, sess.username.clone()))
                } else {
                    None
                }
            });

            if let Some((uuid, name)) = found {
                s.required_players.remove(&uuid.to_string().to_lowercase());
                audit.log_action("haikyo", "pardon", &name, "pardoned");
                tracing::info!("[Haikyo] Jogador {} perdoado", name);

                if s.config.publish_requires_to_proxy && !s.config.link_shared_secret.is_empty() {
                    let now = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_secs() as i64;
                    let sync = RequireSync {
                        require: false,
                        player_uuid: uuid,
                        player_name: name.clone(),
                        reason: "pardon".into(),
                        issued_at: now,
                    };
                    if let Ok(body) = encode_signed_require_sync(&sync, &s.config.link_shared_secret) {
                        let _frame = encode_unfragmented(0x03, &body);
                        tracing::info!("[Haikyo Link] Pacote HKRQ PARDON (0x03) gerado para o Proxy");
                    }
                }
            }
        });

        let msg = format!("§6[Haikyo]§r Jogador §e{}§r perdoado.", target_name);
        context.source.send_message(TextComponent::text(msg));
        Ok(1)
    }
}

pub fn build_haikyo_command(
    state: Arc<RwLock<HaikyoPluginState>>,
    audit: Arc<AuditLogger>,
) -> pumpkin_core::command::node::detached::CommandDetachedNode {
    command("haikyo", "Comandos de administração e consulta do Haikyo Client")
        .then(
            literal("info")
                .executes(HaikyoInfoExecutor { state: state.clone() }),
        )
        .then(
            literal("suspect")
                .then(
                    argument("target", StringArgumentType::SingleWord)
                        .then(
                            argument("reason", StringArgumentType::GreedyPhrase)
                                .executes(HaikyoSuspectExecutor {
                                    state: state.clone(),
                                    audit: audit.clone(),
                                }),
                        )
                        .executes(HaikyoSuspectExecutor {
                            state: state.clone(),
                            audit: audit.clone(),
                        }),
                ),
        )
        .then(
            literal("pardon")
                .then(
                    argument("target", StringArgumentType::SingleWord)
                        .executes(HaikyoPardonExecutor {
                            state: state.clone(),
                            audit: audit.clone(),
                        }),
                ),
        )
        .into()
}
