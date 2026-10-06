//! 三个"配置目录"路由：`/api/presets`、`/api/skills`、`/api/teams`。
//!
//! 形状必须逐字段对齐 Kotlin：界面按 `d.items` 取值，取不到就空一块；而这些请求外面都
//! 包着 `.catch(()=>{})` —— 404 被静默吃掉，看着像"我没建过卡"，其实是"引擎没实现"。
//!
//! 转义在 Kotlin 里是各写各的（preset/feeds 只转 `\` `"` 换行、删 `\r`，自定义技能
//! 额外转制表符），这里照抄这个差异，**不**统一成一个函数 —— 统一了就和源端对不上。
use std::fs;
use std::path::{Path, PathBuf};

use crate::utf16::utf16_take;

/// `Presets.MAX` / `Teams.MAX` / `Teams.MIN_MEMBERS` / `SkillDocs.MAX_DOCS`
const PRESETS_MAX: i64 = 24;
const TEAMS_MAX: i64 = 8;
const TEAM_MIN_MEMBERS: i64 = 2;
const SKILL_MAX_DOCS: i64 = 60;
const MODES: [&str; 3] = ["plan", "ask", "auto"];
/// `Server.BUILTIN_CMDS`：界面上标"这个名字不能用"的那一批
const BUILTIN_CMDS: [&str; 12] = [
    "plan", "ask", "auto", "new", "model", "stop", "clear", "compact", "export", "theme",
    "status", "help",
];
/// `SkillPerms.NOTE`
const SKILL_PERM_NOTE: &str =
    "这份清单是扫出来的，不是沙箱保证：技能正文让模型做的事，不受这份清单限制。";
/// `Preset.NO_OVERRIDE`：这一项不覆盖全局设置。**不能用 0 当哨兵** —— 0 对 temperature
/// 是合法值（确定性输出），用 0 当"没设"就永远表达不了"我要把温度设成 0"。
const NO_OVERRIDE: i64 = -1;

/// preset / team / feed 那一版转义：`\` `"` 换行要转，`\r` **删掉**。
fn js(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => {} // 删掉：Kotlin 那几个 js() 都是这么写的
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `Skills.json()` 那一版：额外转制表符（多行提示词里夹 tab 时两端才一致）。
fn js_tab(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => {}
            _ => out.push(c),
        }
    }
    out.push('"');
    out
}

fn text(v: &serde_json::Value, key: &str, dflt: &str) -> String {
    v.get(key).and_then(|x| x.as_str()).map(String::from).unwrap_or_else(|| dflt.to_string())
}

fn flag(v: &serde_json::Value, key: &str, dflt: bool) -> bool {
    match v.get(key) {
        Some(x) => x.as_bool().unwrap_or_else(|| x.as_str() == Some("true")),
        None => dflt,
    }
}

fn num(v: &serde_json::Value, key: &str, dflt: i64) -> i64 {
    v.get(key).and_then(|x| x.as_i64()).unwrap_or(dflt)
}

fn dbl(v: &serde_json::Value, key: &str, dflt: f64) -> f64 {
    v.get(key).and_then(|x| x.as_f64()).unwrap_or(dflt)
}

