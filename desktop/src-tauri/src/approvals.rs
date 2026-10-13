//! Pending approvals by id. The webview only names a pending item; native code
//! fetches it from the Home Node with the stored bearer, validates it, builds
//! the OS prompt and the signed bytes from that server data, and posts the
//! approval itself. No prompt text, nonce or signature crosses IPC. Only
//! macOS signs, and only after Touch ID (see `model_authorization::touch_id`).
use super::*;
use model_control::Session;
use serde::de::DeserializeOwned;
use sha2::{Digest, Sha256};

/// Pending lists are small; agent previews are their largest field.
const LIST_BYTES: usize = 1024 * 1024;
const NAME_CHARS: usize = 40;
const PLATFORM_CHARS: usize = 16;
const PREVIEW_CHARS: usize = 80;

#[derive(Deserialize)]
struct Me {
    user_id: uuid::Uuid,
    device_id: uuid::Uuid,
}

#[derive(Deserialize)]
struct PairingList {
    requests: Vec<PairingItem>,
}

#[derive(Deserialize)]
struct PairingItem {
    id: uuid::Uuid,
    device_name: String,
    platform: String,
    fingerprint: String,
    nonce: String,
    candidate_public_key: String,
    expires_at: i64,
    // Absent from older Cores.
    fingerprint_full: Option<String>,
}

#[derive(Deserialize)]
struct UnlockList {
    requests: Vec<UnlockItem>,
}

#[derive(Deserialize)]
struct UnlockItem {
    id: uuid::Uuid,
    device_name: String,
    platform: String,
    nonce: String,
}

#[derive(Deserialize)]
struct AgentList {
    pending: Vec<AgentItem>,
}

#[derive(Deserialize)]
struct AgentItem {
    pending_id: uuid::Uuid,
    action: String,
    preview: String,
    nonce: String,
    // Older Cores omit these; their actions cannot be approved here.
    action_sha256: Option<String>,
    approval_message: Option<String>,
}

/// OS prompt text and signed bytes, both built from one server item.
#[derive(Debug)]
struct Approval {
    prompt: String,
    message: Vec<u8>,
    expires_at: Option<i64>,
}

/// Unicode general category Cf (bidi controls, zero-width, tags) plus other
/// invisible or blank-looking default-ignorable and private-use characters.
fn is_hidden(c: char) -> bool {
    matches!(
        c as u32,
        0xAD | 0x34F
            | 0x600..=0x605
            | 0x61C
            | 0x6DD
            | 0x70F
            | 0x890..=0x891
            | 0x8E2
            | 0x115F..=0x1160
            | 0x17B4..=0x17B5
            | 0x180B..=0x180F
            | 0x200B..=0x200F
            | 0x202A..=0x202E
            | 0x2060..=0x2064
            | 0x2066..=0x206F
            // Quotation marks (Pi/Pf and fullwidth): prompts quote server text.
            | 0xAB
            | 0xBB
            | 0x2018..=0x201F
            | 0x2039..=0x203A
            | 0x2E02..=0x2E05
            | 0x2E09..=0x2E0A
            | 0x2E0C..=0x2E0D
            | 0x2E1C..=0x2E1D
            | 0x2E20..=0x2E21
            | 0xFF02
            | 0x2800
            | 0x3164
            | 0xE000..=0xF8FF
            | 0xFE00..=0xFE0F
            | 0xFEFF
            | 0xFFA0
            | 0xFFF9..=0xFFFC
            | 0x110BD
            | 0x110CD
            | 0x13430..=0x1343F
            | 0x1BCA0..=0x1BCA3
            | 0x1D173..=0x1D17A
            | 0xE0000..=0xE0FFF
            | 0xF0000..=0x10FFFF
    )
}

/// Common combining-mark blocks. ponytail: script-specific marks (for
/// example Devanagari) are not counted; add a table if names need them.
fn is_combining(c: char) -> bool {
    matches!(
        c as u32,
        0x300..=0x36F | 0x1AB0..=0x1AFF | 0x1DC0..=0x1DFF | 0x20D0..=0x20FF | 0xFE20..=0xFE2F
    )
}

