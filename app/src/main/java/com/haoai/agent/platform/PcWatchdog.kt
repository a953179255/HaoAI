package com.haoai.agent.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.haoai.agent.MainActivity
import com.haoai.agent.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 电脑上"有人在等"的那条通知 —— B4 安卓第三片的功能核。
 *
 * 为什么不塞进 `KeepAliveService`：那份服务管的是**手机自己**跑的任务（`RunObserver` 读的是
 * 本进程状态）；这一份读的是**另一台机器**上的待批。两者失败方式完全不同（一个是"任务结束了"，
 * 一个是"连不上那台电脑"），混进同一个循环，出错时说不清是谁坏了。
 *
 * 三条刻意的取舍：
 * ① **节律 20 秒，不是网页端那个 4 秒**：网页端是"人盯着页面才刷"，后台轮询是常驻的，
 *    4 秒一次会让手机在兜里持续联网；而电脑上真正卡住的那一步本来就要等人几十秒到几分钟。
 * ② **只在保活服务活着的时候跑**：不谎称"任何情况下都收得到"。省电策略把服务杀掉之后这条通道
 *    就是断的 —— 那正是必须真机验、模拟器验不了的那件事。
 * ③ 通知**只在真的多出新的时候**才响（差集在 `PcWatch`），批完要收回去：后台版最容易坏在
 *    "反复弹到人关不掉"和"批完了还挂着一条假待办"这两处。
 */
object PcWatchdog {

    const val CHANNEL_PC = "haoai_pc_wait"
    /** 跑完的结果走**另一条渠道**：它不是"在等你"，用同一条会把人的"这条可以划掉了"和"这条要你决定"混成一堆。 */
    const val CHANNEL_DONE = "haoai_pc_done"
    private const val NOTIFICATION_ID = 4311
    private const val NOTIFICATION_ID_DONE = 4312

    /** 后台轮询节律（毫秒）。放宽的理由见类注释 ①。 */
    const val POLL_MS = 20_000L

    data class State(
        val paired: Boolean = false,
        val base: String = "",
        val device: String = "",
        val waiting: Int = 0,
        val lastAt: Long = 0L,
        /** 上一次问的结果说明：连不上、没配对、还是"没有要批的"。界面直接显示这一句。 */
        val lastNote: String = "",
        val polling: Boolean = false,
        /**
         * 最近一次轮询落地的时刻（毫秒）。10-01 真机：进程活着、通知挂着，
         * 轮询协程却在长寿命进程里静默死了 40 分钟 —— 从外面看"进程没死"和
         * "轮询没死"完全分不开。这个时间戳就是把两者分开的那把尺：
         * 界面按它显示"上次轮询 X 秒前"，超 [POLL_MS]×3 没动就当死轮处理（见 [ensureRunning]）。
         */
        val lastPollAt: Long = 0L
    )

