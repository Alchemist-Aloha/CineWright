package com.cinewright

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 48 kHz stereo WAV next to the DNG frames; 24-bit when the mic path allows it, else 16-bit. */
class WavRecorder(private val file: File, private val source: Int) {
    @Volatile private var running = true
    var bits = 0; private set
    private val thread: Thread

    init {
        val rec = open(AudioFormat.ENCODING_PCM_24BIT_PACKED, 24) ?: open(AudioFormat.ENCODING_PCM_16BIT, 16)
        thread = Thread({ rec?.let(::loop) }, "wav").apply { start() }
    }

    @SuppressLint("MissingPermission") // created only when RECORD_AUDIO is granted
    private fun open(enc: Int, b: Int): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, enc)
        if (min <= 0) return null
        AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_STEREO, enc, min * 4)
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED }?.also { bits = b }
    }.getOrNull()

    private fun loop(rec: AudioRecord) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            f.write(ByteArray(44))
            val buf = ByteArray(RATE * 2 * bits / 8 / 10)
            var data = 0L
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) { f.write(buf, 0, n); data += n }
            }
            rec.stop(); rec.release()
            f.seek(0)
            f.write(header(data))
        }
    }

    private fun header(data: Long): ByteArray {
        val block = 2 * bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + data).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(RATE); putInt(RATE * block)
            putShort(block.toShort()); putShort(bits.toShort())
            put("data".toByteArray()); putInt(data.toInt())
        }.array()
    }

    fun stop() { running = false; thread.join(3000) }

    companion object { const val RATE = 48_000 }
}
