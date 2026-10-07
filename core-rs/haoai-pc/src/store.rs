use std::fs;
use std::path::{Path, PathBuf};

use serde_json::Value;

/// 盘上格式的唯一读取入口。默认值全部照抄 `Settings.kt` 与 `SessionIndex.kt`，
/// 因为 `/api/state` 与 `/api/sessions` 的字节要和 JVM 侧一致，缺字段时的兜底也算契约。
pub struct Store {
    pub home: PathBuf,
}

#[derive(Clone)]
pub struct Settings {
    pub provider_name: String,
    pub base_url: String,
    pub model: String,
    pub workspace: String,
    pub permission_mode: String,
    pub temperature: f64,
    pub max_tokens: i64,
    pub max_turns: i64,
    pub reasoning_effort: String,
    pub context_chars: i64,
    pub compact_trigger_chars: i64,
    pub tools_off: Vec<String>,
    /// `settings.json` 里 `flags` 对象的**覆盖值**（键是 `HaoFlag.key`）。
    /// 用 Vec 而不是 map：只需要按键查，且要保住文件里的书写顺序，不引进一套哈希。
    pub flags: Vec<(String, bool)>,
}

/// **不能 derive(Default)**：settings.json 整个缺失时，Kotlin 回落的是 `PcSettings`
/// 数据类的默认值（`permissionMode="ask"`、`contextChars=128_000`、
/// `compactTriggerChars=60_000`），不是空串和 0。
impl Default for Settings {
    fn default() -> Self {
        Self {
            provider_name: String::new(),
            base_url: String::new(),
            model: String::new(),
            workspace: String::new(),
            permission_mode: "ask".to_string(),
            temperature: 0.3,
            max_tokens: 4096,
            max_turns: 60,
            reasoning_effort: String::new(),
            context_chars: 128_000,
            compact_trigger_chars: 60_000,
            tools_off: Vec::new(),
            flags: Vec::new(),
        }
    }
}

/// Java 的 `File(String)` 在 Windows 上会把 `/` 归一成 `\`（反斜杠是系统分隔符，
/// 两个分隔符都接受、都存成 `\`）。Kotlin 侧 `Session.workspace` 是 `File`，
/// 所以这条归一发生在**读进来那一刻**，落盘时写的又是 `absolutePath` —— 两端都不归一的话，
/// 同一个正斜杠的 workspace 会分叉成"Rust 一直存正斜杠 / JVM 落盘后变反斜杠"。
pub fn norm_ws(s: &str) -> String {
    s.replace('/', "\\")
}

/// `PcSettings.workspaceFile()` 的等价物：**空或 `.` 用当前目录**，并且要 canonical。
///
/// 不是"顺手规范一下路径"：`/api/state` 在没有驻留引擎时回的是这一个值，
/// 而新建会话的工作区也取自这里。按原始字符串回的话，工作区没配过的状态根
/// 一端回空串、另一端回 `C:\Users\…`，界面上那个目录标签就是白的。
/// canonical 失败（目录不存在）时退到 absolute —— Kotlin 的 `getOrElse` 同样退。
pub fn workspace_file(workspace: &str) -> String {
    let t = ktrim(workspace);
    let mut p = if t.is_empty() || t == "." {
        std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
    } else {
        PathBuf::from(norm_ws(t))
    };
    // Java 的 `getAbsoluteFile()`：相对路径按当前目录补全，不做别的
    if !p.is_absolute() {
        if let Ok(cwd) = std::env::current_dir() {
            p = cwd.join(p);
        }
    }
    match p.canonicalize() {
        // Kotlin 的 `canonicalFile.absolutePath` 没有 `\\?\` 前缀，这里必须抹掉：
        // 它会被写进 `/api/state` 的 workspace，也是新建会话落到会话文件里的那个值
        Ok(c) => strip_verbatim(&c.to_string_lossy()),
        Err(_) => p.to_string_lossy().to_string(),
    }
}

impl Settings {
    /// `PcSettings.workspaceFile()`：界面上与新建会话都取这一个值。
    pub fn workspace_file(&self) -> String {
        workspace_file(&self.workspace)
    }
}

/// `Server.newSessionId` 的 id 形状：`pc` + nanoTime 的 16 进制前 8 位。
/// 低 32 位 4.3 秒就轮一圈，所以撞车要靠下面那层重试兜住 —— **不重试的后果是
/// 把另一条会话整份覆盖掉**，那是不可逆的。
fn fresh_id() -> String {
    let ns = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or_else(|_| crate::engine::now_ms());
    format!("pc{:08x}", ns & 0xFFFF_FFFF)
}

#[derive(Clone, Default)]
pub struct Meta {
    pub id: String,
    pub title: String,
    pub workspace: String,
    pub mode: String,
    pub updated: i64,
    pub messages: usize,
    pub pinned: bool,
    pub model: String,
    pub persona: String,
    pub role: String,
    pub preset: String,
    pub prompt: i64,
    pub completion: i64,
    pub tools_off: Vec<String>,
}

