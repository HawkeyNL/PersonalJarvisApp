//! Fixed offline OS tools only. No PATH lookup, shell, cloud fallback or text
//! in argv. Only fixed, non-secret status values may leave this module.
use super::local_speech_engine::{NativeEngine, TtsEngine};
use jarvis_client_core::speech::SpeechAction;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tokio::sync::{mpsc, watch};

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Status {
    Speaking,
    Idle,
    Unavailable,
    Failed,
    QueueFull,
}

pub(super) fn spawn_failure(error: &std::io::Error) -> Status {
    if error.kind() == std::io::ErrorKind::NotFound {
        Status::Unavailable
    } else {
        Status::Failed
    }
}

pub(crate) struct Worker {
    queue: mpsc::Sender<(u64, String)>,
    generation: Arc<watch::Sender<u64>>,
    task: tauri::async_runtime::JoinHandle<()>,
    report: Arc<dyn Fn(Status) + Send + Sync>,
    overflowed: AtomicBool,
}

impl Worker {
    pub fn new(report: impl Fn(Status) + Send + Sync + 'static) -> Self {
        Self::with_engine(report, Arc::new(NativeEngine))
    }
    fn with_engine(
        report: impl Fn(Status) + Send + Sync + 'static,
        engine: Arc<dyn TtsEngine>,
    ) -> Self {
        let report: Arc<dyn Fn(Status) + Send + Sync> = Arc::new(report);
        let task_report = report.clone();
        let (queue, mut receiver) = mpsc::channel::<(u64, String)>(32);
        let (generation, mut changed) = watch::channel(0u64);
        let generation = Arc::new(generation);
        let task = tauri::async_runtime::spawn(async move {
            let mut failed_epoch = None;
            while let Some((epoch, text)) = receiver.recv().await {
                if epoch != *changed.borrow_and_update() || failed_epoch == Some(epoch) {
                    continue;
                }
                task_report(Status::Speaking);
                tokio::select! {
                    biased;
                    _=changed.changed()=>{task_report(Status::Idle);}
                    result=engine.speak(&text)=>{
                        if let Err(status) = result {
                            failed_epoch = Some(epoch);
                            task_report(status);
                        } else {
                            task_report(Status::Idle);
                        }
                    },
                }
            }
        });
        Self {
            queue,
            generation,
            task,
            report,
            overflowed: AtomicBool::new(false),
        }
    }
    pub fn action(&self, action: SpeechAction) {
        match action {
            SpeechAction::Stop => {
                self.overflowed.store(false, Ordering::SeqCst);
                self.generation.send_modify(|g| *g = g.wrapping_add(1));
                (self.report)(Status::Idle);
            }
            SpeechAction::Speak(text) => {
                if self.overflowed.load(Ordering::SeqCst) {
                    return;
                }
                let epoch = *self.generation.borrow();
                if self.queue.try_send((epoch, text)).is_err() {
                    self.overflowed.store(true, Ordering::SeqCst);
                    self.generation.send_modify(|g| *g = g.wrapping_add(1));
                    (self.report)(Status::QueueFull);
                }
            }
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        self.task.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::super::local_speech_engine::SpeechFuture;
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct FailingEngine(Arc<AtomicUsize>);
    impl TtsEngine for FailingEngine {
        fn speak<'a>(&'a self, _text: &'a str) -> SpeechFuture<'a> {
            Box::pin(async move {
                self.0.fetch_add(1, Ordering::SeqCst);
                Err(Status::Unavailable)
            })
        }
    }
    async fn next_status(receiver: &mut mpsc::UnboundedReceiver<Status>, expected: Status) {
        tokio::time::timeout(std::time::Duration::from_secs(2), async {
            loop {
                if receiver.recv().await.unwrap() == expected {
                    return;
                }
            }
        })
        .await
        .unwrap();
    }
    #[test]
    fn failed_engine_is_not_restarted_for_every_queued_phrase() {
        tauri::async_runtime::block_on(async {
            let calls = Arc::new(AtomicUsize::new(0));
            let (sender, mut receiver) = mpsc::unbounded_channel();
            let worker = Worker::with_engine(
                move |s| {
                    let _ = sender.send(s);
                },
                Arc::new(FailingEngine(calls.clone())),
            );
            worker.action(SpeechAction::Speak("First phrase.".into()));
            worker.action(SpeechAction::Speak("Second phrase.".into()));
            next_status(&mut receiver, Status::Unavailable).await;
            worker.action(SpeechAction::Stop);
            worker.action(SpeechAction::Speak("Next run.".into()));
            next_status(&mut receiver, Status::Unavailable).await;
            assert_eq!(calls.load(Ordering::SeqCst), 2);
        });
    }

    struct PendingEngine(mpsc::UnboundedSender<&'static str>);
    struct PlaybackGuard(mpsc::UnboundedSender<&'static str>);
    impl Drop for PlaybackGuard {
        fn drop(&mut self) {
            let _ = self.0.send("stopped");
        }
    }
    impl TtsEngine for PendingEngine {
        fn speak<'a>(&'a self, _text: &'a str) -> SpeechFuture<'a> {
            Box::pin(async move {
                let _guard = PlaybackGuard(self.0.clone());
                let _ = self.0.send("started");
                std::future::pending().await
            })
        }
    }
    #[test]
    fn stop_and_disconnect_drop_inflight_playback() {
        tauri::async_runtime::block_on(async {
            for disconnect in [false, true] {
                let (sender, mut receiver) = mpsc::unbounded_channel();
                let worker = Worker::with_engine(|_| {}, Arc::new(PendingEngine(sender)));
                worker.action(SpeechAction::Speak("Fixture prose.".into()));
                assert_eq!(
                    tokio::time::timeout(std::time::Duration::from_secs(2), receiver.recv())
                        .await
                        .unwrap(),
                    Some("started")
                );
                if disconnect {
                    drop(worker);
                } else {
                    worker.action(SpeechAction::Stop);
                }
                assert_eq!(
                    tokio::time::timeout(std::time::Duration::from_secs(2), receiver.recv())
                        .await
                        .unwrap(),
                    Some("stopped")
                );
            }
        });
    }

    #[test]
    fn overflow_cancels_playback_and_suppresses_remaining_run() {
        tauri::async_runtime::block_on(async {
            let (engine_sender, mut engine_events) = mpsc::unbounded_channel();
            let (status_sender, mut statuses) = mpsc::unbounded_channel();
            let worker = Worker::with_engine(
                move |s| {
                    let _ = status_sender.send(s);
                },
                Arc::new(PendingEngine(engine_sender)),
            );
            worker.action(SpeechAction::Speak("Playing.".into()));
            assert_eq!(
                tokio::time::timeout(std::time::Duration::from_secs(2), engine_events.recv())
                    .await
                    .unwrap(),
                Some("started")
            );
            for _ in 0..33 {
                worker.action(SpeechAction::Speak("Queued.".into()));
            }
            next_status(&mut statuses, Status::QueueFull).await;
            assert!(worker.overflowed.load(Ordering::SeqCst));
            assert_eq!(
                tokio::time::timeout(std::time::Duration::from_secs(2), engine_events.recv())
                    .await
                    .unwrap(),
                Some("stopped")
            );
            worker.action(SpeechAction::Speak("Late phrase.".into()));
            assert!(engine_events.try_recv().is_err());
            worker.action(SpeechAction::Stop);
            assert!(!worker.overflowed.load(Ordering::SeqCst));
        });
    }
    #[test]
    fn engine_failure_is_fixed_metadata_not_error_text() {
        let missing = std::io::Error::new(std::io::ErrorKind::NotFound, "fixture private error");
        let denied = std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "fixture private error",
        );
        assert_eq!(spawn_failure(&missing), Status::Unavailable);
        assert_eq!(spawn_failure(&denied), Status::Failed);
        assert_eq!(
            serde_json::to_string(&spawn_failure(&missing)).unwrap(),
            "\"unavailable\""
        );
        assert_eq!(
            serde_json::to_string(&spawn_failure(&denied)).unwrap(),
            "\"failed\""
        );
    }
}
