mod approval;
mod engine;
#[cfg(test)]
mod e2e;
mod checkpoints;
mod catalog;
mod diff;
mod flags;
mod guard;
mod http;
#[cfg(test)]
mod golden;
mod policies;
mod prompt;
mod provider;
mod risk;
mod shell;
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

/// 常驻内存的会话条数上限（`Server.maxResident`）。
const MAX_RESIDENT: usize = 12;

pub struct App {
    pub store: store::Store,
    /// 全局设置（`WebServer.settings` 是个 var，POST /api/settings 会换掉它）。
    pub settings: Mutex<store::Settings>,
    /// 最近使用顺序（**新的在前**），等价于 `Server.order`。
    ///
    /// 为什么不是"一个当前会话 id"：`currentId()` 取的是这份表里**第一个还驻留的**，
    /// 于是删掉当前那条会话之后，当前会自动落到次新的那条上；只存一个 id 的话
    /// 删完就变成"没有当前会话"，界面上次新那条不该是空的。
    /// 它同时是驻留上限的账本（见 `touch`）。
    pub order: Mutex<Vec<String>>,
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
    /// S2 权限规则表（`rules.json`）。进程内一份：表很小，不该每次工具调用都读盘。
    pub policies: policies::Policies,
    /// 审批/提问的等待状态机。每个进程一份，**不是全局**：状态挂在一个会话上。
    pub approvals: approval::ApprovalBroker,
    /// 待挂到下一条工具消息上的审批结论（`Engine.markApproval`/`takeNote`）。
    /// 之前它只随 SSE 流一次：刷新之后卡没了，"这文件到底是谁点头写的"就查不出来。
    pub notes: Mutex<HashMap<String, String>>,
    /// 会话的**活档位**（`allow_session` 与 `/api/mode` 改的是它）。
    /// 查的时候 `mode_of(sid, 文件里的 mode)`：没有覆盖就用会话文件那条。
    /// "本任务都允许"绝不能把别的会话也切成 auto。
    pub modes: Mutex<HashMap<String, String>>,
    /// 活着的那条会话的标题（`engine.session.title` 的等价物）。
    ///
    /// 为什么要单独一份而不是只改文件：`stateJson` 的标题读的是**引擎内存**
    /// （`e?.session?.title?.get() ?: "新会话"`），而每回合结束的 `persist()` 又把这份
    /// 写回文件。只改文件的话，改名之后下一回合会被旧标题盖回去；
    /// 只改内存的话，重启就丢了。Kotlin 两边都改，这里也两边都改。
    pub titles: Mutex<HashMap<String, String>>,
    /// 按会话的设置覆盖（`/api/model` 与 `/api/tool` 写的那一层）。
    ///
    /// Kotlin 那边它活在 `engine.settings` 里，而**没落盘**：`modelSet` 走的是
    /// `useSettings(copy(model=…))`，后面没有 `persistNow()`。所以改了模型之后
    /// `/api/state` 立刻看得见，会话文件里却还是旧的 —— 重启就退回旧值，
    /// 除非中间跑过一回合（persist 会把引擎那份写回去）。这个不对称是它的行为，照抄。
    pub overlays: Mutex<HashMap<String, SessionOverlay>>,
}

/// 一条会话的活覆盖。`None` = 这一层没动过，用文件里那一份。
#[derive(Clone, Default)]
pub struct SessionOverlay {
    pub model: Option<String>,
    pub tools_off: Option<Vec<String>>,
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

    /// 规则表要么整份读、要么不改：给闭包一把锁，别把 store 漏在外面。
    pub fn policies_with<F, T>(&self, f: F) -> T
    where
        F: FnOnce(&mut policies::PolicyStore) -> T,
    {
        self.policies.with(f)
    }

    /// 活档位优先，回落到会话文件里的那个。
    pub fn mode_of(&self, sid: &str, fallback: &str) -> String {
        self.modes
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(sid)
            .cloned()
            .unwrap_or_else(|| fallback.to_string())
    }

    pub fn set_mode(&self, sid: &str, mode: &str) {
        if sid.is_empty() {
            return;
        }
        self.modes
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(sid.to_string(), mode.to_string());
    }

    pub fn mark_approval(&self, sid: &str, note: &str) {
        self.notes
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(sid.to_string(), note.to_string());
    }

    /// 活标题。`None` = 这条会话在本进程里没被打开过（Kotlin 那边就是"没有引擎"）。
    pub fn title_of(&self, sid: &str) -> Option<String> {
        self.titles.lock().unwrap_or_else(|p| p.into_inner()).get(sid).cloned()
    }

    pub fn set_title(&self, sid: &str, title: &str) {
        if sid.is_empty() {
            return;
        }
        self.titles
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(sid.to_string(), title.to_string());
    }