fn str_list(v: &serde_json::Value, key: &str) -> Vec<String> {
    v.get(key)
        .and_then(|x| x.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
        .unwrap_or_default()
}

/// 根节点既可能是数组、也可能是 `{"items":[...]}`（`Presets.load` / `Teams.load` 都认）。
fn array_of(v: &serde_json::Value) -> Vec<serde_json::Value> {
    if let Some(a) = v.as_array() {
        return a.clone();
    }
    v.get("items").and_then(|x| x.as_array()).cloned().unwrap_or_default()
}

fn read_json(path: &Path) -> Option<serde_json::Value> {
    serde_json::from_str(&fs::read_to_string(path).ok()?).ok()
}

struct Preset {
    id: String,
    name: String,
    persona: String,
    model: String,
    workspace: String,
    mode: String,
    desc: String,
    icon: String,
    color: String,
    mbti: String,
    quick: Vec<(String, String, String)>,
    kbs: Vec<String>,
    enabled: bool,
    welcome: String,
    temperature: f64,
    max_tokens: i64,
    max_turns: i64,
    default_one: bool,
    created: i64,
}

/// 读一张卡：**没有 id 的直接丢**（Kotlin 是 `return null`），
/// 名字空的补成 id，档位不在 plan/ask/auto 里就当"跟全局默认"（空串）。
fn parse_preset(el: &serde_json::Value) -> Option<Preset> {
    let id = text(el, "id", "");
    if id.is_empty() {
        return None;
    }
    let name = {
        let n = text(el, "name", "");
        if n.trim().is_empty() { id.clone() } else { n }
    };
    let mode = text(el, "mode", "");
    let mode = if MODES.contains(&mode.as_str()) { mode } else { String::new() };
    let quick = el
        .get("quick")
        .and_then(|x| x.as_array())
        .map(|a| {
            a.iter()
                .map(|q| (text(q, "title", ""), text(q, "desc", ""), text(q, "prompt", "")))
                .collect()
        })
        .unwrap_or_default();
    Some(Preset {
        id,
        name,
        persona: text(el, "persona", ""),
        model: text(el, "model", ""),
        workspace: text(el, "workspace", ""),
        mode,
        desc: text(el, "desc", ""),
        icon: text(el, "icon", ""),
        color: text(el, "color", ""),
        mbti: text(el, "mbti", ""),
        quick,
        kbs: str_list(el, "kbs"),
        enabled: flag(el, "enabled", true),
        welcome: text(el, "welcome", ""),
        temperature: dbl(el, "temperature", -1.0),
        max_tokens: num(el, "maxTokens", -1),
        max_turns: num(el, "maxTurns", -1),
        default_one: flag(el, "defaultOne", false),
        created: num(el, "created", crate::engine::now_ms() as i64),
    })
}

/// `Presets.fields(p)` —— 键序与 Kotlin 一字不差（会话之外这些也是两端共读的形状）。
fn preset_fields(p: &Preset) -> String {
    let quick = p
        .quick
        .iter()
        .map(|(t, d, pr)| format!("{{\"title\":{},\"desc\":{},\"prompt\":{}}}", js(t), js(d), js(pr)))
        .collect::<Vec<_>>()
        .join(",");
    format!(
        concat!(
            "\"id\":{},\"name\":{},\"persona\":{},\"model\":{},\"workspace\":{},\"mode\":{},",
            "\"desc\":{},\"icon\":{},\"color\":{},\"mbti\":{},\"quick\":[{}],\"kbs\":[{}],",
            "\"enabled\":{},\"welcome\":{},\"defaultOne\":{},",
            "\"temperature\":{},\"maxTokens\":{},\"maxTurns\":{},\"created\":{}"
        ),
        js(&p.id),
        js(&p.name),
        js(&p.persona),
        js(&p.model),
        js(&p.workspace),
        js(&p.mode),
        js(&p.desc),
        js(&p.icon),
        js(&p.color),
        js(&p.mbti),
        quick,
        p.kbs.iter().map(|k| js(k)).collect::<Vec<_>>().join(","),
        p.enabled,
        js(&p.welcome),
        p.default_one,
        // Kotlin 是 `"${p.temperature}"`：-1.0 要印成 `-1.0` 而不是 `-1`
        format!("{:?}", p.temperature),
        p.max_tokens,
        p.max_turns,
        p.created
    )
}

fn load_presets(root: &Path) -> Vec<Preset> {
    read_json(&root.join("presets.json"))
        .map(|root| array_of(&root).iter().filter_map(parse_preset).collect())
        .unwrap_or_default()
}

/// `GET /api/presets` —— 角色卡（专家）清单。
pub fn presets_json(root: &Path) -> String {
    let items = load_presets(root)
        .iter()
        .map(|p| format!("{{{}}}", preset_fields(p))) // Kotlin 是 "{${fields(it)}}"
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"ok\":true,\"items\":[{}],\"max\":{},\"modes\":[\"plan\",\"ask\",\"auto\"],\"noOverride\":{}}}",
        items, PRESETS_MAX, NO_OVERRIDE
    )
}

/// 头像字符：没设 icon 就取名字的**第一个字符**。Kotlin 是 `name.take(1)`（按 UTF-16
/// 取），名字以 emoji 开头时会取到半个代理码元 —— 界面那颗头像圆用的是同一个函数，
/// 两端必须错得一样。
fn avatar_char(icon: &str, name: &str) -> String {
    if icon.is_empty() {
        utf16_take(name, 1).to_string()
    } else {
        icon.to_string()
    }
}

