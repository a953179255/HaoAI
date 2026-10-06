mod engine;
#[cfg(test)]
mod e2e;
mod http;
#[cfg(test)]
mod golden;
mod prompt;
mod provider;
mod state;
mod store;
mod tools;
mod tools_impl;
mod utf16;

use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::io::ErrorKind;
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::exit;
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::thread;

pub const PC_VERSION: &str = "0.80.0-pc";

pub struct App {
    pub store: store::Store,
    pub settings: store::Settings,
    /// 当前会话 = 最近使用列表的头一个（`Server.currentId`）
    pub current: Mutex<String>,
    /// 已驻留的会话 id。`stateJson` 用 `sessions[it]` 纯 map 读、**不懒加载**，
    /// 所以"驻留与否"直接决定 `/api/state` 返回完整状态还是空态，必须照搬。
    pub resident: Mutex<HashSet<String>>,
    pub bus: Mutex<Vec<Sender<String>>>,
    /// 跑起来的会话的在内存历史。落盘之外还要有一份，是因为一轮里工具会往历史里
    /// 追加多条，而文件只在回合结束时写一次。
    pub histories: Mutex<HashMap<String, Vec<store::Msg>>>,
    /// 一条会话同时只跑一个任务（不同会话可并行）——与 Kotlin 侧同语义。
    pub running: Mutex<HashSet<String>>,
    /// 点了"停止"的会话（`Engine.stopRequested`）。回合边界与工具边界各查一次，
    /// 所以这是**收尾式**停止，不是掐断 HTTP 流。
    pub stops: Mutex<HashSet<String>>,
    /// 进程内的 token 活计数器（`Engine.totalPrompt/totalCompletion`）。
    /// **restore 时不从文件读回**，所以重启后从 0 重新累计 —— 这是 Kotlin 侧的既有行为，
    /// 连"落盘写的是活值、于是文件里的总数重启后会变小"也一起照抄，不顺手"修好"它。
    pub usage: Mutex<HashMap<String, (i64, i64)>>,
}

impl App {
    pub fn add_usage(&self, sid: &str, pt: i64, ct: i64) {
        let mut m = self.usage.lock().unwrap_or_else(|p| p.into_inner());
        let e = m.entry(sid.to_string()).or_insert((0, 0));
        e.0 += pt;
        e.1 += ct;
    }

    pub fn usage_of(&self, sid: &str) -> (i64, i64) {
        self.usage.lock().unwrap_or_else(|p| p.into_inner()).get(sid).copied().unwrap_or((0, 0))
    }
    /// 占坑。已被占用返回 false，调用方据此给前端 409。
    ///
    /// 清停止旗**必须与置 running 在同一把锁里**（`Engine.beginRun` 的注释专门说过）：
    /// 分两步的话"提交后立刻按停止"会被晚一步的清旗抹掉，症状是刚发出去的任务停不下来。
    pub fn try_begin(&self, sid: &str) -> bool {
        let mut r = self.running.lock().unwrap_or_else(|p| p.into_inner());
        let ok = r.insert(sid.to_string());
        if ok {
            self.stops.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
        }
        ok
    }

    pub fn request_stop(&self, sid: &str) {
        self.stops.lock().unwrap_or_else(|p| p.into_inner()).insert(sid.to_string());
    }

    pub fn stopped(&self, sid: &str) -> bool {
        self.stops.lock().unwrap_or_else(|p| p.into_inner()).contains(sid)
    }

    pub fn end_run(&self, sid: &str) {
        self.running.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
    }

    pub fn is_running(&self, sid: &str) -> bool {
        self.running.lock().unwrap_or_else(|p| p.into_inner()).contains(sid)
    }

    pub fn history(&self, sid: &str) -> Vec<store::Msg> {
        self.histories
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(sid)
            .cloned()
            .unwrap_or_default()
    }

    pub fn set_history(&self, sid: &str, h: Vec<store::Msg>) {
        self.histories
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(sid.to_string(), h);
    }
}

