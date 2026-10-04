//! Native-only private application updater.
//!
//! The bearer token and signing public key never enter the webview. Tauri owns
//! download signature verification and installation; the Home Node is only an
//! authenticated mirror.

use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tauri::{ipc::Channel, AppHandle, Emitter, Manager, State};
use tauri_plugin_updater::{Update, UpdaterExt};

use super::{load_secure_auth, normalize_home_node_origin};

pub(crate) const CURRENT_DESKTOP_UPDATE_PROTOCOL: u32 = 1;
const MAX_CAPABILITY_BYTES: usize = 64 * 1024;

pub(crate) struct UpdateRuntime {
    pub enabled: bool,
}

pub(crate) fn updater_public_key() -> Option<&'static str> {
    if cfg!(feature = "realtime-acceptance") {
        return None;
    }
    option_env!("JARVIS_TAURI_UPDATER_PUBKEY").filter(|value| !value.trim().is_empty())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum UpdateState {
    Ready,
    Unconfigured,
    Unauthenticated,
    Unsupported,
    Incompatible,
    Unavailable,
    UpToDate,
    Available,
    Checking,
    Downloading,
    /// Verified package downloaded; installs together with the restart.
    ReadyToRestart,
    Error,
}

#[derive(Clone, Serialize)]
pub(crate) struct UpdateStatus {
    pub(crate) state: UpdateState,
    pub(crate) current_version: String,
    pub(crate) version: Option<String>,
    pub(crate) notes: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(tag = "event", content = "data", rename_all = "snake_case")]
pub(crate) enum DownloadEvent {
    Started { content_length: Option<u64> },
    Progress { chunk_length: usize },
    Finished,
}

fn status(app: &AppHandle, state: UpdateState) -> UpdateStatus {
    UpdateStatus {
        state,
        current_version: app.package_info().version.to_string(),
        version: None,
        notes: None,
    }
}

fn status_with_notes(app: &AppHandle, state: UpdateState, notes: String) -> UpdateStatus {
    let mut result = status(app, state);
    result.notes = Some(notes);
    result
}

fn origin_and_token(app: &AppHandle) -> Result<(String, String), UpdateStatus> {
    let auth = load_secure_auth(app).map_err(|_| status(app, UpdateState::Unsupported))?;
    let origin = auth
        .metadata
        .home_node_origin
        .ok_or_else(|| status(app, UpdateState::Unconfigured))?;
    let token = auth
        .token
        .filter(|value| !value.is_empty())
        .ok_or_else(|| status(app, UpdateState::Unauthenticated))?;
    Ok((origin, token))
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct UpdateCapability {
    schema_version: u32,
    channel: String,
    check_endpoint: String,
    minimum_client_protocol: u32,
}

fn validate_capability(
    capability: UpdateCapability,
    enrolled_origin: &str,
    allow_local_http: bool,
) -> Result<String, &'static str> {
    if capability.schema_version != 1 || capability.channel != "stable" {
        return Err("update capability is incompatible");
    }
    if capability.minimum_client_protocol == 0 {
        return Err("update capability protocol is invalid");
    }
    if capability.minimum_client_protocol > CURRENT_DESKTOP_UPDATE_PROTOCOL {
        return Err("release requires a newer updater protocol");
    }
    for placeholder in ["{{target}}", "{{arch}}", "{{current_version}}"] {
        if !capability.check_endpoint.contains(placeholder) {
            return Err("update capability endpoint is incomplete");
        }
    }
    let parsed = url::Url::parse(&capability.check_endpoint)
        .map_err(|_| "update capability endpoint is invalid")?;
    let origin = parsed.origin().ascii_serialization();
    let origin = normalize_home_node_origin(&origin, allow_local_http)
        .map_err(|_| "update capability endpoint is insecure")?;
    let enrolled_origin = normalize_home_node_origin(enrolled_origin, allow_local_http)
        .map_err(|_| "enrolled Home Node origin is invalid")?;
    if origin != enrolled_origin {
        return Err("update capability endpoint changed origin");
    }
    if !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return Err("update capability endpoint contains credentials");
    }
    Ok(format!(
        "{}?client_protocol={CURRENT_DESKTOP_UPDATE_PROTOCOL}",
        capability.check_endpoint
    ))
}

