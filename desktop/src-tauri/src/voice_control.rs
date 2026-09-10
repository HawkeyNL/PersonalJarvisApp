//! Native, session-bound voice control. No caller-selected endpoint or identity.
use super::speech_playback::State;
use std::time::Duration;
use tokio::sync::mpsc;

enum Request {
    Release(uuid::Uuid),
    Playback(uuid::Uuid, State),
}

pub(super) struct VoiceControl {
    release: mpsc::Sender<Request>,
    task: tauri::async_runtime::JoinHandle<()>,
}

impl VoiceControl {
    pub fn new(client: reqwest::Client, origin: String, token: String) -> Self {
        let (release, mut requests) = mpsc::channel(16);
        let task = tauri::async_runtime::spawn(async move {
            while let Some(request) = requests.recv().await {
                let (path, body) = match request {
                    Request::Release(run_id) => ("release", serde_json::json!({"run_id":run_id})),
                    Request::Playback(run_id, state) => (
                        "playback",
                        serde_json::json!({"run_id":run_id,"state":state}),
                    ),
                };
                // The origin/token are one immutable snapshot from the socket's
                // native auth load. Never reload either independently here.
                let _ = client
                    .post(format!("{origin}/v1/voice/{path}"))
                    .bearer_auth(&token)
                    .json(&body)
                    .timeout(Duration::from_secs(5))
                    .send()
                    .await;
                // No response body, transport error or credential is emitted.
                // Offline failures are repaired by lease expiry/reconciliation.
            }
        });
        Self { release, task }
    }
    pub fn release(&self, run: uuid::Uuid) {
        let _ = self.release.try_send(Request::Release(run));
    }
    pub fn reporter(&self) -> impl Fn(uuid::Uuid, State) + Send + Sync + 'static {
        let sender = self.release.clone();
        move |run, state| {
            let _ = sender.try_send(Request::Playback(run, state));
        }
    }
}
impl Drop for VoiceControl {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn request(socket: &mut tokio::net::TcpStream) -> String {
        let mut bytes = Vec::new();
        loop {
            let mut chunk = [0; 1024];
            let n = socket.read(&mut chunk).await.unwrap();
            assert!(n > 0);
            bytes.extend_from_slice(&chunk[..n]);
            assert!(bytes.len() < 8192);
            if let Some(end) = bytes.windows(4).position(|w| w == b"\r\n\r\n") {
                let headers = String::from_utf8_lossy(&bytes[..end]).to_ascii_lowercase();
                let length: usize = headers
                    .lines()
                    .find_map(|line| line.strip_prefix("content-length: "))
                    .unwrap()
                    .parse()
                    .unwrap();
                if bytes.len() >= end + 4 + length {
                    return String::from_utf8(bytes).unwrap();
                }
            }
        }
    }

    #[test]
    fn playback_posts_only_run_and_fixed_status_in_order() {
        tauri::async_runtime::block_on(async {
            tokio::time::timeout(Duration::from_secs(3), async {
                let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let control = VoiceControl::new(
                    crate::native_http_client().unwrap(),
                    format!("http://{}", listener.local_addr().unwrap()),
                    "fixture-session".into(),
                );
                let report = control.reporter();
                let run = uuid::Uuid::from_u128(8);
                report(run, State::Started);
                report(run, State::Stopped);
                for state in ["started", "stopped"] {
                    let (mut socket, _) = listener.accept().await.unwrap();
                    let received = request(&mut socket).await;
                    assert!(received.starts_with("POST /v1/voice/playback HTTP/1.1\r\n"));
                    assert!(received
                        .to_ascii_lowercase()
                        .contains("authorization: bearer fixture-session\r\n"));
                    let body: serde_json::Value =
                        serde_json::from_str(received.split_once("\r\n\r\n").unwrap().1).unwrap();
                    assert_eq!(body, serde_json::json!({"run_id":run,"state":state}));
                    socket
                        .write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
                        .await
                        .unwrap();
                }
            })
            .await
            .unwrap();
        });
    }

    #[test]
    fn release_uses_fixed_path_native_header_and_exact_run_without_redirect() {
        tauri::async_runtime::block_on(async {
            tokio::time::timeout(Duration::from_secs(3), async {
                let first = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let other = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let control = VoiceControl::new(crate::native_http_client().unwrap(), format!("http://{}", first.local_addr().unwrap()), "fixture-session".into());
                let run = uuid::Uuid::from_u128(7);
                control.release(run);
                let (mut socket, _) = first.accept().await.unwrap();
                let received = request(&mut socket).await;
                assert!(received.starts_with("POST /v1/voice/release HTTP/1.1\r\n"));
                assert!(received.to_ascii_lowercase().contains("authorization: bearer fixture-session\r\n"));
                let body: serde_json::Value = serde_json::from_str(received.split_once("\r\n\r\n").unwrap().1).unwrap();
                assert_eq!(body, serde_json::json!({"run_id":run}));
                socket.write_all(format!("HTTP/1.1 307 Temporary Redirect\r\nLocation: http://{}/wrong\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", other.local_addr().unwrap()).as_bytes()).await.unwrap();
                assert!(tokio::time::timeout(Duration::from_millis(150), other.accept()).await.is_err());
                drop(control);
            }).await.unwrap();
        });
    }

    #[test]
    fn dropping_session_cancels_pending_request_and_queued_controls() {
        tauri::async_runtime::block_on(async {
            tokio::time::timeout(Duration::from_secs(3), async {
                let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
                let control = VoiceControl::new(
                    crate::native_http_client().unwrap(),
                    format!("http://{}", listener.local_addr().unwrap()),
                    "fixture-session".into(),
                );
                control.release(uuid::Uuid::from_u128(1));
                let (mut socket, _) = listener.accept().await.unwrap();
                request(&mut socket).await;
                for _ in 0..100 {
                    control.release(uuid::Uuid::from_u128(2));
                }
                assert_eq!(control.release.capacity(), 0);
                drop(control);
                let mut byte = [0; 1];
                assert_eq!(socket.read(&mut byte).await.unwrap(), 0);
                assert!(
                    tokio::time::timeout(Duration::from_millis(150), listener.accept())
                        .await
                        .is_err()
                );
            })
            .await
            .unwrap();
        });
    }
}
