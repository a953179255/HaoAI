//! 改前快照 + 一轮运行的检查点账本。移植自 `Tools.kt` 的 `Snapshots` 与 `Checkpoints.kt`。
//!
//! `.haoai-snap/` 只解决"单个文件改坏了退回上一版"，而真正想按的那颗钮是
//! **"回到这次任务之前"**：跑歪了的不止一个文件，还可能新建了三个。所以这里记的是
//! "这一轮动过哪些路径、各自改之前是什么样（或本来不存在）"。
//!
//! 快照本身不复制第二份 —— `checkpoints.jsonl` 只记指针。同一条路径在一轮里被改三次，
//! 回滚要的是**最早**那一份（= 这轮开始前的样子）。
use std::fs;
use std::path::{Path, PathBuf};

use crate::state::quote;
use crate::utf16::utf16_take;

/// 快照文件落在工作区里（跟着项目走，删掉工作区就一起没了）。
pub fn snap_dir(workspace: &Path) -> PathBuf {
    workspace.join(".haoai-snap")
}

/// 快照键 = 相对路径把分隔符换成 `__`。
///
/// 以前只用裸文件名，于是 `src/A.md` 与 `docs/A.md` 会写进同一份快照，
/// 回滚时把错目录的内容盖回来 —— 而"回滚"恰恰是用户最信任的一步。
pub fn key_of(workspace: &Path, target: &Path) -> String {
    let name = target.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let ws = crate::tools_impl::canon_str(workspace);
    let t = crate::tools_impl::canon_str(target);
    // 取不到相对路径（不在工作区里）就回落到裸文件名，与 Kotlin 的 getOrDefault 同
    let rel = if t.starts_with(&ws) { t[ws.len()..].trim_start_matches(['\\', '/']) } else { "" };
    let flat = if t.starts_with(&ws) { rel.to_string() } else { name.clone() };
    let flat = flat.replace('\\', "/").replace('/', "__");
    if flat.trim().is_empty() { name } else { flat }
}

/// 一轮的文件检查点。`snap` 为空 = 这轮之前它不存在（回滚时该**删掉**而不是还原）。
pub struct Entry {
    pub run: String,
    pub sid: String,
    pub ts: i64,
    pub goal: String,
    pub path: String,
    pub snap: String,
    /// 这一轮**开头那句话**在历史里的下标。没有它，"回到这一句之前"只能按时间猜，
    /// 而定时任务、手机派活、子任务都往同一条会话里追加消息 —— 按时间猜会退错。
    pub at: i64,
}

const KEEP: usize = 4000;

fn file(home: &Path) -> PathBuf {
    home.join("checkpoints.jsonl")
}

/// 一轮开始登记一条头行（带任务名）。没有它，列表里就不知道这一轮是干什么的。
pub fn begin(home: &Path, sid: &str, run: &str, goal: &str, at: i64) {
    write(
        home,
        &Entry {
            run: run.to_string(),
            sid: sid.to_string(),
            ts: crate::engine::now_ms() as i64,
            // Kotlin 的 goal.take(200) 是 UTF-16 单元数
            goal: utf16_take(goal, 200).to_string(),
            path: String::new(),
            snap: String::new(),
            at,
        },
    );
}

/// 一次改动记一笔。`run` 为空 = 不在任何一轮里（CLI 单发、测试），不记账。
pub fn note(home: &Path, run: &str, sid: &str, path: &str, snap: Option<&Path>) {
    if run.is_empty() {
        return;
    }
    write(
        home,
        &Entry {
            run: run.to_string(),
            sid: sid.to_string(),
            ts: crate::engine::now_ms() as i64,
            goal: String::new(),
            path: path.to_string(),
            snap: snap.map(|p| p.to_string_lossy().to_string()).unwrap_or_default(),
            at: -1,
        },
    );
}

/// 行形状与 Kotlin 的 `buildJsonObject` 一字不差：键序 `run,sid,ts,goal,path,snap,at`。
fn write(home: &Path, e: &Entry) {
    let p = file(home);
    let line = format!(
        "{{\"run\":{},\"sid\":{},\"ts\":{},\"goal\":{},\"path\":{},\"snap\":{},\"at\":{}}}\n",
        quote(&e.run),
        quote(&e.sid),
        e.ts,
        quote(&e.goal),
        quote(&e.path),
        quote(&e.snap),
        e.at
    );
    let _ = fs::create_dir_all(home);
    let _ = append(&p, &line);
    prune(&p);
}

