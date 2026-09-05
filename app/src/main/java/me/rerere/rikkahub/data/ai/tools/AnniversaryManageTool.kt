/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.AnniversaryEntry
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 纪念日管理工具：读写与 Prompt 注入相同来源的 DisplaySetting.anniversaries。
 * add/edit/delete 直接修改用户本地设置，不经过任何云端服务。
 */
fun createAnniversaryManageTool(settingsStore: SettingsStore): Tool = Tool(
    name = "anniversary_manage",
    description = "List, add, edit or delete the user's local anniversaries (memorial days / countdown days). " +
        "The injected AI context is built from the same data, so changes take effect immediately.",
    needsApproval = true,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Action to perform."))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("list"))
                        add(JsonPrimitive("add"))
                        add(JsonPrimitive("edit"))
                        add(JsonPrimitive("delete"))
                    })
                }
                putJsonObject("id") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Anniversary id. Required for edit/delete; get it from 'list'."))
                }
                putJsonObject("name") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Title of the anniversary. Required for add; optional for edit."))
                }
                putJsonObject("date") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("ISO local date, for example 2026-09-05. Required for add; optional for edit."))
                }
                putJsonObject("countdown") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("true = countdown to the date; false = count days since the date. Optional for add/edit."))
                }
            },
            required = listOf("action")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull

        fun err(msg: String) = listOf(UIMessagePart.Text(
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive(msg))
            }.toString()
        ))

        if (action !in listOf("list", "add", "edit", "delete")) {
            return@Tool err("Unknown action: ${action.orEmpty()}")
        }

        val current = settingsStore.settingsFlow.value.displaySetting
        val today = LocalDate.now()

        when (action) {
            "list" -> {
                val items = current.anniversaries.map { entry ->
                    val date = runCatching { LocalDate.parse(entry.startDate) }.getOrNull()
                    buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("name", JsonPrimitive(entry.title))
                        put("date", JsonPrimitive(entry.startDate))
                        put("countdown", JsonPrimitive(entry.countdown))
                        if (date != null) {
                            if (entry.countdown) {
                                put("remaining_days", JsonPrimitive(ChronoUnit.DAYS.between(today, date)))
                            } else {
                                put("day_number", JsonPrimitive(ChronoUnit.DAYS.between(date, today) + 1))
                            }
                        }
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("anniversaries", buildJsonArray { items.forEach { add(it) } })
                }.toString()))
            }
            "add" -> {
                val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val dateText = params["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val countdown = params["countdown"]?.jsonPrimitive?.booleanOrNull ?: false
                val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
                if (name.isEmpty() || date == null) {
                    return@Tool err("name and ISO date (yyyy-MM-dd) are required for add")
                }
                val entry = AnniversaryEntry(
                    id = UUID.randomUUID().toString(),
                    title = name,
                    startDate = dateText,
                    countdown = countdown,
                )
                settingsStore.update { settings ->
                    settings.copy(displaySetting = settings.displaySetting.copy(
                        anniversaries = settings.displaySetting.anniversaries + entry
                    ))
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("id", JsonPrimitive(entry.id))
                    put("message", JsonPrimitive("added"))
                }.toString()))
            }
            "edit" -> {
                val id = params["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val dateText = params["date"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val countdown = params["countdown"]?.jsonPrimitive?.booleanOrNull
                if (dateText != null && runCatching { LocalDate.parse(dateText) }.getOrNull() == null) {
                    return@Tool err("invalid ISO date: $dateText")
                }
                var found = false
                settingsStore.update { settings ->
                    val entries = settings.displaySetting.anniversaries
                    val target = entries.firstOrNull { it.id == id }
                    if (target == null) {
                        settings
                    } else {
                        found = true
                        val updated = target.copy(
                            title = name ?: target.title,
                            startDate = dateText ?: target.startDate,
                            countdown = countdown ?: target.countdown,
                        )
                        settings.copy(displaySetting = settings.displaySetting.copy(
                            anniversaries = entries.map { if (it.id == id) updated else it }
                        ))
                    }
                }
                if (!found) return@Tool err("anniversary not found: $id")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("id", JsonPrimitive(id))
                    put("message", JsonPrimitive("updated"))
                }.toString()))
            }
            else -> {
                val id = params["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var deletedTitle: String? = null
                settingsStore.update { settings ->
                    val entries = settings.displaySetting.anniversaries
                    val target = entries.firstOrNull { it.id == id }
                    if (target == null) {
                        settings
                    } else {
                        deletedTitle = target.title
                        settings.copy(displaySetting = settings.displaySetting.copy(
                            anniversaries = entries.filterNot { it.id == id }
                        ))
                    }
                }
                val deleted = deletedTitle ?: return@Tool err("anniversary not found: $id")
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("id", JsonPrimitive(id))
                    put("deleted", JsonPrimitive(deleted))
                    put("message", JsonPrimitive("deleted"))
                }.toString()))
            }
        }
    }
)