/// 会话文件里的一条消息。字段集与 `Engine.persist()` 写的完全一致：
/// `role/content` 恒有，其余**只在非空/大于 0 时才存在文件里**，所以读的时候都得兜底。
#[derive(Clone, Default)]
pub struct Msg {
    pub role: String,
    pub content: String,
    /// tool 消息回指哪一次调用（文件里叫 `tool_call_id`）
    pub call_id: String,
    pub name: String,
    pub reasoning: String,
    pub diff: String,
    pub note: String,
    pub notice: String,
    pub sub: String,
    pub images: Vec<String>,
    pub media: Vec<String>,
    pub pt: i64,
    pub ct: i64,
    pub ms: i64,
    /// (id, name, arguments)
    pub calls: Vec<(String, String, String)>,
}

#[derive(Clone, Default)]
pub struct Todo {
    pub text: String,
    pub status: String,
}

#[derive(Clone, Default)]
pub struct SessionFile {
    pub meta: Meta,
    pub msgs: Vec<Msg>,
    pub todos: Vec<Todo>,
    pub summary: Option<String>,
    pub compacted_through: i64,
}

/// Kotlin 的 `Char.isWhitespace()` / `String.trim()` 口径。
///
/// 实测表（`jshell --class-path <引擎 app 目录>/kotlin-stdlib-2.4.10.jar`，逐个码点比
/// `Character.isWhitespace` 与 `kotlin.text.CharsKt.isWhitespace`）。Kotlin 认的这些：
/// `09-0D  1C-20  A0  1680  2000-200A  2028  2029  202F  205F  3000`
/// 唯独**不认 U+0085（NEL）**；Java 那份则把 A0/2007/202F 三个不换行空格排除在外。
/// 换成 Rust 的话就是：Unicode 空白（`char::is_whitespace`）**加上** 0x1C–0x1F（那是 Cc，
/// Unicode 不当空白、Java/Kotlin 当）、**去掉** U+0085。
///
/// 这不是纸面上的讲究：改名那条路由上，标题开头是一个不换行空格
/// （从网页复制下来的一句提问常常就是这样）时 JVM 会削掉，而我最初按 Java 口径写的那版不削 ——
/// 于是两端落盘的是两个不同的标题。差分台架里那条用例就是这么抓出来的。
pub fn kotlin_ws(c: char) -> bool {
    if matches!(c, '\u{1c}'..='\u{1f}') {
        return true;
    }
    c != '\u{85}' && c.is_whitespace()
}

pub fn ktrim(s: &str) -> &str {
    let start = s.find(|c: char| !kotlin_ws(c)).unwrap_or(s.len());
    let end = s
        .rfind(|c: char| !kotlin_ws(c))
        .map(|i| i + s[i..].chars().next().map_or(0, |c| c.len_utf8()))
        .unwrap_or(start);
    &s[start..end]
}

/// 抹平 Windows 上 `fs::canonicalize` 多出来的 `\\?\` 前缀：Kotlin 的 `canonicalPath` 没有它。
/// 这个前缀会一路走进 `/api/state` 的 workspace、`rules.json` 的键（**两端共读的一份表**，
/// 键对不上就等于用户写的规则莫名其妙不再生效）以及边界判定的报错文案里。
pub fn strip_verbatim(p: &str) -> String {
    if let Some(t) = p.strip_prefix(r"\\?\UNC\") {
        return format!(r"\\{t}");
    }
    if let Some(t) = p.strip_prefix(r"\\?\") {
        return t.to_string();
    }
    p.to_string()
}

fn s(v: Option<&Value>) -> String {
    v.and_then(|x| x.as_str()).unwrap_or("").to_string()
}

fn i(v: Option<&Value>) -> i64 {
    // Kotlin 侧写的是 `?.jsonPrimitive?.content?.toLongOrNull()`：字符串和数字都当数读，
    // 读不到就回落默认值。这里同样两种都接。
    match v {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0),
        Some(Value::String(t)) => t.parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

fn bool_strict(v: Option<&Value>) -> bool {
    // `contentOrNull?.toBooleanStrictOrNull() == true`：只有字面 "true"/true 才算置顶
    match v {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(t)) => t == "true",
        _ => false,
    }
}

impl Store {
    pub fn new(home: PathBuf) -> Self {
        Self { home }
    }

    fn sessions_dir(&self) -> PathBuf {
        self.home.join("sessions")
    }

