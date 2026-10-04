//! Fixed-path model administration. OS authentication, keys, signatures and
//! bearer HTTP remain native; JavaScript supplies only the requested toggle or
//! the typed routing document, never bytes to sign.
use super::*;
use jarvis_client_core::model_control::{
    ModelRoutingApproval, ModelToggleApproval, RoutingDocument,
};

#[derive(Deserialize)]
struct Snapshot {
    mutation: String,
    policy_sha256: Option<String>,
    // Absent on a Core without signed routing.
    routing_mutation: Option<String>,
    routing_sha256: Option<String>,
    user_id: uuid::Uuid,
    device_id: uuid::Uuid,
    server_time: i64,
    models: Vec<Entry>,
}

#[derive(Deserialize)]
struct Entry {
    provider: String,
    model: String,
    enabled: bool,
}

fn validate_snapshot(
    snapshot: &Snapshot,
    provider: &str,
    model: &str,
    hash: &str,
    device: &str,
    now: i64,
) -> Result<(), String> {
    if snapshot.mutation != "device-signed-model-toggle-v1"
        || snapshot.policy_sha256.as_deref() != Some(hash)
        || snapshot.device_id.to_string() != device
        || now.abs_diff(snapshot.server_time) > 30
        || !snapshot
            .models
            .iter()
            .any(|entry| entry.provider == provider && entry.model == model)
    {
        return Err("Model policy, device or clock changed; refresh and try again.".into());
    }
    Ok(())
}

/// Providers a routed chain may name (Core `jarvis_llm::ROUTING_PROVIDERS`).
const ROUTING_PROVIDERS: [&str; 10] = [
    "anthropic-api",
    "openai-api",
    "deepseek-api",
    "xai-api",
    "zai-api",
    "ollama",
    "ollama-cloud",
    "huggingface",
    "claude-cli",
    "codex-cli",
];

fn is_subscription(provider: &str) -> bool {
    matches!(provider, "claude-cli" | "codex-cli")
}

/// Same rules as Core's `ModelRouting::validate` plus the broker's
/// "every pair is discovered" check, so a bad document fails before OS auth.
fn validate_routing(routing: &RoutingDocument, models: &[Entry]) -> Result<(), &'static str> {
    if routing.version != 1 {
        return Err("Unsupported routing version");
    }
    let tiers = &routing.tiers;
    for route in [&tiers.cheap, &tiers.default, &tiers.hard]
        .into_iter()
        .flatten()
    {
        if route.chain.is_empty() || route.chain.len() > 9 {
            return Err("Each routed tier needs 1 to 9 models");
        }
        let mut seen = std::collections::BTreeSet::new();
        let mut after_subscription = false;
        for entry in &route.chain {
            if !ROUTING_PROVIDERS.contains(&entry.provider.as_str()) {
                return Err("Routing names an unknown provider");
            }
            if entry.model.is_empty()
                || entry.model.chars().count() > 256
                || entry.model.chars().any(char::is_control)
            {
                return Err("Routing names an invalid model");
            }
            if !seen.insert((entry.provider.as_str(), entry.model.as_str())) {
                return Err("A tier lists the same model twice");
            }
            let metered = !matches!(
                entry.provider.as_str(),
                "ollama" | "claude-cli" | "codex-cli"
            );
            if after_subscription && metered && !route.metered_after_subscription {
                return Err("A paid API after a subscription needs explicit approval");
            }
            if !models
                .iter()
                .any(|known| known.provider == entry.provider && known.model == entry.model)
            {
                return Err("Routing names a model the Home Node has not discovered");
            }
            after_subscription |= is_subscription(&entry.provider);
        }
    }
    Ok(())
}

fn validate_routing_snapshot(
    snapshot: &Snapshot,
    hash: &str,
    device: &str,
    now: i64,
) -> Result<(), String> {
    if snapshot.routing_mutation.as_deref() != Some("device-signed-model-route-v1")
        || snapshot.routing_sha256.as_deref() != Some(hash)
        || snapshot.device_id.to_string() != device
        || now.abs_diff(snapshot.server_time) > 30
    {
        return Err("Model routing, device or clock changed; refresh and try again.".into());
    }
    Ok(())
}

