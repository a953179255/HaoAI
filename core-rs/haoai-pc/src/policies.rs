//! S2 权限规则表：把"每次都问"变成"规则命中就不问"。移植自 `Policies.kt`。
//!
//! 为什么 PC 端比手机端更需要这一层：手机上 agent 顶多点几下屏幕，PC 上它能 `rm -rf`。
//! 没有一张用户可写的规则表，`ask` 档位就只能是"前二十次很新鲜、之后开始乱点"，
//! 而 `auto` 档位又是全裸 —— 中间必须有一层，否则无人值守在 PC 上根本不成立。
//!
//! 语义抄的两家，各自解决一个问题：
//! - **有序 + 后覆盖前**（opencode 的 `findLast`）：规则是追加式的，
//!   "先允许 git，再单独禁掉 git push"这种表达必须能写出来；
//! - **命令前缀归约**（见 [`risk::command_prefix`]）：`git checkout main` 要能命中
//!   `git checkout` 这条规则，否则规则表只能按整条命令写死，等于没有规则表。
//!
//! 两条红线：
//! 1. [`ALWAYS_ASK`] 里的前缀**任何档位都绕不过**，包括 auto —— 这是给"删库、改系统、
//!    装东西"留的最后一道人工闸。
//! 2. 拒绝时把**原因和命中的规则**回给模型，而不是只回一个 deny —— 否则模型会换个写法重试。
use std::fs;
use std::path::{Path, PathBuf};

use serde_json::Value;

use crate::risk::{self, lower, trim};
use crate::tools_impl::glob_to_regex;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Decision {
    Allow,
    Ask,
    Deny,
}

impl Decision {
    /// Kotlin 枚举的 `name`（`"${r.decision}"` 落盘、`Decision.valueOf` 读回都用它）。
    pub fn name(self) -> &'static str {
        match self {
            Decision::Allow => "ALLOW",
            Decision::Ask => "ASK",
            Decision::Deny => "DENY",
        }
    }

    fn parse(s: &str) -> Option<Decision> {
        match s {
            "ALLOW" => Some(Decision::Allow),
            "ASK" => Some(Decision::Ask),
            "DENY" => Some(Decision::Deny),
            _ => None,
        }
    }
}

/// 一条规则：`decision` 作用在 `tool(pattern)` 上。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rule {
    pub tool: String,
    pub pattern: String,
    pub decision: Decision,
}

impl Rule {
    /// Kotlin 的 `substringAfterLast(' ')`：**没有分隔符时返回整串**（不是空串）。
    /// 这一条决定了解析裸词的行为，别按 Rust 的直觉改成空串。
    fn after_last(s: &str, ch: char) -> String {
        match s.rfind(ch) {
            Some(i) => s[i + ch.len_utf8()..].to_string(),
            None => s.to_string(),
        }
    }

    /// Kotlin 的 `substringBeforeLast(' ')`：同样，没有分隔符时返回整串。
    fn before_last(s: &str, ch: char) -> String {
        match s.rfind(ch) {
            Some(i) => s[..i].to_string(),
            None => s.to_string(),
        }
    }

    /// 解析 `shell(git push*) deny`；不带括号时整串当工具名（= 该工具全部放行/拒绝）。
    ///
    /// 从**右边**摘决策词，因为 pattern 里可以有空格（`git push*`），
    /// 按空白切三段就把一条规则切成了三条。
    pub fn parse(s: &str) -> Option<Rule> {
        let t = trim(s).to_string();
        if t.is_empty() {
            return None;
        }
        let last = Self::after_last(&t, ' ').to_uppercase();
        let decision = Decision::parse(&last)?;
        let head = trim(&Self::before_last(&t, ' ')).to_string();
        if head.is_empty() {
            return None;
        }
        let open = head.find('(');
        Some(match open {
            Some(o) if head.ends_with(')') => Rule {
                tool: trim(&head[..o]).to_string(),
                pattern: head[o + '('.len_utf8()..head.len() - 1].to_string(),
                decision,
            },
            _ => Rule { tool: head, pattern: "*".into(), decision },
        })
    }