/// 快照文件名撞车时往后加 `-1`、`-2`…，**永远不许盖掉已有的那一份**。
///
/// 同一毫秒里改同一个文件两次是真会发生的（一轮里连续两个 edit），而名字里带的是毫秒时间戳。
/// 盖掉的会是**最早**那份，回滚要的恰恰也是最早那份 —— 所以这里找的是"第一个还没被占用的名字"。
pub fn free_name(dir: &Path, key: &str) -> PathBuf {
    let mut p = dir.join(key);
    let mut n = 1;
    while p.exists() {
        p = dir.join(format!("{key}-{n}"));
        n += 1;
    }
    p
}

fn append(p: &Path, line: &str) -> std::io::Result<()> {
    use std::io::Write;
    let mut f = fs::OpenOptions::new().create(true).append(true).open(p)?;
    f.write_all(line.as_bytes())
}

/// 只留最后 4000 行：这本账是给人翻的，不该长成无限大的文件。
fn prune(p: &Path) {
    let Ok(text) = fs::read_to_string(p) else { return };
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    if lines.len() > KEEP {
        let body = lines[lines.len() - KEEP..].join("\n");
        let _ = fs::write(p, format!("{body}\n"));
    }
}

/**
 * `Env.excludeFromGit`：把工具产物目录写进 `.git/info/exclude`。
 *
 * `info/exclude` 是**本地**忽略，不入库、不产生 diff，正好放这种工具垃圾。
 * 不做这一步，用户跑完一次任务 `git status` 就会看到 `.haoai-snap/`、`.haoai-output/`
 * 两条永远不该出现的未跟踪项。
 */