/// Login binding captured before any network call; revalidated before signing
/// and before posting.
struct Session {
    app: AppHandle,
    origin: String,
    device: String,
    token: String,
    epoch: u64,
    client: reqwest::Client,
}

impl Session {
    fn load(app: AppHandle) -> Result<Self, String> {
        let (auth, epoch) = {
            let guard = auth_storage()?;
            (load_secure_auth_unlocked(&app)?, *guard)
        };
        Ok(Self {
            origin: auth
                .metadata
                .home_node_origin
                .ok_or("Home Node not configured")?,
            device: auth.metadata.device_id.ok_or("Device not paired")?,
            token: auth.token.ok_or("Not signed in")?,
            epoch,
            client: native_http_client()?,
            app,
        })
    }

    async fn snapshot(&self) -> Result<Snapshot, String> {
        let response = self
            .client
            .get(api_url(Some(&self.origin), "/v1/system/models")?)
            .bearer_auth(&self.token)
            .send()
            .await
            .map_err(|_| "Model policy unreachable")?;
        if !response.status().is_success() {
            return Err("Model policy request refused".into());
        }
        let bytes = native_response::bounded(response, 8 * 1024 * 1024)
            .await
            .map_err(|_| "Invalid model policy")?;
        serde_json::from_slice(&bytes).map_err(|_| "Invalid model policy".into())
    }

    /// Require fresh OS authentication (or the current five-minute model
    /// window), revalidate the login binding, sign the fixed approval and post
    /// it to the fixed privileged route.
    async fn approve<F>(
        self,
        prompt: String,
        message: Vec<u8>,
        expires_at: i64,
        signed_request: F,
        refused: fn(u16) -> &'static str,
    ) -> Result<(), String>
    where
        F: FnOnce(&str) -> Result<serde_json::Value, &'static str> + Send + 'static,
    {
        let Session {
            app,
            origin,
            device,
            token,
            epoch,
            client,
        } = self;
        let signing_app = app.clone();
        let signing_origin = origin.clone();
        let body =
            tauri::async_runtime::spawn_blocking(move || -> Result<serde_json::Value, String> {
                let _prompt = model_authorization::PROMPT
                    .lock()
                    .map_err(|_| "Model authorization unavailable")?;
                let fresh = !model_authorization::valid(epoch)?;
                if fresh {
                    authenticate_owner(&prompt, true)?;
                }
                let guard = auth_storage()?;
                let current = load_metadata(&signing_app)?;
                validate_login_binding(
                    *guard,
                    epoch,
                    current.home_node_origin.as_deref(),
                    &signing_origin,
                )?;
                if current.device_id.as_deref() != Some(device.as_str())
                    || time::OffsetDateTime::now_utc().unix_timestamp() >= expires_at
                {
                    return Err("Approval expired or device changed".into());
                }
                // Only a real OS prompt grants a new fixed window, after binding
                // revalidation. Reused grants never slide the deadline forward.
                if fresh {
                    model_authorization::remember(epoch)?;
                }
                if !model_authorization::valid(epoch)? {
                    return Err("Model authorization expired; try again".into());
                }
                let signature = hex::encode(
                    signing_key_unlocked(&signing_app, false)?
                        .sign(&message)
                        .to_bytes(),
                );
                signed_request(&signature).map_err(str::to_string)
            })
            .await
            .map_err(|_| "OS authentication failed")??;
        {
            let guard = auth_storage()?;
            let current = load_metadata(&app)?;
            validate_login_binding(*guard, epoch, current.home_node_origin.as_deref(), &origin)?;
        }
        // Use the captured origin and token. Changing Home Node cannot forward an
        // old credential/signature to a newly selected host.
        let response = client
            .post(api_url(Some(&origin), "/v1/system/config/privileged")?)
            .bearer_auth(token)
            .json(&body)
            .send()
            .await
            .map_err(|_| "Outcome unknown; refresh the model status")?;
        if !response.status().is_success() {
            return Err(refused(response.status().as_u16()).into());
        }
        let bytes = native_response::bounded(response, 4096)
            .await
            .map_err(|_| "Activation not confirmed")?;
        let result: serde_json::Value =
            serde_json::from_slice(&bytes).map_err(|_| "Activation not confirmed")?;
        if result.get("status").and_then(|v| v.as_str()) != Some("active") {
            return Err("Activation not confirmed".into());
        }
        Ok(())
    }
}

