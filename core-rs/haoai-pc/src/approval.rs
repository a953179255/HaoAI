//! 审批 / 提问的**等待状态机**。移植自 `pc/.../Approval.kt` 的 `ApprovalBroker`。
//!
//! 状态只有一条线：`PENDING → ANSWERED / TIMED_OUT`，出路唯一且在收尾时收摊
//! （maps 不漏删 —— 漏删的表现是"这条审批永远在等人"）。
//! **超时可注入**：测试给 1 秒就把超时这条路径真跑一遍（300 秒没人会去等）。
//! fail-closed 写死在返回值里：超时给 `deny`（审批）/ `""`（提问，模型看到"没人回答"）。
//!
//! 用 `Mutex<Option<String>> + Condvar` 而不是 mpsc 通道：`complete` 必须"第一次赢"，
//! 而通道会让第二个答复者也成功投递 —— 一张卡被答两次比没人答更糟（模型会收到两条矛盾结论）。
//!
//! **阻塞发生在调用方线程**（引擎是同步语义："一次只跑一个回合"）。这个状态机描述的是
//! "等待"这段的内部结构，不是把审批闸改成异步。
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

use crate::risk;
use crate::state::quote;

/// 一条挂着的等待。payload 是给界面/手机的完整载荷（含 id）。
#[derive(Clone, Debug)]
pub struct Waiter {
    pub ev: &'static str,
    pub payload: String,
    pub sid: String,
}

/// 等待的结局。`value` 已经是 fail-closed 之后的答案，调用方不需要再判超时。
pub struct Resolution {
    pub value: String,
    pub timed_out: bool,
}

/// 一次 `complete` 的回执：这几个值都在**答复之前**取好 —— 答复之后引擎会立刻销号
/// （收摊在 finally），事后补读就读不到了。
pub struct Completed {
    pub existed: bool,
    pub already: bool,
    pub is_ask: bool,
    pub sid: String,
}

/// `abort` 的账：拒了几个审批、几个提问（给"顺手拒掉 N 个"那句通知用）。
#[derive(Default)]
pub struct AbortSummary {
    pub approvals: usize,
    pub asks: usize,
}

impl AbortSummary {
    pub fn total(&self) -> usize {
        self.approvals + self.asks
    }
}

#[derive(Default)]
struct Slot {
    answer: Mutex<Option<String>>,
    ready: Condvar,
}

pub struct ApprovalBroker {
    seq: Mutex<usize>,
    slots: Mutex<HashMap<String, Arc<Slot>>>,
    /// 插入序，不是哈希序：`/api/state` 的 pending 投影要稳定，否则同一份等待
    /// 两次刷新可能给出不同顺序，前端 diff 出来的就是"卡片自己跳"。
    order: Mutex<Vec<String>>,
    waiters: Mutex<HashMap<String, Waiter>>,
    pub approval_timeout_sec: u64,
    pub ask_timeout_sec: u64,
}

impl ApprovalBroker {
    /// 生产用 300/900（与 Kotlin 同），测试把秒数注小才能跑到超时那条分支。
    pub fn new() -> Self {
        Self {
            seq: Mutex::new(0),
            slots: Mutex::new(HashMap::new()),
            order: Mutex::new(Vec::new()),
            waiters: Mutex::new(HashMap::new()),
            approval_timeout_sec: 300,
            ask_timeout_sec: 900,
        }
    }

    pub fn with_timeouts(approval_timeout_sec: u64, ask_timeout_sec: u64) -> Self {
        Self {
            approval_timeout_sec,
            ask_timeout_sec,
            ..Self::new()
        }
    }

