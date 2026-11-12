//! Chat photo bytes. The webview hands over an already downscaled image and
//! gets back JPEG bytes; the bearer stays here. Keeping or deleting a photo is
//! small JSON and goes through the ordinary `auth_request` proxy.
use super::*;
use tauri::ipc::{InvokeBody, Request, Response};

/// Core's upload body cap; the client sends far less after its downscale.
const MAX_UPLOAD_BYTES: usize = 15 * 1024 * 1024;
/// Core stores at most 1 MiB per photo; twice that leaves room, no more.
const MAX_PHOTO_BYTES: usize = 2 * 1024 * 1024;
/// `{id,width,height,bytes}` is tiny.
const MAX_UPLOAD_REPLY_BYTES: usize = 4 * 1024;

#[derive(Deserialize, Serialize)]
struct Uploaded {
    id: uuid::Uuid,
    width: u32,
    height: u32,
    bytes: u64,
}

fn upload_body(body: &InvokeBody) -> Result<&[u8], String> {
    match body {
        InvokeBody::Raw(bytes) if !bytes.is_empty() && bytes.len() <= MAX_UPLOAD_BYTES => Ok(bytes),
        InvokeBody::Raw(bytes) if !bytes.is_empty() => Err("the photo is too large".to_string()),
        _ => Err("the photo is invalid".to_string()),
    }
}

/// The canonical path of one photo; only a parsed UUID reaches the URL. Only
/// the hyphenated form Core sends is accepted (not simple, braced or urn).
fn photo_path(id: &str) -> Result<String, String> {
    let id = Some(id)
        .filter(|id| id.len() == 36)
        .and_then(|id| uuid::Uuid::try_parse(id).ok())
        .ok_or_else(|| "the photo id is invalid".to_string())?;
    Ok(format!("/v1/photos/{}", id.hyphenated()))
}

/// A download is accepted only as a 200 JPEG within the size bound.
fn check_photo(status: u16, content_type: Option<&str>, bytes: &[u8]) -> Result<(), String> {
    if status == 404 {
        return Err("photo not found".to_string());
    }
    let jpeg = content_type
        .and_then(|value| value.split(';').next())
        .is_some_and(|essence| essence.trim().eq_ignore_ascii_case("image/jpeg"));
    if status != 200
        || !jpeg
        || bytes.len() > MAX_PHOTO_BYTES
        || !bytes.starts_with(&[0xFF, 0xD8, 0xFF])
    {
        return Err("photo unavailable".to_string());
    }
    Ok(())
}

fn bearer(auth: &SecureAuth) -> Result<String, String> {
    auth.token
        .clone()
        .filter(|value| !value.is_empty())
        .ok_or_else(|| "device is not authenticated".to_string())
}

fn read_error(error: native_response::ReadError) -> String {
    match error {
        native_response::ReadError::TooLarge => "Home Node response is too large".to_string(),
        native_response::ReadError::Transport => "Home Node response is invalid".to_string(),
    }
}

/// POST the raw image body to `/v1/photos`. A non-2xx status comes back with
/// no body, so the page can tell an older Core (404) from a refusal.
#[tauri::command]
pub(crate) async fn photo_upload(
    app: AppHandle,
    request: Request<'_>,
) -> Result<NativeApiResponse, String> {
    let body = upload_body(request.body())?.to_vec();
    let auth = load_secure_auth(&app)?;
    let token = bearer(&auth)?;
    let response = native_http_client()?
        .post(api_url(
            auth.metadata.home_node_origin.as_deref(),
            "/v1/photos",
        )?)
        .bearer_auth(token)
        .header(reqwest::header::ACCEPT, "application/json")
        .header(reqwest::header::CONTENT_TYPE, "application/octet-stream")
        .body(body)
        .send()
        .await
        .map_err(|_| "Home Node is unreachable".to_string())?;
    let status = response.status().as_u16();
    if !response.status().is_success() {
        return Ok(NativeApiResponse { status, body: None });
    }
    let bytes = native_response::bounded(response, MAX_UPLOAD_REPLY_BYTES)
        .await
        .map_err(read_error)?;
    // Only the known fields go back to the page.
    let uploaded: Uploaded =
        serde_json::from_slice(&bytes).map_err(|_| "Home Node response is invalid".to_string())?;
    let body =
        serde_json::to_value(uploaded).map_err(|_| "Home Node response is invalid".to_string())?;
    Ok(NativeApiResponse {
        status,
        body: Some(body),
    })
}

