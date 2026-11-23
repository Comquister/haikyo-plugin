use std::fs::OpenOptions;
use std::io::Write;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

pub struct AuditLogger {
    path: String,
}

impl AuditLogger {
    pub fn new(path: impl Into<String>) -> Self {
        Self { path: path.into() }
    }

    pub fn log_action(&self, actor: &str, action: &str, target: &str, details: &str) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0);

        let line = format!(
            "[{}] actor={} action={} target={} details={}\n",
            now, actor, action, target, details
        );

        if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(Path::new(&self.path)) {
            let _ = f.write_all(line.as_bytes());
        }
    }
}