/// `GET /api/teams` —— 团队。成员是角色卡 id，**卡不见了要说 missing**，
/// 静默跳过会让界面上少一个人而谁都不知道是为什么。
pub fn teams_json(root: &Path) -> String {
    let presets = load_presets(root);
    let teams = read_json(&root.join("teams.json")).map(|r| array_of(&r)).unwrap_or_default();
    let items = teams
        .iter()
        .filter(|t| !text(t, "id", "").is_empty())
        .map(|t| {
            let members = str_list(t, "members")
                .iter()
                .map(|mid| {
                    match presets.iter().find(|p| p.id == *mid) {
                        None => format!("{{\"id\":{},\"missing\":true}}", js(mid)),
                        Some(p) => format!(
                            "{{\"id\":{},\"name\":{},\"icon\":{},\"color\":{},\"mbti\":{},\"model\":{}}}",
                            js(&p.id),
                            js(&p.name),
                            js(&avatar_char(&p.icon, &p.name)),
                            js(&p.color),
                            js(&p.mbti),
                            js(&p.model)
                        ),
                    }
                })
                .collect::<Vec<_>>()
                .join(",");
            format!(
                "{{\"id\":{},\"name\":{},\"desc\":{},\"icon\":{},\"color\":{},\"members\":[{}]}}",
                js(&text(t, "id", "")),
                js(&text(t, "name", "")),
                js(&text(t, "desc", "")),
                js(&text(t, "icon", "")),
                js(&text(t, "color", "")),
                members
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"ok\":true,\"items\":[{}],\"max\":{},\"minMembers\":{}}}",
        items, TEAMS_MAX, TEAM_MIN_MEMBERS
    )
}

/// `SkillDocs.list()`：扫 `<home>/skills/<dir>/SKILL.md`，按目录名排序。
/// 返回 (slug, 展示名, 描述, 正文, 来源 url)。
fn skill_docs(root: &Path) -> Vec<(String, String, String, String, String)> {
    let dir = root.join("skills");
    // Kotlin 是 `File(home,"skills").apply { mkdirs() }`：读之前就建，用户第一次看这页
    // 就该有个空目录，而不是一个报错的空指针
    let _ = fs::create_dir_all(&dir);
    let Ok(rd) = fs::read_dir(&dir) else { return Vec::new() };
    let mut dirs: Vec<PathBuf> = rd
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_dir())
        .map(|e| e.path())
        .collect();
    dirs.sort_by(|a, b| a.file_name().cmp(&b.file_name()));
    let mut out = Vec::new();
    for d in dirs {
        let f = d.join("SKILL.md");
        if !f.is_file() {
            continue;
        }
        let text = fs::read_to_string(&f).unwrap_or_default();
        let (name, desc, body) = parse_skill_md(&text);
        let slug = d.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let display = if name.trim().is_empty() { slug.clone() } else { name };
        out.push((slug, display, desc, body, read_origin(&d.join(".source.json"))));
    }
    out
}

/// `.source.json` 里那个 `url`（装它来的地址）；手写的/本地粘的自然就是空。
fn read_origin(p: &Path) -> String {
    let Ok(text) = fs::read_to_string(p) else { return String::new() };
    // Kotlin 那条是 `"url"\s*:\s*"([^"]*)"`，然后 `\"`→`"`、`\\`→`\`
    let Some(i) = text.find("\"url\"") else { return String::new() };
    let rest = text[i + 5..].trim_start_matches([' ', ':', '\t', '\r', '\n']);
    let Some(rest) = rest.strip_prefix('"') else { return String::new() };
    let mut out = String::new();
    let mut chars = rest.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => match chars.next() {
                Some('"') => out.push('"'),
                Some('\\') => out.push('\\'),
                // 别的转义留着原样（Kotlin 那两条 replace 也不会碰它）
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            },
            '"' => break,
            other => out.push(other),
        }
    }
    out
}

/// `SkillDocs.parse`：`---` front matter 里的 name / description，其余是正文。
fn parse_skill_md(text: &str) -> (String, String, String) {
    let t = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = t.split('\n').collect();
    if lines.first().map(|l| l.trim()) != Some("---") {
        return (String::new(), String::new(), t.trim().to_string());
    }
    let mut name = String::new();
    let mut desc = String::new();
    let mut i = 1;
    while i < lines.len() && lines[i].trim() != "---" {
        let l = lines[i];
        let key = l.split(':').next().unwrap_or("").trim().to_lowercase();
        let val = l.splitn(2, ':').nth(1).unwrap_or("").trim().trim_matches('"').to_string();
        match key.as_str() {
            "name" => name = val,
            "description" => desc = val,
            _ => {}
        }
        i += 1;
    }
    let body = lines
        .iter()
        .skip((i + 1).min(lines.len()))
        .copied()
        .collect::<Vec<&str>>()
        .join("\n")
        .trim()
        .to_string();
    (name, desc, body)
}

