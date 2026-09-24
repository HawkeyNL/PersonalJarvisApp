//! Fixed-path model administration. OS authentication, keys, signatures and
//! bearer HTTP remain native; JavaScript supplies only the requested toggle.
use super::*;
use jarvis_client_core::model_control::ModelToggleApproval;

#[derive(Deserialize)]
struct Snapshot {
    mutation: String,
    policy_sha256: Option<String>,
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
        return Err("Modelpolicy, apparaat of klok gewijzigd; ververs en probeer opnieuw.".into());
    }
    Ok(())
}

#[tauri::command]
pub(super) async fn set_model_enabled(
    app: AppHandle,
    provider: String,
    model: String,
    enabled: bool,
    policy_sha256: String,
) -> Result<(), String> {
    let (auth, epoch) = {
        let guard = auth_storage()?;
        (load_secure_auth_unlocked(&app)?, *guard)
    };
    let origin = auth
        .metadata
        .home_node_origin
        .ok_or("Home Node ontbreekt")?;
    let device = auth.metadata.device_id.ok_or("Apparaat niet gekoppeld")?;
    let token = auth.token.ok_or("Sessie ontbreekt")?;
    let client = native_http_client()?;
    let response = client
        .get(api_url(Some(&origin), "/v1/system/models")?)
        .bearer_auth(&token)
        .send()
        .await
        .map_err(|_| "Modelpolicy niet bereikbaar")?;
    if !response.status().is_success() {
        return Err("Modelpolicy ophalen geweigerd".into());
    }
    let bytes = native_response::bounded(response, 8 * 1024 * 1024)
        .await
        .map_err(|_| "Ongeldige modelpolicy")?;
    let snapshot: Snapshot = serde_json::from_slice(&bytes).map_err(|_| "Ongeldige modelpolicy")?;
    let now = time::OffsetDateTime::now_utc().unix_timestamp();
    validate_snapshot(&snapshot, &provider, &model, &policy_sha256, &device, now)?;
    if snapshot
        .models
        .iter()
        .any(|entry| entry.provider == provider && entry.model == model && entry.enabled == enabled)
    {
        return Err("Modelstatus is al gewijzigd; ververs de lijst".into());
    }
    let mut nonce = [0; 32];
    let mut request_id = [0; 16];
    OsRng.fill_bytes(&mut nonce);
    OsRng.fill_bytes(&mut request_id);
    let approval = ModelToggleApproval {
        request_id: uuid::Builder::from_random_bytes(request_id).into_uuid(),
        nonce_hex: hex::encode(nonce),
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
    let signing_app = app.clone();
    let signing_origin = origin.clone();
    let body =
        tauri::async_runtime::spawn_blocking(move || -> Result<serde_json::Value, String> {
            let _prompt = model_authorization::PROMPT
                .lock()
                .map_err(|_| "Model authorization unavailable")?;
            let fresh = !model_authorization::valid(epoch)?;
            if fresh {
                authenticate_owner(
                    &format!(
                        "Jarvis: {}/{} {}; modelwijzigingen vijf minuten toestaan",
                        approval.provider,
                        approval.model,
                        if approval.enabled {
                            "inschakelen"
                        } else {
                            "uitschakelen"
                        }
                    ),
                    true,
                )?;
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
                || time::OffsetDateTime::now_utc().unix_timestamp() >= approval.expires_at
            {
                return Err("Goedkeuring verlopen of apparaat gewijzigd".into());
            }
            // Only a real OS prompt grants a new fixed window, after binding
            // revalidation. Reused grants never slide the deadline forward.
            if fresh {
                model_authorization::remember(epoch)?;
            }
            if !model_authorization::valid(epoch)? {
                return Err("Modelautorisatie verlopen; probeer opnieuw".into());
            }
            let signature = hex::encode(
                signing_key_unlocked(&signing_app, false)?
                    .sign(&message)
                    .to_bytes(),
            );
            approval.signed_request(&signature).map_err(str::to_string)
        })
        .await
        .map_err(|_| "OS-authenticatie mislukt")??;
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
        .map_err(|_| "Uitkomst onbekend; ververs de modelstatus")?;
    if !response.status().is_success() {
        return Err("Modelwijziging geweigerd of niet geactiveerd; ververs de lijst".into());
    }
    let bytes = native_response::bounded(response, 4096)
        .await
        .map_err(|_| "Activatie niet bevestigd")?;
    let result: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|_| "Activatie niet bevestigd")?;
    if result.get("status").and_then(|v| v.as_str()) != Some("active") {
        return Err("Activatie niet bevestigd".into());
    }
    Ok(())
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
}
