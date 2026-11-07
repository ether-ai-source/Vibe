use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

/// Expands CloudStream shortcodes (including megarepo & Indian repos) and normalizes URLs.
fn normalize_url_internal(raw: &str) -> String {
    let trimmed = raw.trim().trim_matches('"').trim_matches('\'');
    if trimmed.is_empty() {
        return String::new();
    }

    match trimmed.to_ascii_lowercase().as_str() {
        "megarepo" | "3737" | "mega" => {
            return "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json".to_string();
        }
        "phisherrepo" | "864" | "phisher" => {
            return "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json".to_string();
        }
        "cnc" | "cncverse" => {
            return "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json".to_string();
        }
        "csx" | "3670" | "megix" => {
            return "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json".to_string();
        }
        "likdev" | "z7iwuxt" => {
            return "https://codeberg.org/JakeWasHere7/likdev256-tamil-providers/raw/branch/builds/repo.json".to_string();
        }
        "cspr" | "0094" | "official" => {
            return "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json".to_string();
        }
        "hexated" | "n7iqbdm" => {
            return "https://codeberg.org/Stormunblessed/storm-ext/raw/branch/main/hexa.json".to_string();
        }
        "storm" | "l7iwex2" => {
            return "https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json".to_string();
        }
        _ => {}
    }

    if let Some(rest) = trimmed.strip_prefix("cloudstreamrepo://") {
        if rest.starts_with("http://") || rest.starts_with("https://") {
            return rest.to_string();
        }
        return format!("https://{}", rest);
    }

    if trimmed.contains("github.com/") && trimmed.contains("/blob/") {
        return trimmed
            .replace("github.com/", "raw.githubusercontent.com/")
            .replace("/blob/", "/");
    }

    if !trimmed.contains("://")
        && trimmed.matches('/').count() == 1
        && !trimmed.contains(' ')
        && !trimmed.ends_with(".json")
        && !trimmed.ends_with(".m3u")
        && !trimmed.ends_with(".m3u8")
    {
        return format!("https://raw.githubusercontent.com/{}/main/repo.json", trimmed);
    }

    if !trimmed.starts_with("http://") && !trimmed.starts_with("https://") {
        return format!("https://{}", trimmed);
    }

    trimmed.to_string()
}

/// Extracts playable media URLs (.m3u8, .mpd, .mp4) and prioritizes high-resolution streams first.
fn extract_streams_internal(payload: &str, base_url: &str) -> String {
    let unescaped = payload
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&");

    let mut found: Vec<String> = Vec::new();
    let bytes = unescaped.as_bytes();
    let len = bytes.len();
    let mut i = 0;

    while i + 8 < len {
        if (bytes[i] == b'h' || bytes[i] == b'H')
            && bytes[i + 1..].starts_with(b"ttp")
            && (bytes[i + 4] == b':' || (bytes[i + 4] == b's' && bytes[i + 5] == b':'))
        {
            let start = i;
            let mut end = i;
            while end < len {
                let c = bytes[end];
                if c <= b' '
                    || c == b'"'
                    || c == b'\''
                    || c == b'<'
                    || c == b'>'
                    || c == b'`'
                    || c == b')'
                    || c == b']'
                    || c == b'}'
                    || c == b'\\'
                {
                    break;
                }
                end += 1;
            }

            if end > start + 10 {
                if let Ok(candidate) = std::str::from_utf8(&bytes[start..end]) {
                    let lower = candidate.to_ascii_lowercase();
                    let is_stream = lower.contains(".m3u8")
                        || lower.contains(".mpd")
                        || lower.contains(".mp4")
                        || lower.contains("/hls/")
                        || lower.contains("/manifest");

                    if is_stream && !found.iter().any(|existing| existing == candidate) {
                        found.push(candidate.to_string());
                        if found.len() >= 35 {
                            break;
                        }
                    }
                }
            }
            i = end;
        } else {
            i += 1;
        }
    }

    if found.is_empty() && !base_url.is_empty() {
        for token in unescaped.split(|c: char| c == '"' || c == '\'' || c.is_whitespace()) {
            let lower = token.to_ascii_lowercase();
            if (lower.ends_with(".m3u8") || lower.ends_with(".mpd") || lower.ends_with(".mp4"))
                && token.starts_with('/')
            {
                let origin = extract_origin(base_url);
                if !origin.is_empty() {
                    let full = format!("{}{}", origin, token);
                    if !found.contains(&full) {
                        found.push(full);
                    }
                }
            }
        }
    }

    // Sort so master HLS / 4K / 1080p streams appear first
    found.sort_by_key(|u| {
        let l = u.to_ascii_lowercase();
        if l.contains("2160") || l.contains("4k") || l.contains("master.m3u8") {
            0
        } else if l.contains("1080") || l.contains(".m3u8") {
            1
        } else if l.contains("720") {
            2
        } else {
            3
        }
    });

    found.join("\n")
}

fn extract_origin(url: &str) -> String {
    if let Some(scheme_idx) = url.find("://") {
        let rest = &url[scheme_idx + 3..];
        if let Some(slash_idx) = rest.find('/') {
            return url[..scheme_idx + 3 + slash_idx].to_string();
        }
        return url.to_string();
    }
    String::new()
}

fn fnv1a_64(input: &str) -> i64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for b in input.as_bytes() {
        hash ^= *b as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash as i64
}

#[no_mangle]
pub extern "system" fn Java_com_vibe_tv_core_NativeEngine_normalizeRepoUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_url: JString<'local>,
) -> jstring {
    let raw: String = match env.get_string(&input_url) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let normalized = normalize_url_internal(&raw);
    match env.new_string(normalized) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vibe_tv_core_NativeEngine_extractStreamsFromPayload<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_payload: JString<'local>,
    base_url: JString<'local>,
) -> jstring {
    let payload: String = match env.get_string(&raw_payload) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let base: String = match env.get_string(&base_url) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let extracted = extract_streams_internal(&payload, &base);
    match env.new_string(extracted) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vibe_tv_core_NativeEngine_fastHash<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> jlong {
    let text: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    fnv1a_64(&text)
}