async fn discover_endpoint(
    app: &AppHandle,
    origin: &str,
    token: &str,
) -> Result<String, UpdateStatus> {
    if rustls::crypto::CryptoProvider::get_default().is_none() {
        let _ = rustls::crypto::ring::default_provider().install_default();
    }
    let client = reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|_| status(app, UpdateState::Unsupported))?;
    let response = client
        .get(format!("{origin}/v1/app-updates/capability"))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|_| {
            status_with_notes(
                app,
                UpdateState::Unavailable,
                "Update service unreachable".into(),
            )
        })?;
    if !response.status().is_success() {
        return Err(status_with_notes(
            app,
            UpdateState::Unavailable,
            "Update service unavailable".into(),
        ));
    }
    if response
        .content_length()
        .is_some_and(|size| size > MAX_CAPABILITY_BYTES as u64)
    {
        return Err(status(app, UpdateState::Unsupported));
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|_| status(app, UpdateState::Unsupported))?;
    if bytes.len() > MAX_CAPABILITY_BYTES {
        return Err(status(app, UpdateState::Unsupported));
    }
    let capability: UpdateCapability =
        serde_json::from_slice(&bytes).map_err(|_| status(app, UpdateState::Unsupported))?;
    match validate_capability(capability, origin, cfg!(debug_assertions)) {
        Ok(endpoint) => Ok(endpoint),
        Err("release requires a newer updater protocol") => Err(status_with_notes(
            app,
            UpdateState::Incompatible,
            format!(
                "This release requires an updater protocol newer than version {}",
                CURRENT_DESKTOP_UPDATE_PROTOCOL
            ),
        )),
        Err(_) => Err(status(app, UpdateState::Unsupported)),
    }
}

/// Update state shared by Settings and the tray, so both always show the same
/// step and only one check or download runs at a time.
#[derive(Default)]
pub(crate) struct Updates {
    inner: Mutex<Inner>,
    session_active: AtomicBool,
}

#[derive(Default)]
struct Inner {
    status: Option<UpdateStatus>,
    pending: Option<Update>,
    /// Downloaded package whose signature `Update::download` already verified.
    ready: Option<(Update, Vec<u8>)>,
}

pub(crate) const STATUS_EVENT: &str = "app-update-status";
pub(crate) const CHECK_INTERVAL: Duration = Duration::from_secs(6 * 60 * 60);
const STATE_UNAVAILABLE: &str = "update state is unavailable";

fn updates(app: &AppHandle) -> &Updates {
    app.state::<Updates>().inner()
}

fn lock(app: &AppHandle) -> Result<std::sync::MutexGuard<'_, Inner>, String> {
    updates(app)
        .inner
        .lock()
        .map_err(|_| STATE_UNAVAILABLE.to_string())
}

pub(crate) fn session_active(app: &AppHandle) -> bool {
    updates(app).session_active.load(Ordering::SeqCst)
}

pub(crate) fn stored(app: &AppHandle) -> UpdateStatus {
    let current = lock(app).ok().and_then(|inner| inner.status.clone());
    current.unwrap_or_else(|| {
        let enabled = app.state::<UpdateRuntime>().enabled;
        status(
            app,
            if enabled {
                UpdateState::Ready
            } else {
                UpdateState::Unsupported
            },
        )
    })
}

fn update_status(update: &Update, state: UpdateState) -> UpdateStatus {
    UpdateStatus {
        state,
        current_version: update.current_version.clone(),
        version: Some(update.version.clone()),
        notes: update.body.clone(),
    }
}

