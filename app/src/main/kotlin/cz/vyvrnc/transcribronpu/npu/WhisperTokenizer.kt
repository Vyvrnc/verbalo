package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Token ids <-> text for the large-v3 vocabulary. assets/multilingual.tiktoken is OpenAI's
 * byte-level BPE table (MIT, github.com/openai/whisper) with "<base64 bytes> <id>" per line;
 * ids above it are special tokens and never part of the text.
 */
object WhisperTokenizer {
    /** <|xx|> tokens start here, in this order (generation_config.json of large-v3-turbo). */
    const val LANG_FIRST = 50259
    private val LANGS = ("en,zh,de,es,ru,ko,fr,ja,pt,tr,pl,ca,nl,ar,sv,it,id,hi,fi,vi,he,uk,el,ms,cs,ro,da,hu,ta,no," +
        "th,ur,hr,bg,lt,la,mi,ml,cy,sk,te,fa,lv,bn,sr,az,sl,kn,et,mk,br,eu,is,hy,ne,mn,bs,kk,sq,sw,gl,mr,pa,si,km,sn,yo," +
        "so,af,oc,ka,be,tg,sd,gu,am,yi,lo,uz,fo,ht,ps,tk,nn,mt,sa,lb,my,bo,tl,mg,as,tt,haw,ln,ha,ba,jw,su,yue").split(',')

    /** Pre-tokenizer of the "gpt2" tiktoken encoding that Whisper's tokenizer uses. */
    private val PIECES = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

    private class Tables(val vocab: Array<ByteArray>, val ranks: HashMap<String, Int>)

    @Volatile private var tables: Tables? = null

    /** -1 for "auto" or an unknown code: the NPU backend then detects the language. */
    fun langToken(code: String): Int = LANGS.indexOf(code).let { if (it < 0) -1 else LANG_FIRST + it }

    fun langCode(token: Int): String = LANGS.getOrNull(token - LANG_FIRST) ?: "?"

    fun decode(context: Context, ids: List<Int>): String {
        val v = tables(context).vocab
        val out = ByteArrayOutputStream()
        for (id in ids) if (id in v.indices) out.write(v[id])
        // Decoded once over all bytes, so a character split across tokens stays whole.
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** Text -> token ids (byte-level BPE by merge rank, as tiktoken does). */
    fun encode(context: Context, text: String): IntArray {
        val ranks = tables(context).ranks
        val out = ArrayList<Int>()
        for (m in PIECES.findAll(text)) {
            // Bytes as ISO-8859-1 chars: one char per byte, usable as a map key.
            val bytes = String(m.value.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
            ranks[bytes]?.let { out.add(it); continue }
            val parts = bytes.map { it.toString() }.toMutableList()
            while (parts.size > 1) {
                var best = -1
                var bestRank = Int.MAX_VALUE
                for (i in 0 until parts.size - 1) {
                    val r = ranks[parts[i] + parts[i + 1]] ?: continue
                    if (r < bestRank) { bestRank = r; best = i }
                }
                if (best < 0) break
                parts[best] = parts[best] + parts.removeAt(best + 1)
            }
            // Every single byte is in the table, so nothing is dropped here.
            for (p in parts) ranks[p]?.let(out::add)
        }
        return out.toIntArray()
    }

    private fun tables(context: Context): Tables = tables ?: synchronized(this) {
        tables ?: run {
            val map = HashMap<Int, ByteArray>(52000)
            context.assets.open("multilingual.tiktoken").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val sp = line.indexOf(' ')
                    if (sp <= 0) continue
                    // The table has "= 50256" (an empty placeholder) that android.util.Base64
                    // rejects; such entries are not text anyway.
                    map[line.substring(sp + 1).trim().toInt()] =
                        runCatching { Base64.decode(line.substring(0, sp), Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                }
            }
            val vocab = Array((map.keys.maxOrNull() ?: -1) + 1) { map[it] ?: ByteArray(0) }
            val ranks = HashMap<String, Int>(map.size * 2)
            for ((id, bytes) in map) if (bytes.isNotEmpty()) ranks[String(bytes, Charsets.ISO_8859_1)] = id
            Tables(vocab, ranks).also { tables = it }
        }
    }
}
