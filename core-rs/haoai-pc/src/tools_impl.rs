//! 工具执行层。M2d-1 只装 **只读那一档**：`read` / `glob` / `grep` / `todo`。
//!
//! 为什么先做只读的：它们不过审批闸口、不需要 diff 基线、写坏东西的风险为零，
//! 而 5 把 CRITICAL 工具里占了 4 把（read/glob/grep/todo）。做完这一步，Rust 引擎
//! 就能真的回答"这个仓库里 X 在哪、怎么实现的"这类问题，而不只是聊天。
use std::fs;
use std::path::{Path, PathBuf};

use regex::Regex;

use crate::checkpoints;
use crate::utf16::{utf16_len, utf16_take, utf16_take_last};

/// `Tools.kt` 的 SKIP_DIRS。`Env.TOOL_OUTPUT_DIR` 是 `.haoai-output`。
const SKIP_DIRS: [&str; 12] = [
    ".git", ".gradle", "build", "out", "node_modules", ".idea", ".kotlin", ".haoai-snap",
    ".haoai-output", "__pycache__", ".venv", "dist",
];

/// `Env.TOOL_OUTPUT_DIR`：工具产物与溢出输出都落这个目录，且要被 git 忽略。
pub const TOOL_OUTPUT_DIR: &str = ".haoai-output";

/// `Hunks.MAX_BYTES`：基线或目标超过这个长度就不切块（卡片放不下，而切块是 quadratic 的）。
/// 同时它也是"要不要整个读进来当 diff 基线"的闸门 —— 没有这道闸，读一个 2GB 的日志
/// 当基线会把引擎撑死。
const HUNKS_MAX_BYTES: u64 = 400_000;

/// `Tools.kt` 的 REGEX_META。
const REGEX_META: [char; 12] =
    ['.', '+', '(', ')', '|', '^', '$', '{', '}', '[', ']', '\\'];

pub struct Ctx {
    /// 状态根：`checkpoints.jsonl` 落这里。测试各自给临时目录，绝不往真实 `%LOCALAPPDATA%` 写。
    pub home: PathBuf,
    pub workspace: PathBuf,
    /// 实验特性开关的覆盖表（快照、往外写那两条要看它）。
    pub flags: Vec<(String, bool)>,
    /// 这条会话的档位。plan 的只读闸与"auto 不许往外写"都读它。
    pub mode: String,
    pub sid: String,
    /// 这一轮的名字。空 = 不在任何一轮里（CLI 单发、测试）⇒ 不给检查点记账。
    pub run_id: String,
    pub todos: std::sync::Mutex<Vec<(String, String)>>,
}

pub struct Outcome {
    pub text: String,
    pub ok: bool,
    /// 渲染意图 `generic|terminal|diff`（`core/ToolResult.kt`），**不是工具名**。
    /// 前端按它挑卡；只有 `diff` 那一档会挂上 `−N 行 / +M 行` 的统计。
    pub card: &'static str,
    /// 行级 diff：只给界面与落盘，**不进发给模型的那段内容**（几百行 token 的浪费）。
    pub diff: String,
}

impl Outcome {
    fn ok(text: String) -> Self {
        Self { text, ok: true, card: "generic", diff: String::new() }
    }
    fn fail(text: String) -> Self {
        Self { text, ok: false, card: "generic", diff: String::new() }
    }
    /// 写改类工具的成功结果：卡片按 diff 渲染。
    fn edited(text: String, diff: String) -> Self {
        Self { text, ok: true, card: "diff", diff }
    }
}

impl Ctx {
    /// 测试/单发入口：只有工作区，其余取空 —— 空 `run_id` 的语义就是"不记账"。
    /// 生产路径一律走 `for_run`（档位、旗标、轮次都得给齐），所以它只存在于测试里。
    #[cfg(test)]
    pub fn new(workspace: PathBuf) -> Self {
        Self {
            flags: Vec::new(),
            home: workspace.clone(),
            workspace,
            mode: String::new(),
            sid: String::new(),
            run_id: String::new(),
            todos: std::sync::Mutex::new(vec![]),
        }
    }

