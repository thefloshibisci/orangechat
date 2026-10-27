package me.rerere.rikkahub.workflow.ui

import me.rerere.rikkahub.workflow.model.TriggerSpec

internal enum class TriggerChoice(val title: String) {
    MANUAL("手动运行"), TIME("每天定时"), INTERVAL("每隔一段时间"), CRON("自定义定时（Cron）"),
    WIFI_ON("连接 WiFi"), WIFI_OFF("断开 WiFi"),
    HEADPHONES_ON("插入耳机"), HEADPHONES_OFF("拔出耳机"),
    POWER_ON("接入电源"), POWER_OFF("断开电源"), BATTERY_LOW("电量低于"), BATTERY_HIGH("电量达到"),
    SCREEN_ON("屏幕点亮"), SCREEN_OFF("屏幕熄灭"), BOOT("开机完成"),
}

internal enum class IntervalUnit(val title: String, val suffix: String, val minutes: Long) {
    MINUTES("分钟", "m", 1), HOURS("小时", "h", 60), DAYS("天", "d", 1440),
}

internal fun TriggerSpec.editorChoice(): TriggerChoice? = when (this) {
    is TriggerSpec.TimeCron -> when {
        timeOfDay != null -> TriggerChoice.TIME
        cron?.trim()?.matches(Regex("@every\\s+\\d+[mhd]")) == true -> TriggerChoice.INTERVAL
        else -> TriggerChoice.CRON
    }
    is TriggerSpec.WifiConnected -> TriggerChoice.WIFI_ON
    is TriggerSpec.WifiDisconnected -> TriggerChoice.WIFI_OFF
    TriggerSpec.HeadphonesPlugged -> TriggerChoice.HEADPHONES_ON
    TriggerSpec.HeadphonesUnplugged -> TriggerChoice.HEADPHONES_OFF
    TriggerSpec.PowerConnected -> TriggerChoice.POWER_ON
    TriggerSpec.PowerDisconnected -> TriggerChoice.POWER_OFF
    is TriggerSpec.BatteryBelow -> TriggerChoice.BATTERY_LOW
    is TriggerSpec.BatteryAbove -> TriggerChoice.BATTERY_HIGH
    TriggerSpec.ScreenOn -> TriggerChoice.SCREEN_ON
    TriggerSpec.ScreenOff -> TriggerChoice.SCREEN_OFF
    TriggerSpec.BootCompleted -> TriggerChoice.BOOT
    TriggerSpec.Manual -> TriggerChoice.MANUAL
    // Bluetooth is deliberately absent: this app does not declare/request its runtime
    // permission. Existing definitions remain readable and are preserved on editing.
    else -> null
}

internal fun TriggerChoice.toTrigger(
    time: String,
    ssid: String,
    original: TriggerSpec,
    interval: String,
    intervalUnit: IntervalUnit,
    cron: String,
    battery: String,
): TriggerSpec = when (this) {
    TriggerChoice.TIME -> (original as? TriggerSpec.TimeCron)?.takeIf { it.cron == null }
        ?.copy(timeOfDay = time) ?: TriggerSpec.TimeCron(timeOfDay = time)
    TriggerChoice.INTERVAL -> {
        val amount = interval.toLongOrNull() ?: error("请输入有效的间隔")
        val minutes = runCatching { Math.multiplyExact(amount, intervalUnit.minutes) }.getOrNull()
        val expression = "@every $amount${intervalUnit.suffix}"
        val unchangedLegacy = (original as? TriggerSpec.TimeCron)?.cron?.trim() == expression
        require(minutes != null && (minutes in 15L..525_600L || (unchangedLegacy && minutes in 1L..525_600L))) {
            "新间隔需为 15 分钟至 365 天"
        }
        (original as? TriggerSpec.TimeCron)?.copy(cron = expression, timeOfDay = null) ?: TriggerSpec.TimeCron(cron = expression)
    }
    TriggerChoice.CRON -> (original as? TriggerSpec.TimeCron)?.copy(cron = cron.trim(), timeOfDay = null)
        ?: TriggerSpec.TimeCron(cron = cron.trim())
    TriggerChoice.WIFI_ON -> TriggerSpec.WifiConnected(ssid.ifBlank { null })
    TriggerChoice.WIFI_OFF -> TriggerSpec.WifiDisconnected(ssid.ifBlank { null })
    TriggerChoice.HEADPHONES_ON -> TriggerSpec.HeadphonesPlugged
    TriggerChoice.HEADPHONES_OFF -> TriggerSpec.HeadphonesUnplugged
    TriggerChoice.POWER_ON -> TriggerSpec.PowerConnected
    TriggerChoice.POWER_OFF -> TriggerSpec.PowerDisconnected
    TriggerChoice.BATTERY_LOW -> TriggerSpec.BatteryBelow(battery.toIntOrNull() ?: error("请输入有效电量"))
    TriggerChoice.BATTERY_HIGH -> TriggerSpec.BatteryAbove(battery.toIntOrNull() ?: error("请输入有效电量"))
    TriggerChoice.SCREEN_ON -> TriggerSpec.ScreenOn
    TriggerChoice.SCREEN_OFF -> TriggerSpec.ScreenOff
    TriggerChoice.BOOT -> TriggerSpec.BootCompleted
    TriggerChoice.MANUAL -> TriggerSpec.Manual
}

internal fun intervalTriggerSummary(cron: String?): String? {
    val match = Regex("^@every\\s+(\\d+)([smhd])$").matchEntire(cron?.trim().orEmpty()) ?: return null
    val unit = when (match.groupValues[2]) { "s" -> "秒"; "m" -> "分钟"; "h" -> "小时"; else -> "天" }
    return "每隔 ${match.groupValues[1]} $unit"
}
