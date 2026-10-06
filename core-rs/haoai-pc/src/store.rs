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

    fn file_for(&self, id: &str) -> PathBuf {
        self.sessions_dir().join(format!("pc-{id}.json"))
    }

    /// 只读索引需要的那几个字段（`/api/sessions` 用），不解析消息体。
    pub fn meta(&self, id: &str) -> Option<Meta> {
        let path = self.file_for(id);
        let raw = fs::read_to_string(&path).ok()?;
        let v = serde_json::from_str::<Value>(&raw).ok()?;
        let updated = match v.get("updated") {
            Some(x) if x.is_number() || x.is_string() => i(Some(x)),
            _ => file_mtime_ms(&path),
        };
        Some(Meta {
            id: match v.get("id").and_then(|x| x.as_str()) {
                Some(t) if !t.is_empty() => t.to_string(),
                _ => id.to_string(),
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
}
