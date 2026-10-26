package me.rerere.rikkahub.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallMicrophoneTest {
    @Test fun muteAndInactiveStatesAlwaysOverrideReplyListening() {
        VoiceCallStatus.entries.forEach { status ->
            assertFalse(voiceCallShouldListen(status, muted = true, keepListeningDuringReply = true))
        }
        for (status in listOf(VoiceCallStatus.Idle, VoiceCallStatus.Error)) {
            assertFalse(voiceCallShouldListen(status, muted = false, keepListeningDuringReply = true))
        }
        assertTrue(voiceCallShouldListen(VoiceCallStatus.Listening, false, false))
    }

    @Test fun disablingReplyListeningStopsAndInvalidatesQueuedCallbacks() = runTest {
        val asr = FakeAsr()
        val received = mutableListOf<String>()
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, false)
        asr.callbacks.single()("queued before pause")
        microphone.update(VoiceCallStatus.Processing, false, false)
        asr.callbacks.single()("delivered after stop")
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(1, asr.stops)
        assertFalse(microphone.isListening)
        assertNull(microphone.completedTranscript(ASRState(transcript = "late")))
    }

    @Test fun quickUnmuteWaitsForStoppingAndOldCallbacksStayInvalidAfterResume() = runTest {
        val asr = FakeAsr()
        val received = mutableListOf<String>()
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, true)
        microphone.completedTranscript(asr.state.value)
        val old = asr.callbacks.single()
        microphone.update(VoiceCallStatus.Listening, true, true)
        microphone.update(VoiceCallStatus.Listening, false, true)
        assertEquals(1, asr.callbacks.size)
        asr.state.value = ASRState()
        microphone.resumeIfPending()
        old("old session")
        asr.callbacks.last()("new session")
        runCurrent()
        assertEquals(listOf("new session"), received)
        assertEquals(2, asr.callbacks.size)
    }

    @Test fun idleSnapshotProvidesFinalTextBeforeTheProviderCallback() = runTest {
        val asr = FakeAsr()
        val received = mutableListOf<String>()
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, true)
        microphone.completedTranscript(asr.state.value)
        val old = asr.callbacks.single()
        asr.state.value = ASRState(transcript = "final sentence")
        assertEquals("final sentence", microphone.completedTranscript(asr.state.value))
        microphone.resumeIfPending()
        old("final sentence")
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(2, asr.callbacks.size)
    }

    @Test fun singleUtteranceProvidersRestartThroughoutProcessingAndSpeaking() = runTest {
        val asr = FakeAsr()
        val microphone = VoiceCallMicrophone(asr, backgroundScope) {}
        microphone.update(VoiceCallStatus.Listening, false, true)
        for (status in listOf(VoiceCallStatus.Processing, VoiceCallStatus.Speaking)) {
            microphone.update(status, false, true)
            microphone.completedTranscript(asr.state.value)
            asr.state.value = ASRState()
            assertEquals("", microphone.completedTranscript(asr.state.value))
            microphone.resumeIfPending()
            assertTrue(microphone.isListening)
            assertTrue(asr.state.value.isRecording)
        }
        assertEquals(3, asr.callbacks.size)
        microphone.update(VoiceCallStatus.Speaking, false, false)
        asr.state.value = ASRState()
        microphone.resumeIfPending()
        assertEquals(3, asr.callbacks.size)
    }

    @Test fun endingTheCallRejectsQueuedResultsAndNeverResumes() = runTest {
        val asr = FakeAsr()
        val received = mutableListOf<String>()
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, true)
        asr.callbacks.single()("queued")
        microphone.update(VoiceCallStatus.Idle, true, false)
        asr.state.value = ASRState(transcript = "late")
        microphone.resumeIfPending()
        runCurrent()
        assertTrue(received.isEmpty())
        assertEquals(1, asr.callbacks.size)
    }

    @Test fun forwardedIdleCannotRestartANewSessionOrConsumeItsPredecessorsText() = runTest {
        val asr = FakeAsr(lagForwardedState = true)
        val received = mutableListOf<String>()
        asr.state.value = ASRState(transcript = "previous session")
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, true)
        assertNull(microphone.completedTranscript(asr.state.value))
        microphone.update(VoiceCallStatus.Listening, false, true)
        microphone.resumeIfPending()
        assertEquals(1, asr.startAttempts)
        asr.callbacks.single()("current session")
        runCurrent()
        assertEquals(listOf("current session"), received)

        asr.state.value = ASRState(status = ASRStatus.Listening)
        assertNull(microphone.completedTranscript(asr.state.value))
        asr.complete("current final")
        assertEquals("current final", microphone.completedTranscript(asr.state.value))
        microphone.resumeIfPending()
        assertNull(microphone.completedTranscript(asr.state.value))
        assertEquals(2, asr.startAttempts)
    }

    @Test fun previousStoppingSnapshotCannotCountAsTheNewSessionRecording() = runTest {
        val asr = FakeAsr(lagForwardedState = true)
        val microphone = VoiceCallMicrophone(asr, backgroundScope) {}
        microphone.update(VoiceCallStatus.Listening, false, true)
        assertNull(microphone.completedTranscript(ASRState(status = ASRStatus.Stopping)))
        assertNull(microphone.completedTranscript(ASRState(transcript = "old final")))
    }

    @Test fun errorDoesNotCompleteOrRestartAndStoppingInvalidatesQueuedText() = runTest {
        val asr = FakeAsr()
        val received = mutableListOf<String>()
        val microphone = VoiceCallMicrophone(asr, backgroundScope, received::add)
        microphone.update(VoiceCallStatus.Listening, false, true)
        microphone.completedTranscript(asr.state.value)
        asr.callbacks.single()("queued partial")
        asr.state.value = ASRState(status = ASRStatus.Error, transcript = "partial")
        assertNull(microphone.completedTranscript(asr.state.value))
        microphone.update(VoiceCallStatus.Error, false, true)
        microphone.resumeIfPending()
        runCurrent()
        assertFalse(microphone.isListening)
        assertTrue(received.isEmpty())
        assertEquals(1, asr.stops)
        assertEquals(1, asr.startAttempts)
    }

    private class FakeAsr(private val lagForwardedState: Boolean = false) : CustomAsrState {
        override val state = MutableStateFlow(ASRState())
        val callbacks = mutableListOf<(String) -> Unit>()
        var stops = 0
        var startAttempts = 0
        private var providerRecording = false
        override fun start(onTranscriptChange: (String) -> Unit) {
            startAttempts++
            if (lagForwardedState && providerRecording) return
            providerRecording = true
            callbacks += onTranscriptChange
            if (!lagForwardedState) state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun stop() {
            stops++
            providerRecording = false
            state.value = state.value.copy(status = ASRStatus.Stopping)
        }
        fun complete(transcript: String) {
            providerRecording = false
            state.value = ASRState(transcript = transcript)
        }
        override fun cleanup() = Unit
    }
}
