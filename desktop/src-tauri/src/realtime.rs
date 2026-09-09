//! Authenticated native socket. The WebView receives only decoded public
//! events, never credentials or websocket request headers.
use futures_util::{SinkExt, StreamExt};
use jarvis_client_core::{
    realtime::{EventCursor, EventEnvelope, MAX_EVENT_BYTES},
    speech::VoiceGate,
};
use std::{
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Duration,
};
use tauri::{AppHandle, Emitter, Manager};
use tokio_tungstenite::{
    connect_async_with_config,
    tungstenite::{
        client::IntoClientRequest,
        protocol::{Message, WebSocketConfig},
    },
};

#[derive(Default)]
pub(crate) struct Runtime {
    task: Mutex<Option<tauri::async_runtime::JoinHandle<()>>>,
    pub voice_enabled: Arc<AtomicBool>,
}

pub(crate) fn stop(app: &AppHandle) {
    let runtime = app.state::<Runtime>();
    if let Ok(mut task) = runtime.task.lock() {
        if let Some(task) = task.take() {
            task.abort();
        }
    };
}

#[tauri::command]
pub(crate) fn realtime_stop(app: AppHandle) {
    stop(&app);
}

#[tauri::command]
pub(crate) fn realtime_voice_enabled(app: AppHandle, enabled: bool) {
    if app
        .state::<Runtime>()
        .voice_enabled
        .swap(enabled, Ordering::SeqCst)
        == enabled
    {
        return;
    }
    // Restarting the native loop stops its local speech worker immediately.
    stop(&app);
    let _ = realtime_start(app);
}

#[tauri::command]
pub(crate) fn realtime_start(app: AppHandle) -> Result<(), String> {
    super::native_http_client()?; // Installs the reviewed Rustls provider.
    let runtime = app.state::<Runtime>();
    let mut task = runtime.task.lock().map_err(|_| "realtime unavailable")?;
    if task
        .as_ref()
        .is_some_and(|task| !task.inner().is_finished())
    {
        return Ok(());
    }
    let voice = runtime.voice_enabled.clone();
    let handle = app.clone();
    *task = Some(tauri::async_runtime::spawn(async move {
        run(handle, voice).await;
    }));
    Ok(())
}

async fn run(app: AppHandle, voice: Arc<AtomicBool>) {
    let mut attempt = 0u32;
    loop {
        let Ok(auth) = super::load_secure_auth(&app) else {
            return;
        };
        let Some(token) = auth.token else { return };
        let Some(origin) = auth.metadata.home_node_origin else {
            return;
        };
        let Some(device) = auth
            .metadata
            .device_id
            .and_then(|id| uuid::Uuid::parse_str(&id).ok())
        else {
            return;
        };
        let Ok(mut url) = url::Url::parse(&origin) else {
            return;
        };
        let scheme = if url.scheme() == "https" {
            "wss"
        } else if cfg!(debug_assertions) {
            "ws"
        } else {
            return;
        };
        if url.set_scheme(scheme).is_err() {
            return;
        }
        url.set_path("/v1/events");
        let Ok(mut request) = url.as_str().into_client_request() else {
            return;
        };
        let Ok(header) = format!("Bearer {token}").parse() else {
            return;
        };
        request.headers_mut().insert("authorization", header);
        drop(token);
        let config = WebSocketConfig::default()
            .max_message_size(Some(MAX_EVENT_BYTES))
            .max_frame_size(Some(MAX_EVENT_BYTES));
        let connected = tokio::time::timeout(
            Duration::from_secs(15),
            connect_async_with_config(request, Some(config), false),
        )
        .await;
        if let Ok(Ok((mut socket, _))) = connected {
            let started = tokio::time::Instant::now();
            let mut cursor = EventCursor::default();
            let mut gate = VoiceGate::new(device);
            gate.set_enabled(voice.load(Ordering::SeqCst));
            let speech = super::local_speech::Worker::new();
            loop {
                let incoming = tokio::time::timeout(Duration::from_secs(80), socket.next()).await;
                match incoming {
                    Ok(Some(Ok(Message::Text(text)))) => {
                        let Ok(event) = serde_json::from_str::<EventEnvelope>(&text) else {
                            break;
                        };
                        if !cursor.accept(&event) {
                            continue;
                        }
                        for action in gate.event(&event.event) {
                            speech.action(action);
                        }
                        if app.emit("jarvis-realtime", &event).is_err() {
                            break;
                        }
                    }
                    Ok(Some(Ok(Message::Ping(bytes)))) => {
                        if socket.send(Message::Pong(bytes)).await.is_err() {
                            break;
                        }
                    }
                    Ok(Some(Ok(Message::Pong(_)))) => {}
                    _ => break,
                }
            }
            drop(speech);
            if started.elapsed() > Duration::from_secs(30) {
                attempt = 0;
            }
        }
        // No provider error/URL/header is exposed. Native reload on each retry
        // means an origin switch can never forward an old server's token.
        let _ = app.emit("jarvis-realtime-disconnected", ());
        attempt = attempt.saturating_add(1).min(6);
        let jitter = rand::random::<u64>() % 750;
        tokio::time::sleep(Duration::from_millis(
            (500u64 << attempt).min(30_000) + jitter,
        ))
        .await;
    }
}
