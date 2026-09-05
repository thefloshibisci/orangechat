/*
 * 姗樼摚 OrangeChat
 * 琛嶇敓鑷?RikkaHub (https://github.com/rikkahub/rikkahub)锛屽師浣滆€?RE
 * 鏈」鐩熀浜?GNU AGPL v3 寮€婧愶紝璇﹁鏍圭洰褰?LICENSE 鏂囦欢
 */

package me.rerere.rikkahub.data.datastore

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 鏋勫缓绱у噾鐨勫畬鏁寸邯蹇垫棩涓婁笅鏂囥€傛棫閰嶇疆浠嶆部鐢ㄥ悓涓€涓紑鍏筹紱鍘嗗彶涓婄殑鍗曢€?ID
 * 鍙綔涓哄吋瀹瑰瓧娈典繚鐣欙紝涓嶅啀闄愬埗娉ㄥ叆鑼冨洿銆? */
fun DisplaySetting.buildAnniversaryPrompt(today: LocalDate = LocalDate.now()): String? {
    if (!anniversaryAiInjectionEnabled) return null

    val lines = anniversaries.mapNotNull { entry ->
        val date = runCatching { LocalDate.parse(entry.startDate) }.getOrNull() ?: return@mapNotNull null
        if (entry.countdown) {
            val remaining = ChronoUnit.DAYS.between(today, date)
            when {
                remaining > 0 -> "- ${entry.title} | 鏃ユ湡锛?{entry.startDate} | 鍊掓暟锛?{remaining}澶?
                remaining == 0L -> "- ${entry.title} | 鏃ユ湡锛?{entry.startDate} | 浠婂ぉ"
                else -> null
            }
        } else {
            val dayNumber = ChronoUnit.DAYS.between(date, today) + 1
            if (dayNumber < 1) null
            else "- ${entry.title} | 寮€濮嬶細${entry.startDate} | 绗?{dayNumber}澶?
        }
    }

    if (lines.isEmpty()) return null
    return "[绾康鏃ュ垪琛╙\n${lines.joinToString("\n")}"
}
