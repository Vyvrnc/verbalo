package cz.vyvrnc.transcribronpu.file

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Any audio Android can decode (m4a/AAC, mp3, ogg/opus from Telegram or WhatsApp, wav, flac,
 * amr, the audio track of a video) -> 16 kHz mono PCM16, streamed to [sink] in chunks, so an
 * hour of audio never sits in memory.
 */
object AudioDecoder {
    const val RATE = 16000
    private const val TIMEOUT_US = 10_000L

    class Unsupported(message: String) : Exception(message)

    /** Returns the decoded duration in seconds; [progress] gets 0..1 when the length is known. */
    suspend fun decode(
        context: Context,
        uri: Uri,
        sink: (ShortArray, Int) -> Unit,
        progress: (Float) -> Unit,
    ): Double {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Unsupported("Soubor neobsahuje zvukovou stopu")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .getOrElse { throw Unsupported("Formát $mime telefon neumí dekódovat") }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, format, durationUs, sink, progress)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun pump(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        durationUs: Long,
        sink: (ShortArray, Int) -> Unit,
        progress: (Float) -> Unit,
    ): Double {
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var resampler = Resampler(rate)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var written = 0L
        var out = ShortArray(0)
        var idleAfterInput = 0

        while (true) {
            coroutineContext.ensureActive()
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                        extractor.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            // A decoder that never signals the end of output must not spin forever (~5 s idle).
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterInput > 500) break
            if (o >= 0) idleAfterInput = 0
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler(rate)
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = info.size / ((if (float) 4 else 2) * channels)
                    val capacity = frames * resampler.maxOutputsPerInput + 2
                    if (out.size < capacity) out = ShortArray(capacity)
                    var n = 0
                    for (f in 0 until frames) {
                        // Downmix to mono.
                        var sum = 0f
                        for (c in 0 until channels) sum += if (float) buf.float else buf.short / 32768f
                        resampler.push(sum / channels) { s ->
                            out[n++] = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (n > 0) {
                        sink(out, n)
                        written += n
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        progress(1f)
        return written.toDouble() / RATE
    }

    /**
     * Streaming conversion to 16 kHz. Downsampling averages each output period of input samples
     * (a box filter, enough against aliasing for speech); upsampling (8 kHz AMR) interpolates.
     */
    private class Resampler(sourceRate: Int) {
        private val step = sourceRate.toDouble() / RATE   // input samples per output sample
        val maxOutputsPerInput = if (step >= 1.0) 1 else kotlin.math.ceil(1.0 / step).toInt() + 1
        private var acc = 0.0
        private var weight = 0.0
        private var prev = 0f
        private var t = 0.0
        private var started = false

        inline fun push(x: Float, emit: (Float) -> Unit) {
            if (step == 1.0) {
                emit(x)
            } else if (step > 1.0) {
                val remaining = step - weight
                if (remaining > 1.0) {
                    acc += x; weight += 1.0
                } else {
                    acc += x * remaining
                    emit((acc / step).toFloat())
                    val left = 1.0 - remaining
                    acc = x * left; weight = left
                }
            } else {
                if (!started) {
                    prev = x; started = true; return
                }
                while (t <= 1.0) {
                    emit(prev + (x - prev) * t.toFloat())
                    t += step
                }
                t -= 1.0
                prev = x
            }
        }
    }
}
