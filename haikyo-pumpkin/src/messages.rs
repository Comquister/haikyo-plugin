use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize, Deserialize)]
pub struct S2CHello {
    pub protocol_version: u16,
    pub server_role: String,
    pub server_id: String,
    pub plugin_version: String,
    pub require_haikyo: bool,
    pub channels: HashMap<String, u16>,
    pub mod_channels: Vec<String>,
    pub consent_scopes: Vec<String>,
    pub download_url: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct C2SHelloAck {
    pub protocol_version: u16,
    pub client_version: String,
    pub channel: String,
    pub channels: HashMap<String, u16>,
    #[serde(default)]
    pub mod_channels: Vec<String>,
    #[serde(default)]
    pub consented_scopes: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct S2CAttestChallenge {
    pub nonce: String, // hex 32 bytes
    pub server_address: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct S2CAttestResult {
    pub success: bool,
    pub trust_level: String,
    pub message: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct S2CModsRules {
    pub rules_blob: String, // Base64 or JSON
}

#[derive(Debug, Serialize, Deserialize)]
pub struct S2CAdminScreenshotRequest {
    pub request_id: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct C2SAdminScreenshotResponse {
    pub request_id: String,
    pub success: bool,
    pub error: Option<String>,
    pub image_sha256: Option<String>,
    pub image_len: Option<usize>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct C2SAdminHwidResponse {
    pub hwid: String,
}
