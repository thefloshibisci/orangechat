package me.rerere.search

import org.junit.Assert.*
import org.junit.Test

class DoubaoSearchServiceTest {
    @Test fun parsesGlobalDocumentsAndDeduplicatesImages() {
        val result = DoubaoSearchService.parseResponse(DoubaoSearchMode.GLOBAL, """
            {"Result":{"Documents":[{"Title":"标题","Url":"https://example.com",
            "Snippet":[{"Text":"一"},{"Text":"二"},{"Image":{"ImageUrl":"https://example.com/a.png"}},
            {"Image":{"ImageUrl":"https://example.com/a.png"}}]}]}}
        """)
        assertEquals("一\n二", result.items.single().text)
        assertEquals(listOf("https://example.com/a.png"), result.images)
    }
    @Test fun customSearchUsesSummaryAndFallsBackToSnippet() {
        val result = DoubaoSearchService.parseResponse(DoubaoSearchMode.CUSTOM, """
            {"Result":{"WebResults":[{"Title":"A","Url":"https://a","Summary":"summary","Snippet":"snippet"},
            {"Title":"B","Url":"https://b","Summary":"","Snippet":"fallback"}]}}
        """)
        assertEquals(listOf("summary", "fallback"), result.items.map { it.text })
    }
    @Test fun apiErrorsAreNotReportedAsEmptySuccess() {
        for (mode in DoubaoSearchMode.entries) {
            assertTrue(runCatching { DoubaoSearchService.parseResponse(mode,
                """{"ResponseMetadata":{"Error":{"Code":"Denied","Message":"bad key"}}}""") }.isFailure)
        }
        assertTrue(runCatching { DoubaoSearchService.parseResponse(DoubaoSearchMode.GLOBAL,
            """{"Result":{"ErrorCode":401,"ErrorMsg":"bad key"}}""") }.isFailure)
    }
}
