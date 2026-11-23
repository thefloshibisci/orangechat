package me.rerere.rikkahub.data.gongdu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GongduVideoAudioTest {
    @get:Rule val directory = TemporaryFolder()

    private fun source(streams: String): GongduVideoSource = GongduVideoReader.source(
        Json.parseToJsonElement(
            """{"note":{"video":{"media":{"stream":$streams},"subtitles":{"zh":[{"text":"平台字幕"}]}}}}""",
        ).jsonObject,
    )

    private fun files(): Pair<File, File> {
        val video = directory.newFile("video.mp4").apply { writeText("primary") }
        return video to directory.newFile("output.wav")
    }

    private fun assertNoAlternateFiles() {
        assertEquals(setOf("video.mp4", "output.wav"), directory.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun audioMetadataWinsOverSizeWithinCompatibleVideoStreams() {
        val selected = source(
            """{"h264":[
                {"masterUrl":"https://v.xhscdn.com/small.mp4","size":1},
                {"masterUrl":"https://v.xhscdn.com/sound.mp4","size":9,"audioChannels":2}],
                "h265":[{"masterUrl":"https://v.xhscdn.com/hevc.mp4","size":1,"audioCodec":"aac"}]}""",
        )
        assertEquals("https://v.xhscdn.com/sound.mp4", selected.url)
        assertEquals("平台字幕", selected.subtitles)
        assertEquals("https://v.xhscdn.com/hevc.mp4", selected.audioFallbackUrls.first())
    }

    @Test fun eachAvailableAudioMetadataFieldCanIdentifyTheBetterCandidate() {
        for (metadata in listOf(
            "\"audioCodec\":\"aac\"", "\"audioChannels\":2", "\"audioBitrate\":128000",
            "\"audioDuration\":173733",
        )) {
            val selected = source(
                """{"h264":[{"masterUrl":"https://v.xhscdn.com/tiny.mp4","size":1},
                    {"masterUrl":"https://v.xhscdn.com/audio.mp4","size":100,$metadata}]}""",
            )
            assertEquals(metadata, "https://v.xhscdn.com/audio.mp4", selected.url)
        }
    }

    @Test fun missingAndZeroAudioMetadataRetainSmallH264Compatibility() {
        for (metadata in listOf("", ",\"audioCodec\":\"\",\"audioChannels\":0,\"audioBitrate\":0,\"audioDuration\":0")) {
            val selected = source(
                """{"h264":[{"masterUrl":"https://v.xhscdn.com/large.mp4","size":99$metadata},
                    {"masterUrl":"https://v.xhscdn.com/small.mp4","size":9$metadata}],
                    "h265":[{"masterUrl":"https://v.xhscdn.com/hevc.mp4","size":1}]}""",
            )
            assertEquals("https://v.xhscdn.com/small.mp4", selected.url)
        }
    }

    @Test fun emptyAndPlaceholderAudioCodecLabelsDoNotOutrankUnknownSmallerStreams() {
        for (codec in listOf("", "0", " none ", " unknown ", " null ")) {
            val selected = source(
                """{"h264":[{"masterUrl":"https://v.xhscdn.com/small.mp4","size":1},
                    {"masterUrl":"https://v.xhscdn.com/large.mp4","size":9,"audioCodec":"$codec"}]}""",
            )
            assertEquals(codec, "https://v.xhscdn.com/small.mp4", selected.url)
        }
    }

    @Test fun unusableH264EntriesDoNotHideOtherAvailableEncodings() {
        val selected = source(
            """{"h264":[{"size":1},{"masterUrl":""}],
                "h265":[{"masterUrl":"https://v.xhscdn.com/available.mp4","size":9}]}""",
        )
        assertEquals("https://v.xhscdn.com/available.mp4", selected.url)
    }

    @Test fun fallbackBudgetPrioritizesAudioAcrossEncodingsAndDeduplicatesUrls() {
        val selected = source(
            """{"h264":[{"masterUrl":"https://v.xhscdn.com/main.mp4","size":1},
                {"masterUrl":"https://v.xhscdn.com/unknown1.mp4","size":2},
                {"masterUrl":"https://v.xhscdn.com/unknown2.mp4","size":3}],
                "h265":[{"masterUrl":"https://v.xhscdn.com/sound1.mp4","size":8,"audioCodec":"aac"},
                {"masterUrl":"https://v.xhscdn.com/sound1.mp4","size":9,"audioChannels":2}],
                "av1":[{"masterUrl":"https://v.xhscdn.com/sound2.mp4","size":10,"audioBitrate":1000}]}""",
        )
        assertEquals("https://v.xhscdn.com/main.mp4", selected.url)
        assertEquals(
            listOf("https://v.xhscdn.com/sound1.mp4", "https://v.xhscdn.com/sound2.mp4"),
            selected.audioFallbackUrls,
        )
    }

    @Test fun knownOversizedAudioDoesNotDisplaceDownloadableUnknownStreams() {
        val selected = source(
            """{"h264":[{"masterUrl":"https://v.xhscdn.com/oversized.mp4","size":41943041,"audioCodec":"aac"},
                {"masterUrl":"https://v.xhscdn.com/small.mp4","size":100}],
                "h265":[{"masterUrl":"https://v.xhscdn.com/too-large.mp4","size":50000000,"audioCodec":"aac"},
                {"masterUrl":"https://v.xhscdn.com/boundary.mp4","size":41943040,"audioChannels":2}]}""",
        )
        assertEquals("https://v.xhscdn.com/small.mp4", selected.url)
        assertEquals(listOf("https://v.xhscdn.com/boundary.mp4"), selected.audioFallbackUrls)
    }

    @Test fun streamsWithUnknownSizeRemainEligibleAndAllOversizedStreamsFailClearly() {
        assertEquals(
            "https://v.xhscdn.com/unknown.mp4",
            source("""{"h264":[{"masterUrl":"https://v.xhscdn.com/unknown.mp4","audioCodec":"aac"}]}""").url,
        )
        assertThrows(IllegalStateException::class.java) {
            source("""{"h264":[{"masterUrl":"https://v.xhscdn.com/large.mp4","size":41943041}]}""")
        }
    }

    @Test fun readyPrimaryReturnsItsAudioWithoutDownloadingAnyAlternates() = runBlocking {
        val (video, output) = files()
        var extractions = 0
        val result = recoverGongduVideoAudio(video, output, listOf("unused"),
            download = { _, _ -> error("No download expected") },
            extract = { input, wav ->
                assertEquals(video, input)
                extractions++
                wav.writeText("ready audio")
                GongduAudioResult.READY
            },
        )
        assertEquals(GongduAudioResult.READY, result)
        assertEquals(1, extractions)
        assertEquals("ready audio", output.readText())
        assertNoAlternateFiles()
    }

    @Test fun missingPrimaryTrackRecoversFromFirstAlternateAndStopsImmediately() = runBlocking {
        val (video, output) = files()
        val downloads = mutableListOf<String>()
        var extractions = 0
        val result = recoverGongduVideoAudio(video, output, listOf("sound", "unused"),
            download = { url, target -> downloads += url; target.writeText(url) },
            extract = { input, wav ->
                extractions++
                if (input == video) GongduAudioResult.NO_TRACK else {
                    assertEquals("sound", input.readText())
                    wav.writeText("recovered audio")
                    GongduAudioResult.READY
                }
            },
        )
        assertEquals(GongduAudioResult.READY, result)
        assertEquals(listOf("sound"), downloads)
        assertEquals(2, extractions)
        assertEquals("recovered audio", output.readText())
        assertNoAlternateFiles()
    }

    @Test fun recoveryAttemptsAtMostTwoDistinctAlternates() = runBlocking {
        val (video, output) = files()
        val downloads = mutableListOf<String>()
        var extractions = 0
        val result = recoverGongduVideoAudio(video, output, listOf("a", "a", "b", "c"),
            download = { url, target -> downloads += url; target.writeText(url) },
            extract = { _, _ -> extractions++; GongduAudioResult.NO_TRACK },
        )
        assertEquals(GongduAudioResult.NO_TRACK, result)
        assertEquals(listOf("a", "b"), downloads)
        assertEquals(3, extractions)
        assertEquals(0L, output.length())
        assertNoAlternateFiles()
    }

    @Test fun partialAudioAndPartialDownloadsAreClearedBeforeEachRetry() = runBlocking {
        val (video, output) = files()
        output.writeText("old wav")
        var downloads = 0
        val result = recoverGongduVideoAudio(video, output, listOf("bad", "good"),
            download = { url, target ->
                assertEquals(0L, target.length())
                downloads++
                target.writeText(url)
            },
            extract = { input, wav ->
                assertEquals(0L, wav.length())
                if (input == video || input.readText() == "bad") {
                    wav.writeText("incomplete")
                    throw IOException("decoder failed after writing")
                }
                wav.writeText("complete")
                GongduAudioResult.READY
            },
        )
        assertEquals(GongduAudioResult.READY, result)
        assertEquals(2, downloads)
        assertEquals("complete", output.readText())
        assertNoAlternateFiles()
    }

    @Test fun allNoTrackResultsStayDistinctFromAudioOutsideTheRequestedWindow() = runBlocking {
        for (primary in listOf(GongduAudioResult.NO_TRACK, GongduAudioResult.NO_SAMPLES)) {
            val video = directory.newFile("${primary.name}.mp4")
            val output = directory.newFile("${primary.name}.wav")
            val result = recoverGongduVideoAudio(video, output, listOf("other"),
                download = { _, target -> target.writeText("alternate") },
                extract = { input, _ -> if (input == video) primary else GongduAudioResult.NO_TRACK },
            )
            assertEquals(primary, result)
            assertEquals(0L, output.length())
        }
        assertFalse(directory.root.listFiles()!!.any { it.name.startsWith("gongdu-audio-source-") })
    }

    @Test fun aDecoderFailureIsRetainedWhenOtherCandidatesOnlyLackTracks() = runBlocking {
        val (video, output) = files()
        val result = recoverGongduVideoAudio(video, output, listOf("other"),
            download = { _, target -> target.writeText("alternate") },
            extract = { input, wav ->
                wav.writeText("partial")
                if (input == video) throw IOException("unsupported codec")
                GongduAudioResult.NO_TRACK
            },
        )
        assertEquals(GongduAudioResult.FAILED, result)
        assertEquals(0L, output.length())
        assertNoAlternateFiles()
    }

    @Test fun aFailedDownloadCanRecoverUsingTheNextAlternate() = runBlocking {
        val (video, output) = files()
        val downloads = mutableListOf<String>()
        val result = recoverGongduVideoAudio(video, output, listOf("expired", "fresh"),
            download = { url, target ->
                downloads += url
                if (url == "expired") throw IOException("expired signed URL")
                target.writeText("fresh")
            },
            extract = { input, wav ->
                if (input == video) GongduAudioResult.NO_SAMPLES else {
                    wav.writeText("audio")
                    GongduAudioResult.READY
                }
            },
        )
        assertEquals(GongduAudioResult.READY, result)
        assertEquals(listOf("expired", "fresh"), downloads)
        assertEquals("audio", output.readText())
        assertNoAlternateFiles()
    }

    @Test fun failedDownloadsDoNotTurnAWindowExtractionProblemIntoNoTrack() = runBlocking {
        val (video, output) = files()
        val result = recoverGongduVideoAudio(video, output, listOf("expired"),
            download = { _, target -> target.writeText("partial media"); throw IOException("network failed") },
            extract = { _, wav -> wav.writeText("partial wav"); GongduAudioResult.NO_SAMPLES },
        )
        assertEquals(GongduAudioResult.FAILED, result)
        assertEquals(0L, output.length())
        assertNoAlternateFiles()
    }

    @Test fun cancellationDuringAlternateExtractionPropagatesAndReleasesMedia() = runBlocking {
        val (video, output) = files()
        val cancellation = CancellationException("cancelled extraction")
        var downloads = 0
        try {
            recoverGongduVideoAudio(video, output, listOf("first", "unused"),
                download = { _, target -> downloads++; target.writeText("media") },
                extract = { input, _ ->
                    if (input == video) GongduAudioResult.NO_TRACK else throw cancellation
                },
            )
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, downloads)
        assertNoAlternateFiles()
    }

    @Test fun cancellationDuringAlternateDownloadPropagatesAndReleasesPartialMedia() = runBlocking {
        val (video, output) = files()
        val cancellation = CancellationException("cancelled download")
        var downloads = 0
        try {
            recoverGongduVideoAudio(video, output, listOf("first", "unused"),
                download = { _, target ->
                    downloads++
                    target.writeText("partial media")
                    throw cancellation
                },
                extract = { _, _ -> GongduAudioResult.NO_TRACK },
            )
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, downloads)
        assertNoAlternateFiles()
    }
}
