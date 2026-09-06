# -*- coding: utf-8 -*-
# 批次3：把展示/落库边界的裸 take() 换成 UTF-16 安全的 takeSafe()
import io

def patch(path, subs):
    with io.open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    for old, new in subs:
        assert old in s, "NOT FOUND in %s: %r" % (path, old[:70])
        assert s.count(old) == 1, "AMBIGUOUS in %s: %r" % (path, old[:70])
        s = s.replace(old, new, 1)
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print("patched %s: %d subs" % (path, len(subs)))

ENGINE = 'app/src/main/java/com/haoai/agent/agent/engine/AgentEngine.kt'
SESSION = 'app/src/main/java/com/haoai/agent/data/SessionStore.kt'
VM = 'app/src/main/java/com/haoai/agent/ui/ChatViewModel.kt'
WORKER = 'app/src/main/java/com/haoai/agent/platform/AgentWorker.kt'

patch(SESSION, [
    ("import com.haoai.agent.agent.model.ChatMessage\n",
     "import com.haoai.agent.agent.model.ChatMessage\nimport com.haoai.agent.agent.tools.takeSafe\n"),
    ('?.let { session.title = it.content.take(24).replace(\'\\n\', \' \') }',
     '?.let { session.title = it.content.takeSafe(24).replace(\'\\n\', \' \') }'),
])

patch(VM, [
    ("import com.haoai.agent.agent.policy.PolicyEngine\n",
     "import com.haoai.agent.agent.policy.PolicyEngine\nimport com.haoai.agent.agent.tools.takeSafe\n"),
    ("s.runGoal = text.take(200)", "s.runGoal = text.takeSafe(200)"),
    ("?.content?.lineSequence()?.firstOrNull()?.trim()?.take(12)",
     "?.content?.lineSequence()?.firstOrNull()?.trim()?.takeSafe(12)"),
    (").replace('\\n', ' ')\n                        .take(24)",
     ").replace('\\n', ' ')\n                        .takeSafe(24)"),
    ('        content.lineSequence().firstOrNull()?.take(160) ?: ""',
     '        content.lineSequence().firstOrNull()?.takeSafe(160) ?: ""'),
])

patch(WORKER, [
    ("import com.haoai.agent.agent.schedule.ScheduleStore\n",
     "import com.haoai.agent.agent.schedule.ScheduleStore\nimport com.haoai.agent.agent.tools.takeSafe\n"),
    ('.setContentText(result.lineSequence().firstOrNull()?.take(80) ?: "")',
     '.setContentText(result.lineSequence().firstOrNull()?.takeSafe(80) ?: "")'),
    ('.setStyle(NotificationCompat.BigTextStyle().bigText(result.take(2000)))',
     '.setStyle(NotificationCompat.BigTextStyle().bigText(result.takeSafe(2000)))'),
])

print("ALL OK")
