/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import java.time.LocalDate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnniversaryPromptTest {
    @Test
    fun `prompt includes every anniversary and its current status`() {
        val prompt = DisplaySetting(
            anniversaries = listOf(
                AnniversaryEntry("past-countdown", "今天", "2026-09-03", countdown = true),
                AnniversaryEntry("today", "想画画", "2026-09-05"),
                AnniversaryEntry("future-countdown", "考试", "2026-09-10", countdown = true),
                AnniversaryEntry("invalid", "待修正", "not-a-date"),
            ),
            anniversaryAiInjectionEnabled = true,
        ).buildAnniversaryPrompt(LocalDate.of(2026, 9, 5))

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("今天 | 日期：2026-09-03 | 已过期2天"))
        assertTrue(prompt.contains("想画画 | 开始：2026-09-05 | 第1天"))
        assertTrue(prompt.contains("考试 | 日期：2026-09-10 | 倒数：5天"))
        assertTrue(prompt.contains("待修正 | 日期：not-a-date | 日期无效"))
    }
}