fn notify(app: &AppHandle, current: &UpdateStatus) {
    #[cfg(desktop)]
    crate::tray::refresh(app, current, session_active(app));
    let _ = app.emit(STATUS_EVENT, current);
}

fn finish(app: &AppHandle, next: UpdateStatus, pending: Option<Update>) {
    if let Ok(mut inner) = lock(app) {
        inner.status = Some(next.clone());
        inner.pending = pending;
    }
    notify(app, &next);
}

async fn find_update(app: &AppHandle) -> Result<(UpdateStatus, Option<Update>), String> {
    let (origin, token) = match origin_and_token(app) {
        Ok(values) => values,
        Err(state) => return Ok((state, None)),
    };
    let endpoint = match discover_endpoint(app, &origin, &token).await {
        Ok(endpoint) => endpoint,
        Err(state) => return Ok((state, None)),
    };
    let endpoint =
        url::Url::parse(&endpoint).map_err(|_| "stored update endpoint is invalid".to_string())?;
    let updater = app
        .updater_builder()
        .endpoints(vec![endpoint])
        .map_err(|_| "update configuration failed".to_string())?
        .header("Authorization", format!("Bearer {token}"))
        .map_err(|_| "update authentication failed".to_string())?
        // Home Node update routes do not redirect. Refusing all redirects
        // keeps the native bearer credential pinned to the enrolled origin.
        .configure_client(|client| client.redirect(reqwest::redirect::Policy::none()))
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|_| "update service is unavailable".to_string())?;
    let update = updater
        .check()
        .await
        .map_err(|_| "update check failed".to_string())?;
    let Some(update) = update else {
        return Ok((status(app, UpdateState::UpToDate), None));
    };
    let download_origin = update.download_url.origin().ascii_serialization();
    let download_origin = normalize_home_node_origin(&download_origin, cfg!(debug_assertions))
        .map_err(|_| "update download endpoint is insecure".to_string())?;
    if download_origin != origin
        || !update.download_url.username().is_empty()
        || update.download_url.password().is_some()
    {
        return Ok((
            status_with_notes(
                app,
                UpdateState::Unsupported,
                "Update download address does not belong to the paired Home Node".into(),
            ),
            None,
        ));
    }
    Ok((update_status(&update, UpdateState::Available), Some(update)))
}

pub(crate) async fn check(app: &AppHandle) -> Result<UpdateStatus, String> {
    if !app.state::<UpdateRuntime>().enabled {
        return Ok(status(app, UpdateState::Unsupported));
    }
    let checking = status(app, UpdateState::Checking);
    {
        let mut inner = lock(app)?;
        if inner.status.as_ref().is_some_and(|current| {
            matches!(
                current.state,
                UpdateState::Checking | UpdateState::Downloading | UpdateState::ReadyToRestart
            )
        }) {
            return Err("an update action is already running".into());
        }
        inner.status = Some(checking.clone());
    }
    notify(app, &checking);
    match find_update(app).await {
        Ok((next, pending)) => {
            finish(app, next.clone(), pending);
            Ok(next)
        }
        Err(error) => {
            finish(app, status(app, UpdateState::Error), None);
            Err(error)
        }
    }
}

