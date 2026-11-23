use std::io::{Cursor, Read, Write};
use std::time::{SystemTime, UNIX_EPOCH};
use hmac::{Hmac, Mac};
use sha2::Sha256;
use uuid::Uuid;

use crate::crypto::write_lp_str;

type HmacSha256 = Hmac<Sha256>;

pub const LINK_MAGIC: &[u8; 4] = b"HKLK";
pub const REQUIRE_SYNC_MAGIC: &[u8; 4] = b"HKRQ";
pub const FORMAT_VERSION: u8 = 1;
pub const HMAC_LENGTH: usize = 32;
pub const REQUIRE_SYNC_FRESHNESS_SECONDS: u64 = 60;

pub const ACTION_PARDON: u8 = 0;
pub const ACTION_REQUIRE: u8 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequireSync {
    pub require: bool,
    pub player_uuid: Uuid,
    pub player_name: String,
    pub reason: String,
    pub issued_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LinkError {
    MissingSecret,
    MalformedPayload(String),
    InvalidHmac,
    SessionExpired(String),
    UuidMismatch,
}

impl std::fmt::Display for LinkError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LinkError::MissingSecret => write!(f, "Missing shared secret"),
            LinkError::MalformedPayload(msg) => write!(f, "Malformed payload: {}", msg),
            LinkError::InvalidHmac => write!(f, "Invalid HMAC-SHA256 signature"),
            LinkError::SessionExpired(msg) => write!(f, "Session expired: {}", msg),
            LinkError::UuidMismatch => write!(f, "UUID mismatch"),
        }
    }
}

impl std::error::Error for LinkError {}

/// Computes HMAC-SHA256 over data with secret.
pub fn hmac_sha256(secret: &[u8], data: &[u8]) -> [u8; 32] {
    let mut mac = HmacSha256::new_from_slice(secret).expect("HMAC can take key of any size");
    mac.update(data);
    let result = mac.finalize();
    let bytes = result.into_bytes();
    let mut out = [0u8; 32];
    out.copy_from_slice(&bytes);
    out
}

/// Constant-time comparison of two byte slices.
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// Encodes a signed `haikyo:link` `0x03` (`HKRQ`) require/pardon sync packet:
/// `"HKRQ" | ver:u8(1) | action:u8(1=REQUIRE,0=PARDON) | player_uuid:[16] | player_name:lp_str | reason:lp_str | issued_at:i64 || HMAC-SHA256(32B)`
pub fn encode_signed_require_sync(sync: &RequireSync, shared_secret: &str) -> Result<Vec<u8>, LinkError> {
    if shared_secret.is_empty() {
        return Err(LinkError::MissingSecret);
    }

    let mut payload = Vec::with_capacity(128);
    payload.extend_from_slice(REQUIRE_SYNC_MAGIC);
    payload.push(FORMAT_VERSION);
    payload.push(if sync.require { ACTION_REQUIRE } else { ACTION_PARDON });
    payload.extend_from_slice(sync.player_uuid.as_bytes());

    write_lp_str(&mut payload, &sync.player_name)
        .map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    write_lp_str(&mut payload, &sync.reason)
        .map_err(|e| LinkError::MalformedPayload(e.to_string()))?;

    payload.extend_from_slice(&sync.issued_at.to_be_bytes());

    let hmac = hmac_sha256(shared_secret.as_bytes(), &payload);
    payload.extend_from_slice(&hmac);
    Ok(payload)
}

/// Verifies and decodes a signed `haikyo:link` `0x03` (`HKRQ`) payload.
pub fn verify_and_decode_require_sync(
    body: &[u8],
    shared_secret: &str,
    now_epoch_sec: i64,
) -> Result<RequireSync, LinkError> {
    if shared_secret.is_empty() {
        return Err(LinkError::MissingSecret);
    }

    // Minimum: 4(magic) + 1(ver) + 1(action) + 16(uuid) + 2(nameLen) + 2(reasonLen) + 8(issuedAt) + 32(hmac)
    if body.len() < 4 + 1 + 1 + 16 + 2 + 2 + 8 + HMAC_LENGTH {
        return Err(LinkError::MalformedPayload("HKRQ payload too short".into()));
    }

    let payload_len = body.len() - HMAC_LENGTH;
    let (payload, received_hmac) = body.split_at(payload_len);
    let expected_hmac = hmac_sha256(shared_secret.as_bytes(), payload);

    if !constant_time_eq(received_hmac, &expected_hmac) {
        return Err(LinkError::InvalidHmac);
    }

    let mut cursor = Cursor::new(payload);
    let mut magic = [0u8; 4];
    cursor.read_exact(&mut magic).map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    if &magic != REQUIRE_SYNC_MAGIC {
        return Err(LinkError::MalformedPayload("Invalid HKRQ magic".into()));
    }

    let mut ver_buf = [0u8; 1];
    cursor.read_exact(&mut ver_buf).map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    if ver_buf[0] != FORMAT_VERSION {
        return Err(LinkError::MalformedPayload(format!("Unsupported HKRQ version: {}", ver_buf[0])));
    }

    let mut action_buf = [0u8; 1];
    cursor.read_exact(&mut action_buf).map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    let action = action_buf[0];
    if action != ACTION_PARDON && action != ACTION_REQUIRE {
        return Err(LinkError::MalformedPayload(format!("Invalid action: {}", action)));
    }

    let mut uuid_bytes = [0u8; 16];
    cursor.read_exact(&mut uuid_bytes).map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    let player_uuid = Uuid::from_bytes(uuid_bytes);

    let player_name = read_lp_str(&mut cursor)
        .map_err(|e| LinkError::MalformedPayload(format!("Failed reading player_name: {}", e)))?;
    let reason = read_lp_str(&mut cursor)
        .map_err(|e| LinkError::MalformedPayload(format!("Failed reading reason: {}", e)))?;

    let mut issued_at_bytes = [0u8; 8];
    cursor.read_exact(&mut issued_at_bytes).map_err(|e| LinkError::MalformedPayload(e.to_string()))?;
    let issued_at = i64::from_be_bytes(issued_at_bytes);

    if cursor.position() as usize != payload.len() {
        return Err(LinkError::MalformedPayload("Unexpected trailing bytes in HKRQ payload".into()));
    }

    let skew = (now_epoch_sec - issued_at).abs();
    if skew as u64 > REQUIRE_SYNC_FRESHNESS_SECONDS {
        return Err(LinkError::SessionExpired(format!(
            "HKRQ timestamp outside freshness window (skew={}s)",
            skew
        )));
    }

    Ok(RequireSync {
        require: action == ACTION_REQUIRE,
        player_uuid,
        player_name,
        reason,
        issued_at,
    })
}

fn read_lp_str<R: Read>(reader: &mut R) -> Result<String, Box<dyn std::error::Error>> {
    let mut len_buf = [0u8; 2];
    reader.read_exact(&mut len_buf)?;
    let len = u16::from_be_bytes(len_buf) as usize;
    let mut str_buf = vec![0u8; len];
    reader.read_exact(&mut str_buf)?;
    String::from_utf8(str_buf).map_err(|e| e.into())
}

/// Encodes a link request (`0x02` `link_request` backend -> proxy):
/// `player_uuid: [u8; 16]`
pub fn encode_link_request(player_uuid: &Uuid) -> Vec<u8> {
    player_uuid.as_bytes().to_vec()
}