/// Server text for an OS prompt: control, hidden and quote characters
/// dropped (prompts quote it), at most two combining marks per character,
/// whitespace collapsed, at most `max` characters. Longer text keeps its
/// start and end, so a long path cannot push its outcome out of view.
fn display_name(value: &str, max: usize) -> String {
    let mut marks = 0;
    let clean: String = value
        .chars()
        .map(|c| if c.is_whitespace() { ' ' } else { c })
        .filter(|c| !c.is_control() && !is_hidden(*c) && *c != '"')
        .filter(|c| {
            marks = if is_combining(*c) { marks + 1 } else { 0 };
            marks <= 2
        })
        .collect();
    let chars: Vec<char> = clean
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .collect();
    if chars.is_empty() {
        return "unknown".into();
    }
    if chars.len() <= max {
        return chars.into_iter().collect();
    }
    let head = max / 2;
    let tail = max - head - 1;
    let mut name: String = chars[..head].iter().collect();
    name.push('…');
    name.extend(&chars[chars.len() - tail..]);
    name
}

fn hex32(value: &str) -> Option<[u8; 32]> {
    if !jarvis_client_core::is_hex_of_len(value, 64) {
        return None;
    }
    hex::decode(value).ok()?.try_into().ok()
}

/// Mirrors the iOS and webview pairing fingerprint check: lowercase hex
/// SHA-256 of the 32-byte candidate key must match both server fields.
fn pairing_approval(
    items: &[PairingItem],
    request_id: uuid::Uuid,
    user_id: uuid::Uuid,
    approver: uuid::Uuid,
    now: i64,
) -> Result<Approval, String> {
    let item = items
        .iter()
        .find(|item| item.id == request_id)
        .ok_or("pairing request is no longer pending")?;
    if item.expires_at <= now {
        return Err("pairing request expired".into());
    }
    let (Some(key), Some(nonce)) = (hex32(&item.candidate_public_key), hex32(&item.nonce)) else {
        return Err("invalid pairing request".into());
    };
    let fingerprint = hex::encode(Sha256::digest(key));
    if item.fingerprint != fingerprint[..16]
        || item
            .fingerprint_full
            .as_ref()
            .is_some_and(|full| *full != fingerprint)
    {
        return Err("The Home Node's fingerprint does not match the candidate key. Do not approve; check the Home Node.".into());
    }
    let expires_at = time::OffsetDateTime::from_unix_timestamp(item.expires_at)
        .map_err(|_| "invalid pairing request")?;
    let message = jarvis_client_core::pairing_approval_message(
        item.id, &nonce, &key, user_id, approver, expires_at,
    )
    .map_err(|_| "invalid pairing request")?;
    // The verified key comes first: the candidate-chosen name is quoted after
    // it, so a name cannot pose as the fingerprint the owner compares.
    let short = (0..16)
        .step_by(4)
        .map(|i| &fingerprint[i..i + 4])
        .collect::<Vec<_>>()
        .join(" ");
    Ok(Approval {
        prompt: format!(
            "Pair key {short} … {}: \"{}\" ({})",
            &fingerprint[60..],
            display_name(&item.device_name, NAME_CHARS),
            display_name(&item.platform, PLATFORM_CHARS)
        ),
        message,
        expires_at: Some(item.expires_at),
    })
}

fn unlock_approval(
    items: &[UnlockItem],
    request_id: uuid::Uuid,
    approver: uuid::Uuid,
) -> Result<Approval, String> {
    let item = items
        .iter()
        .find(|item| item.id == request_id)
        .ok_or("unlock request is no longer pending")?;
    let nonce = hex32(&item.nonce).ok_or("invalid unlock request")?;
    let message = jarvis_client_core::unlock_approval_message(item.id, approver, &nonce)
        .map_err(|_| "invalid unlock request")?;
    Ok(Approval {
        prompt: format!(
            "Unlock \"{}\" ({})",
            display_name(&item.device_name, NAME_CHARS),
            display_name(&item.platform, PLATFORM_CHARS)
        ),
        message,
        expires_at: None,
    })
}

