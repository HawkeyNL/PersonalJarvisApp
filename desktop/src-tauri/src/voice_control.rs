//! Native, session-bound voice control. No caller-selected endpoint or identity.
use std::time::Duration;
use tokio::sync::mpsc;

pub(super) struct VoiceControl {
    release: mpsc::Sender<uuid::Uuid>,
    task: tauri::async_runtime::JoinHandle<()>,
}

impl VoiceControl {
    pub fn new(client: reqwest::Client, origin: String, token: String) -> Self {
        let (release, mut requests) = mpsc::channel(1);
        let task = tauri::async_runtime::spawn(async move {
            while let Some(run_id) = requests.recv().await {
                // The origin/token are one immutable snapshot from the socket's
                // native auth load. Never reload either independently here.
                let _ = client
                    .post(format!("{origin}/v1/voice/release"))
                    .bearer_auth(&token)
                    .json(&serde_json::json!({"run_id": run_id}))
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
        let _ = self.release.try_send(run);
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