    /// 全局设置的一份快照（`WebServer.settings` 是个 var：改设置、按会话换模型都读活值）。
    pub fn global(&self) -> store::Settings {
        self.settings.lock().unwrap_or_else(|p| p.into_inner()).clone()
    }

    pub fn set_global(&self, s: store::Settings) {
        *self.settings.lock().unwrap_or_else(|p| p.into_inner()) = s;
    }

    /**
     * **这条会话**该用哪份设置 = 全局 + 会话文件那层覆盖 + `/api/model`、`/api/tool`
     * 的活覆盖（对应 `EngineFactory.applySessionOverlay` 与 `engine.useSettings`）。
     *
     * 覆盖的口径有两处容易想错，都照 Kotlin 抄死：
     * 1. `model` 与 `toolsOff` 是**替换**而不是合并。会话文件里 `model` 一直有值
     *    （persist 每回合都写），所以这层覆盖基本总在经济上生效 —— 于是"全局关掉 shell"
     *    对一条已在跑的会话**并不成立**，它用的是自己那张表。界面上"全局"那半边亮着、
     *    这条会话却照旧能用 shell，就是这么来的。Rust 之前拿的是"全局 ∪ 会话"，
     *    比那边严一档，两边会在"全局关了某把工具"时给出不同的工具清单。
     * 2. 只有 `model` 非空**或** `toolsOff` 非空才走覆盖；两者都空时用的就是全局那份。
     */
    pub fn settings_of(&self, sid: &str) -> store::Settings {
        let mut st = self.global();
        if sid.is_empty() {
            return st;
        }
        if let Some(m) = self.store.meta(sid) {
            st = store::overlay(&st, &m);
        }
        let o = self.overlays.lock().unwrap_or_else(|p| p.into_inner());
        if let Some(ov) = o.get(sid) {
            if let Some(md) = &ov.model {
                st.model = md.clone();
            }
            if let Some(off) = &ov.tools_off {
                st.tools_off = off.clone();
            }
        }
        st
    }

    /// 这条会话该按"关着"显示/过滤的工具集合：自己那份，再剔掉 critical
    /// （`AgentConfigs.effectiveToolsOff` —— 关掉 read/glob/grep/task/todo 等于砍掉引擎，
    /// 所以那五把**不认**关闭请求，界面上也不许亮成关着）。
    pub fn tools_off_of(&self, sid: &str) -> Vec<String> {
        self.settings_of(sid)
            .tools_off
            .iter()
            .filter(|t| !crate::guard::is_critical(t))
            .cloned()
            .collect()
    }

    /// 按会话换模型：只改这条会话的活覆盖，不碰全局（全局那条走 `/api/settings`）。
    pub fn set_session_model(&self, sid: &str, model: &str) {
        let mut m = self.overlays.lock().unwrap_or_else(|p| p.into_inner());
        m.entry(sid.to_string()).or_default().model = Some(model.to_string());
    }

    pub fn set_session_tools_off(&self, sid: &str, off: &[String]) {
        let mut m = self.overlays.lock().unwrap_or_else(|p| p.into_inner());
        m.entry(sid.to_string()).or_default().tools_off = Some(off.to_vec());
    }

    /// 会话被删掉之后要清的一切：最近表、驻留、活档位、活标题、活历史、按会话的覆盖。
    /// 留着不清的后果是"删了的会话还在侧栏"——`/api/state` 还能把它拼出来。
    pub fn forget(&self, sid: &str) {
        self.order.lock().unwrap_or_else(|p| p.into_inner()).retain(|x| x != sid);
        self.resident.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
        self.titles.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
        self.modes.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
        self.overlays.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
        self.histories.lock().unwrap_or_else(|p| p.into_inner()).remove(sid);
    }

    /// `Server.touch`：把它挪到最前面，并**把超出上限、又没在跑的最久没碰的那条请出去**。
    /// 每条会话在内存里是一整段历史，开二十条不去管就是几百 MB；
    /// 历史本来就落盘在 `sessions/pc-<id>.json`，下次 `/api/open` 按原 id 重建，用户看不出差别。
    pub fn touch(&self, sid: &str) {
        if sid.is_empty() {
            return;
        }
        {
            let mut res = self.resident.lock().unwrap_or_else(|p| p.into_inner());
            res.insert(sid.to_string());
        }
        let mut order = self.order.lock().unwrap_or_else(|p| p.into_inner());
        order.retain(|x| x != sid);
        order.insert(0, sid.to_string());
        while order.len() > MAX_RESIDENT {
            let victim = match order.iter().rev().find(|id| !self.is_running(id)) {
                Some(v) => v.clone(),
                None => break,
            };
            self.resident.lock().unwrap_or_else(|p| p.into_inner()).remove(&victim);
            order.retain(|x| x != &victim);
        }
    }

