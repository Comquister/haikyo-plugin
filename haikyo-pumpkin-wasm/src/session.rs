use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use uuid::Uuid;

use crate::crypto::AttestationToken;
use crate::framing::Reassembler;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TrustLevel {
    None,
    Attested,
    Suspect,
}

impl TrustLevel {
    pub fn wire_name(&self) -> &'static str {
        match self {
            TrustLevel::None => "none",
            TrustLevel::Attested => "attested",
            TrustLevel::Suspect => "suspect",
        }
    }
}

#[derive(Debug)]
pub struct PlayerSession {
    pub uuid: Uuid,
    pub username: String,
    pub address: String,
    pub trust_level: TrustLevel,
    pub is_haikyo: bool,
    pub client_version: Option<String>,
    pub channel: Option<String>,
    pub consented_scopes: HashSet<String>,
    pub nonce: [u8; 32],
    pub session_key: [u8; 32],
    pub challenge_sent: bool,
    pub challenge_sent_at: u64,
    pub attest_token: Option<AttestationToken>,
    pub hwid: Option<String>,
    pub reassembler: Reassembler,
}

fn random_32_bytes() -> [u8; 32] {
    let mut out = [0u8; 32];
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    out[0..16].copy_from_slice(a.as_bytes());
    out[16..32].copy_from_slice(b.as_bytes());
    out
}

impl PlayerSession {
    pub fn new(uuid: Uuid, username: String, address: String, now_unix: u64) -> Self {
        Self {
            uuid,
            username,
            address,
            trust_level: TrustLevel::None,
            is_haikyo: false,
            client_version: None,
            channel: None,
            consented_scopes: HashSet::new(),
            nonce: random_32_bytes(),
            session_key: random_32_bytes(),
            challenge_sent: false,
            challenge_sent_at: now_unix,
            attest_token: None,
            hwid: None,
            reassembler: Reassembler::new(),
        }
    }
}