impl App {
    /// 帧格式与 `Server.write` 一致：`id: <sid>\n` + `event:` + `data:`。
    /// sid 走 SSE 的 id 字段，浏览器把它挂到 `MessageEvent.lastEventId`，
    /// 于是"这条事件属于哪个会话"不用改任何已有负载的格式就能带出去。
    pub fn publish(&self, sid: &str, event: &str, data: &str) {
        let frame = state::sse_frame(event, data, sid);
        let mut dead: Vec<usize> = vec![];
        if let Ok(mut subs) = self.bus.lock() {
            for (i, tx) in subs.iter().enumerate() {
                if tx.send(frame.clone()).is_err() {
                    dead.push(i);
                }
            }
            for i in dead.into_iter().rev() {
                subs.remove(i);
            }
        }
    }
}

fn main() {
    let argv: Vec<String> = env::args().skip(1).collect();
    let cmd = argv.first().map(String::as_str).unwrap_or("serve");
    let rest: &[String] = argv.get(1..).unwrap_or(&[]);
    match cmd {
        "serve" => serve(rest),
        other => {
            // doctor / sessions / lan 这些子命令还在 Kotlin 那边，M1 不假装已经移植。
            println!("not ported yet: {other}");
            exit(2);
        }
    }
}

/// 状态根。顺序与 `Env.home` 一致：`HAOAI_HOME` 覆盖 → `%LOCALAPPDATA%\HaoAI`。
pub fn home() -> PathBuf {
    if let Some(p) = env::var("HAOAI_HOME").ok().filter(|s| !s.trim().is_empty()) {
        return PathBuf::from(p);
    }
    match env::var("LOCALAPPDATA") {
        Ok(d) => PathBuf::from(d).join("HaoAI"),
        Err(_) => PathBuf::from("HaoAI"),
    }
}

fn port_of(rest: &[String]) -> u16 {
    // 两种写法都要认：`--port 8899` 与 `--port=8899`（Kotlin 侧因只认一种踩过坑）。
    for i in 0..rest.len() {
        if rest[i] == "--port" {
            if let Some(v) = rest.get(i + 1).and_then(|s| s.parse::<u16>().ok()) {
                return v;
            }
        }
        if let Some(v) = rest[i].strip_prefix("--port=") {
            if let Ok(p) = v.parse::<u16>() {
                return p;
            }
        }
    }
    8712
}

fn serve(rest: &[String]) {
    let want = port_of(rest);
    let listener = match TcpListener::bind(("127.0.0.1", want)) {
        Ok(l) => l,
        Err(e) => {
            // 与 JVM 版同行为：绑不上就是起不来，不悄悄挪到别的端口。
            println!("cannot bind 127.0.0.1:{want}: {e}");
            exit(1);
        }
    };
    let actual = listener.local_addr().map(|a| a.port()).unwrap_or(want);

    let h = home();
    let _ = fs::create_dir_all(&h);
    // 落的是**实际**端口：CLI 子命令靠这个文件找活着的实例，写死 8712 会在换端口时骗人。
    let _ = fs::write(h.join("webport"), actual.to_string());

    let store = store::Store::new(h.clone());
    let settings = store.settings();
    // 等价于 `start()` 里那句 `SessionIndex.list(5).firstOrNull()?.let { touch(it.id) }`：
    // 重启后要接上上次用的那条，而不是每次开一个空白新会话。
    let first = store.list(5).first().map(|m| m.id.clone()).unwrap_or_default();
    let resident = first.clone();
    let app = Arc::new(App {
        store,
        settings,
        current: Mutex::new(first),
        resident: Mutex::new([resident].into_iter().filter(|s| !s.is_empty()).collect()),
        bus: Mutex::new(vec![]),
        histories: Mutex::new(HashMap::new()),
        running: Mutex::new(HashSet::new()),
        stops: Mutex::new(HashSet::new()),
        usage: Mutex::new(HashMap::new()),
    });

    // stdout 一律英文：壳按 GBK(936) 解码 engine.log，中文 UTF-8 进去就是乱码。
    println!("HaoAI PC {PC_VERSION} (rust m1) http://127.0.0.1:{actual}/");
    println!("  state root: {}", h.display());

    for stream in listener.incoming() {
        match stream {
            Ok(s) => {
                let app = Arc::clone(&app);
                thread::spawn(move || http::handle(s, app));
            }
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(_) => break,
        }
    }
}
