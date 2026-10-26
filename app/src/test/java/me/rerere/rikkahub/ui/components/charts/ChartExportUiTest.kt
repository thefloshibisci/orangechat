package me.rerere.rikkahub.ui.components.charts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartExportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val spec = ChartSpec(ChartStyle.Bar, title = "导出示例",
        xAxis = ChartAxis(data = listOf("甲", "乙")),
        series = listOf(ChartSeries(color = Color(0xFFCC3300), values = listOf(2.0, 5.0))))

    @Test fun chartImageContentIncludesNativePlotWithoutMessageExportButtons() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(540.dp)) { ExportedChartCard(spec, ChartDisplayMode.Chart) } }
        }
        compose.onNodeWithText("导出示例").assertIsDisplayed()
        val bounds = compose.onNodeWithContentDescription("柱状图").fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle { captureChartRegion(compose.activity, bounds, "export-chart") }
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            assertTrue("Export content should paint its data bars", bitmap.redChartPixelCount() > 0)
        } finally { bitmap.recycle() }
        compose.onNodeWithText("导出图表").assertDoesNotExist()
        compose.onNodeWithText("数据表首屏截图").assertDoesNotExist()
    }

    @Test fun tableImageContentIncludesDataAndExplicitViewportCaption() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(540.dp)) { ExportedChartCard(spec, ChartDisplayMode.Table) } }
        }
        compose.onNodeWithText("甲").assertIsDisplayed()
        compose.onNodeWithText("乙").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithText("数据表首屏截图").assertIsDisplayed()
        compose.onNodeWithContentDescription("柱状图").assertDoesNotExist()
        compose.onNodeWithText("导出表格截图").assertDoesNotExist()
    }
}
