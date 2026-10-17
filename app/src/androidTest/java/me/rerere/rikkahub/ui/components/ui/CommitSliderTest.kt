package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetProgressAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommitSliderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragPersistsOnceOnRelease() {
        val writes = mutableListOf<Float>()
        compose.setContent { MaterialTheme { CommitSlider(0.8f, { writes += it }) } }
        compose.onNode(hasSetProgressAction()).performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(1, writes.size); assertTrue(writes.single() < 0.8f) }
    }

    @Test fun accessibilityProgressIsPersisted() {
        val writes = mutableListOf<Float>()
        compose.setContent { MaterialTheme { CommitSlider(0.2f, { writes += it }) } }
        compose.onNode(hasSetProgressAction()).performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(listOf(0.75f), writes) }
    }
}