    fn lock<'a, T>(&self, m: &'a Mutex<T>) -> std::sync::MutexGuard<'a, T> {
        m.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// 当前还挂着的（id → 等待），给 `stateJson` 的 pending 投影用。
    pub fn rows(&self) -> Vec<(String, Waiter)> {
        let w = self.lock(&self.waiters);
        self.lock(&self.order)
            .iter()
            .filter_map(|id| w.get(id).map(|x| (id.clone(), x.clone())))
            .collect()
    }

    pub fn pending_for(&self, sid: &str) -> Vec<Waiter> {
        self.rows().into_iter().map(|(_, v)| v).filter(|v| v.sid == sid).collect()
    }

    pub fn kind(&self, id: &str) -> &'static str {
        self.lock(&self.waiters).get(id).map(|w| w.ev).unwrap_or("")
    }

    pub fn size(&self) -> usize {
        self.lock(&self.waiters).len()
    }

    fn next_id(&self, prefix: &str) -> String {
        let mut s = self.lock(&self.seq);
        *s += 1;
        format!("{prefix}{s}")
    }

    /// 注册 → 推卡 → 等人答。`make_payload` 拿到 id 之后现造载荷（载荷里要写 id，
    /// 所以 id 必须先出生）。超时发"没人应答"的通知并按拒绝处理。
    pub fn await_approval(
        &self,
        sid: &str,
        publish: &mut dyn FnMut(&str, &str, &str),
        make_payload: &dyn Fn(&str) -> String,
    ) -> Resolution {
        let id = self.next_id("a");
        let slot = Arc::new(Slot::default());
        let payload = make_payload(&id);
        self.register(&id, slot.clone(), Waiter { ev: "approval", payload: payload.clone(), sid: sid.to_string() });
        publish("approval", &payload, sid);
        let out = self.await_slot(&id, slot, self.approval_timeout_sec, "deny");
        if out.timed_out {
            publish("notice", &quote(&format!("{} 秒无人应答，按拒绝处理", self.approval_timeout_sec)), sid);
        }
        out
    }

    fn await_slot(&self, id: &str, slot: Arc<Slot>, secs: u64, on_timeout: &str) -> Resolution {
        let out = {
            let g = slot.answer.lock().unwrap_or_else(|p| p.into_inner());
            // PoisonError 里带着同一个 (Guard, WaitTimeoutResult)，展开就行；
            // 中毒（有人 panic 在持锁时）按"没人答"走 = 拒绝，绝不因为内部异常放行
            let (mut g, res) = slot
                .ready
                .wait_timeout_while(g, Duration::from_secs(secs), |v| v.is_none())
                .unwrap_or_else(|p| p.into_inner());
            match g.take() {
                Some(v) => Resolution { value: v, timed_out: false },
                None => Resolution { value: on_timeout.to_string(), timed_out: res.timed_out() },
            }
        };
        // 收摊：谁在等这件事查不到，但绝不能让它一直挂着
        self.lock(&self.slots).remove(id);
        self.lock(&self.waiters).remove(id);
        self.lock(&self.order).retain(|x| x != id);
        out
    }

    fn register(&self, id: &str, slot: Arc<Slot>, waiter: Waiter) {
        self.lock(&self.slots).insert(id.to_string(), slot);
        self.lock(&self.order).push(id.to_string());
        self.lock(&self.waiters).insert(id.to_string(), waiter);
    }

    /**
     * 答复（网页与手机同一个口）。第一次赢：已经有人答过就返回 `already=true`，
     * 引擎收到的还是第一个答案。
     */
    pub fn complete(&self, id: &str, value: &str) -> Completed {
        let slot = self.lock(&self.slots).get(id).cloned();
        let Some(slot) = slot else {
            return Completed { existed: false, already: false, is_ask: false, sid: String::new() };
        };
        let w = self.lock(&self.waiters).get(id).cloned();
        let (is_ask, wsid) = w.map(|x| (x.ev == "ask", x.sid)).unwrap_or((false, String::new()));
        let mut g = slot.answer.lock().unwrap_or_else(|p| p.into_inner());
        let already = g.is_some();
        if !already {
            *g = Some(value.to_string());
            slot.ready.notify_all();
        }
        drop(g);
        Completed { existed: true, already, is_ask, sid: wsid }
    }

    /// 停止时把某条会话挂着的等待一次性判掉：审批给 `deny`（fail-closed），
    /// 提问给 `""`（模型看到"用户没回答"）。收摊由等待方自己的收尾完成。
    pub fn abort(&self, sid: &str) -> AbortSummary {
        let targets: Vec<(String, Waiter)> = self
            .rows()
            .into_iter()
            .filter(|(_, w)| w.sid == sid)
            .collect();
        let mut sum = AbortSummary::default();
        for (id, w) in targets {
            if w.ev == "ask" {
                self.complete(&id, "");
                sum.asks += 1;
            } else {
                self.complete(&id, "deny");
                sum.approvals += 1;
            }
        }
        sum
    }
}

