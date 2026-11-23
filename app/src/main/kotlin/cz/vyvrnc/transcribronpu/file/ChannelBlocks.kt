package cz.vyvrnc.transcribronpu.file

/**
 * The speech of one speaker's channel packed into blocks of up to [MAX_S] seconds for one NPU
 * call each. Whisper always processes a whole 30 s window, so a 2 s turn costs nearly as much
 * as a full one and gives the model no context (measured on the Fold 7, 23. 11. 2026: 95 calls
 * for a 15 min call, 38 of them under 5 s). Silence between the pieces is left out, and every
 * piece remembers where it came from, so the text can be put back on the recording's time line.
 */
object ChannelBlocks {
    private const val RATE = AudioDecoder.RATE
    /** Below the 29.5 s window, with room for the pauses between the pieces. */
    const val MAX_S = 26.0
    /** A block this long is sent as it is rather than cutting the next piece to fill it. */
    private const val MIN_S = 15.0
    /** Silence put between two pieces, so their words do not run into each other. */
    const val GAP_S = 0.2
    /** Shorter silence inside a turn stays in the piece (breaths, short pauses). */
    private const val KEEP_PAUSE_S = 1.0
    /** Kept around speech, so the first and last syllable are not clipped by the 0.5 s steps. */
    private const val PAD_S = 0.25
    /** A long piece is cut at its quietest step in this many seconds before the limit. */
    private const val SEARCH_S = 6.0

    /** [length] samples of the recording from [recStart], placed at [blockOffset] in the block. */
    data class Piece(val recStart: Int, val length: Int, val blockOffset: Int)

    data class Block(val speaker: SpeakerTurns.Speaker, val pieces: List<Piece>) {
        val length: Int get() = pieces.last().let { it.blockOffset + it.length }
        val recStart: Int get() = pieces.first().recStart

        /** Recording sample of block sample [t]; a time in a gap belongs to the next piece. */
        fun toRecording(t: Int): Int {
            for (p in pieces) {
                if (t < p.blockOffset) return p.recStart
                if (t < p.blockOffset + p.length) return p.recStart + (t - p.blockOffset)
            }
            return pieces.last().let { it.recStart + it.length }
        }
    }

    /**
     * Where [speaker] talks: inside their turns, steps where their own channel ([ownDb], one
     * value per [stepSec]) is above [silenceDb]. The other side's voice leaks into this channel
     * too, which is why only the speaker's own turns count.
     */
    fun speech(
        turns: List<SpeakerTurns.Turn>, speaker: SpeakerTurns.Speaker, ownDb: List<Float>,
        stepSec: Double, silenceDb: Float, totalSamples: Int,
    ): List<IntRange> {
        val out = ArrayList<IntRange>()
        val keepPause = (KEEP_PAUSE_S / stepSec).toInt()
        val pad = (PAD_S * RATE).toInt()
        for (turn in turns) {
            if (turn.speaker != speaker) continue
            val first = (turn.startSec / stepSec).toInt()
            val last = minOf((turn.endSec / stepSec).toInt(), ownDb.size)   // exclusive
            val turnFrom = (turn.startSec * RATE).toInt().coerceIn(0, totalSamples)
            val turnTo = (turn.endSec * RATE).toInt().coerceIn(turnFrom, totalSamples)
            var runStart = -1
            var runEnd = -1
            fun emit() {
                if (runStart < 0) return
                val from = ((runStart * stepSec * RATE).toInt() - pad).coerceIn(turnFrom, turnTo)
                val to = ((runEnd * stepSec * RATE).toInt() + pad).coerceIn(from, turnTo)
                if (to > from) out.add(from until to)
            }
            for (i in first until last) {
                if (ownDb[i] < silenceDb) continue
                if (runStart >= 0 && i - runEnd <= keepPause) {
                    runEnd = i + 1
                } else {
                    emit()
                    runStart = i
                    runEnd = i + 1
                }
            }
            emit()
        }
        return out
    }

    /** [spans] (in time order) packed into blocks; a span longer than a block is cut. */
    fun pack(spans: List<IntRange>, speaker: SpeakerTurns.Speaker, ownDb: List<Float>, stepSec: Double): List<Block> {
        val max = (MAX_S * RATE).toInt()
        val min = (MIN_S * RATE).toInt()
        val gap = (GAP_S * RATE).toInt()
        val blocks = ArrayList<Block>()
        var current = ArrayList<Piece>()
        var length = 0
        fun flush() {
            if (current.isNotEmpty()) blocks.add(Block(speaker, current))
            current = ArrayList()
            length = 0
        }
        for (span in spans) {
            var s = span.first
            val e = span.last + 1
            while (s < e) {
                val lead = if (current.isEmpty()) 0 else gap
                val room = max - length - lead
                when {
                    e - s <= room -> {
                        current.add(Piece(s, e - s, length + lead))
                        length += lead + e - s
                        s = e
                    }
                    length >= min || room < RATE -> flush()
                    else -> {
                        val cut = quietestCut(ownDb, stepSec, s + room - (SEARCH_S * RATE).toInt(), s + room).coerceIn(s + 1, s + room)
                        current.add(Piece(s, cut - s, length + lead))
                        length += lead + cut - s
                        s = cut
                        flush()
                    }
                }
            }
        }
        flush()
        return blocks
    }

    /** Middle of the quietest level step between samples [from] and [to]. */
    private fun quietestCut(db: List<Float>, stepSec: Double, from: Int, to: Int): Int {
        val step = stepSec * RATE
        val a = (from / step).toInt().coerceAtLeast(0)
        val b = minOf((to / step).toInt(), db.size - 1)
        if (b < a) return to
        var best = a
        for (i in a..b) if (db[i] < db[best]) best = i
        return ((best + 0.5) * step).toInt()
    }
}