/// Downloads and verifies the pending update, then installs and relaunches
/// right away when no voice or chat session is active. Otherwise the verified
/// package waits until the webview reports idle or the owner confirms.
pub(crate) async fn install(
    app: &AppHandle,
    on_event: impl Fn(DownloadEvent),
) -> Result<UpdateStatus, String> {
    let update = {
        let mut inner = lock(app)?;
        let available = inner
            .status
            .as_ref()
            .is_some_and(|current| current.state == UpdateState::Available);
        let update = inner
            .pending
            .take()
            .filter(|_| available)
            .ok_or_else(|| "there is no verified pending update".to_string())?;
        let downloading = update_status(&update, UpdateState::Downloading);
        inner.status = Some(downloading.clone());
        drop(inner);
        notify(app, &downloading);
        update
    };
    let started = AtomicBool::new(false);
    let downloaded = update
        .download(
            |chunk_length, content_length| {
                if !started.swap(true, Ordering::Relaxed) {
                    on_event(DownloadEvent::Started { content_length });
                }
                on_event(DownloadEvent::Progress { chunk_length });
            },
            || on_event(DownloadEvent::Finished),
        )
        .await;
    let Ok(bytes) = downloaded else {
        finish(app, status(app, UpdateState::Error), None);
        return Err("update download or signature verification failed".into());
    };
    let ready = update_status(&update, UpdateState::ReadyToRestart);
    {
        let mut inner = lock(app)?;
        inner.ready = Some((update, bytes));
        inner.status = Some(ready.clone());
    }
    notify(app, &ready);
    if !session_active(app) {
        apply_and_restart(app).await?;
    }
    Ok(ready)
}

/// Installs the verified package and relaunches Jarvis. On Windows the
/// updater hands over to the NSIS installer, which exits this process and
/// starts the new version itself.
pub(crate) async fn apply_and_restart(app: &AppHandle) -> Result<(), String> {
    let (update, bytes) = lock(app)?
        .ready
        .take()
        .ok_or_else(|| "there is no verified update ready to install".to_string())?;
    let installed = tauri::async_runtime::spawn_blocking(move || update.install(bytes)).await;
    if !matches!(installed, Ok(Ok(()))) {
        finish(app, status(app, UpdateState::Error), None);
        return Err("update installation failed".into());
    }
    app.request_restart();
    Ok(())
}

/// Tray action: install the available update, or confirm a restart that is
/// waiting for an active session to end.
pub(crate) fn install_from_tray(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let _ = if stored(&app).state == UpdateState::ReadyToRestart {
            apply_and_restart(&app).await
        } else {
            install(&app, |_| {}).await.map(|_| ())
        };
    });
}

pub(crate) fn spawn_periodic_check(app: &AppHandle) {
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            tokio::time::sleep(CHECK_INTERVAL).await;
            // Keep an offered update; a transient error must not replace it.
            if stored(&app).state != UpdateState::Available {
                let _ = check(&app).await;
            }
        }
    });
}

#[tauri::command]
pub(crate) fn app_update_status(app: AppHandle, runtime: State<'_, UpdateRuntime>) -> UpdateStatus {
    if !runtime.enabled {
        return status(&app, UpdateState::Unsupported);
    }
    if let Err(state) = origin_and_token(&app) {
        return state;
    }
    let current = stored(&app);
    match current.state {
        UpdateState::Checking
        | UpdateState::Downloading
        | UpdateState::Available
        | UpdateState::ReadyToRestart
        | UpdateState::UpToDate => current,
        _ => status(&app, UpdateState::Ready),
    }
}

#[tauri::command]
pub(crate) async fn app_update_check(app: AppHandle) -> Result<UpdateStatus, String> {
    check(&app).await
}

#[tauri::command]
pub(crate) async fn app_update_install(
    app: AppHandle,
    on_event: Channel<DownloadEvent>,
) -> Result<UpdateStatus, String> {
    install(&app, |event| {
        let _ = on_event.send(event);
    })
    .await
}

#[tauri::command]
pub(crate) async fn app_update_restart(app: AppHandle) -> Result<(), String> {
    apply_and_restart(&app).await
}

