use std::fs;
use std::path::Path;
use uuid::Uuid;

use haikyo_pumpkin::crypto::{AttestationToken, KeyStore, parse_attestation_token};
use haikyo_pumpkin::framing::{
    DecodeResult, Reassembler, encode_fragmented, encode_unfragmented,
};

#[test]
fn test_protocol_vectors_cross_language() {
    let local_path = Path::new("tests/protocol_vectors.json");
    let fallback_path = Path::new("../../tools/protocol_vectors.json");
    let content = if local_path.exists() {
        fs::read_to_string(local_path).expect("Could not read tests/protocol_vectors.json")
    } else {
        fs::read_to_string(fallback_path).expect("Could not read ../../tools/protocol_vectors.json")
    };
    let json: serde_json::Value = serde_json::from_str(&content).expect("Valid JSON");

    let reference_now = json["reference_now_unix"].as_u64().unwrap();

    let mut keystore = KeyStore::new();

    let attest_primary = &json["test_keys"]["attest_primary"];
    let primary_id = attest_primary["key_id"].as_str().unwrap().to_string();
    let primary_b64 = attest_primary["public_raw_b64"].as_str().unwrap();
    keystore
        .insert_base64(primary_id.clone(), primary_b64)
        .expect("Primary key insertion");

    let attest_rotated = &json["test_keys"]["attest_rotated"];
    let rotated_id = attest_rotated["key_id"].as_str().unwrap().to_string();
    let rotated_b64 = attest_rotated["public_raw_b64"].as_str().unwrap();
    keystore
        .insert_base64(rotated_id.clone(), rotated_b64)
        .expect("Rotated key insertion");

    let fields = &json["attestation_tokens"]["fields"];
    let expected_uuid_str = fields["player_uuid"].as_str().unwrap();
    let expected_uuid = Uuid::parse_str(expected_uuid_str).unwrap();

    let expected_nonce_hex = fields["nonce_hex"].as_str().unwrap();
    let mut expected_nonce = [0u8; 32];
    hex::decode_to_slice(expected_nonce_hex, &mut expected_nonce).unwrap();

    let server_address_raw = fields["server_address_raw"].as_str().unwrap();

    // 1. Valid token test
    let valid_hex = json["attestation_tokens"]["valid"]["token_hex"].as_str().unwrap();
    let valid_bytes = hex::decode(valid_hex).unwrap();
    let token = parse_attestation_token(&valid_bytes).expect("Valid token parsed");

    assert_eq!(token.key_id, primary_id);
    assert_eq!(token.version, "v2026.11.22");
    assert_eq!(token.channel, "release");
    assert_eq!(token.player_uuid, expected_uuid);
    assert_eq!(token.nonce, expected_nonce);

    keystore
        .verify_token(
            &token,
            &expected_uuid,
            &expected_nonce,
            server_address_raw,
            reference_now,
        )
        .expect("Token signature and claims verified");

    // 2. Expired token test
    let expired_hex = json["attestation_tokens"]["expired"]["token_hex"].as_str().unwrap();
    let expired_bytes = hex::decode(expired_hex).unwrap();
    let expired_token = parse_attestation_token(&expired_bytes).expect("Parsed expired token");
    let expired_err = keystore
        .verify_token(
            &expired_token,
            &expected_uuid,
            &expected_nonce,
            server_address_raw,
            reference_now,
        )
        .expect_err("Should fail expiration");
    assert!(expired_err.contains("Token expired"));

    // 3. Rotated key test
    let rotated_hex = json["attestation_tokens"]["rotated_key"]["token_hex"].as_str().unwrap();
    let rotated_bytes = hex::decode(rotated_hex).unwrap();
    let rotated_token = parse_attestation_token(&rotated_bytes).expect("Parsed rotated token");
    keystore
        .verify_token(
            &rotated_token,
            &expected_uuid,
            &expected_nonce,
            server_address_raw,
            reference_now,
        )
        .expect("Rotated token verifies with rotated key");

    // 4. Tampered payload test
    let tampered_p_hex = json["attestation_tokens"]["tampered_payload"]["token_hex"]
        .as_str()
        .unwrap();
    let tampered_p_bytes = hex::decode(tampered_p_hex).unwrap();
    let tampered_p_token = parse_attestation_token(&tampered_p_bytes).expect("Parsed token");
    let tampered_p_err = keystore
        .verify_token(
            &tampered_p_token,
            &expected_uuid,
            &expected_nonce,
            server_address_raw,
            reference_now,
        )
        .expect_err("Signature must fail on tampered payload");
    assert!(tampered_p_err.contains("signature verification failed"));

    // 5. Tampered signature test
    let tampered_s_hex = json["attestation_tokens"]["tampered_signature"]["token_hex"]
        .as_str()
        .unwrap();
    let tampered_s_bytes = hex::decode(tampered_s_hex).unwrap();
    let tampered_s_token = parse_attestation_token(&tampered_s_bytes).expect("Parsed token");
    let tampered_s_err = keystore
        .verify_token(
            &tampered_s_token,
            &expected_uuid,
            &expected_nonce,
            server_address_raw,
            reference_now,
        )
        .expect_err("Signature must fail on altered signature byte");
    assert!(tampered_s_err.contains("signature verification failed"));
}

