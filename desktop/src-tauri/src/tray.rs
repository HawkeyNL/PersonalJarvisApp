//! System tray with update status and quick actions. It only drives the
//! native updater in `app_updates`; there is no separate trust path here.

use std::panic::{catch_unwind, AssertUnwindSafe};

use tauri::{
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager,
};

use crate::app_updates::{self, UpdateState, UpdateStatus};

pub(crate) struct Tray {
    icon: TrayIcon,
    status: MenuItem<tauri::Wry>,
    check: MenuItem<tauri::Wry>,
    install: MenuItem<tauri::Wry>,
    normal: Image<'static>,
    attention: Image<'static>,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct TrayView {
    pub status: String,
    pub tooltip: String,
    pub install_label: &'static str,
    pub install_enabled: bool,
    pub check_enabled: bool,
    pub attention: bool,
}

/// Pure mapping from the shared update status to the tray menu.
pub(crate) fn view(current: &UpdateStatus, session_active: bool) -> TrayView {
    let version = current.version.as_deref().unwrap_or("?");
    let status = match current.state {
        UpdateState::Ready => "Updates: not checked yet".to_string(),
        UpdateState::Checking => "Checking for updates…".to_string(),
        UpdateState::UpToDate => "Up to date".to_string(),
        UpdateState::Available => format!("Update available: v{version}"),
        UpdateState::Downloading => format!("Downloading v{version}…"),
        UpdateState::ReadyToRestart if session_active => {
            "Ready to restart — waits until Jarvis is idle".to_string()
        }
        UpdateState::ReadyToRestart => "Ready to restart".to_string(),
        UpdateState::Unconfigured => "Updates: Home Node not configured".to_string(),
        UpdateState::Unauthenticated => "Updates: sign in to check".to_string(),
        UpdateState::Unsupported => "Updates not available in this build".to_string(),
        UpdateState::Incompatible => "Update needs a newer app".to_string(),
        UpdateState::Unavailable => "Update service unreachable".to_string(),
        UpdateState::Error => "Update failed".to_string(),
    };
    let (install_label, install_enabled) = match (current.state, session_active) {
        (UpdateState::Available, true) => ("Install update (restart when idle)", true),
        (UpdateState::ReadyToRestart, true) => ("Restart now (ends active session)", true),
        (UpdateState::Available | UpdateState::ReadyToRestart, false) => {
            ("Install update and restart", true)
        }
        _ => ("Install update and restart", false),
    };
    TrayView {
        tooltip: format!("Jarvis v{} — {status}", current.current_version),
        status,
        install_label,
        install_enabled,
        check_enabled: !matches!(
            current.state,
            UpdateState::Checking
                | UpdateState::Downloading
                | UpdateState::ReadyToRestart
                | UpdateState::Unsupported
        ),
        attention: matches!(
            current.state,
            UpdateState::Available | UpdateState::ReadyToRestart
        ),
    }
}

/// Paints a small amber dot in the top-right corner of the app icon.
fn with_badge(icon: &Image<'_>) -> Image<'static> {
    let (width, height) = (icon.width() as usize, icon.height() as usize);
    let mut rgba = icon.rgba().to_vec();
    let radius = width.min(height) / 4;
    let (cx, cy) = (width.saturating_sub(radius + 1), radius + 1);
    for y in 0..height {
        for x in 0..width {
            if x.abs_diff(cx).pow(2) + y.abs_diff(cy).pow(2) <= radius.pow(2) {
                let i = (y * width + x) * 4;
                rgba[i..i + 4].copy_from_slice(&[0xF5, 0x9E, 0x0B, 0xFF]);
            }
        }
    }
    Image::new_owned(rgba, width as u32, height as u32)
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn build(app: &AppHandle) -> tauri::Result<Tray> {
    let normal = app
        .default_window_icon()
        .cloned()
        .map(Image::to_owned)
        .ok_or_else(|| tauri::Error::InvalidIcon(std::io::Error::other("no app icon")))?;
    let open = MenuItem::with_id(app, "open", "Open Jarvis", true, None::<&str>)?;
    let status = MenuItem::with_id(app, "status", "", false, None::<&str>)?;
    let check = MenuItem::with_id(app, "check", "Check for updates", true, None::<&str>)?;
    let install = MenuItem::with_id(
        app,
        "install",
        "Install update and restart",
        false,
        None::<&str>,
    )?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &open,
            &PredefinedMenuItem::separator(app)?,
            &status,
            &check,
            &install,
            &PredefinedMenuItem::separator(app)?,
            &quit,
        ],
    )?;
    let icon = TrayIconBuilder::with_id("jarvis")
        .icon(normal.clone())
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => show_main_window(app),
            "check" => {
                let app = app.clone();
                tauri::async_runtime::spawn(async move {
                    let _ = app_updates::check(&app).await;
                });
            }
            "install" => app_updates::install_from_tray(app),
            "quit" => app.exit(0),
            _ => {}
        })
        // Linux AppIndicator reports no click events; "Open Jarvis" covers it.
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(Tray {
        icon,
        status,
        check,
        install,
        attention: with_badge(&normal),
        normal,
    })
}

