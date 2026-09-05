/*
 * 姗樼摚 OrangeChat
 * 琛嶇敓鑷?RikkaHub (https://github.com/rikkahub/rikkahub)锛屽師浣滆€?RE
 * 鏈」鐩熀浜?GNU AGPL v3 寮€婧愶紝璇﹁鏍圭洰褰?LICENSE 鏂囦欢
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * 绾康鏃ョ鐞嗗伐鍏凤細璇诲啓涓庢敞鍏ュ垪琛ㄥ悓婧愮殑 DisplaySetting.anniversaries銆? * add/edit/delete 鐩存帴淇敼鐢ㄦ埛鏈湴閰嶇疆锛屼笉缁忚繃浠讳綍浜戠銆? */
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
                val countdown = params["countdown"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
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
                val target = current.anniversaries.firstOrNull { it.id == id }
                    ?: return@Tool err("anniversary not found: $id")
                val name = params["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: target.title
                val dateText = params["date"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: target.startDate
                val countdown = params["countdown"]?.jsonPrimitive?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: target.countdown
                runCatching { LocalDate.parse(dateText) }.getOrNull()
                    ?: return@Tool err("invalid ISO date: $dateText")
                val updated = target.copy(title = name, startDate = dateText, countdown = countdown)
                val newList = current.anniversaries.map { if (it.id == id) updated else it }
                settingsStore.update { settings ->
                    settings.copy(displaySetting = settings.displaySetting.copy(anniversaries = newList))
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("id", JsonPrimitive(id))
                    put("message", JsonPrimitive("updated"))
                }.toString()))
            }
            else -> {
                val id = params["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val target = current.anniversaries.firstOrNull { it.id == id }
                    ?: return@Tool err("anniversary not found: $id")
                val newList = current.anniversaries.filterNot { it.id == id }
                settingsStore.update { settings ->
                    settings.copy(displaySetting = settings.displaySetting.copy(anniversaries = newList))
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", JsonPrimitive(true))
                    put("id", JsonPrimitive(id))
                    put("deleted", JsonPrimitive(target.title))
                    put("message", JsonPrimitive("deleted"))
                }.toString()))
            }
        }
    }
)