fn fresh_ids() -> (uuid::Uuid, String) {
    let mut nonce = [0; 32];
    let mut request_id = [0; 16];
    OsRng.fill_bytes(&mut nonce);
    OsRng.fill_bytes(&mut request_id);
    (
        uuid::Builder::from_random_bytes(request_id).into_uuid(),
        hex::encode(nonce),
    )
}

#[tauri::command]
pub(super) async fn set_model_enabled(
    app: AppHandle,
    provider: String,
    model: String,
    enabled: bool,
    policy_sha256: String,
) -> Result<(), String> {
    let session = Session::load(app)?;
    let snapshot = session.snapshot().await?;
    let now = time::OffsetDateTime::now_utc().unix_timestamp();
    validate_snapshot(
        &snapshot,
        &provider,
        &model,
        &policy_sha256,
        &session.device,
        now,
    )?;
    if snapshot
        .models
        .iter()
        .any(|entry| entry.provider == provider && entry.model == model && entry.enabled == enabled)
    {
        return Err("Model status already changed; refresh the list".into());
    }
    let (request_id, nonce_hex) = fresh_ids();
    let approval = ModelToggleApproval {
        request_id,
        nonce_hex,
        user_id: snapshot.user_id,
        device_id: snapshot.device_id,
        issued_at: now,
        expires_at: now + 120,
        provider,
        model,
        enabled,
        expected_policy_sha256: policy_sha256,
    };
    let message = approval.message().map_err(str::to_string)?;
    let prompt = format!(
        "Jarvis: {} {}/{}; allow model changes for five minutes",
        if approval.enabled {
            "enable"
        } else {
            "disable"
        },
        approval.provider,
        approval.model,
    );
    session
        .approve(
            prompt,
            message,
            approval.expires_at,
            move |signature| approval.signed_request(signature),
            |_| "Model change refused or not activated; refresh the list",
        )
        .await
}

/// Replace the owner routing document. The webview supplies the typed document
/// and the `routing_sha256` it was edited against; the signed bytes are built
/// here from Core's canonical payload.
#[tauri::command]
pub(super) async fn set_model_routing(
    app: AppHandle,
    routing: RoutingDocument,
    routing_sha256: String,
) -> Result<(), String> {
    let session = Session::load(app)?;
    let snapshot = session.snapshot().await?;
    let now = time::OffsetDateTime::now_utc().unix_timestamp();
    validate_routing_snapshot(&snapshot, &routing_sha256, &session.device, now)?;
    validate_routing(&routing, &snapshot.models)?;
    let (request_id, nonce_hex) = fresh_ids();
    let approval = ModelRoutingApproval {
        request_id,
        nonce_hex,
        user_id: snapshot.user_id,
        device_id: snapshot.device_id,
        issued_at: now,
        expires_at: now + 120,
        routing,
        expected_routing_sha256: routing_sha256,
    };
    let message = approval.message().map_err(str::to_string)?;
    // The broker reads one 16 KiB frame; refuse before prompting.
    let sized = approval
        .signed_request(&"00".repeat(64))
        .map_err(str::to_string)?;
    if serde_json::to_vec(&serde_json::json!({ "request": sized }))
        .map_err(|_| "Invalid routing")?
        .len()
        > 16 * 1024
    {
        return Err("Routing is too large to send".into());
    }
    session
        .approve(
            "Jarvis: replace model routing; allow model changes for five minutes".into(),
            message,
            approval.expires_at,
            move |signature| approval.signed_request(signature),
            routing_refused,
        )
        .await
}

