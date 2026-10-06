//! 一次性命令执行：`ShellTool` + `Pty.kt` 的 `ShellLauncher`。
//!
//! **Windows 上不能把命令直接当 argv 塞给 bash/pwsh**：进程创建方会把参数拼成一条命令行
//! 字符串交给 CreateProcess，MSYS 的 bash 再按自己的规则二次解析 —— 结果就是命令里的引号
//! 被吃掉（实测 `python -c "print('x'*400)"` 到了 bash 变成 `python -c print(x*400)`，
//! syntax error）。凡是带引号的命令都会踩。
//! 解法与 Kotlin 同一套：**命令写进临时脚本文件**，让 shell 去读文件（`bash -l file.sh` /
//! `pwsh -File file.ps1`），跑完删掉。
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::diff::kotlin_lines;

pub struct Launcher {
    pub exe: String,
    pub args: Vec<&'static str>,
}

/// 与常驻进程共用同一套"在这台 Windows 上找到 shell"的逻辑（两处各写一份候选路径，
/// 将来一定有一份改了另一份没改）。
pub fn launcher_for(shell: &str) -> Option<Launcher> {
    match shell.to_lowercase().as_str() {
        "bash" | "sh" => bash_path().map(|exe| Launcher { exe, args: vec!["-l"] }),
        "pwsh" | "powershell" => pwsh_path().map(|exe| Launcher {
            exe,
            args: vec!["-NoProfile", "-NonInteractive", "-File"],
        }),
        "cmd" => cmd_path().map(|exe| Launcher { exe, args: vec!["/c"] }),
        _ => None,
    }
}

/// 脚本后缀：Kotlin 那边按**找到的可执行文件名**判，不是按用户传的那个词
/// （`shell="powershell"` 落到 Windows PowerShell 时走的也是 pwsh 这条写法）。
pub fn script_kind(exe: &str) -> &'static str {
    let e = exe.to_lowercase();
    if e.ends_with("pwsh.exe") || e.ends_with("powershell.exe") {
        "pwsh"
    } else if e.ends_with("cmd.exe") {
        "cmd"
    } else {
        "sh"
    }
}

fn env_path(key: &str) -> Option<String> {
    std::env::var(key).ok().filter(|v| !v.trim().is_empty())
}

fn is_file(p: &str) -> bool {
    Path::new(p).is_file()
}

fn bash_path() -> Option<String> {
    let mut cands: Vec<String> = Vec::new();
    if let Some(pf) = env_path("ProgramFiles") {
        cands.push(format!("{pf}\\Git\\bin\\bash.exe"));
        cands.push(format!("{pf}\\Git\\usr\\bin\\bash.exe"));
    }
    cands.push("E:\\Git\\bin\\bash.exe".to_string());
    cands.push("C:\\Program Files\\Git\\bin\\bash.exe".to_string());
    cands.into_iter().find(|c| is_file(c))
}

fn pwsh_path() -> Option<String> {
    if let Some(p) = which("pwsh") {
        return Some(p);
    }
    let sysroot = env_path("SystemRoot")?;
    let p = PathBuf::from(sysroot).join("System32\\WindowsPowerShell\\v1.0\\powershell.exe");
    if p.is_file() {
        Some(p.to_string_lossy().to_string())
    } else {
        None
    }
}

fn cmd_path() -> Option<String> {
    env_path("SystemRoot")
        .map(|r| PathBuf::from(r).join("System32\\cmd.exe"))
        .filter(|p| p.is_file())
        .map(|p| p.to_string_lossy().to_string())
}

/// `which`：Windows 上 PATH 以 `;` 分隔，且要试 `.exe` 与 `.cmd` 两个后缀。
fn which(cmd: &str) -> Option<String> {
    let path = env_path("PATH")?;
    let exts: &[&str] = if cfg!(windows) { &[".exe", ".cmd"] } else { &[""] };
    for d in path.split(if cfg!(windows) { ';' } else { ':' }) {
        for e in exts {
            let cand = PathBuf::from(d).join(format!("{cmd}{e}"));
            if cand.is_file() {
                // Kotlin 的 `absolutePath` 不解析符号链接，这里也不能用 canonicalize
                return Some(cand.to_string_lossy().to_string());
            }
        }
    }
    None
}

/// 不切代码页的话，中文输出在 PowerShell 下必乱码（本机实测）。
const PWSH_UTF8_PREFIX: &str = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;";

pub struct Run {
    /// None = 超时被杀
    pub exit: Option<i32>,
    pub out: String,
}

/**
 * 跑一条脚本。stdout 与 stderr **在操作系统层面合成一条流**，
 * 因为 Kotlin 那边是 `redirectErrorStream(true)`：命令自己打的错误信息和正常输出
 * 是按发生顺序交替出现的，分两条流读回来就会丢掉顺序（"先报错后成功"看起来像先成功后报错）。
 *
 * 做法是把两个句柄都指向同一个临时文件，退出后整份读回来 —— 顺带还拿到了
 * "超时被杀之前已经输出到哪"这半段，那正是超时那条分支要回给模型的东西。
 */