    /// 生产入口：一轮一个 ctx，档位/旗标/轮次都在建它的时候交齐。
    pub fn for_run(
        home: &Path,
        workspace: PathBuf,
        flags: Vec<(String, bool)>,
        mode: &str,
        sid: &str,
        run_id: &str,
    ) -> Self {
        Self {
            home: home.to_path_buf(),
            workspace,
            flags,
            mode: mode.to_string(),
            sid: sid.to_string(),
            run_id: run_id.to_string(),
            todos: std::sync::Mutex::new(vec![]),
        }
    }

    /**
     * 逐块切分的基线 = 这个文件**现在**的内容。拿不到（计划模式、太大、读不动）就返回 None，
     * 意思是"这次不摆勾选框，按整条审批"；`Some("")` 表示新文件。
     *
     * 为什么要在弹卡**之前**读：勾掉的那几块要落回原样，就得先有原样可比。
     * `plan` 档先挡掉：只读档根本不会弹卡，白读一遍大文件。
     */
    pub fn diff_base(&self, target: &Path) -> Option<String> {
        if self.mode == "plan" {
            return None;
        }
        if !target.is_file() {
            return Some(String::new());
        }
        if target.metadata().map(|m| m.len() > HUNKS_MAX_BYTES).unwrap_or(true) {
            return None;
        }
        fs::read_to_string(target).ok()
    }

