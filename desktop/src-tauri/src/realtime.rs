//! Authenticated native socket. The WebView receives only decoded public
//! events, never credentials or websocket request headers.
use futures_util::{SinkExt, StreamExt};
use jarvis_client_core::{
    realtime::{Event, EventCursor, EventEnvelope, MAX_EVENT_BYTES},
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
    speech_stop: Mutex<Option<tokio::sync::watch::Sender<()>>>,
    pub voice_enabled: Arc<AtomicBool>,
    speech_rate: Arc<super::local_speech_engine::SpeechRate>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use jarvis_client_core::{
        realtime::{Event, RunIdentity},
        speech::SpeechAction,
    };

    #[test]
    fn stop_discards_late_speech_without_muting_next_run() {
        let device = uuid::Uuid::from_u128(1);
        let mut gate = VoiceGate::new(device);
        gate.set_enabled(true);
        let run = RunIdentity {
            run_id: uuid::Uuid::from_u128(2),
            request_id: uuid::Uuid::from_u128(3),
            conversation_id: uuid::Uuid::from_u128(4),
        };
        gate.event(&Event::VoiceOwnerChanged {
            device_id: Some(device),
            run_id: Some(run.run_id),
        });
        gate.event(&Event::AssistantStarted(run.clone()));
        assert_eq!(gate.set_enabled(true), SpeechAction::Stop);
        assert!(gate
            .event(&Event::AssistantDelta {
                run: run.clone(),
                text: "Late sentence. ".into()
            })
            .is_empty());
        let next = RunIdentity {
            run_id: uuid::Uuid::from_u128(5),
            ..run
        };
        gate.event(&Event::VoiceOwnerChanged {
            device_id: Some(device),
            run_id: Some(next.run_id),
        });
        gate.event(&Event::AssistantStarted(next.clone()));
        assert_eq!(
            gate.event(&Event::AssistantDelta {
                run: next,
                text: "New sentence. ".into()
            }),
            vec![SpeechAction::Speak("New sentence.".into())]
        );
    }
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

/// Stops presentation only. The shared run, socket and saved preference remain intact.
#[tauri::command]
pub(crate) fn realtime_stop_speech(app: AppHandle) {
    if let Ok(sender) = app.state::<Runtime>().speech_stop.lock() {
        if let Some(sender) = sender.as_ref() {
            let _ = sender.send(());
        }
    };
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
    if let Ok(sender) = app.state::<Runtime>().speech_stop.lock() {
        if let Some(sender) = sender.as_ref() {
            let _ = sender.send(());
        }
    }
    // Preference changes do not tear down the shared text connection.
    let _ = realtime_start(app);
}

#[tauri::command]
pub(crate) fn realtime_voice_rate(app: AppHandle, rate: f64) -> Result<(), &'static str> {
    // Local presentation only: do not restart the socket, claim voice or run a model.
    app.state::<Runtime>().speech_rate.set(rate)
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
    let (sender, receiver) = tokio::sync::watch::channel(());
    *runtime
        .speech_stop
        .lock()
        .map_err(|_| "realtime unavailable")? = Some(sender);
    let handle = app.clone();
    *task = Some(tauri::async_runtime::spawn(async move {
        run(handle, voice, receiver).await;
    }));
    Ok(())
}

async fn run(
    app: AppHandle,
    voice: Arc<AtomicBool>,
    mut speech_stop: tokio::sync::watch::Receiver<()>,
) {
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
        let config = WebSocketConfig::default()
            .max_message_size(Some(MAX_EVENT_BYTES))
            .max_frame_size(Some(MAX_EVENT_BYTES));
        let connected = tokio::time::timeout(
            Duration::from_secs(15),
            connect_async_with_config(request, Some(config), false),
        )
        .await;
        if let Ok(Ok((mut socket, _))) = connected {
            let Ok(client) = super::native_http_client() else {
                return;
            };
            let controls = super::voice_control::VoiceControl::new(client, origin, token);
            let started = tokio::time::Instant::now();
            let mut cursor = EventCursor::default();
            let mut gate = VoiceGate::new(device);
            let mut owned_run = None;
            gate.set_enabled(voice.load(Ordering::SeqCst));
            let speech_app = app.clone();
            let speech = super::local_speech::Worker::new(
                move |status| {
                    let _ = speech_app.emit("jarvis-local-speech", status);
                },
                controls.reporter(),
                app.state::<Runtime>().speech_rate.clone(),
            );
            loop {
                let incoming = tokio::select! {
                    biased;
                    result = speech_stop.changed() => {
                        if result.is_err() { return; }
                        // Clear the run buffer without muting the next prompt. Late
                        // deltas/completion cannot restart this stopped run.
                        let enabled = voice.load(Ordering::SeqCst);
                        speech.action(gate.set_enabled(enabled));
                        // Every policy change stops this run; enabling applies
                        // to the next answer, never replays an earlier answer.
                        if let Some(run) = owned_run { controls.release(run); }
                        continue;
                    }
                    incoming = tokio::time::timeout(Duration::from_secs(80), socket.next()) => incoming,
                };
                match incoming {
                    Ok(Some(Ok(Message::Text(text)))) => {
                        let Ok(event) = serde_json::from_str::<EventEnvelope>(&text) else {
                            break;
                        };
                        if !cursor.accept(&event) {
                            continue;
                        }
                        if let Event::VoiceOwnerChanged { device_id, run_id } = &event.event {
                            owned_run = if *device_id == Some(device) {
                                *run_id
                            } else {
                                None
                            };
                        }
                        for action in gate.event(&event.event) {
                            speech.action(action);
                        }
                        match &event.event {
                            Event::AssistantStarted(run)
                                if owned_run == Some(run.run_id)
                                    && voice.load(Ordering::SeqCst) =>
                            {
                                speech.begin(run.run_id);
                            }
                            Event::AssistantCompleted { run, .. } => speech.seal(run.run_id),
                            _ => {}
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
