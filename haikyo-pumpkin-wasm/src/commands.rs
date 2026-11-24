use std::sync::{Arc, Mutex};
use uuid::Uuid;

use pumpkin_plugin_api::{
    Server,
    command::{
        Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs,
        StringType,
    },
    commands::CommandHandler,
    wit::pumpkin::plugin::text::TextComponent,
};

use crate::WasmPluginState;
use crate::session::TrustLevel;

fn extract_string(args: &ConsumedArgs, key: &str) -> Option<String> {
    match args.get_value(key) {
        Arg::Simple(s) | Arg::Msg(s) => Some(s),
        _ => None,
    }
}

struct HaikyoUsageExecutor;

impl CommandHandler for HaikyoUsageExecutor {
    fn handle(
        &self,
        sender: CommandSender,
        _server: Server,
        _args: ConsumedArgs,
    ) -> Result<i32, CommandError> {
        sender.send_message(TextComponent::text(
            "§6[Haikyo]§r Uso: §e/haikyo info [jogador]§r | §e/haikyo suspect <jogador> [motivo]§r | §e/haikyo pardon <jogador>",
        ));
        Ok(1)
    }
}

struct HaikyoInfoExecutor {
    state: Arc<Mutex<WasmPluginState>>,
    with_target: bool,
}

impl CommandHandler for HaikyoInfoExecutor {
    fn handle(
        &self,
        sender: CommandSender,
        _server: Server,
        args: ConsumedArgs,
    ) -> Result<i32, CommandError> {
        let s = self.state.lock().unwrap_or_else(|e| e.into_inner());

        if self.with_target {
            let Some(target) = extract_string(&args, "target") else {
                sender.send_message(TextComponent::text(
                    "§c[Haikyo] Jogador alvo não especificado.",
                ));
                return Ok(0);
            };
            let target_lower = target.to_lowercase();
            let required = s.required_players.contains(&target_lower);

            if let Some(sess) = s
                .sessions
                .values()
                .find(|sess| sess.username.to_lowercase() == target_lower)
            {
                let version = sess
                    .client_version
                    .as_deref()
                    .filter(|v| !v.is_empty())
                    .unwrap_or("-");
                let channel = sess
                    .channel
                    .as_deref()
                    .filter(|c| !c.is_empty())
                    .unwrap_or("-");
                let msg = format!(
                    "§6[Haikyo]§r §e{}§r | Haikyo: {} | Trust: {:?} | Versão: {} ({}) | Obrigatório: {}",
                    sess.username,
                    if sess.is_haikyo { "§aSim§r" } else { "§cNão§r" },
                    sess.trust_level,
                    version,
                    channel,
                    if required || s.required_players.contains(&sess.uuid.to_string().to_lowercase()) {
                        "§eSim§r"
                    } else {
                        "Não"
                    }
                );
                sender.send_message(TextComponent::text(&msg));
            } else {
                let msg = format!(
                    "§6[Haikyo]§r Jogador §e{}§r não está online. Obrigatório: {}",
                    target,
                    if required { "§eSim§r" } else { "Não" }
                );
                sender.send_message(TextComponent::text(&msg));
            }
            return Ok(1);
        }

        let total = s.sessions.len();
        let haikyo_count = s.sessions.values().filter(|sess| sess.is_haikyo).count();
        let attested_count = s
            .sessions
            .values()
            .filter(|sess| sess.trust_level == TrustLevel::Attested)
            .count();
        let suspect_count = s
            .sessions
            .values()
            .filter(|sess| sess.trust_level == TrustLevel::Suspect)
            .count();

        let msg = format!(
            "§6[Haikyo]§r Online: §f{}§r | Haikyo: §a{}§r | Atestados: §b{}§r | Suspeitos: §c{}§r | Obrigatórios: §e{}",
            total,
            haikyo_count,
            attested_count,
            suspect_count,
            s.required_players.len()
        );
        sender.send_message(TextComponent::text(&msg));
        Ok(1)
    }
}

struct HaikyoSuspectExecutor {
    state: Arc<Mutex<WasmPluginState>>,
    with_reason: bool,
}