/// The action type is shown in the OS prompt, so it must be a short identifier.
fn agent_approval(
    items: &[AgentItem],
    pending_id: uuid::Uuid,
    device: uuid::Uuid,
) -> Result<Approval, String> {
    let item = items
        .iter()
        .find(|item| item.pending_id == pending_id)
        .ok_or("agent action is no longer pending")?;
    if item.approval_message.as_deref() != Some(jarvis_client_core::AGENT_APPROVAL_MESSAGE_V1) {
        return Err("Requires newer Core".into());
    }
    let action = &item.action;
    if action.is_empty()
        || action.len() > 64
        || !action
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b'.'))
    {
        return Err("invalid agent action".into());
    }
    let (Some(nonce), Some(action_sha256)) = (
        hex32(&item.nonce),
        item.action_sha256.as_deref().and_then(hex32),
    ) else {
        return Err("invalid agent approval payload".into());
    };
    let message =
        jarvis_client_core::agent_approval_message(item.pending_id, &nonce, &action_sha256, device)
            .map_err(|_| "invalid agent approval payload")?;
    Ok(Approval {
        prompt: format!(
            "Approve agent action {action}: \"{}\"",
            // Core puts the summary (path, verb, size) on the first line.
            display_name(item.preview.lines().next().unwrap_or(""), PREVIEW_CHARS)
        ),
        message,
        expires_at: None,
    })
}

fn session_device(session: &Session) -> Result<uuid::Uuid, String> {
    uuid::Uuid::parse_str(&session.device).map_err(|_| "Device not paired".into())
}

fn parse_id(id: &str) -> Result<uuid::Uuid, String> {
    uuid::Uuid::parse_str(id).map_err(|_| "invalid approval id".into())
}

async fn fetch<T: DeserializeOwned>(session: &Session, path: &str) -> Result<T, String> {
    let response = session
        .client
        .get(api_url(Some(&session.origin), path)?)
        .bearer_auth(&session.token)
        .header(reqwest::header::ACCEPT, "application/json")
        .send()
        .await
        .map_err(|_| "Home Node is unreachable")?;
    if !response.status().is_success() {
        return Err(format!(
            "Home Node refused the request (HTTP {})",
            response.status().as_u16()
        ));
    }
    let bytes = native_response::bounded(response, LIST_BYTES)
        .await
        .map_err(|_| "Home Node response is invalid")?;
    serde_json::from_slice(&bytes).map_err(|_| "Home Node response is invalid".into())
}

/// Shown when this computer cannot approve: approvals need Touch ID.
pub(super) const APPROVE_ON_MAC: &str = "Approve on a Mac with Touch ID";

/// Touch ID prompt, login binding revalidation and signing, then a native POST of
/// the signature with the captured origin and bearer.
async fn sign_and_post(session: Session, approval: Approval, path: String) -> Result<(), String> {
    let Session {
        app,
        origin,
        device,
        token,
        epoch,
        client,
    } = session;
    let signing_app = app.clone();
    let signing_origin = origin.clone();
    let Approval {
        prompt,
        message,
        expires_at,
    } = approval;
    let signature = tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let _prompt = model_authorization::try_prompt()?;
        model_authorization::touch_id(cfg!(target_os = "macos"), APPROVE_ON_MAC, || {
            authenticate_owner(&prompt, false)
        })?;
        let guard = auth_storage()?;
        let current = load_metadata(&signing_app)?;
        validate_login_binding(
            *guard,
            epoch,
            current.home_node_origin.as_deref(),
            &signing_origin,
        )?;
        if current.device_id.as_deref() != Some(device.as_str())
            || expires_at.is_some_and(|at| at <= time::OffsetDateTime::now_utc().unix_timestamp())
        {
            return Err("Approval expired or device changed".into());
        }
        Ok(hex::encode(
            signing_key_unlocked(&signing_app, false)?
                .sign(&message)
                .to_bytes(),
        ))
    })
    .await
    .map_err(|_| "OS authentication failed")??;
    {
        let guard = auth_storage()?;
        let current = load_metadata(&app)?;
        validate_login_binding(*guard, epoch, current.home_node_origin.as_deref(), &origin)?;
    }
    let response = client
        .post(api_url(Some(&origin), &path)?)
        .bearer_auth(token)
        .json(&serde_json::json!({ "signature": signature }))
        .send()
        .await
        .map_err(|_| "Approval outcome unknown; refresh")?;
    if !response.status().is_success() {
        return Err(format!(
            "Home Node did not confirm the approval (HTTP {}); refresh",
            response.status().as_u16()
        ));
    }
    Ok(())
}

