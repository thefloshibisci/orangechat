package me.rerere.rikkahub.ui.components.charts

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyChartTypePaintsItsSeriesOnTheNativeCanvas() {
        var style by mutableStateOf(ChartStyle.Line)
        compose.setContent {
            MaterialTheme {
                ChartCard(spec = spec(style), modifier = Modifier.width(340.dp))
            }
        }
        for ((type, label) in listOf(ChartStyle.Line to "折线图", ChartStyle.Bar to "柱状图", ChartStyle.Scatter to "散点图")) {
            compose.runOnIdle { style = type }
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInWindow
            val bitmap = compose.runOnIdle { captureChartRegion(compose.activity, bounds, "chart-${type.name.lowercase()}") }
            try {
                assertTrue("$type should contain its colored data marks", bitmap.redChartPixelCount() > 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun chartAndTableSwitchPreservesCategoryAndExactValues() {
        compose.setContent { MaterialTheme { ChartCard(spec(ChartStyle.Bar), Modifier.width(340.dp)) } }
        compose.onNodeWithContentDescription("数据表").performClick()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithContentDescription("柱状图").assertDoesNotExist()
        compose.onNodeWithContentDescription("图表").performClick()
        compose.onNodeWithContentDescription("柱状图").assertIsDisplayed()
    }

    @Test fun scatterDataTableIncludesBothNumericCoordinatesAndSeriesIdentity() {
        compose.setContent {
            MaterialTheme { ChartCard(spec(ChartStyle.Scatter), Modifier.width(340.dp), ChartDisplayMode.Table) }
        }
        compose.onNodeWithText("x").assertIsDisplayed()
        compose.onNodeWithText("y").assertIsDisplayed()
        compose.onNodeWithText("样本").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("5").assertIsDisplayed()
    }

    @Test fun narrowDarkChartWithLongCategoryStillRendersAndCanOpenTable() {
        val chart = spec(ChartStyle.Line).copy(xAxis = ChartAxis(data = listOf("长标签".repeat(60), "乙")))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { ChartCard(chart, Modifier.width(220.dp)) }
        }
        compose.onNodeWithContentDescription("折线图").assertIsDisplayed()
        compose.onNodeWithContentDescription("数据表").performClick()
        compose.onNodeWithTag("chart_data_table").performScrollToIndex(2)
        compose.onNodeWithText("乙").assertIsDisplayed()
    }

    private fun spec(style: ChartStyle) = ChartSpec(
        style = style,
        title = "图表示例",
        xAxis = if (style == ChartStyle.Scatter) ChartAxis() else ChartAxis(data = listOf("Alpha", "Beta")),
        series = listOf(ChartSeries(
            name = if (style == ChartStyle.Scatter) "样本" else null,
            color = Color(0xFFCC3300),
            values = if (style == ChartStyle.Scatter) emptyList() else listOf(2.0, 5.0),
            points = if (style == ChartStyle.Scatter) listOf(ChartPoint(2.0, 5.0)) else emptyList(),
        )),
    )
}
