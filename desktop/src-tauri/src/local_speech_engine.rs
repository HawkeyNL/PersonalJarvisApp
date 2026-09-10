//! Cancellation-safe offline TTS boundary. No model or network client exists here.
use super::local_speech::Status;
use std::{future::Future, pin::Pin, process::Stdio};
use tokio::io::AsyncWriteExt;

pub(super) type SpeechFuture<'a> = Pin<Box<dyn Future<Output = Result<(), Status>> + Send + 'a>>;
pub(super) trait TtsEngine: Send + Sync + 'static {
    /// Dropping the future must stop playback. Text must not enter argv/logs.
    /// `started` means the local engine accepted the utterance, not proof that
    /// an attached speaker is audible. Never call it on engine startup failure.
    fn speak<'a>(
        &'a self,
        text: &'a str,
        started: &'a (dyn Fn() + Send + Sync),
    ) -> SpeechFuture<'a>;
}

pub(super) struct NativeEngine;
impl TtsEngine for NativeEngine {
    fn speak<'a>(
        &'a self,
        text: &'a str,
        started: &'a (dyn Fn() + Send + Sync),
    ) -> SpeechFuture<'a> {
        Box::pin(async move {
            let executable = if cfg!(target_os = "macos") {
                "/usr/bin/say"
            } else if cfg!(target_os = "linux") {
                "/usr/bin/espeak-ng"
            } else {
                return Err(Status::Unavailable);
            };
            let mut command = tokio::process::Command::new(executable);
            if cfg!(target_os = "macos") {
                command.args(["-f", "-"]);
            } else {
                command.arg("--stdin");
            }
            let mut child = command
                .stdin(Stdio::piped())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .kill_on_drop(true)
                .spawn()
                .map_err(|error| super::local_speech::spawn_failure(&error))?;
            let mut input = child.stdin.take().ok_or(Status::Failed)?;
            if input.write_all(text.as_bytes()).await.is_err() || input.shutdown().await.is_err() {
                let _ = child.kill().await;
                return Err(Status::Failed);
            }
            drop(input);
            // Report only after successful process creation and input delivery.
            // A later nonzero exit still produces Failed, not a false success.
            started();
            match child.wait().await {
                Ok(status) if status.success() => Ok(()),
                _ => Err(Status::Failed),
            }
        })
    }
}
