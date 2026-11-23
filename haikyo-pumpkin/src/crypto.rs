use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use std::collections::HashMap;
use uuid::Uuid;

pub const TOKEN_MAGIC: [u8; 4] = [0x48, 0x4B, 0x41, 0x54]; // "HKAT"
pub const TOKEN_VERSION: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttestationToken {
    pub key_id: String,
    pub version: String,
    pub channel: String,
    pub exe_sha256: [u8; 32],
    pub player_uuid: Uuid,
    pub nonce: [u8; 32],
    pub server_address: String,
    pub issued_at: u64,
    pub expires_at: u64,
    pub signature: [u8; 64],
    pub signed_payload: Vec<u8>,
}

fn read_lp_str(buf: &[u8], offset: &mut usize) -> Result<String, String> {
    if *offset + 2 > buf.len() {
        return Err("Buffer underflow reading lp_str length".into());
    }
    let len = u16::from_be_bytes([buf[*offset], buf[*offset + 1]]) as usize;
    *offset += 2;
    if *offset + len > buf.len() {
        return Err("Buffer underflow reading lp_str content".into());
    }
    let s = std::str::from_utf8(&buf[*offset..*offset + len])
        .map_err(|e| format!("Invalid UTF-8 in lp_str: {e}"))?
        .to_string();
    *offset += len;
    Ok(s)
}

pub fn normalize_server_address(addr: &str) -> String {
    let trimmed = addr.trim_end_matches('.');
    let mut parts = trimmed.split(':');
    let host = parts.next().unwrap_or("").to_lowercase();
    let port = parts.next().unwrap_or("25565");
    format!("{}:{}", host, port)
}

pub fn parse_attestation_token(raw: &[u8]) -> Result<AttestationToken, String> {
    if raw.len() < 4 + 1 + 2 + 32 + 16 + 32 + 2 + 8 + 8 + 64 {
        return Err("Token size is below minimum binary structure".into());
    }

    if raw[0..4] != TOKEN_MAGIC {
        return Err("Invalid token magic: expected 'HKAT'".into());
    }
    if raw[4] != TOKEN_VERSION {
        return Err(format!("Unsupported token version: {}", raw[4]));
    }

    let mut offset = 5;
    let key_id = read_lp_str(raw, &mut offset)?;
    let version = read_lp_str(raw, &mut offset)?;
    let channel = read_lp_str(raw, &mut offset)?;

    if offset + 32 > raw.len() {
        return Err("Buffer underflow reading exe_sha256".into());
    }
    let mut exe_sha256 = [0u8; 32];
    exe_sha256.copy_from_slice(&raw[offset..offset + 32]);
    offset += 32;

    if offset + 16 > raw.len() {
        return Err("Buffer underflow reading player_uuid".into());
    }
    let player_uuid = Uuid::from_slice(&raw[offset..offset + 16])
        .map_err(|e| format!("Invalid player UUID in token: {e}"))?;
    offset += 16;

    if offset + 32 > raw.len() {
        return Err("Buffer underflow reading nonce".into());
    }
    let mut nonce = [0u8; 32];
    nonce.copy_from_slice(&raw[offset..offset + 32]);
    offset += 32;

    let server_address = read_lp_str(raw, &mut offset)?;

    if offset + 8 > raw.len() {
        return Err("Buffer underflow reading issued_at".into());
    }
    let issued_at = u64::from_be_bytes(raw[offset..offset + 8].try_into().unwrap());
    offset += 8;

    if offset + 8 > raw.len() {
        return Err("Buffer underflow reading expires_at".into());
    }
    let expires_at = u64::from_be_bytes(raw[offset..offset + 8].try_into().unwrap());
    offset += 8;

    let signed_payload = raw[0..offset].to_vec();

    if offset + 64 != raw.len() {
        return Err(format!(
            "Trailing or missing bytes for Ed25519 signature: expected exactly 64 remaining bytes, got {}",
            raw.len() - offset
        ));
    }

    let mut signature = [0u8; 64];
    signature.copy_from_slice(&raw[offset..offset + 64]);

    Ok(AttestationToken {
        key_id,
        version,
        channel,
        exe_sha256,
        player_uuid,
        nonce,
        server_address,
        issued_at,
        expires_at,
        signature,
        signed_payload,
    })
}

#[derive(Debug, Clone, Default)]
pub struct KeyStore {
    keys: HashMap<String, VerifyingKey>,
}

impl KeyStore {
    pub fn new() -> Self {
        Self {
            keys: HashMap::new(),
        }
    }

    pub fn insert_raw(&mut self, key_id: String, raw_key: &[u8; 32]) -> Result<(), String> {
        let vk = VerifyingKey::from_bytes(raw_key)
            .map_err(|e| format!("Invalid Ed25519 public key bytes for {key_id}: {e}"))?;
        self.keys.insert(key_id, vk);
        Ok(())
    }

    pub fn insert_base64(&mut self, key_id: String, b64: &str) -> Result<(), String> {
        use base64::Engine;
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(b64)
            .map_err(|e| format!("Base64 decode error for {key_id}: {e}"))?;
        if bytes.len() != 32 {
            return Err(format!("Expected 32 bytes for Ed25519 public key, got {}", bytes.len()));
        }
        let mut raw = [0u8; 32];
        raw.copy_from_slice(&bytes);
        self.insert_raw(key_id, &raw)
    }

    pub fn verify_token(
        &self,
        token: &AttestationToken,
        expected_uuid: &Uuid,
        expected_nonce: &[u8; 32],
        expected_server_address: &str,
        now_unix: u64,
    ) -> Result<(), String> {
        let key = self
            .keys
            .get(&token.key_id)
            .ok_or_else(|| format!("Unknown public key key_id: {}", token.key_id))?;

        let sig = Signature::from_bytes(&token.signature);
        key.verify(&token.signed_payload, &sig)
            .map_err(|e| format!("Ed25519 signature verification failed: {e}"))?;

        if &token.player_uuid != expected_uuid {
            return Err(format!(
                "Player UUID mismatch: token has {}, expected {}",
                token.player_uuid, expected_uuid
            ));
        }

        if &token.nonce != expected_nonce {
            return Err("Attestation nonce mismatch".into());
        }

        let normalized_expected = normalize_server_address(expected_server_address);
        let normalized_token = normalize_server_address(&token.server_address);
        if normalized_token != normalized_expected {
            return Err(format!(
                "Server address mismatch: token has '{}', server expected '{}'",
                normalized_token, normalized_expected
            ));
        }

        if now_unix < token.issued_at {
            return Err(format!(
                "Token issued in the future: issued_at={}, now={}",
                token.issued_at, now_unix
            ));
        }

        if now_unix > token.expires_at {
            return Err(format!(
                "Token expired: expires_at={}, now={}",
                token.expires_at, now_unix
            ));
        }

        Ok(())
    }
}
