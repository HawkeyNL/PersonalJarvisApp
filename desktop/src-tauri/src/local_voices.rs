//! Bounded metadata discovery from a fixed offline executable, never a shell.
use std::{process::Stdio, sync::Mutex, time::Duration};
use tokio::io::AsyncReadExt;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize)]
pub(super) struct Voice {
    pub id: String,
    pub label: String,
}
#[derive(Default)]
pub(super) struct Selection(Mutex<String>);
impl Selection {
    pub fn get(&self) -> Result<String, &'static str> {
        self.0
            .lock()
            .map(|s| s.clone())
            .map_err(|_| "voice selection unavailable")
    }
    pub fn set(&self, id: String) -> Result<(), &'static str> {
        if !id.is_empty() && !safe_id(&id) {
            return Err("invalid local voice");
        }
        *self.0.lock().map_err(|_| "voice selection unavailable")? = id;
        Ok(())
    }
}
fn safe_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 64
        && id.as_bytes()[0].is_ascii_alphanumeric()
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}
fn parse(text: &str) -> Vec<Voice> {
    let mut voices = Vec::new();
    for line in text.lines().take(512) {
        if line.len() > 512 || line.chars().any(char::is_control) {
            continue;
        }
        let fields: Vec<_> = line.split_whitespace().take(6).collect();
        if fields.len() < 5
            || fields[0].parse::<u8>().is_err()
            || !safe_id(fields[1])
            || fields[3].len() > 128
        {
            continue;
        }
        if voices.iter().any(|v: &Voice| v.id == fields[1]) {
            continue;
        }
        voices.push(Voice {
            id: fields[1].into(),
            label: format!("{} — {}", fields[3].replace('_', " "), fields[1]),
        });
        if voices.len() == 128 {
            break;
        }
    }
    voices
}
pub(super) async fn discover() -> Result<Vec<Voice>, &'static str> {
    static DISCOVERY: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
    let _permit = DISCOVERY
        .try_acquire()
        .map_err(|_| "local voice discovery busy")?;
    if !cfg!(target_os = "linux") {
        return Err("local voice selection unavailable on this platform");
    }
    let mut child = tokio::process::Command::new("/usr/bin/espeak-ng")
        .arg("--voices")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| "local speech engine unavailable")?;
    let result = tokio::time::timeout(Duration::from_secs(3), async {
        let stdout = child
            .stdout
            .take()
            .ok_or("local voice catalog unavailable")?;
        let mut data = Vec::new();
        stdout
            .take(65_537)
            .read_to_end(&mut data)
            .await
            .map_err(|_| "local voice catalog unavailable")?;
        if data.len() > 65_536 {
            return Err("local voice catalog too large");
        }
        if !child
            .wait()
            .await
            .map_err(|_| "local voice catalog unavailable")?
            .success()
        {
            return Err("local voice catalog unavailable");
        }
        let voices = parse(std::str::from_utf8(&data).map_err(|_| "invalid local voice catalog")?);
        if voices.is_empty() {
            return Err("no local voices available");
        }
        Ok(voices)
    })
    .await
    .unwrap_or(Err("local voice catalog timed out"));
    if result.is_err() {
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn catalog_uses_language_ids_not_file_paths_and_rejects_unsafe_rows() {
        let input="Pty Language Age/Gender VoiceName File Other\n5 en-us --/M English_US gmw/en-US\n5 ../bad --/M Bad unsafe\n5 --flag --/M Bad unsafe\n5 en-us --/M Duplicate unsafe\n5 nl --/M Dutch gmw/nl\n";
        let voices = parse(input);
        assert_eq!(voices.len(), 2);
        assert_eq!(voices[0].id, "en-us");
        let selection = Selection::default();
        for bad in ["../bad", "--flag", "en;evil", "en\n", "en/us"] {
            assert!(selection.set(bad.into()).is_err());
        }
        assert_eq!(selection.get().unwrap(), "");
    }
    #[test]
    fn catalog_is_bounded() {
        let text = (0..1000)
            .map(|n| format!("5 v{n} --/M Fixture file\n"))
            .collect::<String>();
        assert_eq!(parse(&text).len(), 128);
    }
}
