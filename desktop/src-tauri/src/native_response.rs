//! Bound decoded HTTP bodies while reading, including chunked responses.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum ReadError {
    TooLarge,
    Transport,
}

pub(super) async fn bounded(
    mut response: reqwest::Response,
    limit: usize,
) -> Result<Vec<u8>, ReadError> {
    if response
        .content_length()
        .is_some_and(|size| size > limit as u64)
    {
        return Err(ReadError::TooLarge);
    }
    let mut data = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|_| ReadError::Transport)? {
        if chunk.len() > limit.saturating_sub(data.len()) {
            return Err(ReadError::TooLarge);
        }
        data.extend_from_slice(&chunk);
    }
    Ok(data)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };
    struct Server(tokio::task::JoinHandle<()>);
    impl Drop for Server {
        fn drop(&mut self) {
            self.0.abort();
        }
    }

    async fn check(wire: &'static [u8], expected: Result<Vec<u8>, ReadError>) {
        tokio::time::timeout(Duration::from_secs(3), async {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}/fixture", listener.local_addr().unwrap());
            let mut server = Server(tokio::spawn(async move {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                while !request.windows(4).any(|s| s == b"\r\n\r\n") {
                    let mut buf = [0; 1024];
                    let n = socket.read(&mut buf).await.unwrap();
                    assert!(n > 0);
                    request.extend_from_slice(&buf[..n]);
                    assert!(request.len() < 8192);
                }
                socket.write_all(wire).await.unwrap();
                // Deliberately never finish oversized bodies: rejection must
                // happen on size, not EOF or the HTTP client timeout.
                std::future::pending::<()>().await;
            }));
            let response = crate::native_http_client()
                .unwrap()
                .get(url)
                .send()
                .await
                .unwrap();
            let actual = bounded(response, 16).await;
            server.0.abort();
            let _ = (&mut server.0).await;
            assert_eq!(actual, expected);
        })
        .await
        .unwrap();
    }
    #[test]
    fn chunked_limit_is_enforced_without_waiting_for_eof() {
        tauri::async_runtime::block_on(async {
            check(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8\r\n12345678\r\n9\r\n123456789\r\n",Err(ReadError::TooLarge)).await;
            check(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8\r\n12345678\r\n8\r\nabcdefgh\r\n0\r\n\r\n",Ok(b"12345678abcdefgh".to_vec())).await;
            check(
                b"HTTP/1.1 200 OK\r\nContent-Length: 99999\r\n\r\n",
                Err(ReadError::TooLarge),
            )
            .await;
        });
    }
}
