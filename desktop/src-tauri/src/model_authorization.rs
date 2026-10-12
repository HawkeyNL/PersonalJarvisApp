//! In-memory, model-only OS authorization. Never stores passwords or signatures.
use std::sync::{Mutex, MutexGuard, PoisonError, TryLockError};
use std::time::{Duration, Instant, SystemTime};

const TTL: Duration = Duration::from_secs(300);
static GRANT: Mutex<Option<Grant>> = Mutex::new(None);
// One OS prompt at a time for every signing path. Taken before AUTH_STORAGE
// and never acquired by lock/logout paths. It guards no data, so a poisoned
// lock is still usable.
static PROMPT: Mutex<()> = Mutex::new(());

/// Wait for the OS prompt (model changes queue behind an open prompt).
pub(super) fn prompt() -> MutexGuard<'static, ()> {
    PROMPT.lock().unwrap_or_else(PoisonError::into_inner)
}

/// Claim the OS prompt without waiting, so a page cannot stack prompts.
pub(super) fn try_prompt() -> Result<MutexGuard<'static, ()>, String> {
    match PROMPT.try_lock() {
        Ok(guard) => Ok(guard),
        Err(TryLockError::Poisoned(poisoned)) => Ok(poisoned.into_inner()),
        Err(TryLockError::WouldBlock) => Err("Another approval is in progress".into()),
    }
}

struct Grant {
    epoch: u64,
    monotonic: Instant,
    wall: SystemTime,
}

impl Grant {
    fn valid_at(&self, epoch: u64, monotonic: Instant, wall: SystemTime) -> bool {
        self.epoch == epoch
            && within_window(monotonic.checked_duration_since(self.monotonic))
            && within_window(wall.duration_since(self.wall).ok())
    }
}

fn within_window(elapsed: Option<Duration>) -> bool {
    elapsed.is_some_and(|elapsed| elapsed < TTL)
}

pub(super) fn valid(epoch: u64) -> Result<bool, String> {
    let grant = GRANT
        .lock()
        .map_err(|_| "Model authorization unavailable")?;
    Ok(grant
        .as_ref()
        .is_some_and(|grant| grant.valid_at(epoch, Instant::now(), SystemTime::now())))
}

pub(super) fn remember(epoch: u64) -> Result<(), String> {
    *GRANT
        .lock()
        .map_err(|_| "Model authorization unavailable")? = Some(Grant {
        epoch,
        monotonic: Instant::now(),
        wall: SystemTime::now(),
    });
    Ok(())
}

pub(super) fn clear() -> Result<(), String> {
    *GRANT
        .lock()
        .map_err(|_| "Model authorization unavailable")? = None;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_five_minutes_and_clock_reversal_fail_closed() {
        assert!(within_window(Some(Duration::from_secs(299))));
        assert!(!within_window(Some(TTL)));
        assert!(!within_window(Some(TTL + Duration::from_secs(1))));
        assert!(!within_window(None));
    }

    #[test]
    fn session_bound_and_explicitly_revocable() {
        let start = Instant::now();
        let wall = SystemTime::now();
        let grant = Grant {
            epoch: 10,
            monotonic: start,
            wall,
        };
        assert!(grant.valid_at(10, start, wall));
        assert!(!grant.valid_at(11, start, wall));
        assert!(grant.valid_at(10, start + TTL - Duration::from_secs(1), wall));
        assert!(!grant.valid_at(10, start + TTL, wall));
        assert!(!grant.valid_at(10, start, wall + TTL));
        assert!(!grant.valid_at(10, start, wall - Duration::from_secs(1)));
    }
}
