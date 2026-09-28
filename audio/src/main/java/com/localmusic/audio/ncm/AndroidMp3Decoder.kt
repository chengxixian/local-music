package com.localmusic.audio.ncm

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

/** Android-only bridge; never relabels compressed MP3 bytes as FLAC. */
internal object AndroidMp3Decoder {
    fun transcode(source: File, destination: File, checkpoint: () -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: VerbatimFlacEncoder? = null
        var started = false
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mpeg"
            } ?: throw IOException("NCM payload is neither native FLAC nor decodable MP3")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType("audio/mpeg")
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start(); started = true
            var inputEnded = false
            var outputEnded = false
            var lastProgress = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            fun applyFormat(format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                if (encoding != AudioFormat.ENCODING_PCM_16BIT)
                    throw IOException("MP3 decoder did not honor PCM16 output (encoding $encoding)")
                val current = encoder
                if (current == null) encoder = VerbatimFlacEncoder(destination, rate, channels)
                else if (current.sampleRate != rate || current.channels != channels)
                    throw IOException("MP3 decoder changed PCM format midstream")
            }
            while (!outputEnded) {
                checkpoint()
                if (System.nanoTime() - lastProgress > 30_000_000_000L)
                    throw IOException("MP3 decoder stalled for 30 seconds")
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("Missing codec input buffer")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (size > buffer.capacity()) throw IOException("Oversized MP3 input sample")
                            decoder.queueInputBuffer(index, 0, size, maxOf(0L, extractor.sampleTime), 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { applyFormat(decoder.outputFormat); lastProgress = System.nanoTime() }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                applyFormat(decoder.getOutputFormat(index))
                                val buffer = decoder.getOutputBuffer(index) ?: throw IOException("Missing codec output buffer")
                                if (info.offset < 0 || info.size < 0 || info.offset > buffer.capacity() - info.size)
                                    throw IOException("Invalid codec output bounds")
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(minOf(32768, info.size))
                                while (buffer.hasRemaining()) {
                                    checkpoint()
                                    val n = minOf(chunk.size, buffer.remaining())
                                    buffer.get(chunk, 0, n)
                                    encoder!!.write(chunk, 0, n)
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            checkpoint()
            (encoder ?: throw IOException("MP3 decoder emitted no PCM")).finish()
        } finally {
            // All native resources must be released even if another cleanup operation fails.
            try { encoder?.close() } finally {
                try { if (started) runCatching { codec?.stop() } } finally {
                    try { codec?.release() } finally { extractor.release() }
                }
            }
        }
    }
}
