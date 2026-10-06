//! 工具执行层。M2d-1 只装 **只读那一档**：`read` / `glob` / `grep` / `todo`。
//!
//! 为什么先做只读的：它们不过审批闸口、不需要 diff 基线、写坏东西的风险为零，
//! 而 5 把 CRITICAL 工具里占了 4 把（read/glob/grep/todo）。做完这一步，Rust 引擎
//! 就能真的回答"这个仓库里 X 在哪、怎么实现的"这类问题，而不只是聊天。
use std::fs;
use std::path::{Path, PathBuf};

use regex::Regex;

use crate::utf16::{utf16_len, utf16_take};

/// `Tools.kt` 的 SKIP_DIRS。`Env.TOOL_OUTPUT_DIR` 是 `.haoai-output`。
const SKIP_DIRS: [&str; 12] = [
    ".git", ".gradle", "build", "out", "node_modules", ".idea", ".kotlin", ".haoai-snap",
    ".haoai-output", "__pycache__", ".venv", "dist",
];

/// `Tools.kt` 的 REGEX_META。
const REGEX_META: [char; 12] =
    ['.', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\'];

pub struct Ctx {
    pub workspace: PathBuf,
    pub todos: std::sync::Mutex<Vec<(String, String)>>,
}

pub struct Outcome {
    pub text: String,
    pub ok: bool,
}

impl Outcome {
    fn ok(text: String) -> Self {
        Self { text, ok: true }
    }
    fn fail(text: String) -> Self {
        Self { text, ok: false }
    }
}

impl Ctx {
    pub fn new(workspace: PathBuf) -> Self {
        Self { workspace, todos: std::sync::Mutex::new(vec![]) }
    }

    /// `ToolCtx.resolve`：反斜杠归一成正斜杠，相对路径挂到工作区，再取 canonical。
    pub fn resolve(&self, p: &str) -> PathBuf {
        let clean = p.trim().replace('\\', "/");
        let raw = if Path::new(&clean).is_absolute() {
            PathBuf::from(&clean)
        } else {
            self.workspace.join(&clean)
        };
        fs::canonicalize(&raw).unwrap_or(raw)
    }

    /// `ToolCtx.rel`：工作区内的相对路径（正斜杠），外面就回绝对路径。
    pub fn rel(&self, f: &Path) -> String {
        let ws = canon_str(&self.workspace);
        let abs = canon_str(f);
        if abs == ws {
            return ".".to_string();
        }
        let prefix = format!("{ws}\\");
        let prefix2 = format!("{ws}/");
        if let Some(r) = abs.strip_prefix(&prefix) {
            r.replace('\\', "/")
        } else if let Some(r) = abs.strip_prefix(&prefix2) {
            r.replace('\\', "/")
        } else {
            abs
        }
    }
}

fn canon_str(p: &Path) -> String {
    let c = fs::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    // Windows 的 canonicalize 会加 \\?\ 前缀，Kotlin 的 canonicalPath 没有
    c.display().to_string().trim_start_matches(r"\\?\").to_string()
}

/// `walkFiles`：手写递归（Kotlin 那边也是，注释说 FileTreeWalk 在 Kotlin 2.x 里类型推断不稳）。
/// 跳过 SKIP_DIRS 与任何点开头目录；深度 >12 停；累计 20000 停。
pub fn walk_files(root: &Path, cap: usize) -> Vec<PathBuf> {
    let mut out = Vec::new();
    if root.is_file() {
        out.push(root.to_path_buf());
        return out;
    }
    walk_rec(root, 0, cap, &mut out);
    out
}

fn walk_rec(d: &Path, depth: usize, cap: usize, out: &mut Vec<PathBuf>) {
    if out.len() >= cap || depth > 12 {
        return;
    }
    let Ok(rd) = fs::read_dir(d) else { return };
    for e in rd.flatten() {
        if out.len() >= cap {
            return;
        }
        let p = e.path();
        let name = e.file_name().to_string_lossy().to_string();
        if p.is_dir() {
            if SKIP_DIRS.contains(&name.as_str()) || name.starts_with('.') {
                continue;
            }
            walk_rec(&p, depth + 1, cap, out);
        } else if p.is_file() {
            out.push(p);
        }
    }
}

/// `globToRegex`：`**` → `.*`（并吃掉紧跟的一个 `/`）、`*` → `[^/]*`、`?` → `[^/]`、
/// 元字符转义、首尾加 `^`/`$`、整体忽略大小写。
pub fn glob_to_regex(glob: &str) -> Result<Regex, String> {
    let g = glob.replace('\\', "/");
    let mut sb = String::from("^");
    let cs: Vec<char> = g.chars().collect();
    let mut i = 0usize;
    while i < cs.len() {
        let c = cs[i];
        if c == '*' && i + 1 < cs.len() && cs[i + 1] == '*' {
            sb.push_str(".*");
            i += 2;
            if i < cs.len() && cs[i] == '/' {
                i += 1;
            }
            continue;
        }
        match c {
            '*' => sb.push_str("[^/]*"),
            '?' => sb.push_str("[^/]"),
            c if REGEX_META.contains(&c) => {
                sb.push('\\');
                sb.push(c);
            }
            c => sb.push(c),
        }
        i += 1;
    }
    sb.push('$');
    Regex::new(&format!("(?i){sb}")).map_err(|e| e.to_string())
}

/// `TextCap.head`：按 **UTF-16 单元**截断，截点落在代理对上时回退一位（宁少勿残），
/// 截过时补一个 `…`。`utf16_take` 本身就不会切开代理对，所以这里不需要额外回退。
pub fn text_head(t: &str, max: usize) -> String {
    if utf16_len(t) <= max {
        return t.to_string();
    }
    let mut s = utf16_take(t, max).to_string();
    s.push('…');
    s
}

/// `readLines` 的切分语义：\r\n、\n、\r 都算换行。
fn read_lines(path: &Path) -> std::io::Result<Vec<String>> {
    let text = fs::read_to_string(path)?;
    Ok(split_lines(&text))
}

fn split_lines(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut it = text.chars().peekable();
    while let Some(c) = it.next() {
        if c == '\r' {
            if it.peek() == Some(&'\n') {
                it.next();
            }
            out.push(std::mem::take(&mut cur));
        } else if c == '\n' {
            out.push(std::mem::take(&mut cur));
        } else {
            cur.push(c);
        }
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

fn arg_str(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key).and_then(|x| x.as_str()).map(|s| s.to_string())
}

fn arg_int(args: &serde_json::Value, key: &str) -> Option<i64> {
    match args.get(key) {
        Some(serde_json::Value::Number(n)) => n.as_i64(),
        Some(serde_json::Value::String(s)) => s.parse().ok(),
        _ => None,
    }
}

fn read_tool(ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(path) = arg_str(args, "path") else {
        return Outcome::fail("read 缺少 path".to_string());
    };
    let f = ctx.resolve(&path);
    if !f.is_file() {
        return Outcome::fail(format!("文件不存在：{}", ctx.rel(&f)));
    }
    let size = fs::metadata(&f).map(|m| m.len()).unwrap_or(0);
    if size > 4_000_000 {
        return Outcome::fail(format!("文件过大（{size} 字节），先用 grep 定位再按行读"));
    }
    let lines = match read_lines(&f) {
        Ok(l) => l,
        Err(e) => return Outcome::fail(format!("读取失败：{e}")),
    };
    let offset = arg_int(args, "offset").unwrap_or(1).max(1) as usize;
    let limit = arg_int(args, "limit").unwrap_or(400).clamp(1, 2000) as usize;
    if offset > lines.len() {
        return Outcome::fail(format!("offset 超出总行数 {}", lines.len()));
    }
    let end = lines.len().min(offset + limit - 1);
    // Kotlin 用 String.format("%5d: %s%n")，`%n` 在 Windows 上是 \r\n —— 逐字节照抄
    let mut sb = format!("[{}] 共 {} 行，显示 {offset}-{end}\n", ctx.rel(&f), lines.len());
    for i in offset..=end {
        sb.push_str(&format!("{:5}: {}\r\n", i, text_head(&lines[i - 1], 500)));
    }
    Outcome::ok(sb.trim_end().to_string())
}

fn glob_tool(ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(pattern) = arg_str(args, "pattern") else {
        return Outcome::fail("glob 缺少 pattern".to_string());
    };
    let root = match arg_str(args, "path") {
        Some(p) => ctx.resolve(&p),
        None => ctx.workspace.clone(),
    };
    if !root.is_dir() {
        return Outcome::fail(format!("目录不存在：{}", ctx.rel(&root)));
    }
    let rx = match glob_to_regex(&pattern) {
        Ok(r) => r,
        Err(e) => return Outcome::fail(format!("通配不合法：{e}")),
    };
    let mut out: Vec<String> = vec![];
    for f in walk_files(&root, 20_000) {
        let rel = ctx.rel(&f);
        if rx.is_match(&rel) {
            out.push(rel);
        }
        if out.len() >= 400 {
            break;
        }
    }
    if out.is_empty() {
        return Outcome::ok(format!("(无匹配) {pattern}"));
    }
    out.sort();
    let n = out.len();
    Outcome::ok(format!("{}\n— 共 {n} 个", out.join("\n")))
}

fn grep_tool(ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(pattern) = arg_str(args, "pattern") else {
        return Outcome::fail("grep 缺少 pattern".to_string());
    };
    let root = match arg_str(args, "path") {
        Some(p) => ctx.resolve(&p),
        None => ctx.workspace.clone(),
    };
    let file_rx = match arg_str(args, "glob") {
        Some(g) => match glob_to_regex(&g) {
            Ok(r) => Some(r),
            Err(e) => return Outcome::fail(format!("通配不合法：{e}")),
        },
        None => None,
    };
    let ci = matches!(args.get("ignore_case"), Some(serde_json::Value::Bool(true)));
    // java.util.regex 支持环视与反向引用，Rust 的 regex crate 不支持。
    // 编不出来时**如实说编不出来**，绝不悄悄换一个近似写法——那会让两端给出不同结果。
    let pat = if ci { format!("(?i){pattern}") } else { pattern.clone() };
    let rx = match Regex::new(&pat) {
        Ok(r) => r,
        Err(_) => {
            return Outcome::fail(format!(
                "正则不合法（Rust 引擎的 regex 不支持环视/反向引用等 Java 特性）：{pattern}"
            ));
        }
    };
    let targets = if root.is_file() { vec![root.clone()] } else { walk_files(&root, 20_000) };
    let mut sb = String::new();
    let mut n = 0usize;
    for f in targets {
        if fs::metadata(&f).map(|m| m.len() > 2_000_000).unwrap_or(true) {
            continue;
        }
        let rel = ctx.rel(&f);
        if let Some(fr) = &file_rx {
            if !fr.is_match(&rel) {
                continue;
            }
        }
        let Ok(lines) = read_lines(&f) else { continue };
        for (idx, line) in lines.iter().enumerate() {
            if rx.is_match(line) {
                n += 1;
                sb.push_str(&format!("{rel}:{}:{}\n", idx + 1, text_head(line.trim(), 200)));
            }
        }
        if n >= 500 {
            break;
        }
    }
    // 语义近邻回退（embedUrl）尚未移植；它只在文本 0 命中时追加一段，不影响上面的结果
    if n == 0 {
        Outcome::ok(format!("(无匹配) {pattern}"))
    } else {
        Outcome::ok(format!("{}\n— 命中 {n} 处", sb.trim_end()))
    }
}

fn todo_tool(ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(items) = args.get("items").and_then(|x| x.as_array()).cloned() else {
        return Outcome::fail("todo 缺少 items".to_string());
    };
    let mut g = ctx.todos.lock().unwrap_or_else(|p| p.into_inner());
    g.clear();
    for el in items {
        let text = el
            .get("text")
            .and_then(|x| x.as_str())
            .or_else(|| el.get("title").and_then(|x| x.as_str()))
            .unwrap_or("(未命名)")
            .to_string();
        let status = el.get("status").and_then(|x| x.as_str()).unwrap_or("pending").to_string();
        g.push((text, status));
    }
    if g.is_empty() {
        return Outcome::ok("(空清单)".to_string());
    }
    let mark = |s: &str| match s {
        "done" => "[x]",
        "doing" => "[>]",
        "cancelled" => "[-]",
        _ => "[ ]",
    };
    let out: Vec<String> =
        g.iter().enumerate().map(|(i, (t, s))| format!("{}. {} {t}", i + 1, mark(s))).collect();
    Outcome::ok(out.join("\n"))
}

pub fn dispatch(ctx: &Ctx, name: &str, args: &serde_json::Value) -> Option<Outcome> {
    match name {
        "read" => Some(read_tool(ctx, args)),
        "glob" => Some(glob_tool(ctx, args)),
        "grep" => Some(grep_tool(ctx, args)),
        "todo" => Some(todo_tool(ctx, args)),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn sandbox(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("haoai-tools-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(p.join("src")).unwrap();
        fs::write(p.join("src/a.rs"), "line1\nline2\nline3\n").unwrap();
        fs::write(p.join("readme.md"), "# hi\n").unwrap();
        fs::create_dir_all(p.join("node_modules/x")).unwrap();
        fs::write(p.join("node_modules/x/hidden.rs"), "should not appear\n").unwrap();
        p
    }

    #[test]
    fn read_numbers_lines_and_skips_dot_dirs() {
        let root = sandbox("read");
        let ctx = Ctx::new(root.clone());
        let r = read_tool(&ctx, &json!({"path": "src/a.rs"}));
        assert!(r.ok);
        // %n 在 Windows 是 \r\n，逐字节照抄 Kotlin
        assert!(r.text.contains("1: line1\r\n"), "{:?}", r.text);
        assert!(r.text.contains("3: line3"));

        let g = glob_tool(&ctx, &json!({"pattern": "**/*.rs"}));
        assert!(g.text.contains("src/a.rs"), "{}", g.text);
        assert!(!g.text.contains("hidden.rs"), "node_modules 没被跳过：{}", g.text);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn grep_reports_rel_line_and_content() {
        let root = sandbox("grep");
        let ctx = Ctx::new(root.clone());
        let r = grep_tool(&ctx, &json!({"pattern": "line2", "glob": "**/*.rs"}));
        assert!(r.ok);
        assert!(r.text.starts_with("src/a.rs:2:line2"), "{}", r.text);
        assert!(r.text.ends_with("— 命中 1 处"));

        let miss = grep_tool(&ctx, &json!({"pattern": "zzz-nope"}));
        assert_eq!(miss.text, "(无匹配) zzz-nope");

        // Java 的负向前瞻在 Rust regex 里编不出来：必须如实报错，不能悄悄近似
        let bad = grep_tool(&ctx, &json!({"pattern": "(?!x)y"}));
        assert!(!bad.ok, "环视应被明确拒绝，却返回了 ok");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn glob_star_does_not_cross_slash() {
        let root = sandbox("glob");
        let ctx = Ctx::new(root.clone());
        let r = glob_tool(&ctx, &json!({"pattern": "*.md"}));
        assert!(r.text.contains("readme.md"), "{}", r.text);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn todo_marks_statuses() {
        let root = sandbox("todo");
        let ctx = Ctx::new(root.clone());
        let r = todo_tool(
            &ctx,
            &json!({"items": [{"text": "第一步", "status": "done"}, {"title": "第二步"}]}),
        );
        assert_eq!(r.text, "1. [x] 第一步\n2. [ ] 第二步");
        let _ = fs::remove_dir_all(&root);
    }
}