impl CommandHandler for HaikyoSuspectExecutor {
    fn handle(
        &self,
        sender: CommandSender,
        _server: Server,
        args: ConsumedArgs,
    ) -> Result<i32, CommandError> {
        let Some(target_name) = extract_string(&args, "target") else {
            sender.send_message(TextComponent::text(
                "§c[Haikyo] Jogador alvo não especificado.",
            ));
            return Ok(0);
        };

        let reason = if self.with_reason {
            extract_string(&args, "reason").unwrap_or_else(|| "manual_suspect".to_string())
        } else {
            "manual_suspect".to_string()
        };

        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let target_lower = target_name.to_lowercase();

        let found_uuid = s.sessions.iter_mut().find_map(|(uuid, sess)| {
            if sess.username.to_lowercase() == target_lower {
                sess.trust_level = TrustLevel::Suspect;
                Some((*uuid, sess.username.clone()))
            } else {
                None
            }
        });

        let (target_uuid, real_name) = match found_uuid {
            Some((uuid, name)) => (uuid, name),
            None => (Uuid::nil(), target_name),
        };

        s.required_players.insert(target_lower);
        if target_uuid != Uuid::nil() {
            s.required_players
                .insert(target_uuid.to_string().to_lowercase());
        }

        let msg = format!(
            "§6[Haikyo]§r Jogador §e{}§r marcado como §cSUSPEITO§r ({}).",
            real_name, reason
        );
        sender.send_message(TextComponent::text(&msg));
        Ok(1)
    }
}

struct HaikyoPardonExecutor {
    state: Arc<Mutex<WasmPluginState>>,
}

impl CommandHandler for HaikyoPardonExecutor {
    fn handle(
        &self,
        sender: CommandSender,
        _server: Server,
        args: ConsumedArgs,
    ) -> Result<i32, CommandError> {
        let Some(target_name) = extract_string(&args, "target") else {
            sender.send_message(TextComponent::text(
                "§c[Haikyo] Jogador alvo não especificado.",
            ));
            return Ok(0);
        };

        let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let target_lower = target_name.to_lowercase();

        s.required_players.remove(&target_lower);

        let found = s.sessions.iter_mut().find_map(|(uuid, sess)| {
            if sess.username.to_lowercase() == target_lower {
                sess.trust_level = if sess.attest_token.is_some() {
                    TrustLevel::Attested
                } else {
                    TrustLevel::None
                };
                Some((*uuid, sess.username.clone()))
            } else {
                None
            }
        });

        let real_name = if let Some((uuid, name)) = found {
            s.required_players
                .remove(&uuid.to_string().to_lowercase());
            name
        } else {
            target_name
        };

        let msg = format!("§6[Haikyo]§r Jogador §e{}§r perdoado.", real_name);
        sender.send_message(TextComponent::text(&msg));
        Ok(1)
    }
}

pub fn build_haikyo_command(state: Arc<Mutex<WasmPluginState>>) -> Command {
    Command::new(
        &["haikyo".to_string()],
        "Comandos de administração e consulta do Haikyo Client",
    )
    .execute(HaikyoUsageExecutor)
    .then(
        CommandNode::literal("info")
            .execute(HaikyoInfoExecutor {
                state: Arc::clone(&state),
                with_target: false,
            })
            .then(
                CommandNode::argument("target", &ArgumentType::String(StringType::SingleWord))
                    .execute(HaikyoInfoExecutor {
                        state: Arc::clone(&state),
                        with_target: true,
                    }),
            ),
    )
    .then(
        CommandNode::literal("suspect").then(
            CommandNode::argument("target", &ArgumentType::String(StringType::SingleWord))
                .execute(HaikyoSuspectExecutor {
                    state: Arc::clone(&state),
                    with_reason: false,
                })
                .then(
                    CommandNode::argument("reason", &ArgumentType::String(StringType::Greedy))
                        .execute(HaikyoSuspectExecutor {
                            state: Arc::clone(&state),
                            with_reason: true,
                        }),
                ),
        ),
    )
    .then(
        CommandNode::literal("pardon").then(
            CommandNode::argument("target", &ArgumentType::String(StringType::SingleWord))
                .execute(HaikyoPardonExecutor { state }),
        ),
    )
}
