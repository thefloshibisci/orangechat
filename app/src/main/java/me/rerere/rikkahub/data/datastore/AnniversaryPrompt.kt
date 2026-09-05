/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.datastore

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 构建紧凑的完整纪念日上下文。
 *
 * 保留旧设置中的开关和选中 ID 以兼容存量数据；开关打开后同步整个列表。
 */
fun DisplaySetting.buildAnniversaryPrompt(today: LocalDate = LocalDate.now()): String? {
    if (!anniversaryAiInjectionEnabled || anniversaries.isEmpty()) return null

    val lines = anniversaries.map { entry ->
        val title = entry.title.replace(Regex("[\\r\\n]+"), " ").trim()
        val date = runCatching { LocalDate.parse(entry.startDate) }.getOrNull()
        when {
            date == null -> "- $title | 日期：${entry.startDate} | 日期无效"
            entry.countdown -> {
                val remaining = ChronoUnit.DAYS.between(today, date)
                when {
                    remaining > 0 -> "- $title | 日期：${entry.startDate} | 倒数：${remaining}天"
                    remaining == 0L -> "- $title | 日期：${entry.startDate} | 今天"
                    else -> "- $title | 日期：${entry.startDate} | 已过期${-remaining}天"
                }
            }
            else -> {
                val dayNumber = ChronoUnit.DAYS.between(date, today) + 1
                if (dayNumber >= 1) {
                    "- $title | 开始：${entry.startDate} | 第${dayNumber}天"
                } else {
                    "- $title | 开始：${entry.startDate} | 尚未开始"
                }
            }
        }
    }

    return "[纪念日列表]\n${lines.joinToString("\n")}"
}