pub fn exclude_from_git(workspace: &Path, names: &[&str]) {
    let mut dir = Some(workspace.to_path_buf());
    while let Some(d) = dir {
        let git = d.join(".git");
        if git.exists() {
            let exclude = git.join("info/exclude");
            let _ = fs::create_dir_all(exclude.parent().unwrap_or(Path::new(".")));
            let have = fs::read_to_string(&exclude).unwrap_or_default();
            let missing: Vec<&str> = names.iter().copied().filter(|n| !have.contains(n)).collect();
            if !missing.is_empty() {
                let body = missing.iter().map(|n| format!("/{n}/")).collect::<Vec<_>>().join("\n");
                let _ = append(
                    &exclude,
                    &format!("\n# HaoAI PC 的产物目录（本地忽略，不入库）\n{body}\n"),
                );
            }
            return;
        }
        dir = d.parent().map(|p| p.to_path_buf());
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn sandbox(tag: &str) -> PathBuf {
        let p = std::env::temp_dir().join(format!("haoai-snap-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(p.join("home")).unwrap();
        fs::create_dir_all(p.join("ws/src")).unwrap();
        p
    }

    #[test]
    fn key_of_flattens_the_relative_path_so_two_same_names_do_not_collide() {
        let root = sandbox("key");
        let ws = root.join("ws");
        fs::write(ws.join("src/A.md"), "x").unwrap();
        fs::create_dir_all(&ws).unwrap();
        let a = key_of(&ws, &ws.join("src/A.md"));
        assert_eq!(a.replace('\\', "/"), "src__A.md", "{a}");
        // 工作区外的目标拿不到相对路径 → 回落到裸文件名
        assert_eq!(key_of(&ws, Path::new("Q:\\elsewhere\\B.md")).contains("B.md"), true);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn checkpoint_lines_keep_the_kotlin_key_order() {
        let root = sandbox("jsonl");
        let home = root.join("home");
        begin(&home, "s1", "r1", "把 diff 接上", 3);
        note(&home, "r1", "s1", "src/A.md", Some(&home.join("snap1")));
        note(&home, "", "s1", "不该记", None); // 不在任何一轮里 → 不记账
        let text = fs::read_to_string(home.join("checkpoints.jsonl")).unwrap();
        let lines: Vec<&str> = text.lines().filter(|l| !l.is_empty()).collect();
        assert_eq!(lines.len(), 2, "run 为空那条不该落盘：{lines:?}");

        // 头行：goal/path/snap 各有其位，at 带上那句的下标
        let head: serde_json::Value = serde_json::from_str(lines[0]).unwrap();
        assert_eq!(head["goal"], "把 diff 接上");
        assert_eq!(head["path"], "");
        assert_eq!(head["at"], 3);
        assert!(head["ts"].as_i64().unwrap() > 0);
        // 键序就是 `buildJsonObject` 的声明序，Kotlin 那边按这个顺序读旧文件
        let keys: Vec<&str> = head.as_object().unwrap().keys().map(|s| s.as_str()).collect();
        assert_eq!(keys, vec!["run", "sid", "ts", "goal", "path", "snap", "at"], "头行：{}", lines[0]);

        let row: serde_json::Value = serde_json::from_str(lines[1]).unwrap();
        assert_eq!(row["path"], "src/A.md");
        assert_eq!(row["at"], -1, "note 的 at 默认 -1");
        assert!(row["snap"].as_str().unwrap().ends_with("snap1"));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn a_repeated_key_gets_the_next_free_name_instead_of_overwriting() {
        // 撞名这条分支不能用"连调两次 snapshot"来测：那取决于两次调用是否落在同一毫秒，
        // 会是一条时好时坏的测试。直接测命名器，输入完全可控。
        let root = sandbox("free");
        let dir = root.join(".haoai-snap");
        fs::create_dir_all(&dir).unwrap();
        let first = free_name(&dir, "1700-src__a.rs");
        fs::write(&first, "最早那份").unwrap();
        let second = free_name(&dir, "1700-src__a.rs");
        assert_eq!(second, dir.join("1700-src__a.rs-1"), "{second:?}");
        fs::write(&second, "第二份").unwrap();
        assert_eq!(free_name(&dir, "1700-src__a.rs"), dir.join("1700-src__a.rs-2"));
        // 谁也没被盖掉
        assert_eq!(fs::read_to_string(&first).unwrap(), "最早那份");
        assert_eq!(fs::read_to_string(&second).unwrap(), "第二份");
        let _ = fs::remove_dir_all(&root);
    }

    /// `\r` 在 Kotlin 的 `quote` 里被**删掉**（不是转义），中文按原样写 —— 两端读同一份账本
    /// 时这两个细节都会露出来，所以专门钉一条。
    #[test]
    fn the_ledger_survives_chinese_and_crlf_in_the_goal() {
        let root = sandbox("cjk");
        let home = root.join("home");
        begin(&home, "s2", "r2", "改「引号」与\r换行", -1);
        let text = fs::read_to_string(home.join("checkpoints.jsonl")).unwrap();
        assert!(text.contains(r#"goal":"改「引号」与换行""#), "{text}");
        let v: serde_json::Value = serde_json::from_str(text.trim()).unwrap();
        assert_eq!(v["sid"], "s2");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn prune_keeps_the_last_four_thousand_lines() {
        let root = sandbox("prune");
        let home = root.join("home");
        let p = home.join("checkpoints.jsonl");
        fs::create_dir_all(&home).unwrap();
        let big = (0..(KEEP + 10)).map(|i| format!(r#"{{"run":"r{i}"}}"#)).collect::<Vec<_>>().join("\n");
        fs::write(&p, format!("{big}\n")).unwrap();
        prune(&p);
        let n = fs::read_to_string(&p).unwrap().lines().count();
        assert_eq!(n, KEEP, "裁剪后应正好留 {KEEP} 行，实际 {n}");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn exclude_from_git_writes_only_the_missing_names() {
        let root = sandbox("exclude");
        let ws = root.join("ws");
        fs::create_dir_all(ws.join(".git/info")).unwrap();
        fs::write(ws.join(".git/info/exclude"), "already-there\n").unwrap();
        exclude_from_git(&ws, &[".haoai-snap", ".haoai-output"]);
        let text = fs::read_to_string(ws.join(".git/info/exclude")).unwrap();
        assert!(text.contains("/.haoai-snap/"), "{text}");
        assert!(text.contains("# HaoAI PC 的产物目录"), "{text}");
        // 再跑一次不该重复追加
        let before = text.len();
        exclude_from_git(&ws, &[".haoai-snap"]);
        assert_eq!(fs::read_to_string(ws.join(".git/info/exclude")).unwrap().len(), before);
        let _ = fs::remove_dir_all(&root);
    }
}
