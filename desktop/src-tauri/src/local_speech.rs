//! Fixed offline OS tools only. No PATH lookup, shell, cloud fallback or text
//! in argv. Only fixed, non-secret status values may leave this module.
use super::local_speech_engine::{NativeEngine, TtsEngine};
use super::speech_playback::{Playback, State};
use jarvis_client_core::speech::SpeechAction;
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex,
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
    playback: Arc<PlaybackReporter>,
}

struct PlaybackReporter {
    state: Mutex<Playback>,
    report: Box<dyn Fn(uuid::Uuid, State) + Send + Sync>,
}
impl PlaybackReporter {
    fn change(&self, change: impl FnOnce(&mut Playback) -> Option<(uuid::Uuid, State)>) {
        if let Ok(mut state) = self.state.lock() {
            if let Some((run, event)) = change(&mut state) {
                (self.report)(run, event);
            }
        }
    }
}

impl Worker {
    pub fn new(
        report: impl Fn(Status) + Send + Sync + 'static,
        playback: impl Fn(uuid::Uuid, State) + Send + Sync + 'static,
        rate: Arc<super::local_speech_engine::SpeechRate>,
    ) -> Self {
        Self::with_reports(report, Arc::new(NativeEngine(rate)), playback)
    }
    #[cfg(test)]
    fn with_engine(
        report: impl Fn(Status) + Send + Sync + 'static,
        engine: Arc<dyn TtsEngine>,
    ) -> Self {
        Self::with_reports(report, engine, |_, _| {})
    }
    fn with_reports(
        report: impl Fn(Status) + Send + Sync + 'static,
        engine: Arc<dyn TtsEngine>,
        playback: impl Fn(uuid::Uuid, State) + Send + Sync + 'static,
    ) -> Self {
        let playback = Arc::new(PlaybackReporter {
            state: Mutex::new(Playback::default()),
            report: Box::new(playback),
        });
        let task_playback = playback.clone();
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
                let started = || {
                    task_report(Status::Speaking);
                    task_playback.change(|p| p.started(epoch));
                };
                tokio::select! {
                    biased;
                    _=changed.changed()=>{task_report(Status::Idle);}
                    result=engine.speak(&text, &started)=>{
                        if let Err(status) = result {
                            failed_epoch = Some(epoch);
                            task_playback.change(|p|p.failed(epoch));
                            task_report(status);
                        } else {
                            task_playback.change(|p|p.finished(epoch));
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
            playback,
        }
    }
    pub fn begin(&self, run: uuid::Uuid) {
        let epoch = *self.generation.borrow();
        self.playback.change(|p| {
            p.begin(run, epoch);
            None
        });
    }
    pub fn seal(&self, run: uuid::Uuid) {
        self.playback.change(|p| p.seal(run));
    }
    pub fn action(&self, action: SpeechAction) {
        match action {
            SpeechAction::Stop => {
                self.playback.change(Playback::stop);
                self.overflowed.store(false, Ordering::SeqCst);
                self.generation.send_modify(|g| *g = g.wrapping_add(1));
                (self.report)(Status::Idle);
            }
            SpeechAction::Speak(text) => {
                if self.overflowed.load(Ordering::SeqCst) {
                    return;
                }
                let epoch = *self.generation.borrow();
                // Register before sending: the worker may finish immediately.
                self.playback.change(|p| {
                    p.queued(epoch);
                    None
                });
                if self.queue.try_send((epoch, text)).is_err() {
                    self.playback.change(|p| p.failed(epoch));
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

    struct QuickEngine;
    impl TtsEngine for QuickEngine {
        fn speak<'a>(
            &'a self,
            _text: &'a str,
            started: &'a (dyn Fn() + Send + Sync),
        ) -> SpeechFuture<'a> {
            Box::pin(async move {
                started();
                Ok(())
            })
        }
    }
    #[test]
    fn worker_reports_one_run_lifecycle_not_one_per_phrase() {
        tauri::async_runtime::block_on(async {
            let (sender, mut events) = mpsc::unbounded_channel();
            let worker = Worker::with_reports(
                |_| {},
                Arc::new(QuickEngine),
                move |run, state| {
                    let _ = sender.send((run, state));
                },
            );
            let run = uuid::Uuid::from_u128(12);
            worker.begin(run);
            worker.action(SpeechAction::Speak("First sentence.".into()));
            worker.action(SpeechAction::Speak("Second sentence.".into()));
            worker.seal(run);
            for expected in [State::Started, State::Stopped] {
                assert_eq!(
                    tokio::time::timeout(std::time::Duration::from_secs(2), events.recv())
                        .await
                        .unwrap(),
                    Some((run, expected))
                );
            }
            worker.seal(run);
            worker.action(SpeechAction::Stop);
            assert!(events.try_recv().is_err());
        });
    }

    struct FailingEngine(Arc<AtomicUsize>);
    impl TtsEngine for FailingEngine {
        fn speak<'a>(
            &'a self,
            _text: &'a str,
            _started: &'a (dyn Fn() + Send + Sync),
        ) -> SpeechFuture<'a> {
            Box::pin(async move {
                self.0.fetch_add(1, Ordering::SeqCst);
                Err(Status::Unavailable)
            })
        }
    }
    #[test]
    fn unavailable_engine_reports_failed_once_without_started_or_stopped() {
        tauri::async_runtime::block_on(async {
            let calls = Arc::new(AtomicUsize::new(0));
            let (sender, mut events) = mpsc::unbounded_channel();
            let worker = Worker::with_reports(
                |_| {},
                Arc::new(FailingEngine(calls.clone())),
                move |run, state| {
                    let _ = sender.send((run, state));
                },
            );
            let run = uuid::Uuid::from_u128(13);
            worker.begin(run);
            worker.action(SpeechAction::Speak("First phrase.".into()));
            worker.action(SpeechAction::Speak("Remaining phrase.".into()));
            worker.seal(run);
            assert_eq!(
                tokio::time::timeout(std::time::Duration::from_secs(2), events.recv())
                    .await
                    .unwrap(),
                Some((run, State::Failed))
            );
            worker.action(SpeechAction::Stop);
            assert!(events.try_recv().is_err());
            assert_eq!(calls.load(Ordering::SeqCst), 1);
        });
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
            // A failed startup must never advertise Speaking, even briefly.
            assert_eq!(
                tokio::time::timeout(std::time::Duration::from_secs(2), receiver.recv())
                    .await
                    .unwrap(),
                Some(Status::Unavailable)
            );
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
        fn speak<'a>(
            &'a self,
            _text: &'a str,
            started: &'a (dyn Fn() + Send + Sync),
        ) -> SpeechFuture<'a> {
            Box::pin(async move {
                let _guard = PlaybackGuard(self.0.clone());
                started();
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
