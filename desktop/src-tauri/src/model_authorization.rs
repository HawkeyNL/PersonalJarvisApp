//! One OS prompt at a time for every signing path. Holds no grant: every
//! signed change or approval needs its own prompt.
use std::sync::{Mutex, MutexGuard, PoisonError, TryLockError};

// Taken before AUTH_STORAGE and never acquired by lock/logout paths. It
// guards no data, so a poisoned lock is still usable.
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

/// Every signature needs Touch ID, never the device password. Only macOS can
/// enforce that: Windows Hello always offers its PIN and Linux has no
/// biometric prompt, so both refuse to sign without prompting. A Mac without
/// Touch ID, or a cancelled prompt, fails with the same `hint`.
///
/// This is a prompt gate in native code; the macOS device key itself is not
/// bound to biometry.
pub(super) fn touch_id(
    macos: bool,
    hint: &str,
    authenticate: impl FnOnce() -> Result<(), String>,
) -> Result<(), String> {
    if !macos {
        return Err(hint.into());
    }
    authenticate().map_err(|_| format!("Touch ID did not confirm. {hint}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signing_needs_touch_id_and_is_refused_off_macos() {
        assert!(touch_id(true, "hint", || Ok(())).is_ok());
        assert_eq!(
            touch_id(true, "Use your phone", || Err(
                "biometrics not supported".into()
            ))
            .unwrap_err(),
            "Touch ID did not confirm. Use your phone"
        );
        // Off macOS the OS prompt is never shown.
        assert_eq!(
            touch_id(false, "Use your phone", || panic!("prompted")).unwrap_err(),
            "Use your phone"
        );
    }
}
