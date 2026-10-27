package com.example.smartaiglasses.ocr

class TextPolicy(
    private val minLength: Int = 3,
    private val cooldownMs: Long = 3000L
) {
    private var lastText = ""
    private var lastSpokenAtMs = 0L

    fun clean(raw: String): String = raw
        .replace(Regex("[|\\\\^~`<>{}\\[\\]]"), "")
        .replace('\n', ' ')
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    fun shouldSpeak(text: String, nowMs: Long, readOnce: Boolean, ttsBusy: Boolean): Boolean {
        if (text.length < minLength) return false
        if (readOnce) return true
        if (ttsBusy) return false

        return text != lastText || nowMs - lastSpokenAtMs >= cooldownMs
    }

    fun markSpoken(text: String, nowMs: Long) {
        lastText = text
        lastSpokenAtMs = nowMs
    }

    fun reset() {
        lastText = ""
        lastSpokenAtMs = 0L
    }
}