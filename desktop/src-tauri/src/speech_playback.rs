//! Run-scoped metadata only; never stores spoken text or credentials.
use uuid::Uuid;

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub(super) enum State {
    Started,
    Stopped,
    Failed,
}

#[derive(Default)]
pub(super) struct Playback {
    current: Option<Run>,
}
struct Run {
    id: Uuid,
    epoch: u64,
    pending: usize,
    started: bool,
    sealed: bool,
}
impl Playback {
    pub fn begin(&mut self, id: Uuid, epoch: u64) {
        self.current = Some(Run {
            id,
            epoch,
            pending: 0,
            started: false,
            sealed: false,
        });
    }
    pub fn queued(&mut self, epoch: u64) {
        if let Some(run) = self.current.as_mut().filter(|run| run.epoch == epoch) {
            run.pending += 1;
        }
    }
    pub fn started(&mut self, epoch: u64) -> Option<(Uuid, State)> {
        let run = self.current.as_mut().filter(|run| run.epoch == epoch)?;
        if run.started {
            return None;
        }
        run.started = true;
        Some((run.id, State::Started))
    }
    pub fn finished(&mut self, epoch: u64) -> Option<(Uuid, State)> {
        let run = self.current.as_mut().filter(|run| run.epoch == epoch)?;
        run.pending = run.pending.saturating_sub(1);
        if run.sealed && run.pending == 0 {
            self.stop()
        } else {
            None
        }
    }
    pub fn seal(&mut self, id: Uuid) -> Option<(Uuid, State)> {
        let run = self.current.as_mut().filter(|run| run.id == id)?;
        run.sealed = true;
        if run.pending == 0 {
            self.stop()
        } else {
            None
        }
    }
    pub fn failed(&mut self, epoch: u64) -> Option<(Uuid, State)> {
        self.current.as_ref().filter(|run| run.epoch == epoch)?;
        self.current.take().map(|run| (run.id, State::Failed))
    }
    pub fn stop(&mut self) -> Option<(Uuid, State)> {
        self.current.take().map(|run| (run.id, State::Stopped))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn streaming_pause_is_not_completion_and_final_drains_once() {
        let id = Uuid::nil();
        let mut p = Playback::default();
        p.begin(id, 1);
        p.queued(1);
        assert_eq!(p.started(1), Some((id, State::Started)));
        assert_eq!(p.finished(1), None);
        p.queued(1);
        assert_eq!(p.started(1), None);
        assert_eq!(p.seal(id), None);
        assert_eq!(p.finished(1), Some((id, State::Stopped)));
        assert_eq!(p.finished(1), None);
        assert_eq!(p.seal(id), None);
    }
    #[test]
    fn stale_callbacks_cannot_finish_new_run() {
        let mut p = Playback::default();
        let a = Uuid::from_u128(1);
        let b = Uuid::from_u128(2);
        p.begin(a, 1);
        p.queued(1);
        assert_eq!(p.stop(), Some((a, State::Stopped)));
        p.begin(b, 2);
        p.queued(2);
        assert_eq!(p.finished(1), None);
        assert_eq!(p.failed(1), None);
        assert_eq!(p.seal(a), None);
        assert_eq!(p.started(1), None);
        assert_eq!(p.started(2), Some((b, State::Started)));
        assert_eq!(p.failed(2), Some((b, State::Failed)));
        assert_eq!(p.finished(2), None);
        assert_eq!(p.seal(b), None);
    }
}
