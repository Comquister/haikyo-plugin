use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub const DEFAULT_PUBLIC_KEY_K1_B64: &str = "BrOlqFYD4IRYXlU56Kcq6t8Zb+Th5YlHNSB/2wlTS4M=";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HaikyoConfig {
    #[serde(default = "default_server_role")]
    pub server_role: String,
    #[serde(default = "default_server_id")]
    pub server_id: String,
    #[serde(default = "default_server_address")]
    pub server_address: String,
    #[serde(default = "default_require_haikyo")]
    pub require_haikyo: bool,
    #[serde(default = "default_challenge_timeout_secs")]
    pub challenge_timeout_secs: u64,
    #[serde(default = "default_kick_message")]
    pub kick_message: String,
    #[serde(default = "default_public_keys")]
    pub public_keys: HashMap<String, String>,
}

fn default_server_role() -> String {
    "standalone".into()
}

fn default_server_id() -> String {
    "pumpkin-server".into()
}

fn default_server_address() -> String {
    String::new()
}

fn default_require_haikyo() -> bool {
    false
}

fn default_challenge_timeout_secs() -> u64 {
    15
}

fn default_kick_message() -> String {
    "Este servidor requer o Haikyo Client oficial (https://haikyo.conquister.net)".into()
}

fn default_public_keys() -> HashMap<String, String> {
    let mut map = HashMap::new();
    map.insert("k1".into(), DEFAULT_PUBLIC_KEY_K1_B64.into());
    map
}

impl Default for HaikyoConfig {
    fn default() -> Self {
        Self {
            server_role: default_server_role(),
            server_id: default_server_id(),
            server_address: default_server_address(),
            require_haikyo: default_require_haikyo(),
            challenge_timeout_secs: default_challenge_timeout_secs(),
            kick_message: default_kick_message(),
            public_keys: default_public_keys(),
        }
    }
}