#[tauri::command]
pub(super) async fn auth_approve_pairing(app: AppHandle, request_id: String) -> Result<(), String> {
    let request_id = parse_id(&request_id)?;
    let session = Session::load(app)?;
    let device = session_device(&session)?;
    let me: Me = fetch(&session, "/v1/auth/me").await?;
    if me.device_id != device {
        return Err("device login session changed".into());
    }
    let list: PairingList = fetch(&session, "/v1/auth/pairing/requests").await?;
    let now = time::OffsetDateTime::now_utc().unix_timestamp();
    let approval = pairing_approval(&list.requests, request_id, me.user_id, device, now)?;
    let path = format!("/v1/auth/pairing/requests/{request_id}/approve");
    sign_and_post(session, approval, path).await
}

#[tauri::command]
pub(super) async fn auth_approve_unlock(app: AppHandle, request_id: String) -> Result<(), String> {
    let request_id = parse_id(&request_id)?;
    let session = Session::load(app)?;
    let device = session_device(&session)?;
    let list: UnlockList = fetch(&session, "/v1/auth/unlock/pending?wait=0").await?;
    let approval = unlock_approval(&list.requests, request_id, device)?;
    let path = format!("/v1/auth/unlock/{request_id}/approve");
    sign_and_post(session, approval, path).await
}

#[tauri::command]
pub(super) async fn auth_approve_agent(app: AppHandle, pending_id: String) -> Result<(), String> {
    let pending_id = parse_id(&pending_id)?;
    let session = Session::load(app)?;
    let device = session_device(&session)?;
    let list: AgentList = fetch(&session, "/v1/agent/pending").await?;
    let approval = agent_approval(&list.pending, pending_id, device)?;
    let path = format!("/v1/agent/pending/{pending_id}/approve");
    sign_and_post(session, approval, path).await
}

#[cfg(test)]
mod tests {
    use super::*;

    const REQUEST: &str = "00112233-4455-6677-8899-aabbccddeeff";
    const DEVICE: &str = "ffeeddcc-bbaa-9988-7766-554433221100";
    const USER: &str = "10213243-5465-7687-98a9-bacbdcedfe0f";
    const NONCE: &str = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    fn id(value: &str) -> uuid::Uuid {
        uuid::Uuid::parse_str(value).unwrap()
    }

    fn pairing(name: &str) -> PairingItem {
        let key = "ab".repeat(32);
        let fingerprint = hex::encode(Sha256::digest(hex::decode(&key).unwrap()));
        PairingItem {
            id: id(REQUEST),
            device_name: name.into(),
            platform: "ios".into(),
            fingerprint: fingerprint[..16].into(),
            nonce: NONCE.into(),
            candidate_public_key: key,
            expires_at: 2_000_000_000,
            fingerprint_full: Some(fingerprint),
        }
    }

