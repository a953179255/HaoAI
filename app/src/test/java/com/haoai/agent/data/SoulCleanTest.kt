package com.haoai.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 存量人设脏前缀清洗（2026-10-24 真机事故兜底）。
 *
 * 背景：旧版 PersonaPresets.fullText 把「你的性格：」一起写进 settings.soul，
 * 而系统提示（EngineFactory.identityOf）和镜像（WorkspaceDocs SOUL.md）渲染时
 * 各自还要再加一次前缀，存量用户实际拿到「你的性格：你的性格：…」。
 * 这几条断言锁住"只剥一次、半角冒号也认、干净文本不动"。
 */
class SoulCleanTest {

    @Test
    fun legacyPrefixIsStripped() {
        val raw = "你的性格：元气甜系（女性向）——一个软糯带点小脾气的助手"
        assertEquals("元气甜系（女性向）——一个软糯带点小脾气的助手", raw.cleanSoul())
    }

    @Test
    fun halfWidthColonAlsoStripped() {
        assertEquals("元气甜系", "你的性格:元气甜系".cleanSoul())
    }

    @Test
    fun prefixStrippedOnlyOnce() {
        assertEquals("你的性格：A", "你的性格：你的性格：A".cleanSoul())
    }

    @Test
    fun cleanTextUntouched() {
        val s = "元气甜系\n\n底线：不许含糊"
        assertEquals(s, s.cleanSoul())
    }

    @Test
    fun leadingWhitespaceTrimmed() {
        assertEquals("元气甜系", "\n\n  你的性格：元气甜系".cleanSoul())
    }

    @Test
    fun blankStaysBlank() {
        assertEquals("", "".cleanSoul())
        assertEquals("", "你的性格：".cleanSoul())
    }

    @Test
    fun settingsExposesCleanedSoul() {
        val st = AppSettings(soul = "你的性格：元气甜系")
        assertEquals("元气甜系", st.soulClean)
        assertEquals("你的性格：元气甜系", st.soul)
    }
}