#[test]
fn test_framing_and_reassembly() {
    let body = b"Hello from Haikyo on PumpkinMC! Testing fragment and unfragmented packaging.";
    let unfrag = encode_unfragmented(0x01, body);

    let mut reassembler = Reassembler::new();
    match reassembler.process_incoming(&unfrag).expect("valid frame") {
        DecodeResult::Complete(frame) => {
            assert_eq!(frame.proto_ver, 1);
            assert_eq!(frame.pkt_type, 0x01);
            assert_eq!(frame.body, body);
        }
        _ => panic!("Expected complete frame"),
    }

    // Fragmentation test: 15-byte chunks
    let fragments = encode_fragmented(0x07, 42, body, 15);
    assert!(fragments.len() > 1);

    let mut reassembler2 = Reassembler::new();
    // Test receiving in reverse order
    for (i, frag) in fragments.iter().rev().enumerate() {
        let res = reassembler2.process_incoming(frag).expect("chunk processed");
        if i == fragments.len() - 1 {
            match res {
                DecodeResult::Complete(frame) => {
                    assert_eq!(frame.pkt_type, 0x07);
                    assert_eq!(frame.body, body);
                }
                _ => panic!("Expected reassembly completion"),
            }
        } else {
            assert_eq!(res, DecodeResult::Pending);
        }
    }
}

#[test]
fn test_link_require_sync_roundtrip() {
    use haikyo_pumpkin::link::{
        RequireSync, encode_signed_require_sync, verify_and_decode_require_sync,
    };

    let secret = "super-secret-cluster-token-123456";
    let player_uuid = Uuid::new_v4();
    let now = 1700000000i64;

    let sync = RequireSync {
        require: true,
        player_uuid,
        player_name: "TestHaikyoPlayer".into(),
        reason: "killaura_flagged".into(),
        issued_at: now,
    };

    let encoded = encode_signed_require_sync(&sync, secret).expect("Encoding succeeds");

    // 1. Valid decode within freshness window
    let decoded = verify_and_decode_require_sync(&encoded, secret, now + 10)
        .expect("Decodes successfully within 10s");
    assert_eq!(decoded, sync);

    // 2. Reject invalid HMAC (wrong secret)
    let bad_secret_res = verify_and_decode_require_sync(&encoded, "wrong-secret", now + 10);
    assert!(bad_secret_res.is_err());

    // 3. Reject expired timestamp (> 60s skew)
    let expired_res = verify_and_decode_require_sync(&encoded, secret, now + 120);
    assert!(expired_res.is_err());

    // 4. Test pardon roundtrip
    let pardon_sync = RequireSync {
        require: false,
        player_uuid,
        player_name: "TestHaikyoPlayer".into(),
        reason: "pardon_admin".into(),
        issued_at: now,
    };
    let pardon_encoded = encode_signed_require_sync(&pardon_sync, secret).expect("Pardon encodes");
    let pardon_decoded = verify_and_decode_require_sync(&pardon_encoded, secret, now)
        .expect("Pardon decodes");
    assert_eq!(pardon_decoded, pardon_sync);
}
