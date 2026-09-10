//! Cancellation-safe offline TTS boundary. No model or network client exists here.
use super::local_speech::Status;
use std::sync::{
    atomic::{AtomicU32, Ordering},
    Arc,
};
use std::{future::Future, pin::Pin, process::Stdio};
use tokio::io::AsyncWriteExt;

pub(super) struct SpeechRate(AtomicU32);
impl Default for SpeechRate {
    fn default() -> Self {
        Self(AtomicU32::new(100))
    }
}
impl SpeechRate {
    pub fn set(&self, rate: f64) -> Result<(), &'static str> {
        if !rate.is_finite() || !(0.5..=2.0).contains(&rate) {
            return Err("speech rate must be between 0.5 and 2");
        }
        self.0
            .store((rate * 100.0).round() as u32, Ordering::Relaxed);
        Ok(())
    }
    fn words_per_minute(&self) -> String {
        (175 * self.0.load(Ordering::Relaxed) / 100).to_string()
    }
}

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

pub(super) struct NativeEngine(pub Arc<SpeechRate>, pub Arc<super::local_voices::Selection>);
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
            let selected = self.1.get().map_err(|_| Status::Failed)?;
            if !selected.is_empty() {
                let catalog = super::local_voices::discover()
                    .await
                    .map_err(|_| Status::Unavailable)?;
                if !catalog.iter().any(|voice| voice.id == selected) {
                    return Err(Status::Unavailable);
                }
                command.args(["-v", &selected]);
            }
            let rate = self.0.words_per_minute();
            if cfg!(target_os = "macos") {
                command.args(["-r", &rate, "-f", "-"]);
            } else {
                command.args(["-s", &rate, "--stdin"]);
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn speech_rate_rejects_invalid_input_without_changing_previous_value() {
        let rate = SpeechRate::default();
        assert_eq!(rate.words_per_minute(), "175");
        for invalid in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, 0.49, 2.01] {
            assert!(rate.set(invalid).is_err());
            assert_eq!(rate.words_per_minute(), "175");
        }
        rate.set(0.5).unwrap();
        assert_eq!(rate.words_per_minute(), "87");
        rate.set(2.0).unwrap();
        assert_eq!(rate.words_per_minute(), "350");
    }
}
