package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ForeignAnnotationTest {
    @Test fun avatarInteractionSurvivesLoadAndSaveAlongsideCitations() {
        val json = Json { ignoreUnknownKeys = true }
        val source = """[{"type":"avatar_interaction","actor":"user","target":"assistant","targetMessageId":"message-id"},{"type":"url_citation","title":"source","url":"https://example.org"}]"""
        val annotations = json.decodeFromString<List<UIMessageAnnotation>>(source)
        assertEquals(UIMessageAnnotation.AvatarInteraction("user", "assistant", "message-id"), annotations.first())
        assertEquals(1, annotations.filterIsInstance<UIMessageAnnotation.UrlCitation>().size)
        assertEquals(annotations, json.decodeFromString<List<UIMessageAnnotation>>(json.encodeToString(annotations)))
    }
}
