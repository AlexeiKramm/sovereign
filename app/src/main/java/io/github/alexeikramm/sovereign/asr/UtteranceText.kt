package io.github.alexeikramm.sovereign.asr

/**
 * The utterance's text, assembled from pause-delimited units (D-032).
 *
 * Pure, and therefore tested on this machine instead of on a phone: the rule is "units are joined by
 * a single space, blank units are skipped and every unit is trimmed". Getting it wrong is the
 * difference between "next week and also" and "next weekand also" — a defect that would otherwise
 * cost a device round trip to find, on a keyboard where the text is already at someone's cursor.
 */
class UtteranceText {

    private val builder = StringBuilder()

    val isEmpty: Boolean get() = builder.isEmpty()

    val length: Int get() = builder.length

    /** Appends one unit. Returns true when it contributed text (blank units are dropped, not joined). */
    fun append(unit: String): Boolean {
        val trimmed = unit.trim()
        if (trimmed.isEmpty()) return false
        if (builder.isNotEmpty()) builder.append(' ')
        builder.append(trimmed)
        return true
    }

    fun clear() = builder.clear()

    override fun toString(): String = builder.toString()
}