    pub fn settings(&self) -> Settings {
        let raw = match fs::read_to_string(self.home.join("settings.json")) {
            Ok(t) => t,
            Err(_) => return Settings::default(),
        };
        // Json { ignoreUnknownKeys = true }：解析失败时 Kotlin 走默认值，这里同样不炸
        let Ok(v) = serde_json::from_str::<Value>(&raw) else {
            return Settings::default();
        };
        Settings {
            provider_name: s(v.get("providerName")),
            base_url: s(v.get("baseUrl")),
            model: s(v.get("model")),
            workspace: s(v.get("workspace")),
            permission_mode: v
                .get("permissionMode")
                .and_then(|x| x.as_str())
                .unwrap_or("ask")
                .to_string(),
            temperature: match v.get("temperature") {
                Some(x) => x.as_f64().unwrap_or(0.3),
                None => 0.3,
            },
            max_tokens: match v.get("maxTokens") {
                Some(x) => i(Some(x)),
                None => 4096,
            },
            max_turns: match v.get("maxTurns") {
                Some(x) => i(Some(x)),
                None => 60,
            },
            reasoning_effort: s(v.get("reasoningEffort")),
            context_chars: match v.get("contextChars") {
                Some(x) => i(Some(x)),
                None => 128_000,
            },
            compact_trigger_chars: match v.get("compactTriggerChars") {
                Some(x) => i(Some(x)),
                None => 60_000,
            },
            tools_off: v
                .get("toolsOff")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|x| x.as_str()).map(String::from).collect())
                .unwrap_or_default(),
            // Kotlin 那边是 `v.jsonPrimitive.content == "true"`，比的是**字符串形态**：
            // 所以布尔 true 与字符串 "true" 都算开，而数字 1 不算。照这个口径来，
            // 不然同一份 settings.json 在两端会拨出不同的开关（手机端写的是哪种不好说）。
            flags: v
                .get("flags")
                .and_then(|x| x.as_object())
                .map(|o| {
                    o.iter()
                        .map(|(k, x)| {
                            (
                                k.clone(),
                                x.as_bool().unwrap_or_else(|| x.as_str() == Some("true")),
                            )
                        })
                        .collect()
                })
                .unwrap_or_default(),
        }
    }

    /// 等价于 `SessionIndex.list`：`pinned` 优先、其后按 `updated` 倒序。
    pub fn list(&self, limit: usize) -> Vec<Meta> {
        let mut all: Vec<Meta> = self.session_ids().into_iter().filter_map(|id| self.meta(&id)).collect();
        all.sort_by(|a, b| {
            b.pinned
                .cmp(&a.pinned)
                .then(b.updated.cmp(&a.updated))
        });
        all.truncate(limit);
        all
    }

    fn session_ids(&self) -> Vec<String> {
        let rd = match fs::read_dir(self.sessions_dir()) {
            Ok(r) => r,
            Err(_) => return vec![],
        };
        let mut ids = vec![];
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.starts_with("pc-") && name.ends_with(".json") {
                ids.push(name["pc-".len()..name.len() - ".json".len()].to_string());
            }
        }
        ids
    }

    pub fn file_for(&self, id: &str) -> PathBuf {
        self.sessions_dir().join(format!("pc-{id}.json"))
    }

    /// 等价于 `SessionIndex.read(f)`：**读不出就退避着重试五次**。
    /// 会话文件每回合都在重写，而 Windows 上"替换一个正被读的文件"未必成功
    /// （move 要对目标开 DELETE 访问，失败时只能退到 copy，那一瞬就读到半截 JSON）。
    /// 一次就放弃的代价是"那条会话从列表里凭空消失"——刚建的那条最容易中。
    fn read_meta(&self, path: &Path, fallback_id: &str) -> Option<Meta> {
        for attempt in 1..=5u64 {
            if let Some(m) = meta_of_file(path, fallback_id) {
                return Some(m);
            }
            if attempt < 5 {
                std::thread::sleep(std::time::Duration::from_millis(20 * attempt));
            }
        }
        None
    }

    /// 只读索引需要的那几个字段（`/api/sessions` 用），不解析消息体。
    pub fn meta(&self, id: &str) -> Option<Meta> {
        self.read_meta(&self.file_for(id), id)
    }

    // ---- 会话文件级的增删改（`SessionIndex` 那一页） ----

    /// 读整份、只动一个键、再整体写回。`runCatching{…}.getOrDefault(false)`：
    /// 读不出、解析不出、写不进去都算失败，由调用方给前端 404。
    ///
    /// 写回用的是 serde 的紧凑序列化，与 JVM 那边 `JsonObject.toString()` **实测同一条字节**
    /// （把一串含 `"` `\` `/` `<` `>` `&`、0x01/0x02/0x07/0x08/0x0B/0x0C/0x1B/0x1C/0x1F、
    /// 0x7F/0x80/NBSP/U+2028/U+2029/U+205F/U+3000、emoji 与汉字的串喂进
    /// `jshell --class-path <引擎 app 目录>/kotlinx-serialization-json-jvm-1.9.0.jar`
    /// 再 `parseToJsonElement(...).toString()`，输出与标准序列化逐字节相同：
    /// 短转义只用 `\b \t \n \f \r`，其余控制字符是小写 `\u00XX`，非 ASCII 一律原样）。
    /// 唯一的口径差是**浮点字面量**：kotlinx 保留源文（`1.0E7` 原样写回），serde 会重排。
    /// 会话文件里只有整数（updated / pt / ct / ms / tokens），所以这条路碰不到；
    /// 真要引入浮点字段，就得在这里换成一保留原文字面量的渲染器。
    fn patch<F: FnOnce(&mut serde_json::Map<String, Value>)>(&self, path: &Path, edit: F) -> bool {
        let Ok(raw) = fs::read_to_string(path) else { return false };
        let Ok(Value::Object(mut o)) = serde_json::from_str::<Value>(&raw) else { return false };
        edit(&mut o);
        fs::write(path, Value::Object(o).to_string()).is_ok()
    }

    /// `SessionIndex.rename`：先 trim（Java 口径）→ 换行变空格 → 取 60 个 UTF-16 单元。
    /// 空标题是**非法输入**，返回 false 让路由给 404，而不是把会话改成空白。
    pub fn rename(&self, id: &str, new_title: &str) -> bool {
        let path = self.file_for(id);
        if !path.is_file() {
            return false;
        }
        let clean: String = crate::utf16::utf16_take(&ktrim(new_title).replace('\n', " "), 60).to_string();
        if clean.is_empty() {
            return false;
        }
        self.patch(&path, |o| {
            o.insert("title".into(), Value::String(clean));
        })
    }

    /// `SessionIndex.pin`：和改名一样只动一个字段，其余原样写回。
    pub fn pin(&self, id: &str, on: bool) -> bool {
        let path = self.file_for(id);
        if !path.is_file() {
            return false;
        }
        self.patch(&path, |o| {
            o.insert("pinned".into(), Value::Bool(on));
        })
    }

    pub fn trash_dir(&self) -> PathBuf {
        self.sessions_dir().join(".trash")
    }

    /// 删会话 —— 其实是**移进回收目录**，不是就地抹掉。理由照 `SessionIndex.delete`：
    /// 会话文件里是用户与 agent 的全部过程记录，误删一次的成本远高于多留一份垃圾；
    /// 而 `.trash/` 在 `sessions/` 下面，`list()` 的前缀过滤看不到它，界面上就是"删掉了"。
    pub fn delete(&self, id: &str) -> bool {
        let f = self.file_for(id);
        if !f.is_file() {
            return false;
        }
        let _ = fs::create_dir_all(self.trash_dir());
        let name = match f.file_name() {
            Some(n) => n.to_string_lossy().to_string(),
            None => return false,
        };
        let dest = self.trash_dir().join(format!("{}-{}", crate::engine::now_ms(), name));
        if fs::rename(&f, &dest).is_ok() {
            return true;
        }
        // 搬不动就退回"复制一份再删原件"（跨卷、目标被占用时 Java 那边也是这个次序）
        if fs::copy(&f, &dest).is_err() {
            return false;
        }
        fs::remove_file(&f).is_ok()
    }

    /// 回收站里有什么。文件名是 `<删除时间>-pc-<id>.json`，标题/条数从文件内容里读。
    ///
    /// 为什么界面上要看得见：删除是移进 `.trash` 而不是抹掉，但**没有入口的"可恢复"
    /// 等于没有** —— 用户删错一条就只能去开文件管理器。
    pub fn list_trash(&self, limit: usize) -> Vec<TrashRow> {
        let files: Vec<PathBuf> = match fs::read_dir(self.trash_dir()) {
            Ok(rd) => rd
                .flatten()
                .map(|e| e.path())
                .filter(|p| {
                    p.is_file() && p.file_name().unwrap_or_default().to_string_lossy().ends_with(".json")
                })
                .collect(),
            Err(_) => return vec![],
        };
        let mut rows: Vec<TrashRow> = files
            .iter()
            .filter_map(|p| {
                let name = p.file_name()?.to_string_lossy().to_string();
                let meta = self.read_meta(p, "")?;
                Some(TrashRow { name, bytes: file_len(p), meta })
            })
            .collect();
        rows.sort_by(|a, b| b.meta.updated.cmp(&a.meta.updated));
        rows.truncate(limit);
        rows
    }

    /// 把一条会话从回收站放回原位，返回它的 id；放不回去说明原因。
    ///
    /// `name` 是**客户端给的**，所以只认回收站里真实存在的那个文件名：
    /// 先按 canonical 判还在不在 `.trash` 目录内，否则 `../../settings.json` 这种
    /// 名字能把任意文件搬进 sessions/ 目录。
    pub fn untrash(&self, name: &str) -> Result<String, String> {
        let dir = self.trash_dir();
        let src = dir.join(name);
        let inside = src.is_file()
            && match (src.canonicalize(), dir.canonicalize()) {
                (Ok(s), Ok(d)) => s.starts_with(&d),
                _ => false,
            };
        if !inside {
            return Err("回收站里没有这个文件".to_string());
        }
        let meta = self.read_meta(&src, "").ok_or("这个文件读不出会话内容")?;
        let dest = self.file_for(&meta.id);
        if dest.exists() {
            return Err("已经有一条同 id 的会话在外面，放回会覆盖它".to_string());
        }
        if fs::rename(&src, &dest).is_ok() {
            return Ok(meta.id);
        }
        // Kotlin 那边的退路是 copy(overwrite=false) + delete，搬不动就报"搬不动"
        if fs::copy(&src, &dest).is_err() {
            return Err("搬不动".to_string());
        }
        let _ = fs::remove_file(&src);
        Ok(meta.id)
    }

    /// 彻底删掉回收站里的某一条（用户点了"清空"才走到这）。
    pub fn purge(&self, name: &str) -> bool {
        let dir = match self.trash_dir().canonicalize() {
            Ok(d) => d,
            Err(_) => return false,
        };
        let f = dir.join(name);
        if !f.is_file() {
            return false;
        }
        match f.canonicalize() {
            Ok(c) if c.starts_with(&dir) => fs::remove_file(&c).is_ok(),
            _ => false,
        }
    }

    /// 等价于 `SessionIndex.restore` 里读历史那一段：引擎未跑时，历史就是文件里的原样。
    /**
     * 造一条新会话并**立刻落盘**（对应 `Server.newSessionId` 末尾那句 `e.persistNow()`）。
     *
     * 键序照 `Engine.persist()` 抄：id → title → workspace → mode → persona → role →
     * [preset] → model → toolsOff → [runState] → updated → [cites] → promptTokens →
     * completionTokens → [summary] → [compactedThrough] → todos → messages。
     * 空的那些（preset / cites / summary / runState）Kotlin 是**不写键**的，
     * 这里同样不写 —— 会话文件是两端共读的，键多一个少一个都会被下一个读的人看见。
     */
    pub fn create(&self, workspace: &str, mode: &str, settings: &Settings) -> String {
        let mut id = fresh_id();
        // id 是 nanoTime 派生的，撞上就要换一个；**不换的后果是把别人的会话整份盖掉**。
        let mut guard = 0;
        while self.file_for(&id).exists() && guard < 8 {
            id = format!("{id}-{guard}");
            guard += 1;
        }
        let now = crate::engine::now_ms();
        let mut o = serde_json::Map::new();
        o.insert("id".into(), Value::String(id.clone()));
        o.insert("title".into(), Value::String("新会话".to_string()));
        o.insert("workspace".into(), Value::String(norm_ws(workspace)));
        o.insert("mode".into(), Value::String(mode.to_string()));
        o.insert("persona".into(), Value::String(String::new()));
        o.insert("role".into(), Value::String(String::new()));
        o.insert("model".into(), Value::String(settings.model.clone()));
        o.insert(
            "toolsOff".into(),
            Value::Array(settings.tools_off.iter().map(|t| Value::String(t.clone())).collect()),
        );
        o.insert("updated".into(), Value::Number(now.into()));
        o.insert("promptTokens".into(), Value::Number(0.into()));
        o.insert("completionTokens".into(), Value::Number(0.into()));
        o.insert("todos".into(), Value::Array(vec![]));
        o.insert("messages".into(), Value::Array(vec![]));
        let _ = fs::create_dir_all(self.sessions_dir());
        let _ = fs::write(self.file_for(&id), Value::Object(o).to_string());
        id
    }

    pub fn restore(&self, id: &str) -> Option<SessionFile> {
        let path = self.file_for(id);
        let raw = fs::read_to_string(&path).ok()?;
        let v = serde_json::from_str::<Value>(&raw).ok()?;
        let mut sf = SessionFile { meta: self.meta(id)?, msgs: vec![], todos: vec![], summary: None, compacted_through: 0 };
        // `Session.workspace` 在 Kotlin 那边是 `File(String)` —— Java 的 File 构造器
        // 在 Windows 上就把 `/` 归一成 `\`，于是 `/api/state`、系统提示的「工作区」行、
        // 以及落盘时写的 `absolutePath` 三处都是反斜杠。不归一就与 JVM 分叉：
        // 正斜杠进、正斜杠出，而那边是正斜杠进、反斜杠出。
        sf.meta.workspace = norm_ws(&sf.meta.workspace);
        if let Some(arr) = v.get("messages").and_then(|x| x.as_array()) {
            for m in arr {
                // Kotlin: `m["content"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed`
                // —— 没有 content 字段的条目整条跳过，不是当空串处理
                let Some(body) = m.get("content").and_then(|x| x.as_str()) else { continue };
                let mut msg = Msg {
                    role: m.get("role").and_then(|x| x.as_str()).unwrap_or("user").to_string(),
                    content: body.to_string(),
                    call_id: s(m.get("tool_call_id")),
                    name: s(m.get("name")),
                    reasoning: s(m.get("reasoning")),
                    diff: s(m.get("diff")),
                    note: s(m.get("note")),
                    notice: s(m.get("notice")),
                    sub: s(m.get("sub")),
                    images: str_list(m.get("images")),
                    media: str_list(m.get("media")),
                    pt: i(m.get("pt")),
                    ct: i(m.get("ct")),
                    ms: i(m.get("ms")),
                    calls: vec![],
                };
                if let Some(tc) = m.get("tool_calls").and_then(|x| x.as_array()) {
                    for c in tc {
                        let f = c.get("function");
                        msg.calls.push((
                            s(c.get("id")),
                            s(f.and_then(|x| x.get("name"))),
                            s(f.and_then(|x| x.get("arguments"))),
                        ));
                    }
                }
                sf.msgs.push(msg);
            }
        }
        if let Some(arr) = v.get("todos").and_then(|x| x.as_array()) {
            for t in arr {
                sf.todos.push(Todo {
                    text: s(t.get("text")),
                    status: s(t.get("status")),
                });
            }
        }
        sf.summary = v.get("summary").and_then(|x| x.as_str()).map(String::from);
        sf.compacted_through = i(v.get("compactedThrough"));
        Some(sf)
    }
}