/// Creates the tray when the desktop supports one. Without a tray host or
/// AppIndicator library Jarvis keeps running without it. Must run on the main
/// thread (setup) so a panic from libappindicator's loader is caught here.
pub(crate) fn init(app: &AppHandle) {
    match catch_unwind(AssertUnwindSafe(|| build(app))) {
        Ok(Ok(tray)) => {
            app.manage(tray);
            refresh(
                app,
                &app_updates::stored(app),
                app_updates::session_active(app),
            );
        }
        Ok(Err(error)) => eprintln!("system tray unavailable: {error}"),
        Err(_) => eprintln!("system tray unavailable: no AppIndicator library"),
    }
}

pub(crate) fn refresh(app: &AppHandle, current: &UpdateStatus, session_active: bool) {
    let Some(tray) = app.try_state::<Tray>() else {
        return;
    };
    let view = view(current, session_active);
    let _ = tray.status.set_text(&view.status);
    let _ = tray.check.set_enabled(view.check_enabled);
    let _ = tray.install.set_text(view.install_label);
    let _ = tray.install.set_enabled(view.install_enabled);
    let _ = tray.icon.set_tooltip(Some(&view.tooltip));
    let icon = if view.attention {
        &tray.attention
    } else {
        &tray.normal
    };
    let _ = tray.icon.set_icon(Some(icon.clone()));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(state: UpdateState, version: Option<&str>) -> UpdateStatus {
        UpdateStatus {
            state,
            current_version: "0.1.16".into(),
            version: version.map(Into::into),
            notes: None,
        }
    }

    #[test]
    fn up_to_date_offers_only_a_check() {
        let view = view(&status(UpdateState::UpToDate, None), false);
        assert_eq!(view.status, "Up to date");
        assert_eq!(view.tooltip, "Jarvis v0.1.16 — Up to date");
        assert!(view.check_enabled);
        assert!(!view.install_enabled);
        assert!(!view.attention);
    }

    #[test]
    fn available_update_is_highlighted_and_installable() {
        let view = view(&status(UpdateState::Available, Some("0.1.17")), false);
        assert_eq!(view.status, "Update available: v0.1.17");
        assert_eq!(view.install_label, "Install update and restart");
        assert!(view.install_enabled);
        assert!(view.attention);
    }

    #[test]
    fn active_session_defers_or_confirms_the_restart() {
        let available = view(&status(UpdateState::Available, Some("0.1.17")), true);
        assert_eq!(
            available.install_label,
            "Install update (restart when idle)"
        );
        let ready = view(&status(UpdateState::ReadyToRestart, Some("0.1.17")), true);
        assert_eq!(
            ready.status,
            "Ready to restart — waits until Jarvis is idle"
        );
        assert_eq!(ready.install_label, "Restart now (ends active session)");
        assert!(ready.install_enabled);
        assert!(!ready.check_enabled);
    }

    #[test]
    fn busy_and_unusable_states_disable_actions() {
        for state in [UpdateState::Checking, UpdateState::Downloading] {
            let view = view(&status(state, Some("0.1.17")), false);
            assert!(!view.check_enabled && !view.install_enabled);
        }
        let unsupported = view(&status(UpdateState::Unsupported, None), false);
        assert!(!unsupported.check_enabled && !unsupported.install_enabled);
        let failed = view(&status(UpdateState::Error, None), false);
        assert_eq!(failed.status, "Update failed");
        assert!(failed.check_enabled && !failed.install_enabled);
    }

    #[test]
    fn badge_keeps_size_and_marks_only_the_corner() {
        let icon = Image::new_owned(vec![0; 16 * 16 * 4], 16, 16);
        let badged = with_badge(&icon);
        assert_eq!((badged.width(), badged.height()), (16, 16));
        let pixel = |x: usize, y: usize| &badged.rgba()[(y * 16 + x) * 4..(y * 16 + x) * 4 + 4];
        assert_eq!(pixel(11, 5), &[0xF5, 0x9E, 0x0B, 0xFF]);
        assert_eq!(pixel(0, 15), &[0, 0, 0, 0]);
    }
}
