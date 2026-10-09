package cz.vyvrnc.transcribronpu.npu

import kotlin.math.sqrt

/**
 * Splits audio longer than one Whisper window (30 s) at the quietest moment instead of a
 * hard cut that can halve a word. The cut lands in the last [SEARCH_S] seconds before each
 * 30 s limit, at the 20 ms frame with the lowest energy averaged over 200 ms around it.
 */
object SilenceSplitter {
    private const val RATE = 16000
    private const val MAX_S = 29.5f   // a little under 30 s so the mel window never truncates
    private const val SEARCH_S = 8f
    private const val FRAME = RATE / 50          // 20 ms
    private const val SMOOTH_FRAMES = 10         // 200 ms
    const val MAX_SAMPLES = (MAX_S * RATE).toInt()

    /** Sample ranges covering [pcm], each at most MAX_S long. */
    fun split(pcm: FloatArray): List<IntRange> {
        val max = MAX_SAMPLES
        if (pcm.size <= max) return listOf(0 until pcm.size)
        val out = ArrayList<IntRange>()
        var start = 0
        while (pcm.size - start > max) {
            val cut = quietestCut(pcm, start + max - (SEARCH_S * RATE).toInt(), start + max)
            out.add(start until cut)
            start = cut
        }
        if (start < pcm.size) out.add(start until pcm.size)
        return out
    }

    private fun quietestCut(pcm: FloatArray, from: Int, to: Int): Int {
        val frames = (to - from) / FRAME
        if (frames <= 0) return to
        val energy = DoubleArray(frames) { f ->
            var s = 0.0
            val o = from + f * FRAME
            for (i in o until o + FRAME) s += pcm[i] * pcm[i]
            sqrt(s / FRAME)
        }
        var best = frames - 1
        var bestValue = Double.MAX_VALUE
        for (f in 0 until frames) {
            var sum = 0.0
            var n = 0
            for (k in (f - SMOOTH_FRAMES / 2).coerceAtLeast(0)..(f + SMOOTH_FRAMES / 2).coerceAtMost(frames - 1)) {
                sum += energy[k]; n++
            }
            val v = sum / n
            if (v < bestValue) { bestValue = v; best = f }
        }
        return from + best * FRAME + FRAME / 2
    }
}