fn routing_refused(status: u16) -> &'static str {
    // A 503 can mean the broker is down or that Core failed closed; the
    // refreshed `routing_unavailable_reason` tells which.
    match status {
        409 => "Routing changed or names an undiscovered model; refresh and try again",
        503 => "Routing not applied or not verified; refresh to see the current state",
        _ => "Routing change refused; refresh and try again",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_policy_wrong_device_and_clock_fail_before_os_auth() {
        let device = uuid::Uuid::from_bytes([1; 16]);
        let snapshot = Snapshot {
            mutation: "device-signed-model-toggle-v1".into(),
            policy_sha256: Some("ab".repeat(32)),
            routing_mutation: None,
            routing_sha256: None,
            user_id: device,
            device_id: device,
            server_time: 100,
            models: vec![Entry {
                provider: "ollama-cloud".into(),
                model: "fixture".into(),
                enabled: false,
            }],
        };
        assert!(validate_snapshot(
            &snapshot,
            "ollama-cloud",
            "fixture",
            &"ab".repeat(32),
            &device.to_string(),
            100
        )
        .is_ok());
        for (hash, device, now) in [
            ("cd".repeat(32), device.to_string(), 100),
            ("ab".repeat(32), "other".into(), 100),
            ("ab".repeat(32), device.to_string(), 131),
        ] {
            assert!(
                validate_snapshot(&snapshot, "ollama-cloud", "fixture", &hash, &device, now)
                    .is_err()
            );
        }
    }

    fn entry(provider: &str, model: &str) -> Entry {
        Entry {
            provider: provider.into(),
            model: model.into(),
            enabled: false,
        }
    }

    // Core's fixed vector (crates/client-core/src/model_control.rs).
    const ROUTING_CANONICAL_PAYLOAD: &str = r#"{"action":"model_routing_set","routing":{"version":1,"paid_api":"off","tiers":{"cheap":{"chain":[{"provider":"huggingface","model":"org/modèl"},{"provider":"claude-cli","model":"claude-haiku-4-5"}],"metered_after_subscription":false},"hard":{"chain":[{"provider":"claude-cli","model":"claude-opus-5"},{"provider":"anthropic-api","model":"claude-opus-5"}],"metered_after_subscription":true}}},"expected_routing_sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"}"#;
    const ROUTING_CANONICAL_PAYLOAD_SHA256: &str =
        "a9c73476991bb884fbaf43378882c25f4403a8ce3daa44d1012221ce15e30a59";

    fn vector_routing() -> RoutingDocument {
        // Shaped like the webview's invoke argument: other key order, no
        // `metered_after_subscription` on one tier.
        serde_json::from_value(serde_json::json!({
            "tiers": {
                "hard": {"metered_after_subscription": true, "chain": [
                    {"model": "claude-opus-5", "provider": "claude-cli"},
                    {"provider": "anthropic-api", "model": "claude-opus-5"}]},
                "cheap": {"chain": [
                    {"provider": "huggingface", "model": "org/modèl"},
                    {"provider": "claude-cli", "model": "claude-haiku-4-5"}]}},
            "paid_api": "off",
            "version": 1
        }))
        .unwrap()
    }

    #[test]
    fn routing_canonical_bytes_match_core_fixed_vector() {
        let approval = ModelRoutingApproval {
            request_id: uuid::Uuid::from_bytes([1; 16]),
            nonce_hex: "02".repeat(32),
            user_id: uuid::Uuid::from_bytes([3; 16]),
            device_id: uuid::Uuid::from_bytes([4; 16]),
            issued_at: 1,
            expires_at: 121,
            routing: vector_routing(),
            expected_routing_sha256:
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".into(),
        };
        assert_eq!(
            String::from_utf8(approval.canonical_payload().unwrap()).unwrap(),
            ROUTING_CANONICAL_PAYLOAD
        );
        let message = approval.message().unwrap();
        let action = b"model.routing_set";
        assert!(message.starts_with(b"jarvis-privileged-config-v1\0"));
        assert_eq!(&message[30..30 + action.len()], action);
        assert_eq!(
            hex::encode(&message[30 + action.len()..30 + action.len() + 32]),
            ROUTING_CANONICAL_PAYLOAD_SHA256
        );
        let body = approval.signed_request(&"00".repeat(64)).unwrap();
        assert_eq!(body["operation"]["action"], "model_routing_set");
    }

    #[test]
    fn routing_validation_mirrors_core_rules() {
        let models = vec![
            entry("huggingface", "org/modèl"),
            entry("claude-cli", "claude-haiku-4-5"),
            entry("claude-cli", "claude-opus-5"),
            entry("anthropic-api", "claude-opus-5"),
            entry("ollama", "llama3.2"),
            entry("codex-cli", "gpt-6-luna"),
            entry("openai-api", "gpt-6-luna"),
        ];
        assert_eq!(validate_routing(&vector_routing(), &models), Ok(()));
        let empty: RoutingDocument = serde_json::from_str(r#"{"version":1}"#).unwrap();
        assert_eq!(validate_routing(&empty, &[]), Ok(()));
        let tier = |chain: &[(&str, &str)], metered: bool| -> RoutingDocument {
            let chain: Vec<_> = chain
                .iter()
                .map(|(provider, model)| serde_json::json!({"provider": provider, "model": model}))
                .collect();
            serde_json::from_value(serde_json::json!({"version": 1, "tiers": {"default": {
                "chain": chain, "metered_after_subscription": metered}}}))
            .unwrap()
        };
        let ok = [
            tier(
                &[
                    ("claude-cli", "claude-opus-5"),
                    ("anthropic-api", "claude-opus-5"),
                ],
                true,
            ),
            tier(
                &[
                    ("anthropic-api", "claude-opus-5"),
                    ("claude-cli", "claude-opus-5"),
                ],
                false,
            ),
            tier(
                &[("claude-cli", "claude-opus-5"), ("ollama", "llama3.2")],
                false,
            ),
        ];
        for routing in ok {
            assert_eq!(validate_routing(&routing, &models), Ok(()));
        }
        let many: Vec<String> = (0..10).map(|i| format!("m{i}")).collect();
        let many_models: Vec<Entry> = many.iter().map(|m| entry("ollama", m)).collect();
        let ten: Vec<(&str, &str)> = many.iter().map(|m| ("ollama", m.as_str())).collect();
        assert!(validate_routing(&tier(&ten[..9], false), &many_models).is_ok());
        assert!(validate_routing(&tier(&ten, false), &many_models).is_err());
        let long = "m".repeat(257);
        let bad = [
            tier(&[], false),
            tier(&[("jev", "a")], false),
            tier(&[("Claude-CLI", "claude-opus-5")], false),
            tier(&[("ollama", "")], false),
            tier(&[("ollama", &long)], false),
            tier(&[("ollama", "a\nb")], false),
            tier(&[("ollama", "llama3.2"), ("ollama", "llama3.2")], false),
            tier(
                &[
                    ("claude-cli", "claude-opus-5"),
                    ("anthropic-api", "claude-opus-5"),
                ],
                false,
            ),
            tier(
                &[("codex-cli", "gpt-6-luna"), ("openai-api", "gpt-6-luna")],
                false,
            ),
            tier(
                &[
                    ("claude-cli", "claude-opus-5"),
                    ("ollama", "llama3.2"),
                    ("huggingface", "org/modèl"),
                ],
                false,
            ),
            tier(&[("ollama", "not-discovered")], false),
        ];
        for routing in bad {
            assert!(validate_routing(&routing, &models).is_err(), "{routing:?}");
        }
        let mut wrong_version = empty;
        wrong_version.version = 2;
        assert!(validate_routing(&wrong_version, &models).is_err());
        assert!(serde_json::from_str::<RoutingDocument>(r#"{"version":1,"x":1}"#).is_err());
    }

    #[test]
    fn routing_requires_signed_route_support_and_fresh_hash() {
        let device = uuid::Uuid::from_bytes([1; 16]);
        let mut snapshot = Snapshot {
            mutation: "unavailable".into(),
            policy_sha256: None,
            routing_mutation: Some("device-signed-model-route-v1".into()),
            routing_sha256: Some("ab".repeat(32)),
            user_id: device,
            device_id: device,
            server_time: 100,
            models: vec![],
        };
        let hash = "ab".repeat(32);
        let id = device.to_string();
        assert!(validate_routing_snapshot(&snapshot, &hash, &id, 100).is_ok());
        assert!(validate_routing_snapshot(&snapshot, &"cd".repeat(32), &id, 100).is_err());
        assert!(validate_routing_snapshot(&snapshot, &hash, "other", 100).is_err());
        assert!(validate_routing_snapshot(&snapshot, &hash, &id, 131).is_err());
        snapshot.routing_mutation = Some("unavailable".into());
        assert!(validate_routing_snapshot(&snapshot, &hash, &id, 100).is_err());
        snapshot.routing_mutation = None;
        assert!(validate_routing_snapshot(&snapshot, &hash, &id, 100).is_err());
    }
}
