package cz.vyvrnc.transcribronpu.npu

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Token ids -> text for the large-v3 vocabulary. assets/multilingual.tiktoken is OpenAI's
 * byte-level BPE table (MIT, github.com/openai/whisper) with "<base64 bytes> <id>" per line;
 * ids above it are special tokens and never part of the text.
 */
object WhisperTokenizer {
    /** <|xx|> tokens start here, in this order (generation_config.json of large-v3-turbo). */
    const val LANG_FIRST = 50259
    private val LANGS = ("en,zh,de,es,ru,ko,fr,ja,pt,tr,pl,ca,nl,ar,sv,it,id,hi,fi,vi,he,uk,el,ms,cs,ro,da,hu,ta,no," +
        "th,ur,hr,bg,lt,la,mi,ml,cy,sk,te,fa,lv,bn,sr,az,sl,kn,et,mk,br,eu,is,hy,ne,mn,bs,kk,sq,sw,gl,mr,pa,si,km,sn,yo," +
        "so,af,oc,ka,be,tg,sd,gu,am,yi,lo,uz,fo,ht,ps,tk,nn,mt,sa,lb,my,bo,tl,mg,as,tt,haw,ln,ha,ba,jw,su,yue").split(',')

    @Volatile private var vocab: Array<ByteArray>? = null

    /** -1 for "auto" or an unknown code: the NPU backend then detects the language. */
    fun langToken(code: String): Int = LANGS.indexOf(code).let { if (it < 0) -1 else LANG_FIRST + it }

    fun langCode(token: Int): String = LANGS.getOrNull(token - LANG_FIRST) ?: "?"

    fun decode(context: Context, ids: List<Int>): String {
        val v = vocab(context)
        val out = ByteArrayOutputStream()
        for (id in ids) if (id in v.indices) out.write(v[id])
        // Decoded once over all bytes, so a character split across tokens stays whole.
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun vocab(context: Context): Array<ByteArray> = vocab ?: synchronized(this) {
        vocab ?: run {
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
            Array((map.keys.maxOrNull() ?: -1) + 1) { map[it] ?: ByteArray(0) }.also { vocab = it }
        }
    }
}
