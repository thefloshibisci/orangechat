package me.rerere.rikkahub.ui.pages.stats

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import me.rerere.rikkahub.data.db.dao.ModelUsage
import me.rerere.rikkahub.data.db.dao.SourceUsage
import me.rerere.rikkahub.data.db.dao.UsageSummary
import me.rerere.rikkahub.data.db.dao.UsageTotals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestUsageCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `unknown consumption is visible without a false zero token claim`() {
        show(UsageSummary(UsageTotals(requestCount = 2, unreportedCount = 2, failedCount = 1), emptyList(), emptyList(), emptyList()))
        compose.onNodeWithText("未返回用量").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 次未返回用量，实际消耗未知。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 次未成功（中断或失败）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已报告 0 token").assertDoesNotExist()
    }

    @Test fun `model and purpose controls show real aggregate breakdowns and preserve reported total`() {
        show(UsageSummary(
            UsageTotals(requestCount = 1, promptTokens = 80, completionTokens = 20, cachedTokens = 50, totalTokens = 100),
            emptyList(),
            listOf(ModelUsage("provider", "model-id", "Test model", 1, 80, 20, 50, 100, 0)),
            listOf(SourceUsage("COMPRESSION", 1, 80, 20, 50, 100, 0)),
        ))
        compose.onNodeWithText("已报告 100 token").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("按模型").performScrollTo().performClick()
        compose.onNodeWithText("Test model · Test provider").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("按用途").performScrollTo().performClick()
        compose.onNodeWithText("对话压缩").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已报告 150 token").assertDoesNotExist()
    }

    @Test fun `range selector remains usable with empty ledger and explains historical exclusion`() {
        var selected: UsageTimeRange? = null
        compose.setContent { MaterialTheme {
            RequestUsageCard(
                RequestUsageState(summary = UsageSummary(UsageTotals(), emptyList(), emptyList(), emptyList()), isLoading = false),
                { selected = it }, {},
            )
        } }
        compose.onNodeWithText("近7天").performClick()
        compose.runOnIdle { assertEquals(UsageTimeRange.WEEK, selected) }
        compose.onNodeWithText("这个时间范围还没有请求记录。新请求完成后会自动显示；旧消息仍可在下方历史统计查看。").assertIsDisplayed()
    }

    private fun show(summary: UsageSummary) {
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RequestUsageCard(RequestUsageState(summary = summary, isLoading = false, providerNames = mapOf("provider" to "Test provider")), {}, {})
            }
        } }
    }
}
