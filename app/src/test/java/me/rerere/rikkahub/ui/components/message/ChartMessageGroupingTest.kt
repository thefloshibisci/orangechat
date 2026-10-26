package me.rerere.rikkahub.ui.components.message

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.buildChartDisplayTool
import me.rerere.rikkahub.ui.components.charts.ChartStyle
import org.junit.Assert.*
import org.junit.Test

class ChartMessageGroupingTest {
    private val input = """{"style":"bar","title":"销量","series":[{"values":[2,5]}]}"""
    private fun tool(
        input: String = this.input,
        output: List<UIMessagePart> = listOf(UIMessagePart.Text("""{"success":true}""")),
        state: ToolApprovalState = ToolApprovalState.Auto,
    ) = UIMessagePart.Tool("chart-1", "chart_display", input, output, state)

    @Test fun realSuccessfulToolAddsChartOutsideItsThinkingTraceInOriginalOrder() = runBlocking {
        val call = tool(output = buildChartDisplayTool().execute(Json.parseToJsonElement(input)))
        val parts = listOf(UIMessagePart.Text("前文"), UIMessagePart.Reasoning("分析"), call,
            UIMessagePart.Tool("other", "get_time_info", "{}"), UIMessagePart.Text("后文"))
        val blocks = parts.groupMessageParts()
        assertEquals(5, blocks.size)
        assertEquals("前文", ((blocks[0] as MessagePartBlock.ContentBlock).part as UIMessagePart.Text).text)
        val trace = (blocks[1] as MessagePartBlock.ThinkingBlock).steps
        assertEquals(2, trace.size)
        assertEquals(call, (trace[1] as ThinkingStep.ToolStep).tool)
        val chart = blocks[2] as MessagePartBlock.ChartBlock
        assertEquals(2, chart.index)
        assertEquals(ChartStyle.Bar, chart.spec.style)
        assertEquals(listOf(2.0, 5.0), chart.spec.series.single().values)
        assertTrue(blocks[3] is MessagePartBlock.ThinkingBlock)
        assertEquals(4, (blocks[4] as MessagePartBlock.ContentBlock).index)
    }

    @Test fun pendingDeniedAndUnexecutedCallsCannotRenderEvenWithForgedSuccess() {
        for (call in listOf(tool(output = emptyList()), tool(state = ToolApprovalState.Pending),
            tool(state = ToolApprovalState.Denied("拒绝")))) {
            assertNull(call.displayedChart())
            assertTrue(listOf(call).groupMessageParts().single() is MessagePartBlock.ThinkingBlock)
        }
        assertNotNull(tool(state = ToolApprovalState.Approved).displayedChart())
    }

    @Test fun failedMalformedAndAmbiguousResultsKeepTraceWithoutChart() {
        for (output in listOf("error", "[]", "{}", """{"success":false}""",
            """{"success":"true"}""", """{"success":true,"error":"INVALID_CHART"}""")) {
            assertNull(output, tool(output = listOf(UIMessagePart.Text(output))).displayedChart())
        }
        assertNull(tool(output = listOf(UIMessagePart.Text("""{"success":true}"""), UIMessagePart.Text("extra"))).displayedChart())
        assertNull(tool().copy(toolName = "other_chart").displayedChart())
    }

    @Test fun corruptOrInvalidHistoricalInputsNeverCreatePartialCharts() {
        for (invalid in listOf("", "{", "null", "[]", """{"style":"pie","series":[]}""",
            """{"style":"bar","series":[{"values":[1]},{"values":[null]}]}""")) {
            assertNull(invalid, tool(input = invalid).displayedChart())
        }
    }

    @Test fun consecutiveChartsRemainDistinctAndReasoningGroupingIsPreserved() {
        val blocks = listOf(tool(), tool().copy(toolCallId = "chart-2"), UIMessagePart.Reasoning("结束")).groupMessageParts()
        assertEquals(5, blocks.size)
        assertEquals(listOf(0, 1), blocks.filterIsInstance<MessagePartBlock.ChartBlock>().map { it.index })
        assertEquals(3, blocks.filterIsInstance<MessagePartBlock.ThinkingBlock>().size)
        val ordinary = listOf(UIMessagePart.Reasoning("one"), tool().copy(toolName = "other"), UIMessagePart.Text("two")).groupMessageParts()
        assertEquals(2, ordinary.size)
        assertEquals(2, (ordinary[0] as MessagePartBlock.ThinkingBlock).steps.size)
    }

    @Test fun chartOptionRoundTripsWithoutChangingLegacyLocalToolIdentifiers() {
        val options = listOf(LocalToolOption.ChartDisplay, LocalToolOption.JavascriptEngine, LocalToolOption.Ssh)
        val encoded = Json.encodeToString<List<LocalToolOption>>(options)
        assertTrue(encoded.contains("chart_display"))
        assertEquals(options, Json.decodeFromString<List<LocalToolOption>>(encoded))
    }
}
