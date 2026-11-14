use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::SystemTime;

use serde_json::{Value, json};

use crate::diagnosis::{self, SourceText};
use crate::tools::{self, LogFile, ToolRequest};
use crate::window;

const MAX_TOOLS: usize = 40;

/// 一次崩溃诊断。日志只留在这份快照里，工具次数和重复查询按 LogAgent 的预算拦下。
pub struct Session {
    files: Vec<LogFile>,
    sources: Vec<SourceText>,
    mods_dir: Option<PathBuf>,
    tools: usize,
    reads: HashSet<String>,
}

impl Session {
    pub fn new() -> Self {
        Self {
            files: Vec::new(),
            sources: Vec::new(),
            mods_dir: None,
            tools: 0,
            reads: HashSet::new(),
        }
    }

    pub fn add_file(&mut self, name: &str, source_type: &str, content: &str) {
        let content = sanitize(content);
        let lines: Vec<String> = content
            .split('\n')
            .map(|line| line.trim_end_matches('\r').to_string())
            .collect();
        let kind = match source_type {
            "crash_report" => "crash_report",
            "jvm_crash" => "jvm_crash",
            _ => "minecraft_log",
        };
        let id = format!("log-{}", self.files.len() + 1);
        let line_count = lines.len();
        self.sources.push(SourceText {
            first_line: 1,
            filename: name.to_string(),
            upload_name: name.to_string(),
            source_type: kind,
            modified: SystemTime::now(),
            content,
        });
        self.files.push(LogFile {
            id,
            name: name.to_string(),
            source_type: kind.to_string(),
            first_line: 1,
            line_count,
            truncated: false,
            lines,
        });
    }

    pub fn set_mods_dir(&mut self, path: &str) {
        let path = path.trim();
        self.mods_dir = if path.is_empty() {
            None
        } else {
            Some(PathBuf::from(path))
        };
    }

    pub fn context(&self) -> String {
        let mods = self
            .mods_dir
            .as_ref()
            .map(|dir| diagnosis::mods_json(dir, &self.sources))
            .unwrap_or(Value::Array(Vec::new()));
        json!({
            "findings": diagnosis::findings_json(&self.sources),
            "mods": mods,
            "files": self.files,
            "window": window::initial_window(&self.files),
        })
        .to_string()
    }

    pub fn tool(&mut self, request_json: &str) -> String {
        self.tools += 1;
        if self.tools > MAX_TOOLS {
            return json!({"error": "LogAgent reached the 40 tool call limit"}).to_string();
        }
        let request: ToolRequest = match serde_json::from_str(request_json) {
            Ok(request) => request,
            Err(_) => return json!({"error": "Unsupported diagnostic tool"}).to_string(),
        };
        let key = format!(
            "{}:{}:{:?}:{:?}:{:?}:{:?}",
            request.name,
            request.file_id,
            request.start_line,
            request.end_line,
            request.query,
            request.context
        );
        if !self.reads.insert(key) {
            return json!({
                "notice": "This exact request was already answered. Reuse earlier evidence or inspect another range."
            })
            .to_string();
        }
        tools::execute(&self.files, &request).to_string()
    }
}

pub type Handle = Mutex<Session>;

fn sanitize(text: &str) -> String {
    let mut out = text.to_string();
    if let Ok(home) = std::env::var("HOME") {
        if home.chars().count() >= 3 {
            out = out.replace(&home, "{HOME}");
        }
    }
    if let Ok(user) = std::env::var("USER").or_else(|_| std::env::var("USERNAME")) {
        if user.chars().count() >= 3 {
            out = out.replace(&user, "{COMPUTER_USERNAME}");
        }
    }
    out
}
