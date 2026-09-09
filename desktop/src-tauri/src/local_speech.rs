//! Fixed offline OS tools only. No PATH lookup, shell, cloud fallback or text
//! in argv. Missing engine is a silent, nonfatal speech-unavailable state.
use jarvis_client_core::speech::SpeechAction;
use std::{process::Stdio, sync::Arc};
use tokio::{
    io::AsyncWriteExt,
    sync::{mpsc, watch},
};

pub(crate) struct Worker {
    queue: mpsc::Sender<(u64, String)>,
    generation: Arc<watch::Sender<u64>>,
    task: tauri::async_runtime::JoinHandle<()>,
}

impl Worker {
    pub fn new() -> Self {
        let (queue, mut receiver) = mpsc::channel::<(u64, String)>(32);
        let (generation, mut changed) = watch::channel(0u64);
        let generation = Arc::new(generation);
        let task = tauri::async_runtime::spawn(async move {
            while let Some((epoch, text)) = receiver.recv().await {
                if epoch != *changed.borrow_and_update() {
                    continue;
                }
                let executable = if cfg!(target_os = "macos") {
                    "/usr/bin/say"
                } else if cfg!(target_os = "linux") {
                    "/usr/bin/espeak-ng"
                } else {
                    continue;
                };
                let mut command = tokio::process::Command::new(executable);
                if cfg!(target_os = "macos") {
                    command.args(["-f", "-"]);
                } else {
                    command.arg("--stdin");
                }
                let Ok(mut child) = command
                    .stdin(Stdio::piped())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .kill_on_drop(true)
                    .spawn()
                else {
                    continue;
                };
                let Some(mut input) = child.stdin.take() else {
                    continue;
                };
                let result = tokio::select! {
                    result=async {input.write_all(text.as_bytes()).await?; input.shutdown().await}=>result,
                    _=changed.changed()=>{let _=child.kill().await;continue},
                };
                drop(input);
                if result.is_err() {
                    let _ = child.kill().await;
                    continue;
                }
                tokio::select! { _=child.wait()=>{}, _=changed.changed()=>{let _=child.kill().await;} }
            }
        });
        Self {
            queue,
            generation,
            task,
        }
    }
    pub fn action(&self, action: SpeechAction) {
        match action {
            SpeechAction::Stop => {
                self.generation.send_modify(|g| *g = g.wrapping_add(1));
            }
            SpeechAction::Speak(text) => {
                let epoch = *self.generation.borrow();
                if self.queue.try_send((epoch, text)).is_err() {
                    self.generation.send_modify(|g| *g = g.wrapping_add(1));
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