/// 审批卡的载荷。键序与 Kotlin `approvalPayload` 的 `buildJsonObject` 一致 ——
/// 这份还会被 `/lan/pending` 原样透给手机端，两边读的是同一份。
///
/// `risk` 三件套（码 / 标签 / 为什么）一起给：界面按码配色，标签给人看，
/// 为什么写进卡里让人**不用去猜**这张卡为什么要拦他。
/// 逐块勾选（`hunks`）要连着 `HunkPlan` 一起搬，现在还没接上（见 M2e 的分解）。
pub fn approval_payload(
    id: &str,
    title: &str,
    detail: &str,
    kind: &str,
    tool: &str,
    pattern: &str,
    risk: Option<&risk::Verdict>,
) -> String {
    let mut s = format!(
        "{{\"id\":{},\"title\":{},\"detail\":{},\"kind\":{},\"tool\":{},\"pattern\":{}",
        quote(id),
        quote(title),
        quote(detail),
        quote(kind),
        quote(tool),
        quote(pattern)
    );
    if let Some(v) = risk {
        s.push_str(&format!(
            ",\"risk\":{},\"riskLabel\":{},\"riskWhy\":{}",
            quote(v.code()),
            quote(risk::label(v.level)),
            quote(&v.why)
        ));
    }
    s.push('}');
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    /// 事件捕获口：测试用它断言"卡推出去了、通知发了"。
    fn recorder() -> (Arc<Mutex<Vec<(String, String, String)>>>, impl Fn(&str, &str, &str)) {
        let log = Arc::new(Mutex::new(Vec::new()));
        let l2 = log.clone();
        // 用 move + clone，因为这个闭包要同时被推卡和超时通知两条路调
        (log, move |ev: &str, data: &str, sid: &str| {
            l2.lock().unwrap().push((ev.to_string(), data.to_string(), sid.to_string()));
        })
    }

    fn payloads(log: &Arc<Mutex<Vec<(String, String, String)>>>) -> Vec<(String, String)> {
        log.lock().unwrap().iter().map(|(e, d, _)| (e.clone(), d.clone())).collect()
    }

    #[test]
    fn approval_payload_has_the_shape_the_desktop_and_phone_both_read() {
        let v = risk::of("shell", "rm -rf build", "", false, false);
        let p = approval_payload("a1", "执行命令（bash）", "rm -rf build", "exec", "shell", "rm", Some(&v));
        let j: serde_json::Value = serde_json::from_str(&p).expect("载荷必须是合法 JSON");
        assert_eq!(j["id"], "a1");
        assert_eq!(j["risk"], "high", "界面按这个码配色，不是按中文");
        assert_eq!(j["riskLabel"], "高危");
        assert!(j["riskWhy"].as_str().unwrap().contains("不可逆"));
        let keys: Vec<&str> = j.as_object().unwrap().keys().map(|s| s.as_str()).collect();
        assert_eq!(keys, ["id", "title", "detail", "kind", "tool", "pattern", "risk", "riskLabel", "riskWhy"]);
    }

    #[test]
    fn an_answered_approval_returns_the_chosen_value() {
        let b = Arc::new(ApprovalBroker::with_timeouts(30, 30));
        let (log, pub_) = recorder();
        let b1 = Arc::clone(&b);
        let h = thread::spawn(move || {
            let mut pub_ = pub_;
            b1.await_approval("s1", &mut pub_, &|id| {
                approval_payload(id, "标题", "细节", "write", "write", "a.md", None)
            })
        });
        // 等卡推出去再答，否则拿不到 id
        let id = wait_for_approval_id(&log);
        let done = b.complete(&id, "allow_once");
        assert!(done.existed);
        assert!(!done.already);
        let res = h.join().unwrap();
        assert_eq!(res.value, "allow_once");
        assert!(!res.timed_out);
        assert_eq!(b.size(), 0, "答完必须收摊干净，否则这条审批永远挂着");
    }

    fn wait_for_approval_id(log: &Arc<Mutex<Vec<(String, String, String)>>>) -> String {
        loop {
            let p = payloads(log);
            if let Some((_, d)) = p.iter().find(|(e, _)| e == "approval") {
                return serde_json::from_str::<serde_json::Value>(d).unwrap()["id"]
                    .as_str()
                    .unwrap()
                    .to_string();
            }
            thread::sleep(Duration::from_millis(10));
        }
    }

    /// 一条 1 秒的超时测试就能把 fail-closed 这条路走实 —— 300 秒没人会去测，
    /// 而 Kotlin 那边"超时无测试"正是这次收编的理由。
    #[test]
    fn nobody_answering_denies_and_says_so() {
        let b = ApprovalBroker::with_timeouts(1, 1);
        let (log, mut pub_) = recorder();
        let res = b.await_approval("s2", &mut pub_, &|id| format!("{{\"id\":{}}}", quote(id)));
        assert_eq!(res.value, "deny", "超时=按拒绝处理，不能默默放行");
        assert!(res.timed_out);
        let evs = payloads(&log);
        assert!(evs.iter().any(|(e, d)| e == "notice" && d.contains("无人应答")), "{evs:?}");
        assert_eq!(b.size(), 0, "超时也要收摊");
    }

    #[test]
    fn the_second_answer_loses_and_the_engine_keeps_the_first() {
        let b = Arc::new(ApprovalBroker::with_timeouts(30, 30));
        let (log, pub_) = recorder();
        let b1 = Arc::clone(&b);
        let h = thread::spawn(move || {
            let mut pub_ = pub_;
            b1.await_approval("s3", &mut pub_, &|id| format!("{{\"id\":{}}}", quote(id)))
        });
        let id = wait_for_approval_id(&log);
        assert!(!b.complete(&id, "allow_once").already);
        assert!(b.complete(&id, "deny").already, "同一张卡答两次必须被认出来");
        assert_eq!(h.join().unwrap().value, "allow_once", "引擎拿到的必须是第一个答复");
    }

    /// 按了停止而引擎正卡在等答复上 —— 不判掉这些等待，"停止"按钮就成了
    /// 它自己要中止的那件事的受害者。
    #[test]
    fn abort_denies_everything_hanging_on_that_session() {
        let b = Arc::new(ApprovalBroker::with_timeouts(30, 30));
        let (log, pub_) = recorder();
        let b1 = Arc::clone(&b);
        let h = thread::spawn(move || {
            let mut pub_ = pub_;
            b1.await_approval("s4", &mut pub_, &|id| format!("{{\"id\":{}}}", quote(id)))
        });
        let _ = wait_for_approval_id(&log);
        let sum = b.abort("s4");
        assert_eq!((sum.approvals, sum.asks, sum.total()), (1, 0, 1));
        assert_eq!(h.join().unwrap().value, "deny");
        // 别的会话不受影响，而且已经判掉的再 abort 不计入
        assert_eq!(b.abort("other").total(), 0);
        assert_eq!(b.abort("s4").total(), 0, "已收摊的不该再记一次");
    }

    #[test]
    fn pending_rows_come_back_in_arrival_order() {
        let b = ApprovalBroker::with_timeouts(30, 30);
        thread::scope(|sc| {
            let mut handles = Vec::new();
            for _ in 0..2 {
                handles.push(sc.spawn(|| {
                    let mut nop = |_: &str, _: &str, _: &str| {};
                    b.await_approval("s5", &mut nop, &|id| format!("{{\"id\":{}}}", quote(id)))
                }));
                // 错开出生时间，才有"到达序"可断言
                std::thread::sleep(Duration::from_millis(80));
            }
            let rows = b.rows();
            assert_eq!(rows.len(), 2, "{rows:?}");
            let ids: Vec<&str> = rows.iter().map(|(i, _)| i.as_str()).collect();
            assert_eq!(ids, ["a1", "a2"], "投影按到达序给，不然前端的卡会自己跳");
            for id in ids.clone() {
                b.complete(id, "allow_once");
            }
            for h in handles {
                assert_eq!(h.join().unwrap().value, "allow_once");
            }
        });
        assert_eq!(b.size(), 0);
    }
}
