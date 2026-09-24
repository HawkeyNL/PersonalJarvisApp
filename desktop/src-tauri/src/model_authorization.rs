//! In-memory, model-only OS authorization. Never stores passwords or signatures.
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime};

const TTL: Duration = Duration::from_secs(300);
static GRANT: Mutex<Option<Grant>> = Mutex::new(None);
// Avoid overlapping OS prompts. This is never acquired by lock/logout paths.
pub(super) static PROMPT: Mutex<()> = Mutex::new(());

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