/// 回收站里的一条（`name` 是文件名，标题/条数从内容里读，`bytes` 给界面上报大小）。
pub struct TrashRow {
    pub name: String,
    pub bytes: u64,
    pub meta: Meta,
}

fn file_len(p: &Path) -> u64 {
    fs::metadata(p).map(|m| m.len()).unwrap_or(0)
}

/// 从**任意路径**读会话头（`SessionIndex.metaOf` 的等价物）。每个字段缺省时都有一个兜底值，
/// 因为会话文件是两端共读的，缺字段是常态而不是错误。
fn meta_of_file(path: &Path, fallback_id: &str) -> Option<Meta> {
    let raw = fs::read_to_string(path).ok()?;
    let v = serde_json::from_str::<Value>(&raw).ok()?;
    let stem = path.file_name()?.to_string_lossy().to_string();
    // 文件里没有 id 时按文件名补（`removePrefix("pc-").removeSuffix(".json")`）
    let fallback = if fallback_id.is_empty() {
        stem.strip_prefix("pc-").unwrap_or(&stem).strip_suffix(".json").unwrap_or("").to_string()
    } else {
        fallback_id.to_string()
    };
    // `content?.toLongOrNull() ?: f.lastModified()`：字符串形态的数字与真数都当数读，
    // **读不出数的（"abc"、null、对象）一律回落文件时间**，不是当 0 处理。
    let updated = match v.get("updated") {
        Some(Value::Number(n)) => n.as_i64().unwrap_or_else(|| file_mtime_ms(path)),
        Some(Value::String(t)) => t.parse::<i64>().unwrap_or_else(|_| file_mtime_ms(path)),
        _ => file_mtime_ms(path),
    };
    Some(Meta {
        id: match v.get("id").and_then(|x| x.as_str()) {
            Some(t) if !t.is_empty() => t.to_string(),
            _ => fallback,
        },
        title: match v.get("title").and_then(|x| x.as_str()) {
            Some(t) => t.to_string(),
            None => "（无标题）".to_string(),
        },
        workspace: s(v.get("workspace")),
        mode: v.get("mode").and_then(|x| x.as_str()).unwrap_or("ask").to_string(),
        updated,
        messages: v.get("messages").and_then(|x| x.as_array()).map(|a| a.len()).unwrap_or(0),
        pinned: bool_strict(v.get("pinned")),
        model: s(v.get("model")),
        persona: s(v.get("persona")),
        role: s(v.get("role")),
        preset: s(v.get("preset")),
        prompt: i(v.get("promptTokens")),
        completion: i(v.get("completionTokens")),
        tools_off: v
            .get("toolsOff")
            .and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
            .unwrap_or_default(),
    })
}