/// 手写的 `/命令`（`skills.json`）。
fn load_custom_skills(root: &Path) -> Vec<(String, String, String)> {
    let Some(root) = read_json(&root.join("skills.json")) else { return Vec::new() };
    let arr = root.as_array().cloned().unwrap_or_default();
    arr.iter()
        .filter_map(|e| {
            let name = text(e, "name", "").trim().to_string();
            if name.is_empty() {
                return None;
            }
            Some((name, text(e, "desc", ""), text(e, "text", "")))
        })
        .collect()
}

/// 订阅源（`skill-feeds.json`）。
fn load_feeds(root: &Path) -> Vec<String> {
    let Some(root) = read_json(&root.join("skill-feeds.json")) else { return Vec::new() };
    root.as_array()
        .cloned()
        .unwrap_or_default()
        .iter()
        .map(|e| {
            format!(
                "{{\"id\":{},\"name\":{},\"url\":{}}}",
                js(&text(e, "id", "")),
                js(&text(e, "name", "")),
                js(&text(e, "url", ""))
            )
        })
        .collect()
}

/// `GET /api/skills` —— 手写的 `/命令` 与导入的 SKILL.md 拼成**一份清单**：
/// 界面上是同一个列表，`doc:true/false` 只是"删"按钮指的删谁不同。
pub fn skills_json(root: &Path) -> String {
    let mut items: Vec<String> = load_custom_skills(root)
        .iter()
        .map(|(n, d, t)| {
            format!("{{\"name\":{},\"desc\":{},\"text\":{},\"doc\":false}}", js_tab(n), js_tab(d), js_tab(t))
        })
        .collect();
    for (slug, name, desc, body, origin) in skill_docs(root) {
        items.push(format!(
            "{{\"name\":{},\"desc\":{},\"text\":{},\"slug\":{},\"origin\":{},\"doc\":true}}",
            js(&name),
            js(&desc),
            js(&body),
            js(&slug),
            js(&origin)
        ));
    }
    format!(
        "{{\"ok\":true,\"items\":[{}],\"taken\":{},\"feeds\":[{}],\"permNote\":{},\"dir\":{},\"maxDocs\":{}}}",
        items.join(","),
        js(&BUILTIN_CMDS.join(",")),
        load_feeds(root).join(","),
        js(SKILL_PERM_NOTE),
        js(&root.join("skills").to_string_lossy()),
        SKILL_MAX_DOCS
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn scratch(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("haoai-cat-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn presets_shape_matches_the_kotlin_field_order() {
        let root = scratch("presets");
        // 一条字段齐全的、一条**只给了 id 和名字**的（缺省要补齐，界面按字段取值才不会空一块）
        fs::write(
            root.join("presets.json"),
            r#"[{"id":"pr1","name":"剪辑专家","persona":"剪片","temperature":0.7,"quick":[{"title":"开剪","prompt":"开工"}],"kbs":["kb1"]},
                {"id":"pr2"}]"#,
        )
        .unwrap();
        let raw = presets_json(&root);
        let j: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(j["ok"], true);
        assert_eq!(j["max"], 24, "Presets.MAX");
        assert_eq!(j["noOverride"], -1, "哨兵值不能是 0（0 对 temperature 是合法值）");
        assert_eq!(j["modes"], serde_json::json!(["plan", "ask", "auto"]));
        let items = j["items"].as_array().unwrap();
        assert_eq!(items.len(), 2);
        let keys: Vec<&str> = items[0].as_object().unwrap().keys().map(|s| s.as_str()).collect();
        assert_eq!(
            keys,
            [
                "id", "name", "persona", "model", "workspace", "mode", "desc", "icon", "color",
                "mbti", "quick", "kbs", "enabled", "welcome", "defaultOne", "temperature",
                "maxTokens", "maxTurns", "created",
            ],
            "{keys:?}"
        );
        assert_eq!(items[0]["temperature"], 0.7);
        assert_eq!(items[0]["quick"][0]["title"], "开剪");
        // 没 id 的卡要丢、只有 id 的卡要补齐默认值
        assert_eq!(items[1]["name"], "pr2", "名字空的补成 id");
        assert_eq!(items[1]["enabled"], true, "关着的卡没写 enabled 就是开着");
        assert_eq!(items[1]["maxTokens"], -1);
        assert_eq!(items[1]["kbs"], serde_json::json!([]));
        assert_eq!(items[1]["mode"], "", "非法档位当\"跟全局默认\"");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_preset_without_id_is_dropped_not_rendered_broken() {
        let root = scratch("badpreset");
        fs::write(root.join("presets.json"), r#"[{"name":"没 id 的卡"},{"id":"ok","name":"好的"}]"#).unwrap();
        let raw = presets_json(&root);
        let j: serde_json::Value = serde_json::from_str(&raw).unwrap();
        assert_eq!(j["items"].as_array().unwrap().len(), 1, "没 id 的那条 Kotlin 是 return null");
        assert_eq!(j["items"][0]["id"], "ok");
        let _ = fs::remove_dir_all(&root);
    }

    /// SKILL.md 的 front matter 解析 + 一个目录只认 SKILL.md：
    /// 这 15 份文档是**真实数据**（用户状态根里就有一份），解析错了界面上就是空列表。
    #[test]
    fn skill_docs_scan_the_front_matter_and_skip_dirs_without_it() {
        let root = scratch("skills");
        // 名字按目录名排序：slug 在前
        fs::create_dir_all(root.join("skills/beta")).unwrap();
        fs::write(
            root.join("skills/beta/SKILL.md"),
            "---\nname: 日志排障\ndescription: 从日志里定位根因\n---\n\n# 正文\nbody 里有\r\n回车\r\n",
        )
        .unwrap();
        fs::create_dir_all(root.join("skills/alpha")).unwrap();
        fs::write(root.join("skills/alpha/SKILL.md"), "# 没有 front matter\n").unwrap();
        fs::create_dir_all(root.join("skills/skipped")).unwrap(); // 没 SKILL.md → 不认

        let j: serde_json::Value = serde_json::from_str(&skills_json(&root)).unwrap();
        let items = j["items"].as_array().unwrap();
        assert_eq!(items.len(), 2, "没有 SKILL.md 的目录不该进来：{items:?}");
        assert_eq!(j["maxDocs"], 60);
        assert_eq!(j["taken"], "plan,ask,auto,new,model,stop,clear,compact,export,theme,status,help");
        assert!(j["permNote"].as_str().unwrap().contains("不是沙箱保证"));
        // 目录名排序在前：alpha 没 front matter → 展示名回落成目录名
        assert_eq!(items[0]["name"], "alpha");
        assert_eq!(items[0]["doc"], true);
        assert_eq!(items[1]["name"], "日志排障");
        assert_eq!(items[1]["desc"], "从日志里定位根因");
        // 正文里的 \r 要按 Kotlin 的 js() 删掉
        let body = items[1]["text"].as_str().unwrap();
        assert!(!body.contains('\r'), "js() 会删掉 \\r：{body:?}");
        assert!(body.contains("body 里有"), "{body}");
        let _ = fs::remove_dir_all(&root);
    }

    /// 团队成员的卡要是**找不到了**，必须显式说 missing —— 静默跳过会让界面上少一个人，
    /// 而谁都不知道少的那个是谁。
    #[test]
    fn team_members_that_lost_their_preset_say_missing() {
        let root = scratch("teams");
        fs::write(
            root.join("presets.json"),
            // raw string 用两级 `##`：JSON 里那个 `"#123456` 会把单个 `r#"..."#` 提前截断
            r##"[{"id":"pr1","name":"A","icon":"","color":"#123456","mbti":"INTJ","model":"m1"}]"##,
        )
        .unwrap();
        fs::write(
            root.join("teams.json"),
            r#"{"items":[{"id":"t1","name":"小队","desc":"干活的","members":["pr1","prGONE"]}]}"#,
        )
        .unwrap();
        let j: serde_json::Value = serde_json::from_str(&teams_json(&root)).unwrap();
        assert_eq!(j["max"], 8);
        assert_eq!(j["minMembers"], 2);
        let team = &j["items"][0];
        assert_eq!(team["name"], "小队");
        let m = team["members"].as_array().unwrap();
        assert_eq!(m[0]["id"], "pr1");
        assert_eq!(m[0]["icon"], "A", "没设 icon 就拿名字首字（= avatarChar）");
        assert_eq!(m[1]["missing"], true, "卡不见了要说 missing");
        let _ = fs::remove_dir_all(&root);
    }
}