    #[test]
    fn display_names_drop_control_and_format_characters() {
        assert_eq!(display_name("Owner's iPhone", 40), "Owner's iPhone");
        assert_eq!(
            display_name("Evil\u{202E}enohPi\u{200B}\u{2066}", 40),
            "EvilenohPi"
        );
        assert_eq!(display_name("a\nApprove\u{2028}all\t", 40), "a Approve all");
        assert_eq!(display_name("\u{0}\u{7}\u{FEFF} ", 40), "unknown");
        assert_eq!(
            display_name(
                "A\u{34F}\u{3164}\u{2800}\u{FE0F}\u{E0100}\u{F0000}\u{E000}\"B",
                40
            ),
            "AB"
        );
        assert_eq!(
            display_name(
                "\u{201C}A\u{2019}\u{AB}B\u{BB}\u{FF02}\u{2039}C\u{203A}",
                40
            ),
            "ABC"
        );
        // At most two combining marks per base character.
        assert_eq!(
            display_name("e\u{301}\u{302}\u{303}\u{304} o\u{308}", 40),
            "e\u{301}\u{302} o\u{308}"
        );
        let long = display_name(&format!("{}{}", "a".repeat(60), "z".repeat(40)), 40);
        assert_eq!(long.chars().count(), 40);
        assert_eq!(long, format!("{}…{}", "a".repeat(20), "z".repeat(19)));
        assert_eq!(display_name(&"x".repeat(40), 40), "x".repeat(40));
    }

    #[test]
    fn pairing_prompt_and_message_come_from_the_server_item() {
        let item = pairing("Owner's\u{202E} iPhone");
        let key = hex::decode(&item.candidate_public_key).unwrap();
        let approval =
            pairing_approval(&[item], id(REQUEST), id(USER), id(DEVICE), 1_900_000_000).unwrap();
        assert_eq!(
            approval.prompt,
            "Pair key 9a2d b2e2 3f15 04cd … f885: \"Owner's iPhone\" (ios)"
        );
        assert_eq!(approval.expires_at, Some(2_000_000_000));
        let expected = jarvis_client_core::pairing_approval_message(
            id(REQUEST),
            &hex::decode(NONCE).unwrap(),
            &key,
            id(USER),
            id(DEVICE),
            time::OffsetDateTime::from_unix_timestamp(2_000_000_000).unwrap(),
        )
        .unwrap();
        assert_eq!(approval.message, expected);
        // A name cannot pose as the fingerprint: the verified key comes first
        // and the quoted name cannot close its quotes.
        let forged = pairing("x\" (ios) key 1a2b 3c4d 5e6f 7a8b");
        let prompt = pairing_approval(&[forged], id(REQUEST), id(USER), id(DEVICE), 0)
            .unwrap()
            .prompt;
        assert_eq!(
            prompt,
            "Pair key 9a2d b2e2 3f15 04cd … f885: \"x (ios) key 1a2b 3c4d 5e6f 7a8b\" (ios)"
        );
        // Older Cores omit fingerprint_full; the short fingerprint still binds.
        let mut legacy = pairing("Phone");
        legacy.fingerprint_full = None;
        assert!(pairing_approval(&[legacy], id(REQUEST), id(USER), id(DEVICE), 0).is_ok());
    }

    #[test]
    fn pairing_refuses_unknown_expired_or_mismatched_requests() {
        let approve = |item: PairingItem, now: i64| {
            pairing_approval(&[item], id(REQUEST), id(USER), id(DEVICE), now)
        };
        assert!(
            pairing_approval(&[pairing("Phone")], id(DEVICE), id(USER), id(DEVICE), 0)
                .unwrap_err()
                .contains("no longer pending")
        );
        assert!(approve(pairing("Phone"), 2_000_000_000)
            .unwrap_err()
            .contains("expired"));
        let mut item = pairing("Phone");
        item.fingerprint = "0".repeat(16);
        assert!(approve(item, 0).unwrap_err().contains("does not match"));
        let mut item = pairing("Phone");
        item.fingerprint_full = Some("0".repeat(64));
        assert!(approve(item, 0).unwrap_err().contains("does not match"));
        let mut item = pairing("Phone");
        item.fingerprint_full = item.fingerprint_full.map(|f| f.to_uppercase());
        assert!(approve(item, 0).is_err());
        let mut item = pairing("Phone");
        item.candidate_public_key = "ab".repeat(33);
        assert!(approve(item, 0).is_err());
        let mut item = pairing("Phone");
        item.nonce = "ab".repeat(31);
        assert!(approve(item, 0).is_err());
    }

