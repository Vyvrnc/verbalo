package cz.vyvrnc.transcribronpu.file

import androidx.annotation.StringRes
import dev.soupslurpr.transcribro.R

/**
 * Who speaks when, from a stereo call recording with the phone's microphone on the left and the
 * other side (call downlink, Meet) on the right, as the Fold records them. No diarisation: the
 * louder channel wins. The microphone also hears the loudspeaker (about 10 dB down), so the
 * channels are compared with each other, never against a fixed level.
 *
 * Measured on a 44 min Meet recording (12. 11. 2026): own speech left -24 / right -43 dB, the
 * other side left -34 / right -24 dB, the other side with the own microphone muted -74 / -24 dB.
 */
object SpeakerTurns {
    /** [labelRes] is written into the transcript, resolve it with a context. */
    enum class Speaker(@StringRes val labelRes: Int) { ME(R.string.speaker_me), OTHER(R.string.speaker_other) }

    data class Turn(val speaker: Speaker, val startSec: Double, val endSec: Double)

    /** One channel this much louder than the other decides the step. */
    private const val MARGIN_DB = 6f
    /** Steps where both channels are quieter are silence. */
    private const val SILENCE_DB = -50f
    /** Shorter runs (1.5 s) join the turn before them, so a "jo" in between does not split it. */
    private const val MIN_TURN_STEPS = 3
    /** Fewer voiced steps (10 s) is too little to tell a call from a stereo music file. */
    private const val MIN_VOICED_STEPS = 20

    /**
     * Turns covering the whole recording, or null when the channels do not separate the speakers
     * (a mono source saved as stereo, a stereo microphone, only one side speaking).
     */
    fun find(left: List<Float>, right: List<Float>, stepSec: Double = AudioDecoder.ChannelLevels.STEP_S): List<Turn>? {
        val n = minOf(left.size, right.size)
        val labels = arrayOfNulls<Speaker>(n)
        var voiced = 0
        var me = 0
        var other = 0
        for (i in 0 until n) {
            if (maxOf(left[i], right[i]) < SILENCE_DB) continue
            voiced++
            val d = left[i] - right[i]
            labels[i] = when {
                d > MARGIN_DB -> Speaker.ME.also { me++ }
                d < -MARGIN_DB -> Speaker.OTHER.also { other++ }
                else -> null
            }
        }
        val decided = me + other
        // Most voiced steps must lean clearly to one side, and both sides must speak.
        if (voiced < MIN_VOICED_STEPS || decided < voiced * 0.5 || minOf(me, other) < decided * 0.03) return null

        // Undecided steps (silence, both at once) belong to the turn that is running.
        var current = labels.firstOrNull { it != null } ?: return null
        for (i in 0 until n) labels[i]?.let { current = it } ?: run { labels[i] = current }

        val turns = ArrayList<Turn>()
        var start = 0
        for (i in 1..n) {
            if (i < n && labels[i] == labels[start]) continue
            val speaker = labels[start]!!
            val last = turns.lastOrNull()
            if (last != null && (i - start < MIN_TURN_STEPS || last.speaker == speaker)) {
                turns[turns.size - 1] = last.copy(endSec = i * stepSec)
            } else {
                turns.add(Turn(speaker, start * stepSec, i * stepSec))
            }
            start = i
        }
        return turns
    }
}
