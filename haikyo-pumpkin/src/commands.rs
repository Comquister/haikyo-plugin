use std::sync::Arc;
use tokio::sync::RwLock;

use pumpkin_core::command::argument_builder::{ArgumentBuilder, command, literal};
use pumpkin_core::command::context::command_context::CommandContext;
use pumpkin_core::command::node::{CommandExecutor, CommandExecutorResult};
use pumpkin_util::text::{TextComponent, color::NamedColor};

use crate::HaikyoPluginState;
use crate::audit::AuditLogger;
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

            // Logging info
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

pub fn build_haikyo_command(
    state: Arc<RwLock<HaikyoPluginState>>,
    _audit: Arc<AuditLogger>,
) -> pumpkin_core::command::node::detached::CommandDetachedNode {
    command("haikyo", "Comandos de administração e consulta do Haikyo Client")
        .then(
            literal("info")
                .executes(HaikyoInfoExecutor { state: state.clone() }),
        )
        .into()
}