    /**
     * 改之前留一份，并**回给检查点账本**。
     *
     * 返回快照文件（没快照就返回 None）不是为了好看：`/rewind` 要区分
     * "这轮改坏了它"（有快照 ⇒ 还原）与"这轮新建了它"（没快照 ⇒ 删掉）。
     * 开关关着时不留快照，也就没法回滚 —— 这时候如实返回 None，
     * 界面上那句"这一轮没登记改动"才是真话。
     */
    pub fn snapshot_before(&self, target: &Path) -> Option<PathBuf> {
        if !target.is_file() {
            checkpoints::note(&self.home, &self.run_id, &self.sid, &self.rel(target), None);
            return None;
        }
        if !crate::flags::enabled(crate::flags::Flag::SnapshotBeforeWrite, &self.flags) {
            checkpoints::note(&self.home, &self.run_id, &self.sid, &self.rel(target), None);
            return None;
        }
        let dir = checkpoints::snap_dir(&self.workspace);
        let _ = fs::create_dir_all(&dir);
        checkpoints::exclude_from_git(&self.workspace, &[".haoai-snap", TOOL_OUTPUT_DIR]);
        // 同一毫秒里改同一个文件两次是真会发生的（一轮里连续两个 edit）。
        // 名字撞车会把**最早**那份盖掉，而回滚要的恰好就是最早那份，所以找第一个空位。
        let key = format!("{}-{}", crate::engine::now_ms(), checkpoints::key_of(&self.workspace, target));
        let snap = checkpoints::free_name(&dir, &key);
        match fs::copy(&target, &snap) {
            Ok(_bytes) => {
                checkpoints::note(&self.home, &self.run_id, &self.sid, &self.rel(target), Some(&snap));
                Some(snap)
            }
            Err(_) => None,
        }
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

    /// `ToolCtx.outside`：路径撞在工作区外面没有。
    /// 判据是 `canonicalPath.startsWith(workspace.canonicalPath + separator)` ——
    /// 少了那个分隔符，`G:\ws2` 会被算成 `G:\ws` 里面，那种误判正好是"往外面写"能漏过去的方式。
    pub fn outside(&self, f: &Path) -> bool {
        let ws = canon_str(&self.workspace);
        let abs = canon_str(f);
        !(abs.starts_with(&format!("{ws}\\")) || abs.starts_with(&format!("{ws}/")))
    }
}

pub(crate) fn canon_str(p: &Path) -> String {
    match fs::canonicalize(p) {
        // Windows 的 canonicalize 会加 \\?\ 前缀，Kotlin 的 canonicalPath 没有
        Ok(c) => c.display().to_string().trim_start_matches(r"\\?\").to_string(),
        Err(_) => lexical(p),
    }
}

/*
 * 路径**还不存在**时按字面规范化。这不是可选的优化：Rust 的 `canonicalize` 要求文件已存在，
 * 而 Java 的 `File.getCanonicalPath` 不要求 —— 它把已存在的最近祖先规范化，剩下的按字面接上
 * （并消掉 `.`/`..`）。
 *
 * 少了这一半，`rel()` 会拿"正斜杠的原始路径"去比"反斜杠的已存在工作区"，前缀对不上，
 * 于是 `outside()` 把**工作区里新建的文件**判成"在工作区之外"：auto 档当场拒掉一次完全
 * 合法的写，ask 档的审批卡上多一句"（在工作区之外）"的谎。
 * 而"写一个还不存在的文件"恰恰是 agent 最常做的事。（这条是被 e2e 的
 * `auto_mode_write_lands_…` 抓出来的，不是想出来的。）
 */
fn lexical(p: &Path) -> String {
    let abs = if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")).join(p)
    };
    // 往上走到第一个真实存在的祖先，用系统给的答案（顺带解掉符号链接与盘符大小写），
    // 中间那些还不存在的段按字面接回去
    let mut tail: Vec<String> = Vec::new();
    let mut cur: &Path = abs.as_path();
    loop {
        if let Ok(c) = fs::canonicalize(cur) {
            let base = c
                .display()
                .to_string()
                .trim_start_matches(r"\\?\")
                .trim_end_matches(['\\', '/'])
                .to_string();
            let mut out = base;
            for seg in tail.iter().rev() {
                out.push('\\');
                out.push_str(seg);
            }
            return out;
        }
        match cur.parent() {
            Some(parent) => {
                if let Some(n) = cur.file_name() {
                    tail.push(n.to_string_lossy().to_string());
                }
                cur = parent;
            }
            // 一个祖先都不存在（盘没挂载）：整条按字面走
            None => return lexical_abs(&abs),
        }
    }
}

/// 纯字面的绝对路径规范化：消 `.`、按 `..` 退段、分隔符统一成 `\`。
fn lexical_abs(p: &Path) -> String {
    use std::path::Component;
    let mut head = String::new();
    let mut segs: Vec<String> = Vec::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => {
                segs.pop();
            }
            Component::Prefix(x) => head.push_str(&x.as_os_str().to_string_lossy()),
            Component::RootDir => head.push('\\'),
            Component::Normal(n) => segs.push(n.to_string_lossy().to_string()),
        }
    }
    if head.is_empty() {
        head.push('\\');
    }
    let body = segs.join("\\");
    if head.ends_with('\\') || body.is_empty() {
        format!("{head}{body}")
    } else {
        format!("{head}\\{body}")
    }
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
///
/// **不**把 `\` 归一成 `/`（这里曾经归一过，是对齐 Kotlin 时改掉的一处）：
/// `globToRegex` 的 `REGEX_META` 里就有反斜杠，Kotlin 会把它转义成"字面反斜杠"，
/// 而匹配对象 `ctx.rel(f)` 永远是正斜杠 —— 于是 Windows 写法的 pattern 在 Kotlin 侧
/// 就是匹配不上。看着像 bug，但**两端必须一样错**：悄悄让 Rust 更宽容，同一个 pattern
/// 就会在一边给出文件列表、另一边"无匹配"，那种差异比一致的难用更危险。
/// 权限规则（`Policies.matchesSubject`）用的也是这一个函数，不能被两种口径共用。
pub fn glob_to_regex(glob: &str) -> Result<Regex, String> {
    let g = glob.to_string();
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

/// `Tool.bool`：比的是 primitive 的**字符串形态** == "true"。
/// 所以布尔 true 算真、字符串 "true" 也算真、数字 1 不算 —— 与 Kotlin 同一口径，
/// 否则模型给 `"all":"true"` 这种写法时两端行为不一样。
fn arg_bool(args: &serde_json::Value, key: &str) -> bool {
    match args.get(key) {
        Some(serde_json::Value::Bool(b)) => *b,
        Some(serde_json::Value::String(s)) => s == "true",
        _ => false,
    }
}

/// `WriteTool`：新建或整篇覆盖。
///
/// 两处顺序是有理由的：**基线要在弹卡之前拿到**（勾掉的那几块要落回原样就得先有原样可比，
/// 代价是自动档也白读一次文件 —— 换回来的是"夜里那条高危卡也能逐块挑"）；
/// **快照要在写之前留**，写坏了才有东西可回。
fn write_tool(app: &crate::App, ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(path) = arg_str(args, "path") else { return Outcome::fail("write 缺少 path".into()) };
    let Some(content) = arg_str(args, "content") else { return Outcome::fail("write 缺少 content".into()) };
    let f = ctx.resolve(&path);
    let rel = ctx.rel(&f);
    let base = ctx.diff_base(&f);
    let detail = || format!("新建或覆盖，共 {} 字符", utf16_len(&content));
    if let Some(why) = crate::guard::guard(app, ctx, "write", &rel, &format!("写入文件 {rel}"), &detail, true) {
        return Outcome::fail(why);
    }
    let before = match &base {
        Some(b) => b.clone(),
        None => fs::read_to_string(&f).unwrap_or_default(),
    };
    // 逐块勾选（HunkPlan）还没搬：plan 恒为 None ⇒ 整条批准 ⇒ 写的就是模型给的内容
    let to_write = content.clone();
    let _ = ctx.snapshot_before(&f);
    if let Some(p) = f.parent() {
        let _ = fs::create_dir_all(p);
    }
    if let Err(e) = fs::write(&f, to_write.as_bytes()) {
        return Outcome::fail(format!("写入失败：{e}"));
    }
    let n = crate::diff::kotlin_lines(&to_write).len();
    Outcome::edited(
        format!("已写入 {rel}（{} 字符 / {n} 行）", utf16_len(&to_write)),
        crate::diff::unified(&rel, &before, &to_write, 80),
    )
}

/// `EditTool`：精确替换。
///
/// **匹配检查挪到审批之前**：原来是人批完五分钟、写到一半才发现 `old_string` 根本没找到 ——
/// 那张卡白等了。先问"能不能做"再问"让不让做"才是省人的顺序。
fn edit_tool(app: &crate::App, ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(path) = arg_str(args, "path") else { return Outcome::fail("edit 缺少 path".into()) };
    let Some(old) = arg_str(args, "old_string") else { return Outcome::fail("edit 缺少 old_string".into()) };
    let Some(new) = arg_str(args, "new_string") else { return Outcome::fail("edit 缺少 new_string".into()) };
    let all = arg_bool(args, "all");
    let f = ctx.resolve(&path);
    let rel = ctx.rel(&f);
    if !f.is_file() {
        return Outcome::fail(format!("文件不存在：{rel}"));
    }
    let Some(text) = fs::read_to_string(&f).ok() else {
        return Outcome::fail(format!("读不到 {rel}，没改任何东西"));
    };
    let hits = text.split(old.as_str()).count() - 1;
    if hits == 0 {
        return Outcome::fail(
            "没找到要替换的内容。先用 read 看清当前文本（注意缩进与换行是否一致）。".to_string(),
        );
    }
    if hits > 1 && !all {
        return Outcome::fail(format!("匹配到 {hits} 处，不唯一。扩大 old_string 的上下文，或传 all=true。"));
    }
    let updated = if hits == 1 {
        text.replacen(&old, &new, 1)
    } else {
        text.replace(&old, &new)
    };
    let detail = || {
        format!(
            "{} → {} 字符（匹配 {hits} 处）",
            utf16_len(&old),
            utf16_len(&new)
        )
    };
    if let Some(why) = crate::guard::guard(app, ctx, "write", &rel, &format!("编辑 {rel}"), &detail, true) {
        return Outcome::fail(why);
    }
    let _ = ctx.snapshot_before(&f);
    if let Err(e) = fs::write(&f, updated.as_bytes()) {
        return Outcome::fail(format!("编辑失败：{e}"));
    }
    Outcome::edited(
        format!("已编辑 {rel}（替换 {} 处）", if all { hits } else { 1 }),
        crate::diff::unified(&rel, &text, &updated, 80),
    )
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

/// `ShellTool`：跑一条命令，回 `exit=N` + 合并的输出。
///
/// `card="terminal"` —— 界面按它选那张等宽、可展开的卡；写类才是 `diff`。
/// **注意退出码非零不算 error**：命令自己失败是模型要看的事实，不是工具坏了。
/// 只有"跑不起来/被拒/超时"才回 error=true。
fn shell_tool(app: &crate::App, ctx: &Ctx, args: &serde_json::Value) -> Outcome {
    let Some(command) = arg_str(args, "command") else {
        return Outcome::fail("shell 缺少 command".into());
    };
    let shell = arg_str(args, "shell").unwrap_or_else(|| "pwsh".to_string()).to_lowercase();
    let timeout = arg_int(args, "timeout").unwrap_or(180).clamp(1, 1800);
    let cwd = match arg_str(args, "cwd") {
        Some(p) => ctx.resolve(&p),
        None => ctx.workspace.clone(),
    };
    // 先确认这台机器上有这把 shell，再问用户要不要跑 —— 找不到根本不该弹卡
    let Some(l) = crate::shell::launcher_for(&shell) else {
        return Outcome::fail(format!("这台机器上找不到 {shell}，换一种 shell 或给绝对路径"));
    };
    let title = format!("执行命令（{shell}）");
    let detail = || command.clone();
    if let Some(why) = crate::guard::guard(app, ctx, "shell", &command, &title, &detail, true) {
        return Outcome::fail(why);
    }
    match crate::shell::run(&l, &command, &cwd, &ctx.workspace, timeout) {
        Err(e) => Outcome::fail(e),
        Ok(r) => match r.exit {
            None => Outcome {
                text: format!("超时 {timeout}s 已终止。已输出：\n{}", text_middle(&r.out, 4000)),
                ok: false,
                card: "terminal",
                diff: String::new(),
            },
            Some(code) => Outcome {
                text: format!("exit={code}\n---\n{}", r.out),
                ok: true,
                card: "terminal",
                diff: String::new(),
            },
        },
    }
}

/// `TextCap.middle`：超长的输出留头 65%、留尾 25%，中间报一句省略了多少**字符**。
/// 头部给的多是因为命令失败的原因通常在最前面，尾部给一点是因为最终状态在最后一行。
fn text_middle(text: &str, max: usize) -> String {
    let len = utf16_len(text);
    if len <= max {
        return text.to_string();
    }
    let head = (max as f64 * 0.65) as usize;
    let tail = (max as f64 * 0.25) as usize;
    let omitted = len.saturating_sub(head + tail);
    format!(
        "{}\n…［中间省略约 {omitted} 字符］…\n{}",
        utf16_take(text, head),
        utf16_take_last(text, tail)
    )
}

pub fn dispatch(
    app: &crate::App,
    ctx: &Ctx,
    name: &str,
    args: &serde_json::Value,
) -> Option<Outcome> {
    match name {
        "read" => Some(read_tool(ctx, args)),
        "glob" => Some(glob_tool(ctx, args)),
        "grep" => Some(grep_tool(ctx, args)),
        "todo" => Some(todo_tool(ctx, args)),
        "write" => Some(write_tool(app, ctx, args)),
        "edit" => Some(edit_tool(app, ctx, args)),
        "shell" => Some(shell_tool(app, ctx, args)),
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

    /// 带旗标与轮次名字的 ctx：快照与检查点两件事都要看它。
    fn run_ctx(tag: &str, flags: Vec<(String, bool)>, mode: &str) -> (PathBuf, Ctx) {
        let root = sandbox(tag);
        let ctx = Ctx::for_run(
            &root.join("home"),
            root.clone(),
            flags,
            mode,
            "sx",
            "r123",
        );
        (root, ctx)
    }

    fn on(k: &str) -> (String, bool) {
        (k.to_string(), true)
    }
    fn off(k: &str) -> (String, bool) {
        (k.to_string(), false)
    }

    /// 写一个**还不存在**的文件是 agent 最常做的事，这时候 `rel`/`outside` 必须已经正确：
    /// Rust 的 canonicalize 对不存在的路径直接返回 Err，早先没兜这一步，于是工作区里的
    /// 新建文件被判成"在工作区之外"，auto 档当场拒掉一次合法的新建。
    #[test]
    fn a_file_that_does_not_exist_yet_is_still_inside_the_workspace() {
        let root = sandbox("notin");
        let ctx = Ctx::new(root.clone());
        let fresh = ctx.resolve("src/deep/new.md");
        assert!(!fresh.exists(), "这条测的就是「还没生出来」那一段");
        assert_eq!(ctx.rel(&fresh), "src/deep/new.md", "新文件也要给出相对路径");
        assert!(!ctx.outside(&fresh), "工作区里的新建文件不算在外面");
        // 外面的还是算外面，不能被这次修复带松
        assert!(ctx.outside(&Path::new("Q:\\somewhere\\a.md")));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn snapshot_before_keeps_a_copy_and_registers_it() {
        let (root, ctx) = run_ctx("snap", vec![], "ask");
        let target = ctx.resolve("src/a.rs");
        let snap = ctx.snapshot_before(&target).expect("默认开着快照，应留下一份");
        assert!(snap.is_file());
        assert_eq!(fs::read_to_string(&snap).unwrap(), "line1\nline2\nline3\n");
        // 键里带相对路径：src/a.rs → src__a.rs，否则 docs/a.rs 会和它撞成同一份
        assert!(snap.file_name().unwrap().to_string_lossy().contains("src__a.rs"), "{snap:?}");
        // 账本里那一行要说清"动了哪个文件、快照在哪"
        let ledger = fs::read_to_string(root.join("home/checkpoints.jsonl")).unwrap();
        assert!(ledger.contains(r#""path":"src/a.rs""#), "{ledger}");
        assert!(ledger.contains("snap"), "{ledger}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 连改两次同一个文件，**两份快照都得活着**（撞名分支本身在 `checkpoints::free_name`
    /// 那边用固定 key 测，这里不能依赖"两次调用撞在同一毫秒"）。
    #[test]
    fn two_snapshots_of_the_same_file_do_not_overwrite_each_other() {
        let (root, ctx) = run_ctx("snap2", vec![], "ask");
        let target = ctx.resolve("src/a.rs");
        let a = ctx.snapshot_before(&target).expect("第一份");
        fs::write(&target, "已经改过了\n").unwrap();
        let b = ctx.snapshot_before(&target).expect("第二份");
        assert_ne!(a, b, "两份快照撞成了同一个文件");
        assert_eq!(fs::read_to_string(&a).unwrap(), "line1\nline2\nline3\n", "最早那份必须还在");
        let ledger = fs::read_to_string(root.join("home/checkpoints.jsonl")).unwrap();
        assert_eq!(ledger.lines().filter(|l| !l.trim().is_empty()).count(), 2, "{ledger}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 开关关着 = 不留快照，也就没法回滚。这时候**如实返回 None**，
    /// 界面上那句"这一轮没登记改动"才是真话，而不是假装有一份其实没有。
    #[test]
    fn snapshot_is_skipped_and_still_registered_when_the_flag_is_off() {
        let (root, ctx) = run_ctx("snapoff", vec![off("snapshot_before_write")], "ask");
        let target = ctx.resolve("src/a.rs");
        assert!(ctx.snapshot_before(&target).is_none());
        assert!(!root.join(".haoai-snap").exists() || root.join(".haoai-snap").read_dir().unwrap().next().is_none());
        let ledger = fs::read_to_string(root.join("home/checkpoints.jsonl")).unwrap();
        assert!(ledger.contains(r#""snap":""#), "没快照也要记一笔（空 snap=这轮之前不存在/没留）：{ledger}");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_brand_new_file_gets_no_snapshot_but_is_still_noted() {
        let (root, ctx) = run_ctx("newfile", vec![on("snapshot_before_write")], "ask");
        let target = ctx.resolve("src/fresh.md");
        assert!(ctx.snapshot_before(&target).is_none(), "新建的文件没有「上一版」可留");
        let ledger = fs::read_to_string(root.join("home/checkpoints.jsonl")).unwrap();
        assert!(ledger.contains(r#""snap":""#), "{ledger}");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn diff_base_refuses_in_plan_mode_and_for_huge_files() {
        // plan 是只读档，根本不会弹卡 → 读基线是白读一遍大文件
        let (root1, ctx) = run_ctx("base", vec![], "plan");
        assert_eq!(ctx.diff_base(&ctx.resolve("src/a.rs")), None);
        drop(ctx);
        let _ = fs::remove_dir_all(&root1);

        let (root, ctx) = run_ctx("base2", vec![], "ask");
        assert_eq!(ctx.diff_base(&ctx.resolve("src/nope.rs")), Some(String::new()), "新文件=空基线");
        let big = ctx.resolve("src/big.txt");
        fs::write(&big, "x".repeat(500_000)).unwrap();
        assert_eq!(ctx.diff_base(&big), None, "超过 HUNKS_MAX_BYTES 不读进来");
        assert_eq!(ctx.diff_base(&ctx.resolve("src/a.rs")).unwrap(), "line1\nline2\nline3\n");
        let _ = fs::remove_dir_all(&root);
    }
}
