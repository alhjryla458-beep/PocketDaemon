package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID

/** Offline Arabic fixed replies sent through the call audio pipeline. */
class FixedArabicReplyPlayer(
    context: Context,
    private val audio: CallAudioHandler,
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "FixedArabicReplies"
        private const val GAP_AFTER_REPLY_MS = 8_000L
        private val REPLIES = listOf(
            "السلام عليكم، معك المساعد الآلي لمالك هذا الهاتف. كيف أقدر أساعدك؟",
            "تفضل، يمكنك ترك رسالة لصاحب الهاتف، وسأبلغه بها عندما يكون متاحاً.",
            "شكراً لاتصالك. انتهى الرد الآلي، ويمكنك ترك رسالة بعد هذه المكالمة."
        )
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private var index = 0
    private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var tempFile: File? = null
    private var pendingId: String? = null

    fun start() {
        if (stopped) return
        waitForTelephonyRoute(0)
    }

    private fun waitForTelephonyRoute(attempt: Int) {
        if (stopped) return
        if (audio.isTelephonyPlaybackReady()) {
            initializeSpeech()
            return
        }
        if (attempt >= 12) {
            fail("No verified TELEPHONY audio output route; fixed replies were not played")
            return
        }
        mainHandler.postDelayed({ waitForTelephonyRoute(attempt + 1) }, 250L)
    }

    private fun initializeSpeech() {
        if (stopped) return
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (stopped) return@post
                if (status != TextToSpeech.SUCCESS) {
                    fail("Android text-to-speech initialization failed")
                    return@post
                }
                val engine = tts ?: return@post
                val result = engine.setLanguage(Locale("ar"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    fail("Arabic voice is unavailable on this device")
                    return@post
                }
                ttsReady = true
                engine.setSpeechRate(0.88f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { if (!stopped) fail("Arabic speech synthesis failed") }
                    }
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (stopped || utteranceId != pendingId) return@post
                            val file = tempFile
                            if (file != null && file.exists()) decodeAndQueue(file)
                            tempFile?.delete()
                            tempFile = null
                            pendingId = null
                            if (!stopped && index < REPLIES.size) {
                                mainHandler.postDelayed({ speakNext() }, GAP_AFTER_REPLY_MS)
                            } else if (!stopped) {
                                onStatus("Fixed Arabic reply sequence completed")
                            }
                        }
                    }
                })
                speakNext()
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        tempFile?.delete()
        tempFile = null
        pendingId = null
        audio.flushPlayback()
    }

    private fun speakNext() {
        if (stopped || !ttsReady || index >= REPLIES.size) return
        if (!audio.isTelephonyPlaybackReady()) {
            fail("TELEPHONY audio route was lost; stopping fixed replies")
            return
        }
        val phrase = REPLIES[index++]
        val file = File(appContext.cacheDir, "fixed-ar-" + UUID.randomUUID() + ".wav")
        tempFile = file
        val id = "fixed-ar-" + UUID.randomUUID()
        pendingId = id
        val result = tts?.synthesizeToFile(phrase, null, file, id)
        if (result != TextToSpeech.SUCCESS) {
            fail("Could not synthesize Arabic reply")
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        onStatus(message)
        stop()
    }

    private fun decodeAndQueue(file: File) {
        try {
            val wav = readPcm16Wav(file)
            val converted = resampleMonoPcm16(wav.samples, wav.sampleRate, CallAudioHandler.PLAYBACK_RATE, wav.channels)
            if (converted.isEmpty()) {
                fail("Synthesized Arabic audio was empty")
            } else if (!audio.enqueuePlayback(converted)) {
                fail("Call audio output is not ready; fixed reply stopped")
            }
        } catch (e: Exception) {
            fail("Could not decode Arabic speech audio: " + e.message)
        }
    }

    private data class WavPcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    private fun readPcm16Wav(file: File): WavPcm {
        RandomAccessFile(file, "r").use { raf ->
            require(raf.length() >= 44) { "WAV file too short" }
            fun fourCC(): String {
                val bytes = ByteArray(4)
                raf.readFully(bytes)
                return String(bytes, Charsets.US_ASCII)
            }
            require(fourCC() == "RIFF") { "Missing RIFF header" }
            raf.skipBytes(4)
            require(fourCC() == "WAVE") { "Missing WAVE header" }
            var format = -1
            var channels = 0
            var rate = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = 0
            while (raf.filePointer + 8 <= raf.length()) {
                val id = fourCC()
                val sizeBytes = ByteArray(4)
                raf.readFully(sizeBytes)
                val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int
                if (size < 0 || raf.filePointer + size > raf.length()) break
                when (id) {
                    "fmt " -> {
                        if (size >= 16) {
                            val bytes = ByteArray(size)
                            raf.readFully(bytes)
                            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            format = bb.short.toInt() and 0xffff
                            channels = bb.short.toInt() and 0xffff
                            rate = bb.int
                            bb.int
                            bb.short
                            bits = bb.short.toInt() and 0xffff
                        } else raf.skipBytes(size)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        raf.seek(raf.filePointer + size)
                    }
                    else -> raf.skipBytes(size)
                }
                if (size % 2 == 1) raf.skipBytes(1)
            }
            require(format == 1 && bits == 16) { "Only PCM16 WAV is supported" }
            require(channels in 1..2 && rate > 0 && dataOffset >= 0) { "Unsupported WAV format" }
            val bytes = ByteArray(dataSize)
            raf.seek(dataOffset)
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(bytes.size / 2) { bb.short }
            return WavPcm(rate, channels, samples)
        }
    }

    private fun resampleMonoPcm16(input: ShortArray, sourceRate: Int, targetRate: Int, channels: Int): ByteArray {
        val frames = input.size / channels
        if (frames == 0) return ByteArray(0)
        val mono = ShortArray(frames) { frame ->
            if (channels == 1) input[frame]
            else ((input[frame * 2].toInt() + input[frame * 2 + 1].toInt()) / 2).toShort()
        }
        val outFrames = (frames.toLong() * targetRate / sourceRate).toInt().coerceAtLeast(1)
        val output = ByteBuffer.allocate(outFrames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outFrames) {
            val pos = i.toDouble() * sourceRate / targetRate
            val left = pos.toInt().coerceIn(0, frames - 1)
            val right = (left + 1).coerceAtMost(frames - 1)
            val fraction = pos - left
            val sample = (mono[left] * (1.0 - fraction) + mono[right] * fraction)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(sample.toShort())
        }
        return output.array()
    }
}
