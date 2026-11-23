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
    pub challenge_sent_at: u64,
    pub attest_token: Option<AttestationToken>,
    pub hwid: Option<String>,
    pub reassembler: Reassembler,
}

impl PlayerSession {
    pub fn new(uuid: Uuid, username: String, address: String, now_unix: u64) -> Self {
        let mut nonce = [0u8; 32];
        let rand_bytes = Uuid::new_v4();
        let rand_bytes2 = Uuid::new_v4();
        nonce[0..16].copy_from_slice(rand_bytes.as_bytes());
        nonce[16..32].copy_from_slice(rand_bytes2.as_bytes());

        Self {
            uuid,
            username,
            address,
            trust_level: TrustLevel::None,
            is_haikyo: false,
            client_version: None,
            channel: None,
            consented_scopes: HashSet::new(),
            nonce,
            challenge_sent_at: now_unix,
            attest_token: None,
            hwid: None,
            reassembler: Reassembler::new(),
        }
    }
}
