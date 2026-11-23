package cz.vyvrnc.transcribronpu.calls

import android.telephony.PhoneNumberUtils
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a call recorder puts into the file name. Built in: ShizuCallRecorder,
 * `20261123_113759.374+0100_in_+420600000000.ogg`. A custom pattern is a regular expression with
 * named groups: `number` (required), and optionally `dir` (in/out), `date` (yyyyMMdd),
 * `time` (HHmmss) and `offset` (+0100).
 */
object CallName {
    enum class Direction(val code: String) { IN("in"), OUT("out") }

    data class Info(val number: String, val direction: Direction?, val started: OffsetDateTime?)

    const val SHIZU_CALL_RECORDER =
        """^(?<date>\d{8})_(?<time>\d{6})(?:\.\d{1,3})?(?<offset>[+-]\d{4})?_(?<dir>in|out)_(?<number>\+?\d+)\.\w+$"""

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)

    /** Null when the name does not match or has no number (withheld numbers). */
    fun parse(fileName: String, customPattern: String?): Info? {
        val regex = runCatching { Regex(customPattern ?: SHIZU_CALL_RECORDER) }.getOrNull() ?: return null
        val m = regex.find(fileName) ?: return null
        fun group(name: String) = runCatching { m.groups[name]?.value }.getOrNull()
        val number = group("number")?.takeIf { it.any(Char::isDigit) } ?: return null
        val direction = when (group("dir")?.lowercase(Locale.ROOT)) {
            "in", "incoming", "prichozi", "příchozí" -> Direction.IN
            "out", "outgoing", "odchozi", "odchozí" -> Direction.OUT
            else -> null
        }
        val started = runCatching {
            val local = LocalDateTime.parse(group("date")!! + group("time")!!, DATE_TIME)
            val offset = group("offset")?.let { ZoneOffset.of(it.substring(0, 3) + ":" + it.substring(3)) }
                ?: ZoneId.systemDefault().rules.getOffset(local)
            local.atOffset(offset)
        }.getOrNull()
        return Info(number, direction, started)
    }

    /** True when [pattern] compiles and has a `number` group. */
    fun valid(pattern: String): Boolean =
        runCatching { Regex(pattern); pattern.contains("(?<number>") }.getOrDefault(false)
}

/** Comparing phone numbers written differently (+420 602…, 00420602…, 602…). */
object PhoneNumbers {
    /** International form where it can be worked out, otherwise digits (and a leading +). */
    fun normalize(raw: String, region: String = Locale.getDefault().country.ifEmpty { "CZ" }): String {
        val e164 = runCatching { PhoneNumberUtils.formatNumberToE164(raw, region) }.getOrNull()
        if (!e164.isNullOrEmpty()) return e164
        val digits = raw.filter { it.isDigit() || it == '+' }
        return if (digits.startsWith("00")) "+" + digits.drop(2) else digits
    }

    /** The same subscriber: equal international forms, or the same last nine digits. */
    fun same(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x == y) return true
        val dx = x.filter(Char::isDigit)
        val dy = y.filter(Char::isDigit)
        return dx.length >= 9 && dy.length >= 9 && dx.takeLast(9) == dy.takeLast(9)
    }
}