    /// `Server.currentId()`：最近表里**第一个还驻留的**。全都没驻留就是空串。
    pub fn current_id(&self) -> String {
        let order = self.order.lock().unwrap_or_else(|p| p.into_inner());
        let res = self.resident.lock().unwrap_or_else(|p| p.into_inner());
        order.iter().find(|id| res.contains(id.as_str())).cloned().unwrap_or_default()
    }

    /// 取走（并清空）：一句结论只属于紧接着的那一条工具消息，留着会挂到下一次调用上。
    pub fn take_note(&self, sid: &str) -> String {
        self.notes
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(sid)
            .unwrap_or_default()
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

impl App {
    /// 测试专用装配：状态根给一个临时目录，其余全空。
    /// 六个测试各写一遍字段清单是错的 —— 加一个字段就要漏改一处，
    /// 而漏改的表现是"编译不过"而不是"测错了"，至少这条还站在我们这边。
    #[cfg(test)]
    pub fn at(home: std::path::PathBuf) -> Arc<App> {
        let store = store::Store::new(home);
        let settings = Mutex::new(store.settings());
        Arc::new(App {
            policies: policies::Policies::new(&store.home),
            approvals: approval::ApprovalBroker::with_timeouts(2, 2),
            notes: Mutex::new(HashMap::new()),
            modes: Mutex::new(HashMap::new()),
            titles: Mutex::new(HashMap::new()),
            overlays: Mutex::new(HashMap::new()),
            order: Mutex::new(vec![]),
            resident: Mutex::new(HashSet::new()),
            bus: Mutex::new(vec![]),
            histories: Mutex::new(HashMap::new()),
            running: Mutex::new(HashSet::new()),
            stops: Mutex::new(HashSet::new()),
            usage: Mutex::new(HashMap::new()),
            store,
            settings,
        })
    }

    /// 让一条会话驻留并切成当前会话（等价于前端先打过 `/api/open`）。
    #[cfg(test)]
    pub fn open_for_test(&self, sid: &str) {
        self.touch(sid);
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
///
/// 出口按 Java 的 `File` 归一分隔符：Kotlin 侧 `Env.home` 是个 `File`，构造时就把
/// `/` 换成 `\`。不归一的话，HAOAI_HOME 用正斜杠（bash 这么写最顺手）时，
/// 系统提示里那 15 条技能路径会一边是 `G:\x\skills\…`、一边是 `G:/x\skills\…` ——
/// 两边**长度一样**，所以逐字节的金标准抓不到，但文本确实分叉了。
pub fn home() -> PathBuf {
    let raw = if let Some(p) = env::var("HAOAI_HOME").ok().filter(|s| !s.trim().is_empty()) {
        p
    } else {
        match env::var("LOCALAPPDATA") {
            Ok(d) => PathBuf::from(d).join("HaoAI").to_string_lossy().to_string(),
            Err(_) => "HaoAI".to_string(),
        }
    };
    PathBuf::from(raw.replace('/', "\\"))
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
    let settings = Mutex::new(store.settings());
    // 等价于 `start()` 里那句 `SessionIndex.list(5).firstOrNull()?.let { touch(it.id) }`：
    // 重启后要接上上次用的那条，而不是每次开一个空白新会话。
    let first = store.list(5).first().map(|m| m.id.clone()).unwrap_or_default();
    let app = Arc::new(App {
        store,
        settings,
        order: Mutex::new(vec![]),
        resident: Mutex::new(HashSet::new()),
        bus: Mutex::new(vec![]),
        histories: Mutex::new(HashMap::new()),
        running: Mutex::new(HashSet::new()),
        stops: Mutex::new(HashSet::new()),
        usage: Mutex::new(HashMap::new()),
        policies: policies::Policies::new(&h),
        approvals: approval::ApprovalBroker::new(),
        notes: Mutex::new(HashMap::new()),
        modes: Mutex::new(HashMap::new()),
        titles: Mutex::new(HashMap::new()),
        overlays: Mutex::new(HashMap::new()),
    });
    // 重启后接上的那条会话在 Kotlin 那边是 `touch(id)` → 建引擎 → `title.set(meta.title)`，
    // 于是 `/api/state` 报的是**引擎内存里那份**标题。这里同样先 touch 再种活标题，
    // 否则下一次 `persist()` 会把文件里的旧标题当"活标题"再写一遍。
    if !first.is_empty() {
        app.touch(&first);
        if let Some(m) = app.store.meta(&first) {
            app.set_title(&first, &m.title);
        }
    }

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
