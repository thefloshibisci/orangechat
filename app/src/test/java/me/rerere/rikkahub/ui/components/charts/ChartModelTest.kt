package me.rerere.rikkahub.ui.components.charts

import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ChartModelTest {
    @Test fun parsesThreeSupportedShapesAndKeepsExactNumericData() {
        fun parse(text: String) = ChartSpec.fromJson(JsonInstant.parseToJsonElement(text))
        val line = parse("""{"style":"line","x_axis":{"data":["甲","乙"]},"series":[{"color":"#F80","values":[-2,3]}]}""")!!
        assertEquals(ChartStyle.Line, line.style)
        assertEquals("乙", line.categoryLabel(1))
        assertEquals(listOf(-2.0, 3.0), line.series.single().values)
        assertEquals(Color(0xFFFF8800), line.series.single().color)
        assertEquals(ChartStyle.Bar, parse("""{"style":"bar","series":[{"values":[-1,0,1]}]}""")!!.style)
        val scatter = parse("""{"style":"scatter","series":[{"points":[{"x":2,"y":3}]}]}""")!!
        assertEquals(listOf(ChartPoint(2.0, 3.0)), scatter.series.single().points)
    }

    @Test fun malformedHistoricalPayloadDoesNotCreateAPartialOrMisleadingChart() {
        for (text in listOf("null", "[]", "true", "{}", """{"style":"pie","series":[{"values":[1]}]}""",
            """{"style":"line","series":[{"values":["2"]}]}""",
            """{"style":"scatter","series":[{"points":[{"x":0,"y":1e309}]}]}""")) {
            assertNull(text, ChartSpec.fromJson(JsonInstant.parseToJsonElement(text)))
        }
    }

    @Test fun negativeConstantAndLogRangesProduceFiniteCoordinatesAndBoundedTicks() {
        val negative = buildAxisScale(listOf(-5.0, 5.0), null, null, false, true)
        assertTrue(negative.min <= -5 && negative.max >= 5)
        val constant = buildAxisScale(listOf(10.0, 10.0), null, null, false, false)
        assertTrue(constant.min < constant.max)
        val log = buildAxisScale(listOf(1.0, 100.0), null, null, true, false)
        assertEquals(0.5f, log.fraction(10.0), 0.0001f)
        val tight = buildAxisScale(listOf(1e100), 1e-100, 2e-100, false, false)
        assertTrue(tight.fraction(1e100).isFinite())
        assertTrue(tight.ticks.size <= 100)
    }

    @Test fun explicitSingleBoundRemainsAuthoritativeWhenValuesAreClipped() {
        val upper = buildAxisScale(listOf(50.0), null, 10.0, false, false)
        assertEquals(10.0, upper.max, 0.0)
        assertTrue(upper.min < upper.max)
        val lower = buildAxisScale(listOf(-50.0), 10.0, null, false, false)
        assertEquals(10.0, lower.min, 0.0)
        assertTrue(lower.min < lower.max)
        val logUpper = buildAxisScale(listOf(1000.0), null, 10.0, true, false)
        assertEquals(10.0, logUpper.max, 0.00001)
        assertTrue(logUpper.min < logUpper.max)
    }

    @Test fun tinyAndVeryLargeNumbersRemainVisibleInsteadOfBecomingZeroLabels() {
        assertTrue(formatChartNumber(1e-50).uppercase().contains("E"))
        assertTrue(formatChartNumber(-1e100).uppercase().contains("E"))
        assertEquals("0", formatChartNumber(0.0))
    }

    @Test fun shortSeriesKeepCategoryPositionsAndMissingCellsExplicit() {
        val spec = ChartSpec(ChartStyle.Line, xAxis = ChartAxis(data = listOf("a", "b", "c")), series = listOf(
            ChartSeries(values = listOf(1.0, 2.0)), ChartSeries(values = listOf(3.0, 4.0, 5.0)),
        ))
        assertEquals(3, spec.categoryCount)
        assertEquals("c", spec.categoryLabel(2))
        assertEquals("4", spec.categoryLabel(3))
        assertEquals(2, spec.seriesColors().size)
    }
}