    /// 命令拿**三种形态**去比：归约前缀（`git checkout`）、前两个 token（`git commit`）、
    /// 整条命令。只用归约前缀会出这种事：arity 词典里没有 `git commit`（它不算危险命令），
    /// 于是用户写的 `shell(git commit*)` 永远匹配不上，规则形同虚设。三种都比一遍，
    /// "宽规则盖住一族、窄规则盖住具体子命令"两种写法都成立。
    fn matches_subject(&self, subject: &str) -> bool {
        let rx = match glob_to_regex(&self.pattern) {
            Ok(r) => r,
            // Kotlin 侧编不出来会抛，一路冒到 guard 外面 —— 但那只会把整条回合弄崩。
            // Rust 的 regex 引擎确实接不住某些 java 写法（见 tools_impl 的说明），
            // 这里按"不命中"处理：宁可多问一次，也不让一条规则把服务打挂。
            Err(_) => return false,
        };
        if self.tool != "shell" {
            return rx.is_match(subject);
        }
        let reduced = risk::command_prefix(subject);
        let head2 = subject
            .split(|c: char| c.is_whitespace())
            .filter(|t| !t.is_empty())
            .take(2)
            .collect::<Vec<_>>()
            .join(" ");
        let full = trim(subject).to_string();
        [reduced.as_str(), head2.as_str(), full.as_str()].iter().any(|it| {
            rx.is_match(it)
                // Kotlin 是 `pattern.dropLast(1)`：**只摘最后一个字符**（`git**` 与 `git*`
                // 的前缀不一样），不是"把所有尾部星号去掉"。
                || (self.pattern.ends_with('*')
                    && it.starts_with(&self.pattern[..self.pattern.len() - 1]))
        })
    }
}

/// 判定结果：给模型看的理由要能说清是哪条规则生效的。
pub struct Verdict {
    pub decision: Decision,
    pub why: String,
}

/// 必须人工确认的命令前缀，auto 也绕不过。
pub const ALWAYS_ASK: &[&str] = &[
    "rm -rf", "rm", "del", "erase", "remove-item", "rmdir", "format", "diskpart",
    "git push --force", "git reset --hard", "git clean", "git filter-branch", "shutdown",
    "restart-computer", "stop-computer", "takeown", "vssadmin", "reg", "regedit", "schtasks",
    "set-executionpolicy", "net user", "netsh", "cipher", "bcdedit", "docker system prune",
    "kubectl delete", "winget uninstall",
];

/// workspace 绝对路径（正斜杠）→ 该工作区的规则表。
///
/// 用 `Vec` 而不是 HashMap：Kotlin 那边是 `mutableMapOf()`（LinkedHashMap），
/// 落盘按插入序遍历 —— 换成哈希序会让两端写出内容不同的 rules.json。
pub struct PolicyStore {
    file: PathBuf,
    by_workspace: Vec<(String, Vec<Rule>)>,
}

impl PolicyStore {
    pub fn new(file: PathBuf) -> Self {
        let mut s = Self { file, by_workspace: Vec::new() };
        s.reload();
        s
    }

    /// 读失败/格式不对 → 整张表当空的（Kotlin 是整段 runCatching 兜住，粒度就是"全部或没有"）。
    pub fn reload(&mut self) {
        self.by_workspace = read_rules(&self.file);
    }

    pub fn rules(&self, workspace: &Path) -> Vec<Rule> {
        self.by_workspace
            .iter()
            .find(|(k, _)| *k == key(workspace))
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }

    pub fn add(&mut self, workspace: &Path, rule: Rule) {
        let k = key(workspace);
        if !self.by_workspace.iter().any(|(ek, _)| *ek == k) {
            self.by_workspace.push((k.clone(), Vec::new()));
        }
        let list = &mut self.by_workspace.iter_mut().find(|(ek, _)| *ek == k).expect("刚确保存在").1;
        list.retain(|r| r.tool != rule.tool || r.pattern != rule.pattern);
        list.push(rule);
        self.persist();
    }

    pub fn remove(&mut self, workspace: &Path, tool: &str, pattern: &str) -> bool {
        let k = key(workspace);
        let Some(e) = self.by_workspace.iter_mut().find(|(ek, _)| *ek == k) else {
            return false;
        };
        let n = e.1.len();
        e.1.retain(|r| r.tool != tool || r.pattern != pattern);
        let changed = e.1.len() != n;
        if changed {
            self.persist();
        }
        changed
    }