    private var app: Context? = null
    private var store: PcStore? = null
    private val watch = PcWatch()
    /** 定时结果走另一套判重：审批是当下状态，结果是过去事件（见 [PcDigestWatch]）。 */
    private val results = PcDigestWatch()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** 应用起来时调一次（保活服务的 onCreate 里）：拿住 context 并建好通知渠道。 */
    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        store = PcStore(KeystorePcCipher(), File(c.filesDir, "pc-link.json"))
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_PC, "电脑待确认", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "电脑上的 HaoAI 在等你批准时提醒（跨端联动）"
                }
            )
        // IMPORTANCE_DEFAULT 而不是 HIGH：跑完一条简报不该盖过"在等你决定"那条。
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "电脑跑完了", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "电脑上的定时任务/任务链跑完之后，把结果送到这里（跨端联动）"
                }
            )
        refreshConfig()
    }

    private fun file(): File? = app?.filesDir?.let { File(it, "pc-link.json") }

    fun refreshConfig() {
        val f = file() ?: return
        val ep = store?.load(f)
        _state.value = _state.value.copy(
            paired = ep != null && ep.token.isNotBlank(),
            base = ep?.base.orEmpty(),
            device = ep?.device.orEmpty()
        )
        if (ep == null) { watch.reset(); results.reset() }
    }

    fun save(base: String, token: String, device: String): PcOut<String> {
        val s = store ?: return PcOut.Fail("应用还没起完")
        val f = file() ?: return PcOut.Fail("应用还没起完")
        val out = s.save(f, base, token, device)
        refreshConfig()
        return out
    }

    /**
     * 解除配对。**两边都要忘**：只清本地会留下一条"电脑还当这台手机配着"的僵尸设备，
     * 而它已经拿不到 token 了 —— 真机上就是这么发现的（手机显示"没配对"，
     * 电脑上那台设备还在列表里）。电脑连不上时仍然清本地：人要走不能被他拦在门外。
     */
    suspend fun forget(): String {
        val f = file() ?: return "应用还没起完"
        val ep = store?.load(f)
        val pcSaid = if (ep != null && ep.token.isNotBlank())
            when (val out = PcLink(ep.base, ep.token).unpair()) {
                is PcOut.Ok -> out.value
                is PcOut.Fail -> "（那台电脑没答上：${out.message}）"
            } else "（本地本来就没配对）"
        store?.clear(f)
        hideNotification()
        refreshConfig()
        return pcSaid
    }

    /** 幂等：已经在跑就直接返回。保活服务每次 onCreate 都会调它。 */
    fun start() {
        if (job?.isActive == true) return
        if (app == null) return
        job = scope.launch {
            try {
                while (isActive) {
                    // 循环体整体兜底：10-01 真机那次"轮询静默 40 分钟"的根因就是
                    // 循环体里任何一次抛出都会把整个 while 带走，而外面什么都看不见。
                    // 一轮出错只许记账（界面显示一句），绝不能杀通道（硬规矩 1 同款）。
                    try {
                        if (_state.value.paired) pollOnce() else delay(POLL_MS * 3)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        android.util.Log.w("HaoPcLink", "轮询一轮失败，继续下一轮：${t.message}")
                        _state.value = _state.value.copy(lastNote = "这轮没问成（下一轮会继续）：${t.message}")
                    }
                    _state.value = _state.value.copy(lastPollAt = System.currentTimeMillis())
                    delay(POLL_MS)
                }
            } finally {
                _state.value = _state.value.copy(polling = false)
            }
        }
        _state.value = _state.value.copy(polling = true)
    }

    /**
     * 自检：循环死了或卡死了就地重启。由界面打开时与任何"看见状态"的地方调。
     *
     * 判据只有两条：① job 不在跑（异常把 while 带走过 / 从没起来）；
     * ② job 在跑但 [State.lastPollAt] 超过 3 个节律没动（卡在网络调用里出不来 ——
     * 正常一轮 20 秒 + 请求超时十几秒，60 秒不动必有毛病）。第二条靠的是
     * "设了尺就要有人读尺"：光有时间戳没人看，那次 40 分钟静默照样能重演。
     */
    fun ensureRunning() {
        if (app == null) return
        val st = _state.value
        val dead = job?.isActive != true
        val stale = st.lastPollAt > 0L && System.currentTimeMillis() - st.lastPollAt > POLL_MS * 3
        if (!dead && !stale) return
        android.util.Log.w(
            "HaoPcLink",
            if (dead) "轮询不在跑，重启" else "轮询 ${System.currentTimeMillis() - st.lastPollAt}ms 没动，重启"
        )
        stop()
        start()
    }

    fun stop() {
        job?.cancel(); job = null
        _state.value = _state.value.copy(polling = false)
    }

    /** 问一次电脑上有没有在等的。返回这次该对通知做什么（界面的"现在问一次"也走这条）。 */
    suspend fun pollOnce(): PcWatchAction {
        val f = file() ?: return PcWatchAction.Same
        val ep = store?.load(f)
        if (ep == null || ep.token.isBlank()) {
            _state.value = _state.value.copy(lastNote = "还没配对那台电脑")
            return PcWatchAction.Same
        }
        return when (val out = PcLink(ep.base, ep.token).pending()) {
            is PcOut.Fail -> {
                // 连不上/被解除：先把"在等"的假象收掉，并清空差集 —— 重连之后如果还是那几条，
                // 应该重新提醒一次（人可能根本没看到刚才那条）。
                watch.reset()
                results.reset()
                hideNotification()
                _state.value = _state.value.copy(
                    waiting = 0, lastAt = System.currentTimeMillis(), lastNote = out.message,
                    paired = !out.unauthorized
                )
                PcWatchAction.Clear
            }
            is PcOut.Ok -> {
                val items = out.value
                val act = watch.onPending(items.map { it.id })
                _state.value = _state.value.copy(
                    waiting = items.size, lastAt = System.currentTimeMillis(),
                    lastNote = if (items.isEmpty()) "问过了：电脑上没有要批的"
                    else "电脑上有 ${items.size} 条在等你批"
                )
                when (act) {
                    is PcWatchAction.Notify -> showNotification(items, act.total)
                    PcWatchAction.Clear -> hideNotification()
                    PcWatchAction.Same -> Unit
                }
                /*
                 * 顺手问一次"有没有刚跑完的"。放在 pending 之后而不是并起来：
                 * pending 失败时上面已经 reset 过判重了，这时候再拿一份可能过期的
                 * digest 去判重，会把"其实没见过"的那几条永久记成"说过了"。
                 * 一次轮询两个请求（20 秒一轮）换的是"人不在电脑前也知道结果出来了"，
                 * 值；真要省，就该把两个口合成一个，而不是少问一次。
                 */
                announceResults(PcLink(ep.base, ep.token).digest())
                act
            }
        }
    }

    /** 在通知上点了"允许一次 / 本任务都允许 / 拒绝"。 */
    suspend fun decide(id: String, decision: String) {
        val f = file() ?: return
        val ep = store?.load(f) ?: return
        _state.value = _state.value.copy(
            lastNote = when (val out = PcLink(ep.base, ep.token).decide(id, decision)) {
                is PcOut.Ok -> out.value
                is PcOut.Fail -> out.message
            }
        )
        // 点完立刻再问一次：批条数变了通知就该跟着变（全批完就该消失）
        pollOnce()
    }

    /** 在通知上直接答了电脑上那句提问（`ask_user`）。送的是回答文字，不是那三个决定值。 */
    suspend fun answer(id: String, text: String) {
        val f = file() ?: return
        val ep = store?.load(f) ?: return
        _state.value = _state.value.copy(
            lastNote = when (val out = PcLink(ep.base, ep.token).answer(id, text)) {
                is PcOut.Ok -> out.value
                is PcOut.Fail -> out.message
            }
        )
        pollOnce()
    }

    private fun showNotification(items: List<PcApproval>, total: Int) {
        val a = app ?: return
        val first = items.firstOrNull() ?: return
        val ask = first.kind == "ask"
        val batch = ask && first.payload.batch
        val body = buildString {
            append(first.payload.title.ifBlank { if (ask) "（没问出口）" else "（没标题）" })
            if (first.payload.detail.isNotBlank()) append('\n').append(first.payload.detail)
            // 批量问卷不摆题面选项（通知按钮是给"一句话提问"的；问卷要逐题在网页上答）
            if (batch) append('\n').append("${first.payload.questionCount} 题问卷 · 选完自动下一题，到网页上逐题作答")
            else if (ask && first.payload.options.isNotEmpty())
                append('\n').append("可选：").append(first.payload.options.take(4).joinToString(" / "))
            if (first.payload.riskWhy.isNotBlank()) append('\n').append("为什么算高危：${first.payload.riskWhy}")
        }
        val b = NotificationCompat.Builder(a, CHANNEL_PC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when {
                    batch -> if (total > 1) "电脑上有 ${first.payload.questionCount} 题问卷要答（还有 ${total - 1} 条）"
                    else "电脑上有 ${first.payload.questionCount} 题问卷要答"
                    ask -> if (total > 1) "电脑上有话要问你（还有 ${total - 1} 条要批）" else "电脑上有话要问你"
                    total > 1 -> "电脑上有 $total 条在等你批"
                    else -> "电脑上有 1 条在等你批"
                }
            )
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(a))
            .setAutoCancel(false)
        // 动作直接挂在通知上：人在外面不用解锁找应用就能放行、拒绝，或者答那一句
        if (ask) {
            first.payload.options.take(3).forEach { opt ->
                b.addAction(0, opt.take(12), decidePi(a, first.id, opt, opt.hashCode(), isAnswer = true))
            }
            // 选项超过三个、或一个都没给：通知上摆不下，得给一个**真能写句子**的地方。
            // 这里指向电脑上那个手机网页端（它的回答输入框正是这一批像素验过的东西），
            // 不指向应用内 —— `PcLinkScreen` 现在只有配对与状态，没有写回答的框，
            // 按钮写着「打开写回答」而打开的页面写不了，那就是个假出口。
            if (first.payload.options.size > 3 || first.payload.options.isEmpty())
                b.addAction(0, "在网页上答", openWebIntent(a, _state.value.base))
        } else {
            listOf("允许一次" to "allow_once", "本任务都允许" to "allow_session", "拒绝" to "deny")
                .forEach { (label, d) -> b.addAction(0, label, decidePi(a, first.id, d, label.hashCode())) }
        }
        (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, b.build())
    }

    /**
     * 把"电脑上刚跑完的那次"发成一条通知。
     *
     * 三条刻意的取舍：
     * ① **正文里就带结果**（哪一轮、结论、正文第一行）—— 多数时候人看一眼就够了，
     *    不该为了读一句话再跳一层；
     * ② 点开之后打开的是电脑上那个手机网页端的「结果」页签 —— 应用内现在没有结果列表，
     *    按钮指一个没有内容的屏就是假出口（同一个理由见「在网页上答」）；
     * ③ `setAutoCancel(true)`：这是"告知"，不是"在等你"，划过就该消失，
     *    不像待批那条要一直挂着。
     */
    private fun announceResults(out: PcOut<List<PcDigestItem>>) {
        val a = app ?: return
        if (out !is PcOut.Ok) return
        val fresh = results.onDigest(out.value)
        if (fresh.isEmpty()) return
        val last = fresh.last()
        val more = fresh.size - 1
        val body = buildString {
            append(last.at).append(" · ").append(last.title.ifBlank { "（没标题）" })
            append('\n').append(last.verdict.ifBlank { "跑完了" })
            if (last.text.isNotBlank()) append('\n').append(last.text.lineSequence().first().take(120))
            if (more > 0) append('\n').append("还有 $more 条：点进去看")
        }
        val n = NotificationCompat.Builder(a, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            // 真机实测抓的：`$fresh.size` 在 Kotlin 模板里 = `${fresh}` + 字面量 ".size" ——
            // 批量≥2 时标题把整个列表 toString 吐出来（"电脑上有 [PcDigestItem(...)].size 次跑完了"）。
            // 单条走 else 分支躲过了，一攒批就现形：模板里必须写 `${fresh.size}`。
            .setContentTitle(if (more > 0) "电脑上有 ${fresh.size} 次跑完了" else "电脑上的一次任务跑完了")
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openWebIntent(a, _state.value.base))
            .setAutoCancel(true)
        (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID_DONE, n.build())
        _state.value = _state.value.copy(lastNote = "结果已经送到手机：" + last.title)
    }

    private fun hideNotification() {
        val a = app ?: return
        (a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    private fun decidePi(
        a: Context, id: String, decision: String, code: Int, isAnswer: Boolean = false
    ): PendingIntent {
        val i = Intent(a, PcActionReceiver::class.java)
            .setAction(PcActionReceiver.ACTION_PC_DECIDE)
            .putExtra(PcActionReceiver.EXTRA_ID, id)
            .putExtra(PcActionReceiver.EXTRA_DECISION, decision)
            .putExtra(PcActionReceiver.EXTRA_IS_ANSWER, isAnswer)
        return PendingIntent.getBroadcast(
            a, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAppIntent(a: Context): PendingIntent {
        val i = Intent(a, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("haoai://debug/pc")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            a, 900, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * 打开电脑上那个手机网页端（`base` 本身就是它）。
     * 通知上摆不下的回答（没给选项、或选项超过三个）目前只有那里写得进去 ——
     * 应用内的 `PcLinkScreen` 还没有回答输入框，按钮指过去就是个假出口。
     */
    private fun openWebIntent(a: Context, base: String): PendingIntent {
        if (base.isBlank()) return openAppIntent(a)   // 连地址都没有时别发一个打不开的 intent
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(base))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            a, 901, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
