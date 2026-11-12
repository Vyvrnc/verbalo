package cz.vyvrnc.transcribronpu.file

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.soupslurpr.transcribro.R
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

    /**
     * Loudness of each channel of a stereo source, in dBFS per [STEP_S] seconds. A call or Meet
     * recorded on the Fold has the phone's microphone on the left and the other side on the right.
     * [channels], when set, also gets both channels at 16 kHz (left, right, count), so each
     * speaker can be transcribed from their own channel.
     */
    class ChannelLevels(val channels: ((ShortArray, ShortArray, Int) -> Unit)? = null) {
        val left = ArrayList<Float>()
        val right = ArrayList<Float>()

        companion object {
            const val STEP_S = 0.5
        }
    }

    /**
     * Returns the decoded duration in seconds; [progress] gets 0..1 when the length is known.
     * [levels], when given, is filled for two-channel sources only.
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        sink: (ShortArray, Int) -> Unit,
        levels: ChannelLevels? = null,
        progress: (Float) -> Unit,
    ): Double {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Unsupported(context.getString(R.string.file_error_no_audio_track))
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val codec = runCatching { MediaCodec.createDecoderByType(mime) }
                .getOrElse { throw Unsupported(context.getString(R.string.file_error_format_unsupported, mime)) }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, format, durationUs, sink, levels, progress)
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
        levels: ChannelLevels?,
        progress: (Float) -> Unit,
    ): Double {
        // Per-channel energy of the current level step (stereo only).
        var sq0 = 0.0
        var sq1 = 0.0
        var stepFrames = 0
        fun db(sq: Double, n: Int) = (10 * kotlin.math.log10(kotlin.math.max(sq / n, 1e-12))).toFloat()
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        var resampler = Resampler(rate)
        var resampler0 = Resampler(rate)
        var resampler1 = Resampler(rate)
        var out0 = ShortArray(0)
        var out1 = ShortArray(0)
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
                    resampler0 = Resampler(rate)
                    resampler1 = Resampler(rate)
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val frames = info.size / ((if (float) 4 else 2) * channels)
                    val capacity = frames * resampler.maxOutputsPerInput + 2
                    if (out.size < capacity) out = ShortArray(capacity)
                    var n = 0
                    val stereo = levels != null && channels == 2
                    val split = if (stereo) levels.channels else null
                    if (split != null && out0.size < capacity) {
                        out0 = ShortArray(capacity)
                        out1 = ShortArray(capacity)
                    }
                    var n0 = 0
                    var n1 = 0
                    val step = (rate * ChannelLevels.STEP_S).toInt()
                    for (f in 0 until frames) {
                        // Downmix to mono.
                        var sum = 0f
                        for (c in 0 until channels) {
                            val v = if (float) buf.float else buf.short / 32768f
                            sum += v
                            if (stereo) {
                                if (c == 0) sq0 += v * v else sq1 += v * v
                            }
                            if (split != null) {
                                // Same input count per channel, so both get the same number of outputs.
                                if (c == 0) resampler0.push(v) { s -> out0[n0++] = pcm16(s) }
                                else resampler1.push(v) { s -> out1[n1++] = pcm16(s) }
                            }
                        }
                        if (stereo && ++stepFrames >= step) {
                            levels.left.add(db(sq0, stepFrames))
                            levels.right.add(db(sq1, stepFrames))
                            sq0 = 0.0; sq1 = 0.0; stepFrames = 0
                        }
                        resampler.push(sum / channels) { s ->
                            out[n++] = pcm16(s)
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (split != null && n0 > 0) split(out0, out1, minOf(n0, n1))
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

    private fun pcm16(s: Float): Short = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

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