fn str_list(v: Option<&Value>) -> Vec<String> {
    v.and_then(|x| x.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
        .unwrap_or_default()
}

fn file_mtime_ms(p: &Path) -> i64 {
    fs::metadata(p)
        .and_then(|m| m.modified())
        .map(|t| {
            t.duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
        })
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// workspace 的分隔符必须**照 Java 的 `File` 归一**：
    /// `Session.workspace` 在 Kotlin 是 `File`，构造时就把 `/` 换成 `\`，
    /// 落盘写的又是 `absolutePath`。不归一就会出现"Rust 一直存正斜杠 / JVM 落一次盘
    /// 变反斜杠"——同一个文件在两端读出不同的 workspace。
    ///
    /// 同时**会话列表**（`meta()`）得保持原样：`SessionIndex.kt` 那边把 workspace
    /// 当纯字符串解析，JVM 落盘之前列表里就是原始写法。这一处不对称是照着两端抄的。
    #[test]
    fn restore_normalizes_the_workspace_but_the_session_list_does_not() {
        let root = std::env::temp_dir().join(format!("haoai-ws-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("sessions")).unwrap();
        let path = root.join("sessions/pc-wsfix.json");
        fs::write(
            &path,
            r#"{"id":"wsfix","title":"t","workspace":"G:/x/y","mode":"ask","messages":[]}"#,
        )
        .unwrap();

        let store = Store::new(root.clone());
        let listed = store.meta("wsfix").expect("meta 该读得到");
        assert_eq!(listed.workspace, "G:/x/y", "会话列表读的是原始字符串（SessionIndex 就是纯 String）");
        let restored = store.restore("wsfix").expect("restore 该读得到");
        assert_eq!(restored.meta.workspace, r"G:\x\y", "活的会话按 File 的规矩归一");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn norm_ws_only_swaps_separators_and_never_touches_a_path_that_is_already_windows_ish() {
        assert_eq!(norm_ws("G:/x/y"), r"G:\x\y");
        assert_eq!(norm_ws(r"G:\x\y"), r"G:\x\y");
        assert_eq!(norm_ws(""), "");
        assert_eq!(norm_ws("//server/share"), r"\\server\share");
    }

    fn scratch(tag: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!("haoai-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(root.join("sessions")).unwrap();
        root
    }

    fn seed_session(root: &Path, id: &str, body: &str) -> PathBuf {
        let p = root.join("sessions").join(format!("pc-{id}.json"));
        fs::write(&p, body).unwrap();
        p
    }

    /// 改名/置顶是"读整份、换一个键、整份写回"，所以**序列化器本身就是契约的一部分**。
    /// 期望值不是推出来的：把这一串（短转义家族 + 0x01/0x02/0x07/0x0B/0x1B/0x1C/0x1F +
    /// 0x7F/0x80/NBSP/U+2028/U+2029/U+205F/U+3000 + emoji + 汉字 + 数字与嵌套）
    /// 喂进引擎 app 目录里那份 kotlinx-serialization-json 1.9.0
    /// （`jshell --class-path …` 跑 `Json.Default.parseToJsonElement(s).toString()`），
    /// 落回文件再与这里逐字节对照 —— 实测两端同一条字节。
    #[test]
    fn re_serializing_a_session_file_matches_the_jvm_renderer_byte_for_byte() {
        let s = format!(
            "A\"B\\C/D<E>&F\u{1}\u{2}\u{7}\u{8}\t\n\u{b}\u{c}\r\u{1b}\u{1c}\u{1f}\u{7f}\u{80}\u{a0}\u{2028}\u{2029}\u{205f}　😀中文"
        );
        let mut o = serde_json::Map::new();
        o.insert("t".into(), Value::String(s));
        o.insert("n".into(), Value::Number(60.into()));
        o.insert("big".into(), Value::Number(1_759_000_000_000i64.into()));
        o.insert("arr".into(), Value::Array(vec![Value::Number(1.into()), Value::String("x".into())]));
        let mut inner = serde_json::Map::new();
        inner.insert("k".into(), Value::String("v".into()));
        o.insert("o".into(), Value::Object(inner));
        let got = Value::Object(o).to_string();

        // 短转义只有 \b \t \n \f \r 五个；其余控制字符是**小写** \u00XX；
        // 0x7F 及以上（含 NBSP 与两个行分隔符）一律原样；斜杠、尖号、与号都不转义。
        let exp = format!(
            concat!(
                "{{\"t\":\"A\\\"B\\\\C/D<E>&F",
                "\\u0001\\u0002\\u0007\\b\\t\\n\\u000b\\f\\r\\u001b\\u001c\\u001f",
                "{}{}{}{}{}{}　😀中文\",",
                "\"n\":60,\"big\":1759000000000,\"arr\":[1,\"x\"],\"o\":{{\"k\":\"v\"}}}}"
            ),
            '\u{7f}',
            '\u{80}',
            '\u{a0}',
            '\u{2028}',
            '\u{2029}',
            '\u{205f}'
        );
        assert_eq!(got, exp, "写回会话文件的字节必须与 JVM 那份一致");
    }

    /// 空白集合是**量出来的**（jshell + 引擎自带的 kotlin-stdlib 2.4.10，逐个码点比
    /// `Character.isWhitespace` 与 `kotlin.text.CharsKt.isWhitespace`）：
    /// Kotlin 与 Rust 的 `char::is_whitespace` 只差 U+0085 一个字符，
    /// 而 Java 那份把三个不换行空格排除在外 —— 按 Java 口径写就会与 JVM 的改名分叉
    /// （差分台架里那条"标题开头是不换行空格"就是这么抓出来的）。
    #[test]
    fn trim_follows_kotlin_not_java_or_rust() {
        assert_eq!(ktrim("\u{a0}标题\u{a0}"), "标题", "NBSP 在 Kotlin 里是空白");
        assert_eq!(ktrim("\u{2007}q\u{202f}"), "q", "两个不换行空格也算");
        assert_eq!(ktrim("  \t标题\n "), "标题");
        assert_eq!(ktrim("\u{1c}\u{1f}x\u{1e}"), "x", "0x1C–0x1F 算");
        assert_eq!(ktrim("\u{85}x\u{85}"), "\u{85}x\u{85}", "唯独 U+0085 不算 —— Rust 会削掉它");
        assert_eq!(ktrim("\u{2028}w\u{2029}"), "w");
        assert_eq!(ktrim("   "), "");
        assert_eq!(ktrim(""), "");
        assert_eq!(ktrim("中文"), "中文");
        // 对照：Rust 自带的 trim 会削掉 U+0085，所以这里不能直接用它
        assert_eq!("  \u{85}x".trim(), "x", "Rust 把 NEL 当空白，Kotlin 不这么认为");
    }

    #[test]
    fn the_verbatim_prefix_that_windows_canonicalize_adds_is_stripped() {
        assert_eq!(strip_verbatim(r"\\?\G:\hbt\pc-demo"), r"G:\hbt\pc-demo");
        assert_eq!(strip_verbatim(r"\\?\UNC\server\share"), r"\\server\share");
        assert_eq!(strip_verbatim(r"G:\已存在"), r"G:\已存在", "没有前缀的原样返回");
        // 真实路径必须剥干净：`/api/state` 的 workspace 与 rules.json 的键都走这一条
        let s = workspace_file(env!("CARGO_MANIFEST_DIR"));
        assert!(!s.starts_with(r"\\?\"), "canonicalize 的前缀没剥掉：{s}");
        assert!(s.contains("core-rs"), "{s}");
    }

    #[test]
    fn rename_trims_then_flattens_newlines_then_takes_sixty_utf16_units() {
        let root = scratch("rename");
        let p = seed_session(&root, "r1", r#"{"id":"r1","title":"旧","workspace":"w","mode":"ask","updated":7,"messages":[]}"#);
        let store = Store::new(root.clone());
        // 只动 title，其余与键序原样
        assert!(store.rename("r1", "  新标题\n第二行  "));
        let after = fs::read_to_string(&p).unwrap();
        assert!(after.contains(r#""title":"新标题 第二行""#), "{after}");
        assert!(after.starts_with(r#"{"id":"r1","title":"#), "键序必须原样：{after}");
        assert!(after.contains(r#""updated":7"#), "{after}");

        // 60 个 UTF-16 单元：😀 占两个，所以第 30 个 emoji 之后就砍
        let mut long = String::new();
        for _ in 0..40 {
            long.push_str("😀");
        }
        assert!(store.rename("r1", &long));
        let got = store.meta("r1").unwrap().title;
        assert_eq!(got.chars().count(), 30, "60 单元 = 30 个 emoji：{got}");

        // 空标题是非法输入，不是"把标题改成空"
        assert!(!store.rename("r1", "   \n  "));
        assert_eq!(store.meta("r1").unwrap().title.chars().count(), 30);
        // 没有这条会话 → false（路由据此给 404）
        assert!(!store.rename("nope", "x"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn pin_writes_a_real_boolean_and_leaves_everything_else_alone() {
        let root = scratch("pin");
        let p = seed_session(&root, "p1", r#"{"id":"p1","title":"t","updated":7,"messages":[]}"#);
        let store = Store::new(root.clone());
        assert!(store.pin("p1", true));
        let after = fs::read_to_string(&p).unwrap();
        // Kotlin 那边 `put("pinned", JsonPrimitive(true))` 是**真布尔**，不是 "true" 字符串；
        // 而 `read()` 的口径是 `contentOrNull?.toBooleanStrictOrNull() == true`，两种都认。
        assert!(after.ends_with(r#""pinned":true}"#), "新键追加在末尾、真布尔：{after}");
        assert!(store.meta("p1").unwrap().pinned);
        assert!(store.pin("p1", false));
        assert!(!store.meta("p1").unwrap().pinned);
        assert!(!store.pin("nope", true));
        let _ = fs::remove_dir_all(&root);
    }

    /// 删除是移进 `sessions/.trash`，界面上必须有一个放回与清空的入口 ——
    /// 没有入口的"可恢复"等于没有。
    #[test]
    fn delete_moves_to_trash_and_untrash_puts_it_back() {
        let root = scratch("trash");
        let store = Store::new(root.clone());
        let p = seed_session(&root, "d1", r#"{"id":"d1","title":"要被删的","updated":9,"messages":[]}"#);
        assert!(store.delete("d1"));
        assert!(!p.exists(), "原件必须不在原位了");
        let rows = store.list_trash(60);
        assert_eq!(rows.len(), 1, "{:?}", rows.iter().map(|r| &r.name).collect::<Vec<_>>());
        assert_eq!(rows[0].meta.id, "d1", "id 从文件内容读，不是从文件名猜");
        assert!(rows[0].name.ends_with("-pc-d1.json"), "文件名是 <删除时间>-<原名>：{}", rows[0].name);
        assert!(rows[0].bytes > 0);
        // 列表（sessions/）里看不见 .trash 里的那一份
        assert!(store.list(50).is_empty(), "回收站里的会话不该出现在历史列表");

        let id = store.untrash(&rows[0].name).unwrap();
        assert_eq!(id, "d1");
        assert!(p.exists(), "放回原位");
        assert_eq!(store.meta("d1").unwrap().title, "要被删的");

        // 客户端给的名字必须真的在回收站里：`../../x` 想把任意文件搬进 sessions/
        assert!(store.delete("d1"));
        assert_eq!(store.untrash(r"..\..\settings.json"), Err("回收站里没有这个文件".to_string()));
        assert!(!root.join("sessions").join("settings.json").exists());
        assert_eq!(store.untrash("nope.json"), Err("回收站里没有这个文件".to_string()));
        // 同 id 已经在外面时不能覆盖
        let row = store.list_trash(60).remove(0);
        seed_session(&root, "d1", r#"{"id":"d1","title":"外面的","updated":1,"messages":[]}"#);
        assert_eq!(
            store.untrash(&row.name),
            Err("已经有一条同 id 的会话在外面，放回会覆盖它".to_string())
        );

        // 彻底删掉：只认回收站里的那个文件
        assert!(store.purge(&row.name));
        assert!(store.list_trash(60).is_empty());
        assert!(!store.purge("nope.json"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn an_unparseable_updated_falls_back_to_the_file_time_not_zero() {
        let root = scratch("upd");
        seed_session(&root, "u1", r#"{"id":"u1","title":"t","updated":"abc","messages":[]}"#);
        let m = Store::new(root.clone()).meta("u1").unwrap();
        assert!(m.updated > 1_700_000_000_000, "读不出数就该用文件时间，不是 0：{}", m.updated);
        let _ = fs::remove_dir_all(&root);
    }
}
