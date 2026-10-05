//! Fixed-path model administration. OS authentication, keys, signatures and
//! bearer HTTP remain native; JavaScript supplies only the requested toggle or
//! the typed routing document, never bytes to sign.
use super::*;
use jarvis_client_core::model_control::{
    ModelRoutingApproval, ModelToggleApproval, PaidApi, ResearchWebSearch, RoutingDocument,
};

#[derive(Deserialize)]
struct Snapshot {
    mutation: String,
    policy_sha256: Option<String>,
    // Absent on a Core without signed routing.
    routing_mutation: Option<String>,
    routing_sha256: Option<String>,
    // Kept untyped so a newer routing shape never breaks model toggles.
    routing: Option<serde_json::Value>,
    routing_unavailable_reason: Option<String>,
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

/// Ways a routing change lets Jarvis spend more than the routing it replaces.
#[derive(Debug, Default, PartialEq, Eq)]
struct Relaxation {
    paid_api: bool,
    paid_fallback: bool,
    paid_models: bool,
    research_web_search: bool,
}

impl Relaxation {
    fn any(&self) -> bool {
        self.paid_api || self.paid_fallback || self.paid_models || self.research_web_search
    }

    fn prompt(&self) -> String {
        let mut prompt = String::from("Jarvis: replace model routing");
        if !self.any() {
            prompt.push_str("; allow model changes for five minutes");
        }
        for (relaxed, text) in [
            (self.paid_api, "; allow paid APIs"),
            (
                self.paid_fallback,
                "; allow paid fallback after subscription",
            ),
            (self.paid_models, "; add paid API models"),
            (self.research_web_search, "; allow research web search"),
        ] {
            if relaxed {
                prompt.push_str(text);
            }
        }
        prompt
    }
}

/// The routing Core currently runs. `None` when it cannot be known or Core
/// failed closed (paid APIs off): every paid permission then counts as new.
fn current_routing(snapshot: &Snapshot) -> Option<RoutingDocument> {
    if snapshot.routing_unavailable_reason.is_some() {
        return None;
    }
    match &snapshot.routing {
        // No routing file: built-in order with paid APIs allowed.
        None | Some(serde_json::Value::Null) => serde_json::from_str(r#"{"version":1}"#).ok(),
        Some(value) => serde_json::from_value(value.clone()).ok(),
    }
}

fn relaxation(current: Option<&RoutingDocument>, next: &RoutingDocument) -> Relaxation {
    // Web search sends research questions to a provider tool, whatever the
    // paid API setting, so switching it on always needs a fresh prompt.
    let research_web_search = next.research_web_search == ResearchWebSearch::On
        && current.is_none_or(|current| current.research_web_search != ResearchWebSearch::On);
    if next.paid_api == PaidApi::Off {
        // No metered backend can run at all.
        return Relaxation {
            research_web_search,
            ..Relaxation::default()
        };
    }
    let mut result = Relaxation {
        paid_api: current.is_none_or(|current| current.paid_api == PaidApi::Off),
        research_web_search,
        ..Relaxation::default()
    };
    let tier = |routing: &RoutingDocument, index: usize| {
        let tiers = &routing.tiers;
        [&tiers.cheap, &tiers.default, &tiers.hard][index].clone()
    };
    for index in 0..3 {
        let Some(next) = tier(next, index) else {
            continue;
        };
        // A built-in (absent) tier counts as no paid fallback and no pinned
        // paid models, so opting in is always treated as new.
        let before = current.and_then(|current| tier(current, index));
        if next.metered_after_subscription
            && !before
                .as_ref()
                .is_some_and(|before| before.metered_after_subscription)
        {
            result.paid_fallback = true;
        }
        if next.chain.iter().any(|entry| {
            !matches!(
                entry.provider.as_str(),
                "ollama" | "claude-cli" | "codex-cli"
            ) && !before
                .as_ref()
                .is_some_and(|before| before.chain.contains(entry))
        }) {
            result.paid_models = true;
        }
    }
    result
}

/// Approval lifetime: covers the OS prompt (up to 120 s) and the post, within
/// Core's 300 s maximum.
const ROUTING_APPROVAL_SECONDS: i64 = 240;
/// Broker frame limit; the broker counts the trailing newline.
const BROKER_FRAME_BYTES: usize = 16 * 1024;

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
    /// window unless `always_prompt`), revalidate the login binding, sign the
    /// fixed approval and post it to the fixed privileged route.
    async fn approve<F>(
        self,
        prompt: String,
        always_prompt: bool,
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
                let fresh = always_prompt || !model_authorization::valid(epoch)?;
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
                // A cost-relaxing prompt is single-use and grants no window.
                if !always_prompt {
                    if fresh {
                        model_authorization::remember(epoch)?;
                    }
                    if !model_authorization::valid(epoch)? {
                        return Err("Model authorization expired; try again".into());
                    }
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
            false,
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
    let relaxed = relaxation(current_routing(&snapshot).as_ref(), &routing);
    let (request_id, nonce_hex) = fresh_ids();
    let approval = ModelRoutingApproval {
        request_id,
        nonce_hex,
        user_id: snapshot.user_id,
        device_id: snapshot.device_id,
        issued_at: now,
        expires_at: now + ROUTING_APPROVAL_SECONDS,
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
        >= BROKER_FRAME_BYTES
    {
        return Err("Routing is too large to send".into());
    }
    session
        .approve(
            relaxed.prompt(),
            relaxed.any(),
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
            routing: None,
            routing_unavailable_reason: None,
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
            routing: None,
            routing_unavailable_reason: None,
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

    fn doc(json: serde_json::Value) -> RoutingDocument {
        serde_json::from_value(json).unwrap()
    }

    #[test]
    fn cost_relaxing_routing_is_classified_for_a_fresh_prompt() {
        let sub = serde_json::json!({"provider": "claude-cli", "model": "claude-opus-5"});
        let paid = serde_json::json!({"provider": "anthropic-api", "model": "claude-opus-5"});
        let local = serde_json::json!({"provider": "ollama", "model": "llama3.2"});
        let routed = |paid_api: &str, chain: serde_json::Value, fallback: bool| {
            doc(
                serde_json::json!({"version": 1, "paid_api": paid_api, "tiers": {"hard": {
                "chain": chain, "metered_after_subscription": fallback}}}),
            )
        };
        let off = doc(serde_json::json!({"version": 1, "paid_api": "off"}));
        let allowed = doc(serde_json::json!({"version": 1}));
        let with_paid = routed("allowed", serde_json::json!([sub, paid]), true);
        let relaxed = |paid_api, paid_fallback, paid_models| Relaxation {
            paid_api,
            paid_fallback,
            paid_models,
            research_web_search: false,
        };

        // Relaxing: paid API back on (also from an unknown or failed-closed state).
        assert_eq!(
            relaxation(Some(&off), &allowed),
            relaxed(true, false, false)
        );
        assert_eq!(relaxation(None, &allowed), relaxed(true, false, false));
        // Relaxing: paid fallback switched on, paid model added.
        let no_fallback = routed("allowed", serde_json::json!([paid, sub]), false);
        assert_eq!(
            relaxation(Some(&no_fallback), &with_paid),
            relaxed(false, true, false)
        );
        let only_sub = routed("allowed", serde_json::json!([sub]), false);
        assert_eq!(
            relaxation(
                Some(&only_sub),
                &routed("allowed", serde_json::json!([sub, local, paid]), true)
            ),
            relaxed(false, true, true)
        );
        assert_eq!(
            relaxation(
                Some(&allowed),
                &routed("allowed", serde_json::json!([paid]), false)
            ),
            relaxed(false, false, true)
        );

        // Tightening or neutral: may reuse the window.
        for (current, next) in [
            (&with_paid, &off),
            (&with_paid, &only_sub),
            (&with_paid, &with_paid),
            (
                &with_paid,
                &routed("allowed", serde_json::json!([paid, sub]), true),
            ),
            (
                &allowed,
                &routed("allowed", serde_json::json!([sub, local]), false),
            ),
        ] {
            assert!(!relaxation(Some(current), next).any(), "{next:?}");
        }
        assert!(!relaxation(None, &off).any());

        assert_eq!(
            relaxed(false, false, false).prompt(),
            "Jarvis: replace model routing; allow model changes for five minutes"
        );
        let prompt = relaxed(true, true, true).prompt();
        assert!(prompt.contains("allow paid APIs"));
        assert!(prompt.contains("allow paid fallback after subscription"));
        assert!(!prompt.contains("five minutes"));
    }

    #[test]
    fn switching_research_web_search_on_always_needs_a_fresh_prompt() {
        let research = |paid_api: &str, on: &str| {
            doc(serde_json::json!({"version": 1, "paid_api": paid_api, "research_web_search": on}))
        };
        let on_only = Relaxation {
            research_web_search: true,
            ..Relaxation::default()
        };
        // Relaxing even with paid APIs off, and from an unknown state.
        assert_eq!(
            relaxation(Some(&research("off", "off")), &research("off", "on")),
            on_only
        );
        assert_eq!(relaxation(None, &research("off", "on")), on_only);
        assert!(
            relaxation(Some(&research("off", "off")), &research("off", "on"))
                .prompt()
                .ends_with("; allow research web search")
        );
        // Already on, or switched off: no research relaxation.
        assert!(!relaxation(Some(&research("off", "on")), &research("off", "on")).any());
        assert!(!relaxation(Some(&research("off", "on")), &research("off", "off")).any());
    }

    #[test]
    fn current_routing_fails_closed() {
        let device = uuid::Uuid::from_bytes([1; 16]);
        let mut snapshot = Snapshot {
            mutation: "unavailable".into(),
            policy_sha256: None,
            routing_mutation: Some("device-signed-model-route-v1".into()),
            routing_sha256: Some("ab".repeat(32)),
            routing: None,
            routing_unavailable_reason: None,
            user_id: device,
            device_id: device,
            server_time: 100,
            models: vec![],
        };
        // No file: built-in order, paid APIs allowed.
        assert_eq!(
            current_routing(&snapshot).unwrap().paid_api,
            PaidApi::Allowed
        );
        snapshot.routing = Some(serde_json::json!({"version": 1, "paid_api": "off"}));
        assert_eq!(current_routing(&snapshot).unwrap().paid_api, PaidApi::Off);
        snapshot.routing = Some(serde_json::json!({"version": 1, "future": true}));
        assert!(current_routing(&snapshot).is_none());
        snapshot.routing = None;
        snapshot.routing_unavailable_reason = Some("routing_invalid".into());
        assert!(current_routing(&snapshot).is_none());
    }

    #[test]
    fn approval_outlives_the_os_prompt_within_core_limits() {
        // authenticate_owner waits up to 120 s; Core accepts at most 300 s.
        const _: () = assert!(ROUTING_APPROVAL_SECONDS > 120 + 60);
        let approval = ModelRoutingApproval {
            request_id: uuid::Uuid::from_bytes([1; 16]),
            nonce_hex: "02".repeat(32),
            user_id: uuid::Uuid::from_bytes([3; 16]),
            device_id: uuid::Uuid::from_bytes([4; 16]),
            issued_at: 1,
            expires_at: 1 + ROUTING_APPROVAL_SECONDS,
            routing: vector_routing(),
            expected_routing_sha256: "00".repeat(32),
        };
        assert!(approval.message().is_ok());
    }
}
