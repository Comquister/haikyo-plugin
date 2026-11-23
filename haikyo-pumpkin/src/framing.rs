use bytes::Bytes;
use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub const MAGIC_UNFRAGMENTED: [u8; 2] = [0x48, 0x4B]; // "HK"
pub const PROTO_VER: u16 = 1;
pub const FLAG_UNFRAGMENTED: u8 = 0x00;
pub const FLAG_FRAGMENTED: u8 = 0x01;

pub const UNFRAGMENTED_HEADER_LEN: usize = 6;
pub const FRAGMENTED_HEADER_LEN: usize = 46;
pub const MAX_FRAGMENTS: u16 = 64;
pub const MAX_REASSEMBLED_BYTES: usize = 2 * 1024 * 1024; // 2 MiB
pub const S2C_WIRE_LIMIT: usize = 1048576; // 1 MiB
pub const C2S_WIRE_LIMIT: usize = 32767;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HaikyoFrame {
    pub proto_ver: u16,
    pub pkt_type: u8,
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DecodeResult {
    Complete(HaikyoFrame),
    Pending,
    Ignored(String),
}

pub fn encode_unfragmented(pkt_type: u8, body: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(UNFRAGMENTED_HEADER_LEN + body.len());
    out.extend_from_slice(&MAGIC_UNFRAGMENTED);
    out.extend_from_slice(&PROTO_VER.to_be_bytes());
    out.push(FLAG_UNFRAGMENTED);
    out.push(pkt_type);
    out.extend_from_slice(body);
    out
}

pub fn encode_fragmented(pkt_type: u8, frag_id: u32, body: &[u8], max_chunk_size: usize) -> Vec<Vec<u8>> {
    let mut hasher = Sha256::new();
    hasher.update(body);
    let payload_sha256: [u8; 32] = hasher.finalize().into();

    let chunks: Vec<&[u8]> = body.chunks(max_chunk_size).collect();
    let frag_total = chunks.len() as u16;
    let mut out = Vec::with_capacity(chunks.len());

    for (idx, chunk) in chunks.into_iter().enumerate() {
        let mut frame = Vec::with_capacity(FRAGMENTED_HEADER_LEN + chunk.len());
        frame.extend_from_slice(&MAGIC_UNFRAGMENTED);
        frame.extend_from_slice(&PROTO_VER.to_be_bytes());
        frame.push(FLAG_FRAGMENTED);
        frame.push(pkt_type);
        frame.extend_from_slice(&frag_id.to_be_bytes());
        frame.extend_from_slice(&(idx as u16).to_be_bytes());
        frame.extend_from_slice(&frag_total.to_be_bytes());
        frame.extend_from_slice(&payload_sha256);
        frame.extend_from_slice(chunk);
        out.push(frame);
    }
    out
}

#[derive(Debug, Default)]
struct PendingReassembly {
    pkt_type: u8,
    frag_total: u16,
    payload_sha256: [u8; 32],
    chunks: HashMap<u16, Vec<u8>>,
}

#[derive(Debug, Default)]
pub struct Reassembler {
    pending: HashMap<u32, PendingReassembly>,
}

impl Reassembler {
    pub fn new() -> Self {
        Self {
            pending: HashMap::new(),
        }
    }

    pub fn process_incoming(&mut self, raw: &[u8]) -> Result<DecodeResult, String> {
        if raw.len() < UNFRAGMENTED_HEADER_LEN {
            return Err("Packet smaller than unfragmented header".into());
        }
        if raw[0..2] != MAGIC_UNFRAGMENTED {
            return Err("Invalid magic: expected 'HK'".into());
        }

        let proto_ver = u16::from_be_bytes([raw[2], raw[3]]);
        if proto_ver > PROTO_VER {
            return Ok(DecodeResult::Ignored(format!(
                "proto_ver {} > 1 ignored for forward compatibility",
                proto_ver
            )));
        }

        let flags = raw[4];
        let pkt_type = raw[5];

        if flags == FLAG_UNFRAGMENTED {
            let body = raw[6..].to_vec();
            return Ok(DecodeResult::Complete(HaikyoFrame {
                proto_ver,
                pkt_type,
                body,
            }));
        }

        if flags != FLAG_FRAGMENTED {
            return Err(format!("Unknown frame flags: 0x{:02x}", flags));
        }

        if raw.len() < FRAGMENTED_HEADER_LEN {
            return Err("Packet smaller than fragmented header (46 bytes)".into());
        }

        let frag_id = u32::from_be_bytes([raw[6], raw[7], raw[8], raw[9]]);
        let frag_idx = u16::from_be_bytes([raw[10], raw[11]]);
        let frag_total = u16::from_be_bytes([raw[12], raw[13]]);
        let mut payload_sha256 = [0u8; 32];
        payload_sha256.copy_from_slice(&raw[14..46]);
        let chunk = raw[46..].to_vec();

        if frag_total == 0 || frag_total > MAX_FRAGMENTS {
            return Err(format!("Invalid frag_total: {}", frag_total));
        }
        if frag_idx >= frag_total {
            return Err(format!("frag_idx {} >= frag_total {}", frag_idx, frag_total));
        }

        if !self.pending.contains_key(&frag_id) {
            if self.pending.len() >= 8 {
                // Remove oldest pending
                if let Some(oldest_id) = self.pending.keys().next().copied() {
                    self.pending.remove(&oldest_id);
                }
            }
            self.pending.insert(
                frag_id,
                PendingReassembly {
                    pkt_type,
                    frag_total,
                    payload_sha256,
                    chunks: HashMap::new(),
                },
            );
        }

        let entry = self.pending.get_mut(&frag_id).unwrap();
        if entry.pkt_type != pkt_type
            || entry.frag_total != frag_total
            || entry.payload_sha256 != payload_sha256
        {
            return Err("Fragment metadata mismatch for same frag_id".into());
        }

        if let Some(existing) = entry.chunks.get(&frag_idx) {
            if existing != &chunk {
                return Err("Duplicate frag_idx with conflicting chunk content".into());
            }
        } else {
            entry.chunks.insert(frag_idx, chunk);
        }

        if entry.chunks.len() == entry.frag_total as usize {
            let mut assembled = Vec::new();
            for i in 0..entry.frag_total {
                if let Some(c) = entry.chunks.get(&i) {
                    assembled.extend_from_slice(c);
                } else {
                    return Err(format!("Missing chunk {} on completion", i));
                }
            }

            if assembled.len() > MAX_REASSEMBLED_BYTES {
                self.pending.remove(&frag_id);
                return Err("Reassembled payload exceeds MAX_REASSEMBLED_BYTES (2 MiB)".into());
            }

            let mut hasher = Sha256::new();
            hasher.update(&assembled);
            let calculated_sha256: [u8; 32] = hasher.finalize().into();

            if calculated_sha256 != entry.payload_sha256 {
                self.pending.remove(&frag_id);
                return Err("Reassembled payload SHA-256 checksum mismatch".into());
            }

            let completed_pkt_type = entry.pkt_type;
            self.pending.remove(&frag_id);

            Ok(DecodeResult::Complete(HaikyoFrame {
                proto_ver,
                pkt_type: completed_pkt_type,
                body: assembled,
            }))
        } else {
            Ok(DecodeResult::Pending)
        }
    }
}