pub fn run(
    l: &Launcher,
    command: &str,
    cwd: &Path,
    workspace: &Path,
    timeout_sec: i64,
) -> Result<Run, String> {
    run_in(&std::env::temp_dir(), l, command, cwd, workspace, timeout_sec)
}

/// 脚本与输出文件放在哪 —— 生产用系统临时目录，**测试传自己的私有目录**：
/// 共享目录里数 `haoai-cmd-*` 会被别人（同期跑的 JVM 引擎也往同一个 TEMP 写）带偏，
/// 那会得到一条时好时坏的清理断言。
fn run_in(
    scratch: &Path,
    l: &Launcher,
    command: &str,
    cwd: &Path,
    workspace: &Path,
    timeout_sec: i64,
) -> Result<Run, String> {
    let kind = script_kind(&l.exe);
    let dir = scratch.to_path_buf();
    let stem = format!("haoai-cmd-{}-{}", std::process::id(), now_nanos());
    let ext = if kind == "pwsh" { ".ps1" } else if kind == "cmd" { ".bat" } else { ".sh" };
    let script = dir.join(format!("{stem}{ext}"));
    // 输出文件放系统临时目录，不污染用户仓库；跑完连脚本一起删
    let outfile = dir.join(format!("{stem}.out"));
    let body = if kind == "pwsh" { format!("{PWSH_UTF8_PREFIX} {command}") } else { command.to_string() };
    // Windows PowerShell 5.1 没有 BOM 就按 GBK 读脚本，命令里只要出现中文就会被解坏
    // （表现是"命令跑通了但参数是乱码"）。
    let written = if kind == "pwsh" {
        let mut bytes = vec![0xEFu8, 0xBB, 0xBF];
        bytes.extend_from_slice(body.as_bytes());
        fs::write(&script, bytes)
    } else {
        fs::write(&script, body.as_bytes())
    };
    if let Err(e) = written {
        return Err(format!("临时脚本写不下去：{e}"));
    }

    let dir_run = if cwd.is_dir() { cwd.to_path_buf() } else { workspace.to_path_buf() };
    let executed = execute(&l, &script, &outfile, &dir_run, timeout_sec);
    let _ = fs::remove_file(&script);
    let _ = fs::remove_file(&outfile);
    let (exit, raw) = executed?;

    // Kotlin 那边是 InputStreamReader(…, UTF_8) 一行行读、每行补一个 `\n`：
    // 于是 CRLF 会被归一成 LF。这里读整份再按同样的口径重排，两端逐字节一致。
    let text = String::from_utf8_lossy(&raw).to_string();
    let out = if text.is_empty() {
        String::new()
    } else {
        format!("{}\n", kotlin_lines(&text).join("\n"))
    };
    Ok(Run { exit, out })
}

fn execute(
    l: &Launcher,
    script: &Path,
    outfile: &Path,
    dir: &Path,
    timeout_sec: i64,
) -> Result<(Option<i32>, Vec<u8>), String> {
    let f = fs::File::create(outfile).map_err(|e| format!("执行失败：{e}"))?;
    let f2 = f.try_clone().map_err(|e| format!("执行失败：{e}"))?;
    let mut cmd = Command::new(&l.exe);
    cmd.args(&l.args)
        .arg(script)
        .current_dir(dir)
        .env("PYTHONUTF8", "1")
        .env("PYTHONIOENCODING", "utf-8")
        // 脚本不读 stdin；给 null 而不是管道，免得有谁在等一个永远不来的 EOF
        .stdin(Stdio::null())
        .stdout(Stdio::from(f2))
        .stderr(Stdio::from(f));
    let mut child = cmd.spawn().map_err(|e| format!("执行失败：{e}"))?;

    let deadline = Instant::now() + Duration::from_secs(timeout_sec.max(1) as u64);
    let exit = loop {
        match child.try_wait() {
            Ok(Some(st)) => break st.code(),
            Ok(None) if Instant::now() >= deadline => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(50)),
            Err(e) => return Err(format!("执行失败：{e}")),
        }
    };
    Ok((exit, fs::read(outfile).unwrap_or_default()))
}

