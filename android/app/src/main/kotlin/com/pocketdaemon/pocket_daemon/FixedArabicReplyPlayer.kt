package com.pocketdaemon.pocket_daemon

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Offline fixed replies. Refuses to play unless call-audio routing is verified. */
class FixedArabicReplyPlayer(
    private val context: Context,
    private val audioHandler: CallAudioHandler,
) {
    companion object {
        private const val TAG = "FixedArabicReplies"
        private const val RATE = CallAudioHandler.PLAYBACK_RATE
        private val REPLIES = listOf(
            "السلام عليكم، معك المساعد الآلي لصاحب هذا الهاتف. كيف أقدر أساعدك؟",
            "تفضل، أنا أستمع إليك. إذا كنت تريد ترك رسالة لصاحب الهاتف، قل اسمك وسبب اتصالك.",
            "شكرًا لاتصالك. في هذا الوضع لا أستطيع فهم كلامك أو تسجيله تلقائيًا. يرجى معاودة الاتصال لاحقًا."
        )
    }

    @Volatile private var stopped = false
    @Volatile private var worker: Thread? = null
    @Volatile private var tts: TextToSpeech? = null
    private val files = mutableListOf<File>()

    fun start() {
        worker = Thread({
            try {
                val latch = CountDownLatch(1)
                var status = TextToSpeech.ERROR
                val engine = TextToSpeech(context.applicationContext) { result ->
                    status = result
                    latch.countDown()
                }
                tts = engine
                if (!latch.await(10, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
                    Log.e(TAG, "TTS initialization failed")
                    return@Thread
                }
                val lang = engine.setLanguage(Locale("ar"))
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Arabic voice unavailable; no reply played")
                    return@Thread
                }
                for ((i, text) in REPLIES.withIndex()) {
                    if (stopped) break
                    if (i > 0) Thread.sleep(if (i == 1) 8_000L else 12_000L)
                    if (stopped) break
                    if (!audioHandler.isTelephonyPlaybackRouted()) {
                        Log.e(TAG, "No verified TELEPHONY output; refusing local-speaker fallback")
                        break
                    }
                    val pcm = synthesize(engine, text, i)
                    if (stopped) break
                    if (pcm == null || !audioHandler.isTelephonyPlaybackRouted()) {
                        Log.e(TAG, "Reply synthesis or telephony route check failed")
                        break
                    }
                    audioHandler.enqueuePlayback(pcm)
                    Log.i(TAG, "Queued fixed reply " + (i + 1))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Reply sequence failed: " + e.message)
            } finally {
                try { tts?.stop() } catch (_: Exception) {}
                try { tts?.shutdown() } catch (_: Exception) {}
                files.forEach { try { it.delete() } catch (_: Exception) {} }
                files.clear()
            }
        }, "fixed-arabic-replies")
        worker?.start()
    }

    fun stop() {
        stopped = true
        worker?.interrupt()
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun synthesize(engine: TextToSpeech, text: String, index: Int): ByteArray? {
        val file = File(context.cacheDir, "fixed_reply_" + System.nanoTime() + "_" + index + ".wav")
        files.add(file)
        val id = "fixed_reply_" + index
        val done = CountDownLatch(1)
        var ok = false
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) { ok = true; done.countDown() }
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }
        })
        if (engine.synthesizeToFile(text, android.os.Bundle(), file, id) != TextToSpeech.SUCCESS) return null
        if (!done.await(20, TimeUnit.SECONDS) || !ok || stopped) return null
        return wavToPcm(file)
    }

    private fun wavToPcm(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return null
            val first = ByteArray(12)
            raf.readFully(first)
            if (String(first, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(first, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var offset = -1L
            var size = 0
            while (raf.filePointer + 8 <= raf.length()) {
                val h = ByteArray(8)
                raf.readFully(h)
                val id = String(h, 0, 4, Charsets.US_ASCII)
                val n = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (n < 0 || raf.filePointer + n > raf.length()) return null
                if (id == "fmt ") {
                    if (n < 16) return null
                    val b = ByteArray(16)
                    raf.readFully(b)
                    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.short.toInt() and 0xffff
                    channels = bb.short.toInt() and 0xffff
                    sampleRate = bb.int
                    bb.int
                    bb.short
                    bits = bb.short.toInt() and 0xffff
                    raf.seek(raf.filePointer + n - 16)
                } else if (id == "data") {
                    offset = raf.filePointer
                    size = n
                    break
                } else {
                    raf.seek(raf.filePointer + n)
                }
                if (n % 2 == 1 && raf.filePointer < raf.length()) raf.seek(raf.filePointer + 1)
            }
            if (format != 1 || channels !in 1..2 || sampleRate <= 0 || bits != 16 || offset < 0 || size < 2) return null
            val bytes = ByteArray(size)
            raf.seek(offset)
            raf.readFully(bytes)
            val count = bytes.size / (2 * channels)
            if (count <= 0) return null
            val input = ShortArray(count)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                var sum = 0
                repeat(channels) { sum += bb.short.toInt() }
                input[i] = (sum / channels).coerceIn(-32768, 32767).toShort()
            }
            val outCount = (count.toLong() * RATE / sampleRate).toInt().coerceAtLeast(1)
            val out = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until outCount) {
                val pos = i.toDouble() * sampleRate / RATE
                val left = pos.toInt().coerceIn(0, count - 1)
                val right = (left + 1).coerceAtMost(count - 1)
                val frac = pos - left
                val sample = (input[left] * (1.0 - frac) + input[right] * frac).toInt()
                out.putShort(sample.coerceIn(-32768, 32767).toShort())
            }
            return out.array()
        }
    }
}