/// GET one photo as JPEG bytes (an ArrayBuffer in the page). Fails with
/// "photo not found" on 404, so the page can show a placeholder.
#[tauri::command]
pub(crate) async fn photo_get(app: AppHandle, id: String) -> Result<Response, String> {
    let path = photo_path(&id)?;
    let auth = load_secure_auth(&app)?;
    let token = bearer(&auth)?;
    let response = native_http_client()?
        .get(api_url(auth.metadata.home_node_origin.as_deref(), &path)?)
        .bearer_auth(token)
        .header(reqwest::header::ACCEPT, "image/jpeg")
        .send()
        .await
        .map_err(|_| "Home Node is unreachable".to_string())?;
    let status = response.status().as_u16();
    let content_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string);
    let bytes = if status == 200 {
        native_response::bounded(response, MAX_PHOTO_BYTES)
            .await
            .map_err(read_error)?
    } else {
        Vec::new()
    };
    check_photo(status, content_type.as_deref(), &bytes)?;
    Ok(Response::new(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uploads_are_raw_non_empty_and_bounded() {
        assert!(upload_body(&InvokeBody::Raw(vec![1; 10])).is_ok());
        assert!(upload_body(&InvokeBody::Raw(vec![0; MAX_UPLOAD_BYTES])).is_ok());
        assert!(upload_body(&InvokeBody::Raw(vec![0; MAX_UPLOAD_BYTES + 1])).is_err());
        assert!(upload_body(&InvokeBody::Raw(Vec::new())).is_err());
        assert!(upload_body(&InvokeBody::Json(serde_json::json!({"bytes": [1]}))).is_err());
    }

    #[test]
    fn photo_paths_come_only_from_uuids() {
        let id = "0B6F3C2E-8D1A-4F5E-9C3B-2A1D0E9F8C7B";
        assert_eq!(
            photo_path(id).unwrap(),
            "/v1/photos/0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b"
        );
        assert!(authenticated_api_path(&photo_path(id).unwrap()));
        for bad in [
            "",
            "../auth/login",
            "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b/../x",
            "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b?x=1",
            "%2e%2e",
            "0b6f3c2e8d1a4f5e9c3b2a1d0e9f8c7b",
            "{0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b}",
            "urn:uuid:0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b",
        ] {
            assert!(photo_path(bad).is_err(), "{bad}");
        }
    }

    #[test]
    fn downloads_must_be_small_jpegs() {
        let jpeg = [0xFF, 0xD8, 0xFF, 0xE0, 0, 0];
        assert!(check_photo(200, Some("image/jpeg"), &jpeg).is_ok());
        assert!(check_photo(200, Some("Image/JPEG; charset=binary"), &jpeg).is_ok());
        assert_eq!(
            check_photo(404, Some("application/json"), &[]).unwrap_err(),
            "photo not found"
        );
        let mut big = vec![0; MAX_PHOTO_BYTES + 1];
        big[..3].copy_from_slice(&[0xFF, 0xD8, 0xFF]);
        for (status, content_type, bytes) in [
            (200, None, &jpeg[..]),
            (200, Some("image/png"), &jpeg[..]),
            (200, Some("text/html"), &jpeg[..]),
            (200, Some("image/jpeg"), b"<svg onload=x>".as_slice()),
            (200, Some("image/jpeg"), &[][..]),
            (200, Some("image/jpeg"), &big[..]),
            (201, Some("image/jpeg"), &jpeg[..]),
            (302, Some("image/jpeg"), &jpeg[..]),
            (500, Some("image/jpeg"), &jpeg[..]),
        ] {
            assert_eq!(
                check_photo(status, content_type, bytes).unwrap_err(),
                "photo unavailable",
                "{status} {content_type:?}"
            );
        }
    }

    #[test]
    fn upload_replies_keep_only_known_fields() {
        let reply: Uploaded = serde_json::from_value(serde_json::json!({
            "id": "0b6f3c2e-8d1a-4f5e-9c3b-2a1d0e9f8c7b", "width": 10, "height": 20,
            "bytes": 300, "token": "secret"
        }))
        .unwrap();
        let value = serde_json::to_value(reply).unwrap();
        assert!(value.get("token").is_none());
        assert!(serde_json::from_value::<Uploaded>(serde_json::json!({"id": "x"})).is_err());
    }
}