fn now_nanos() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_launcher_table_matches_kotlin_candidates() {
        // 用户传的这个词必须能落到一个真实存在的可执行文件；认不出来就回 None，
        // 表现是那句"这台机器上找不到 X"，而不是起一个假进程
        assert!(launcher_for("nosuchshell").is_none());
        assert!(launcher_for("pwsh").map(|l| l.args.first() == Some(&"-NoProfile")).unwrap_or(true));
        if let Some(l) = launcher_for("cmd") {
            assert_eq!(l.args, vec!["/c"]);
        }
        if let Some(l) = launcher_for("bash") {
            assert_eq!(l.args, vec!["-l"]);
        }
    }

    #[test]
    fn script_kind_is_decided_by_the_binary_found_not_the_word_asked() {
        assert_eq!(script_kind("C:\\Program Files\\PowerShell\\7\\pwsh.exe"), "pwsh");
        assert_eq!(script_kind("C:\\WINDOWS\\system32\\WINDOWSPOWERSHELL\\v1.0\\powershell.exe"), "pwsh");
        assert_eq!(script_kind("C:\\Windows\\System32\\cmd.exe"), "cmd");
        assert_eq!(script_kind("E:\\Git\\bin\\bash.exe"), "sh");
    }

    #[test]
    fn cmd_is_resolved_from_systemroot() {
        if env_path("SystemRoot").is_none() {
            return;
        }
        let c = cmd_path().expect("SystemRoot 在就该找得到 cmd.exe");
        assert!(c.to_lowercase().ends_with("cmd.exe"), "{c}");
    }

    /// 真的起一个进程跑一条命令，看三件事：退出码进不进内容、输出怎么归一行尾、
    /// 以及 stderr 是不是真的并到了同一条流里。
    #[test]
    fn a_real_command_reports_exit_code_and_merged_output() {
        let Some(l) = launcher_for("cmd") else { return };
        let ws = std::env::temp_dir().join(format!("haoai-shell-{}", std::process::id()));
        let _ = fs::remove_dir_all(&ws);
        fs::create_dir_all(&ws).unwrap();
        let r = run(&l, "echo hello& echo boom 1>&2& exit 3", &ws, &ws, 60).expect("cmd 该跑起来");
        assert_eq!(r.exit, Some(3), "退出码要如实报：{:?}", r.exit);
        assert!(r.out.contains("hello"), "{:?}", r.out);
        assert!(r.out.contains("boom"), "stderr 必须并进同一条流：{:?}", r.out);
        assert!(!r.out.contains('\r'), "CRLF 该被归一成 LF（与 Kotlin 的 readLine 同）");
        let _ = fs::remove_dir_all(&ws);
    }

    /// 超时不是"卡住"：要杀掉、要说等多久、并把已经打出来的那半段交回去 ——
    /// 模型接着判断时靠的就是这半段。
    #[test]
    fn a_timeout_kills_the_process_and_keeps_what_it_already_printed() {
        let Some(l) = launcher_for("cmd") else { return };
        let ws = std::env::temp_dir().join(format!("haoai-shell-to-{}", std::process::id()));
        let _ = fs::remove_dir_all(&ws);
        fs::create_dir_all(&ws).unwrap();
        let r = run(&l, "echo first& ping -n 30 127.0.0.1 > nul", &ws, &ws, 1).unwrap();
        assert_eq!(r.exit, None, "超时这条不该有一个退出码");
        assert!(r.out.contains("first"), "已经打出来的要留下：{:?}", r.out);
        let _ = fs::remove_dir_all(&ws);
    }

    /// 这条要验证"跑完不留垃圾"，所以临时目录必须**这一条自己说了算** ——
    /// 数共享的系统临时目录会被同期跑的别的进程带偏（JVM 引擎的 ShellTool
    /// 也往同一个 TEMP 写 `haoai-cmd-*`），那得到的是一条时好时坏的断言。
    #[test]
    fn the_temp_script_is_deleted_whether_it_ran_or_not() {
        let Some(l) = launcher_for("cmd") else { return };
        let ws = std::env::temp_dir().join(format!("haoai-shell-clean-{}", std::process::id()));
        let scratch = std::env::temp_dir().join(format!("haoai-scratch-{}", std::process::id()));
        let _ = fs::remove_dir_all(&ws);
        let _ = fs::remove_dir_all(&scratch);
        fs::create_dir_all(&ws).unwrap();
        fs::create_dir_all(&scratch).unwrap();

        let _ = run_in(&scratch, &l, "echo x", &ws, &ws, 30).unwrap();
        assert_eq!(files_in(&scratch), 0, "成功的那次跑完必须把脚本与输出文件都删掉");

        // 失败的那次也不能留：超时被杀这条最容易半途撂下东西
        let _ = run_in(&scratch, &l, "ping -n 30 127.0.0.1 > nul", &ws, &ws, 1).unwrap();
        assert_eq!(files_in(&scratch), 0, "超时被杀这条路也要清干净");

        let _ = fs::remove_dir_all(&ws);
        let _ = fs::remove_dir_all(&scratch);
    }

    fn files_in(dir: &Path) -> usize {
        fs::read_dir(dir)
            .map(|rd| rd.filter_map(|e| e.ok()).count())
            .unwrap_or(0)
    }
}
