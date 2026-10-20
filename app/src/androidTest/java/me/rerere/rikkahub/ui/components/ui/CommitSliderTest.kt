package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class CommitSliderTest {
    @get:Rule val compose = createComposeRule()
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test fun dragPersistsOnceOnRelease() {
        val writes = mutableListOf<Float>()
        compose.setContent { MaterialTheme { CommitSlider(0.8f, { writes += it }) } }
        compose.onNode(slider).performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertTrue(writes.single() < 0.8f)
        }
    }

    @Test fun accessibilityProgressIsPersisted() {
        val writes = mutableListOf<Float>()
        compose.setContent {
            MaterialTheme {
                CommitSlider(0.2f, { writes += it }) { draft, sliderContent ->
                    Column {
                        sliderContent()
                        Text("${(draft * 100).roundToInt()}%")
                    }
                }
            }
        }
        compose.onNode(slider).performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(listOf(0.75f), writes) }
        compose.onNodeWithText("75%").assertExists()
    }

    @Test fun numberChangesBeforeReleaseWithoutPersistingEveryMove() {
        val writes = mutableListOf<Float>()
        var preview = 0.2f
        compose.setContent {
            CompositionLocalProvider(LocalDisplaySettings provides DisplaySetting()) {
                MaterialTheme {
                    CardGroup {
                        item(
                            headlineContent = { Text("Opacity") },
                            supportingContent = {
                                CommitSlider(
                                    value = 0.2f,
                                    onValueChange = { writes += it },
                                    onValuePreview = { preview = it },
                                ) { draft, sliderContent ->
                                    Column {
                                        sliderContent()
                                        Text("${(draft * 100).roundToInt()}%")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.onNode(slider).performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
        }
        compose.runOnIdle {
            assertTrue(preview > 0.5f)
            assertTrue(writes.isEmpty())
        }
        compose.onNodeWithText("${(preview * 100).roundToInt()}%").assertExists()
        compose.onNode(slider).performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(preview), writes) }
    }

    @Test fun oldSettingsEchoKeepsDraggingNumberAndThumbTogether() {
        val writes = mutableListOf<Float>()
        var persisted by mutableStateOf(DisplaySetting(drawerItemAlpha = 0.2f))
        compose.setContent {
            var pageDraft by remember(persisted) { mutableStateOf(persisted) }
            CompositionLocalProvider(LocalDisplaySettings provides persisted) {
                MaterialTheme {
                    CardGroup {
                        item(
                            headlineContent = { Text("Opacity") },
                            supportingContent = {
                                CommitSlider(
                                    value = pageDraft.drawerItemAlpha,
                                    onValueChange = { writes += it },
                                    onValuePreview = { pageDraft = pageDraft.copy(drawerItemAlpha = it) },
                                ) { draft, sliderContent ->
                                    Column {
                                        sliderContent()
                                        Text("${(draft * 100).roundToInt()}%")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.onNode(slider).performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.7f, centerY))
        }
        val draggingValue = compose.onNode(slider).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.runOnIdle {
            assertTrue(draggingValue > 0.5f)
            assertTrue(writes.isEmpty())
            // Another field's previous save echoes the old opacity into the page's keyed draft.
            persisted = persisted.copy(interfaceSurfaceOpacity = persisted.interfaceSurfaceOpacity - 1f)
        }
        compose.waitForIdle()
        val afterEcho = compose.onNode(slider).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current
        assertEquals(draggingValue, afterEcho, 0.001f)
        compose.onNodeWithText("${(draggingValue * 100).roundToInt()}%").assertExists()
        compose.runOnIdle { assertTrue(writes.isEmpty()) }
        compose.onNode(slider).performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(draggingValue), writes) }
    }

    @Test fun externalValueUpdatesIdleThumb() {
        var persisted by mutableFloatStateOf(0.2f)
        compose.setContent { MaterialTheme { CommitSlider(persisted, {}) } }
        compose.runOnIdle { persisted = 0.6f }
        compose.waitForIdle()
        val progress = compose.onNode(slider).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.6f, progress.current, 0.001f)
    }
}