    pub fn clear(&mut self, workspace: &Path) {
        let k = key(workspace);
        let before = self.by_workspace.len();
        self.by_workspace.retain(|(ek, _)| *ek != k);
        if self.by_workspace.len() != before {
            self.persist();
        }
    }

    /// 复刻 Kotlin `persist` 的字面格式：键行前一个换行、规则紧凑、文件尾 `"\n}\n"`。
    fn persist(&self) {
        let body = self
            .by_workspace
            .iter()
            .map(|(ws, rules)| {
                let rs = rules
                    .iter()
                    .map(|r| {
                        format!(
                            "{{\"tool\":{},\"pattern\":{},\"decision\":\"{}\"}}",
                            json_str(&r.tool),
                            json_str(&r.pattern),
                            r.decision.name()
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(",");
                format!("{}:[{}]", json_str(ws), rs)
            })
            .collect::<Vec<_>>()
            .join(",\n");
        let _ = fs::create_dir_all(self.file.parent().unwrap_or(Path::new(".")));
        let _ = fs::write(&self.file, format!("{{\n{body}\n}}\n"));
    }

    /**
     * 判定一次工具调用。`subject` 是"这次动作的对象"：shell 用整条命令，
     * 写类工具用相对路径。返回 None 表示规则表没意见，交给档位（plan/ask/auto）决定。
     */
    pub fn decide(&self, workspace: &Path, tool: &str, subject: &str) -> Option<Verdict> {
        let rules = self.rules(workspace);
        if rules.is_empty() && tool != "shell" {
            return None;
        }

        // 后写的规则覆盖先写的（findLast）：要找的是"**最后一条命中的**"，
        // 不是"最后一条该工具的规则再看它命不命中" —— 后者会让前面的宽规则被后面的窄规则
        // 无条件顶掉，`shell(git*) allow` + `shell(git push*) deny` 就表达不出来。
        let hit = rules.iter().rev().find(|r| {
            (r.tool == tool || r.tool == "*") && r.matches_subject(subject)
        });

        if tool == "shell" {
            let prefix = risk::command_prefix(subject);
            // 既比归约后的前缀，也比整条命令：`git push` 与 `git push --force` 危险等级不同，
            // 只比前缀会把后者漏掉（而 force push 恰恰是最需要拦的那一个）。
            let hay = [lower(&prefix), lower(&trim(subject).to_string())];
            let dangerous = ALWAYS_ASK.iter().any(|a| {
                hay.iter()
                    .any(|h| h == a || h.starts_with(&format!("{a} ")) || h.starts_with(&format!("{a}-")))
            });
            if dangerous {
                return Some(Verdict {
                    decision: Decision::Ask,
                    why: format!("「{prefix}」在必须人工确认的清单里（任何档位都绕不过，包括 auto）"),
                });
            }
        }
        hit.map(|r| Verdict {
            decision: r.decision,
            why: format!("命中规则 {}({}) -> {}", r.tool, r.pattern, r.decision.name()),
        })
    }
}

fn read_rules(file: &Path) -> Vec<(String, Vec<Rule>)> {
    if !file.is_file() {
        return Vec::new();
    }
    let Ok(text) = fs::read_to_string(file) else { return Vec::new() };
    let Ok(root) = serde_json::from_str::<Value>(&text) else { return Vec::new() };
    let Some(obj) = root.as_object() else { return Vec::new() };
    obj.iter()
        .map(|(ws, el)| {
            let rules = el
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .filter_map(|r| {
                            let o = r.as_object()?;
                            let tool = str_field(o.get("tool"))?;
                            let pattern = str_field(o.get("pattern")).unwrap_or_else(|| "*".to_string());
                            let decision = str_field(o.get("decision"))
                                .and_then(|d| Decision::parse(&d))
                                .unwrap_or(Decision::Ask);
                            Some(Rule { tool, pattern, decision })
                        })
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            (ws.clone(), rules)
        })
        .collect()
}

/// Kotlin 的 `JsonObject.str(key)`：不是字符串（或是 JSON null）就当没有。
fn str_field(v: Option<&Value>) -> Option<String> {
    v.and_then(|x| x.as_str()).map(|s| s.to_string())
}

/// `Env`/`ToolCtx` 那套 canonicalPath 的键：反斜杠统一成正斜杠。
/// Windows 上 `fs::canonicalize` 会带 `\\?\` 前缀，Kotlin 的 `canonicalPath` 没有 ——
/// 摘掉前缀再换正斜杠，两端才会写出同一个键（`\\?\` 没摘会导致规则读回来是空表）。
fn key(workspace: &Path) -> String {
    crate::tools_impl::canon_str(workspace).replace('\\', "/")
}

/// Kotlin 的 `jsonStr`：只转反斜杠和双引号（不转换行 —— 规则里不该有换行，有也照原样写）。
fn json_str(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

/// 进程内一份就够：规则表很小，没必要每次工具调用都读盘（`Policies` 单例的等价物）。
pub struct Policies {
    store: std::sync::Mutex<PolicyStore>,
}

impl Policies {
    pub fn new(home: &Path) -> Self {
        Self { store: std::sync::Mutex::new(PolicyStore::new(home.join("rules.json"))) }
    }

    pub fn with<F, T>(&self, f: F) -> T
    where
        F: FnOnce(&mut PolicyStore) -> T,
    {
        let mut s = self.store.lock().unwrap_or_else(|p| p.into_inner());
        f(&mut s)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn tmp(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("haoai-policy-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn rule_parse_takes_the_decision_from_the_right_end() {
        let r = Rule::parse("shell(git push*) deny").unwrap();
        assert_eq!((r.tool.as_str(), r.pattern.as_str()), ("shell", "git push*"));
        assert_eq!(r.decision, Decision::Deny);
        // 不带括号 = 整个工具
        let r = Rule::parse("read allow").unwrap();
        assert_eq!((r.tool.as_str(), r.pattern.as_str()), ("read", "*"));
        // 裸一个决策词：Kotlin 的 substringBeforeLast 无分隔符时返回**整串**，
        // 于是 "deny" 变成"工具名叫 deny"而不是"解析失败" —— 照抄，不顺手改成 None
        let r = Rule::parse("deny").unwrap();
        assert_eq!((r.tool.as_str(), r.decision), ("deny", Decision::Deny));
        assert!(Rule::parse("shell(x) maybe").is_none());
        assert!(Rule::parse("").is_none());
        // JVM PolicyTest 那条：只写了 `tool(pattern)` 没带决策词的，必须是 null 而不是"整串当工具名"
        assert!(Rule::parse("shell(x)").is_none());
        let r = Rule::parse("write(.env) ALLOW").unwrap();
        assert_eq!((r.tool.as_str(), r.pattern.as_str()), ("write", ".env"));
        assert_eq!(r.decision, Decision::Allow);
    }

    #[test]
    fn last_matching_rule_wins_not_last_rule_of_that_tool() {
        let dir = tmp("order");
        let mut s = PolicyStore::new(dir.join("rules.json"));
        let ws = dir.clone();
        s.add(&ws, Rule { tool: "shell".into(), pattern: "git*".into(), decision: Decision::Allow });
        s.add(&ws, Rule { tool: "shell".into(), pattern: "git push*".into(), decision: Decision::Deny });
        // 宽规则盖一族、窄规则盖具体子命令 —— 两种写法都得成立
        let v = s.decide(&ws, "shell", "git status").unwrap();
        assert_eq!(v.decision, Decision::Allow, "{:?}", v.why);
        let v = s.decide(&ws, "shell", "git push --force").unwrap();
        // 但 git push --force 先撞上 ALWAYS_ASK：那条清单在任何档位都绕得过规则表的 allow，
        // 绕不过的是"这张卡必须弹"
        assert_eq!(v.decision, Decision::Ask, "ALWAYS_ASK 该压过 allow 规则：{:?}", v.why);
        let v = s.decide(&ws, "shell", "git push origin").unwrap();
        assert_eq!(v.decision, Decision::Deny);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn always_ask_catches_the_prefix_and_the_whole_command() {
        let dir = tmp("always");
        let s = PolicyStore::new(dir.join("rules.json"));
        let ws = dir.clone();
        for cmd in ["rm -rf /x", "Remove-Item -Force a", "git clean -fdx", "reg add HKCU\\x"] {
            let v = s.decide(&ws, "shell", cmd).expect("空规则表也要给 shell 判 ALWAYS_ASK");
            assert_eq!(v.decision, Decision::Ask, "{cmd} 必须问人：{}", v.why);
            assert!(v.why.contains("任何档位都绕不过"));
        }
        assert!(s.decide(&ws, "shell", "git status").is_none());
        assert!(s.decide(&ws, "write", "a.md").is_none(), "空表 + 非 shell → 没意见");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn git_commit_rule_matches_even_though_arity_has_no_entry_for_it() {
        let dir = tmp("commit");
        let mut s = PolicyStore::new(dir.join("rules.json"));
        let ws = dir.clone();
        s.add(&ws, Rule { tool: "shell".into(), pattern: "git commit*".into(), decision: Decision::Allow });
        // arity 词典里没有 git commit（它不算危险命令），只比归约前缀这条规则就形同虚设
        let v = s.decide(&ws, "shell", "git commit -m x").unwrap();
        assert_eq!(v.decision, Decision::Allow);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn non_shell_rules_match_the_subject_as_a_glob() {
        let dir = tmp("glob");
        let mut s = PolicyStore::new(dir.join("rules.json"));
        let ws = dir.clone();
        s.add(&ws, Rule { tool: "write".into(), pattern: "**/*.md".into(), decision: Decision::Allow });
        assert_eq!(s.decide(&ws, "write", "docs/a.md").unwrap().decision, Decision::Allow);
        assert!(s.decide(&ws, "write", "docs/a.rs").is_none());
        // 同一个形状在两端必须一致：`*` 不跨 `/`，所以裸 `*.md` 只盖得住根下那一个
        s.add(&ws, Rule { tool: "write".into(), pattern: "*.txt".into(), decision: Decision::Deny });
        assert_eq!(s.decide(&ws, "write", "a.txt").unwrap().decision, Decision::Deny);
        assert!(s.decide(&ws, "write", "docs/a.txt").is_none(), "`*` 不该跨目录");
        let _ = fs::remove_dir_all(&dir);
    }

    /// 规则的存取必须走同一套字面格式：Kotlin 的 UI、手机端、以及已有文件都读这一份。
    #[test]
    fn persists_the_same_shape_it_reads() {
        let dir = tmp("roundtrip");
        let ws_key;
        {
            let mut s = PolicyStore::new(dir.join("rules.json"));
            s.add(&dir, Rule { tool: "shell".into(), pattern: "git push*".into(), decision: Decision::Deny });
            s.add(&dir, Rule { tool: "write".into(), pattern: "a\\b.md".into(), decision: Decision::Allow });
            ws_key = key(&dir);
        }
        let raw = fs::read_to_string(dir.join("rules.json")).unwrap();
        assert!(raw.starts_with("{\n"), "首字符要是换行：{raw:?}");
        assert!(raw.ends_with("\n}\n"), "文件尾要是「换行 右花括号 换行」：{raw:?}");
        assert!(raw.contains(&format!("\"{ws_key}\":[")), "键是正斜杠的绝对路径：{raw:?}");
        assert!(raw.contains(r#"{"tool":"shell","pattern":"git push*","decision":"DENY"}"#), "{raw:?}");
        // 反斜杠走 jsonStr：只转 `\` 和 `"`，不转别的
        assert!(raw.contains(r#""pattern":"a\\b.md""#), "{raw:?}");
        // 读回来一模一样
        let s2 = PolicyStore::new(dir.join("rules.json"));
        let rs = s2.rules(&dir);
        assert_eq!(rs.len(), 2);
        assert_eq!(rs[1].tool, "write");
        assert_eq!(rs[1].pattern, "a\\b.md");
        assert_eq!(rs[0].decision, Decision::Deny);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_broken_rules_file_degrades_to_no_rules_rather_than_no_service() {
        let dir = tmp("broken");
        fs::write(dir.join("rules.json"), "{ not json").unwrap();
        let s = PolicyStore::new(dir.join("rules.json"));
        assert!(s.rules(&dir).is_empty());
        let _ = fs::remove_dir_all(&dir);
    }
}