    #[test]
    fn unlock_prompt_uses_the_sanitised_server_name() {
        let items: UnlockList = serde_json::from_value(serde_json::json!({ "requests": [{
            "id": REQUEST, "device_name": "Mac\u{202E}koob\nApprove", "platform": "macos",
            "nonce": NONCE, "created_at": 1,
        }]}))
        .unwrap();
        let approval = unlock_approval(&items.requests, id(REQUEST), id(DEVICE)).unwrap();
        assert_eq!(approval.prompt, "Unlock \"Mackoob Approve\" (macos)");
        assert_eq!(
            approval.message,
            jarvis_client_core::unlock_approval_message(
                id(REQUEST),
                id(DEVICE),
                &hex::decode(NONCE).unwrap()
            )
            .unwrap()
        );
        assert!(unlock_approval(&items.requests, id(USER), id(DEVICE))
            .unwrap_err()
            .contains("no longer pending"));
    }

    fn agent(action: &str) -> serde_json::Value {
        serde_json::json!({
            "pending_id": REQUEST, "action": action, "preview": "write /tmp/x", "nonce": NONCE,
            "action_sha256": "8d2dd02e0f1e485d0be7f748f2fdd0d2160dd245b000ca1bff1ca15cd043dbe5",
            "approval_message": "agent-approval-v1", "created_at": "2026-10-12T00:00:00Z",
        })
    }

    fn agents(items: Vec<serde_json::Value>) -> Vec<AgentItem> {
        serde_json::from_value::<AgentList>(serde_json::json!({ "pending": items }))
            .unwrap()
            .pending
    }

    #[test]
    fn agent_prompt_keeps_the_outcome_of_a_long_preview_visible() {
        let mut item = agent("write_file");
        item["preview"] = format!(
            "WriteFile notes/{}/plan.md (OVERWRITES, 512 bytes)\n--- content ---\nsecret",
            "deep/".repeat(30)
        )
        .into();
        let prompt = agent_approval(&agents(vec![item]), id(REQUEST), id(DEVICE))
            .unwrap()
            .prompt;
        assert!(prompt.starts_with("Approve agent action write_file: \"WriteFile notes/deep/"));
        assert!(
            prompt.ends_with("/plan.md (OVERWRITES, 512 bytes)\""),
            "{prompt}"
        );
        assert!(!prompt.contains("content"));
    }

    #[test]
    fn agent_prompt_takes_the_action_type_from_server_data() {
        let approval =
            agent_approval(&agents(vec![agent("write_file")]), id(REQUEST), id(DEVICE)).unwrap();
        assert_eq!(
            approval.prompt,
            "Approve agent action write_file: \"write /tmp/x\""
        );
        assert_eq!(
            hex::encode(&approval.message),
            concat!(
                "6a61727669732f6167656e742d617070726f76616c2f763100",
                "00112233445566778899aabbccddeeff",
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                "8d2dd02e0f1e485d0be7f748f2fdd0d2160dd245b000ca1bff1ca15cd043dbe5",
                "ffeeddccbbaa99887766554433221100"
            )
        );
        assert!(
            agent_approval(&agents(vec![agent("write_file")]), id(USER), id(DEVICE))
                .unwrap_err()
                .contains("no longer pending")
        );
        let mut legacy = agent("write_file");
        legacy.as_object_mut().unwrap().remove("approval_message");
        let mut newer = agent("write_file");
        newer["approval_message"] = "agent-approval-v2".into();
        let mut short_hash = agent("write_file");
        short_hash["action_sha256"] = "ab".into();
        for item in [
            agent(""),
            agent("write file\nApprove"),
            agent(&"a".repeat(65)),
            legacy,
            newer,
            short_hash,
        ] {
            assert!(agent_approval(&agents(vec![item]), id(REQUEST), id(DEVICE)).is_err());
        }
    }
}
