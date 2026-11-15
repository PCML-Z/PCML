use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::SystemTime;

use serde_json::{Value, json};

use crate::diagnosis::{self, SourceText};
use crate::tools::{self, LogFile, ToolRequest};
use crate::window;

const MAX_TOOLS: usize = 40;
const MAX_LINES: usize = 4000;

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
        let kind = match source_type {
            "crash_report" => "crash_report",
            "jvm_crash" => "jvm_crash",
            _ => "minecraft_log",
        };
        let all: Vec<String> = content
            .split('\n')
            .map(|line| line.trim_end_matches('\r').to_string())
            .collect();
        let overflow = all.len().saturating_sub(MAX_LINES);
        // 崩溃报告的原因在开头，游戏日志的原因在末尾。裁的时候把原因留下，行号仍按原文计。
        let (first_line, kept) = if overflow == 0 {
            (1, all)
        } else if kind == "minecraft_log" {
            (overflow + 1, all[overflow..].to_vec())
        } else {
            (1, all[..MAX_LINES].to_vec())
        };
        let raw = kept.join("\n");
        let lines: Vec<String> = raw.split('\n').map(|line| sanitize(line)).collect();
        let id = format!("log-{}", self.files.len() + 1);
        let line_count = lines.len();
        self.sources.push(SourceText {
            first_line,
            filename: name.to_string(),
            upload_name: name.to_string(),
            source_type: kind,
            modified: SystemTime::now(),
            content: raw,
        });
        self.files.push(LogFile {
            id,
            name: name.to_string(),
            source_type: kind.to_string(),
            first_line,
            line_count,
            truncated: overflow > 0,
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
        let mut value = json!({
            "findings": diagnosis::findings_json(&self.sources),
            "mods": mods,
            "files": self.files,
            "window": window::initial_window(&self.files),
        });
        sanitize_value(&mut value);
        value.to_string()
    }

    pub fn tool(&mut self, request_json: &str) -> String {
        self.tools += 1;
        if self.tools > MAX_TOOLS {
            return json!({"error": "LogAgent reached the 40 tool call limit"}).to_string();
        }
        let request: ToolRequest = match serde_json::from_str(request_json) {
            Ok(request) => request,
            Err(error) => {
                return json!({"error": format!("Could not read the diagnostic tool request: {error}")})
                    .to_string();
            }
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

fn sanitize_value(value: &mut Value) {
    match value {
        Value::String(text) => *text = sanitize(text),
        Value::Array(items) => items.iter_mut().for_each(sanitize_value),
        Value::Object(map) => map.values_mut().for_each(sanitize_value),
        _ => {}
    }
}

fn sanitize(text: &str) -> String {
    let home = std::env::var("HOME")
        .ok()
        .filter(|value| !value.is_empty())
        .or_else(|| {
            std::env::var("USERPROFILE")
                .ok()
                .filter(|value| !value.is_empty())
        });
    let user = std::env::var("USER")
        .ok()
        .filter(|value| !value.is_empty())
        .or_else(|| std::env::var("USERNAME").ok().filter(|value| !value.is_empty()));
    sanitize_with(text, home.as_deref(), user.as_deref())
}

fn sanitize_with(text: &str, home: Option<&str>, user: Option<&str>) -> String {
    let mut out = text.to_string();
    if let Some(home) = home.filter(|value| value.chars().count() >= 3) {
        out = out.replace(home, "{HOME}");
    }
    if let Some(user) = user.filter(|value| acceptable_username(value)) {
        out = replace_path_segment(&out, user, "{COMPUTER_USERNAME}");
    }
    out
}

fn acceptable_username(user: &str) -> bool {
    user.chars().count() >= 3 && !user.contains(['/', '\\'])
}

/// 只替换目录名。整段替换会把用户名 “java”“mod” 写进异常类名和模组句子里。
fn replace_path_segment(text: &str, user: &str, replacement: &str) -> String {
    let chars: Vec<char> = text.chars().collect();
    let user_chars: Vec<char> = user.chars().collect();
    let mut out = String::with_capacity(text.len());
    let mut index = 0;
    while index < chars.len() {
        let rest = &chars[index..];
        let preceded = index > 0 && matches!(chars[index - 1], '/' | '\\');
        if preceded
            && rest.len() >= user_chars.len()
            && rest
                .iter()
                .zip(user_chars.iter())
                .all(|(left, right)| left.eq_ignore_ascii_case(right))
        {
            let end = index + user_chars.len();
            let boundary = end == chars.len() || is_segment_end(chars[end]);
            if boundary {
                out.push_str(replacement);
                index = end;
                continue;
            }
        }
        out.push(chars[index]);
        index += 1;
    }
    out
}

fn is_segment_end(ch: char) -> bool {
    matches!(
        ch,
        '/' | '\\' | ' ' | '"' | '\'' | ',' | ';' | ')' | ']' | '>' | '\t'
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn long_game_log_keeps_the_tail_and_original_line() {
        let mut session = Session::new();
        let mut lines = Vec::new();
        for n in 1..=5000 {
            lines.push(format!("line {n}"));
        }
        lines.push("java.lang.OutOfMemoryError: Java heap space".to_string());
        session.add_file("latest.log", "minecraft_log", &lines.join("\n"));
        let context = session.context();
        let value: Value = serde_json::from_str(&context).unwrap();
        assert_eq!(value["findings"][0]["id"], "out_of_memory");
        assert_eq!(value["files"][0]["first_line"], 1002);
        assert_eq!(value["findings"][0]["evidence"][0]["line"], 5001);
        assert_eq!(value["files"][0]["truncated"], true);
    }

    #[test]
    fn long_crash_report_keeps_the_cause_at_the_top() {
        let mut session = Session::new();
        let mut lines = vec!["java.lang.OutOfMemoryError: Java heap space".to_string()];
        for n in 2..=5000 {
            lines.push(format!("at com.example.Mod.run(Mod.java:{n})"));
        }
        session.add_file("crash-report.txt", "crash_report", &lines.join("\n"));
        let context = session.context();
        let value: Value = serde_json::from_str(&context).unwrap();
        assert_eq!(value["findings"][0]["id"], "out_of_memory");
        assert_eq!(value["files"][0]["first_line"], 1);
        assert_eq!(value["findings"][0]["evidence"][0]["line"], 1);
    }

    #[test]
    fn username_is_only_redacted_as_a_path_segment() {
        let text = sanitize_with(
            "java.lang.OutOfMemoryError C:\\Users\\java\\AppData /Users/java/Library modern",
            Some("/Users/java"),
            Some("java"),
        );
        assert!(text.contains("java.lang.OutOfMemoryError"), "{text}");
        assert!(text.contains("modern"), "{text}");
        assert!(text.contains("{HOME}/Library"), "{text}");
        assert!(
            text.contains("C:\\Users\\{COMPUTER_USERNAME}\\AppData"),
            "{text}"
        );
        assert!(!text.contains("/Users/java"), "{text}");

        let mods = sanitize_with("Loading mod modern /opt/mod/config", None, Some("mod"));
        assert_eq!(mods, "Loading mod modern /opt/{COMPUTER_USERNAME}/config");
    }
}
