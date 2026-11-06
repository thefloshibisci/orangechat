package me.rerere.rikkahub.service

import me.rerere.ai.ui.CallTranscriptEntry
import me.rerere.tts.model.SpeechMarkup

/** Per-call, per-utterance snapshots; streaming revisions replace one line instead of duplicating it. */
internal class VoiceCallTranscript {
    private val lines = linkedMapOf<String, CallTranscriptEntry>()
    @Synchronized fun record(key: String, role: String, speaker: String, text: String, now: Long = System.currentTimeMillis()) {
        val words = SpeechMarkup.transcript(text).trim()
        if (words.isNotEmpty()) lines[key] = CallTranscriptEntry(role, speaker, words, lines[key]?.timestampMillis ?: now)
    }
    @Synchronized fun snapshot(): List<CallTranscriptEntry> = lines.values.toList()
    @Synchronized fun clear() = lines.clear()
}
