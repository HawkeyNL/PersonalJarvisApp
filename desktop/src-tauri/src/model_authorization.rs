//! One OS prompt at a time for every signing path. Holds no grant: every
//! signed change needs its own prompt.
use std::sync::{Mutex, MutexGuard, PoisonError};

// Taken before AUTH_STORAGE and never acquired by lock/logout paths. It
// guards no data, so a poisoned lock is still usable.
static PROMPT: Mutex<()> = Mutex::new(());

/// Wait for the OS prompt (model changes queue behind an open prompt).
pub(super) fn prompt() -> MutexGuard<'static, ()> {
    PROMPT.lock().unwrap_or_else(PoisonError::into_inner)
}