/// The webview reports whether a reply is streaming or the mic is listening.
/// A verified update that waited for this moment is applied once it is idle.
#[tauri::command]
pub(crate) async fn app_update_set_session_active(
    app: AppHandle,
    active: bool,
) -> Result<(), String> {
    updates(&app).session_active.store(active, Ordering::SeqCst);
    let current = stored(&app);
    #[cfg(desktop)]
    crate::tray::refresh(&app, &current, active);
    if !active && current.state == UpdateState::ReadyToRestart {
        apply_and_restart(&app).await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::AuthMetadata;

    #[test]
    fn status_serialization_never_contains_endpoint_or_token_fields() {
        let encoded = serde_json::to_string(&UpdateStatus {
            state: UpdateState::Available,
            current_version: "1.0.0".to_string(),
            version: Some("1.1.0".to_string()),
            notes: Some("notes".to_string()),
        })
        .unwrap();
        assert!(!encoded.contains("token"));
        assert!(!encoded.contains("endpoint"));
        assert!(!encoded.contains("signature"));
    }

    #[test]
    fn home_node_origin_is_ordinary_metadata_only() {
        let metadata = AuthMetadata {
            device_id: Some("device".to_string()),
            home_node_origin: Some("https://home.invalid".to_string()),
        };
        let encoded = serde_json::to_string(&metadata).unwrap();
        assert!(encoded.contains("home_node_origin"));
        assert!(!encoded.contains("Bearer"));
    }

    fn capability(minimum_client_protocol: u32, endpoint: &str) -> UpdateCapability {
        UpdateCapability {
            schema_version: 1,
            channel: "stable".into(),
            check_endpoint: endpoint.into(),
            minimum_client_protocol,
        }
    }

    #[test]
    fn capability_enforces_desktop_protocol_compatibility() {
        let endpoint =
            "https://home.invalid/v1/app-updates/stable/{{target}}/{{arch}}/{{current_version}}";
        assert_eq!(
            validate_capability(capability(1, endpoint), "https://home.invalid", false).unwrap(),
            format!("{endpoint}?client_protocol=1")
        );
        assert_eq!(
            validate_capability(capability(2, endpoint), "https://home.invalid", false),
            Err("release requires a newer updater protocol")
        );
        assert!(
            validate_capability(capability(0, endpoint), "https://home.invalid", false).is_err()
        );
    }

    #[test]
    fn capability_rejects_insecure_or_incomplete_endpoints() {
        assert!(validate_capability(
            capability(
                1,
                "http://home.invalid/v1/{{target}}/{{arch}}/{{current_version}}"
            ),
            "https://home.invalid",
            false
        )
        .is_err());
        assert!(validate_capability(
            capability(1, "https://home.invalid/v1/{{target}}/{{arch}}"),
            "https://home.invalid",
            false
        )
        .is_err());
        assert!(validate_capability(
            capability(
                1,
                "https://token@home.invalid/v1/{{target}}/{{arch}}/{{current_version}}"
            ),
            "https://home.invalid",
            false
        )
        .is_err());
    }

    #[test]
    fn capability_cannot_move_native_authorization_to_another_origin() {
        for endpoint in [
            "https://other.invalid/v1/{{target}}/{{arch}}/{{current_version}}",
            "https://home.invalid:444/v1/{{target}}/{{arch}}/{{current_version}}",
            "http://home.invalid/v1/{{target}}/{{arch}}/{{current_version}}",
        ] {
            assert_eq!(
                validate_capability(capability(1, endpoint), "https://home.invalid", false),
                Err(if endpoint.starts_with("http:") {
                    "update capability endpoint is insecure"
                } else {
                    "update capability endpoint changed origin"
                })
            );
        }
    }

    #[test]
    fn update_download_origin_must_match_enrolled_origin() {
        let enrolled = normalize_home_node_origin("https://home.invalid", false).unwrap();
        for value in [
            "https://other.invalid/update.tar.gz",
            "https://home.invalid:444/update.tar.gz",
            "http://home.invalid/update.tar.gz",
        ] {
            let url = url::Url::parse(value).unwrap();
            let candidate = normalize_home_node_origin(&url.origin().ascii_serialization(), false);
            assert!(candidate.is_err() || candidate.unwrap() != enrolled);
        }
    }
}
