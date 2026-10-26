package com.jadbarakat.blooddonor.server.validation

/**
 * Free text other people see (names, notes, report details) must not carry invisible or
 * control characters: NUL, tabs, bidi overrides that reverse how a name reads, zero-width
 * spaces that make two names look the same, and so on.
 */
object TextRules {
    // Zero-width (non-)joiners are part of normal Arabic/Persian text and emoji sequences.
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** True if [text] has a Unicode control or format character; a line break counts only unless [allowNewlines]. */
    fun hasHiddenCharacters(text: String, allowNewlines: Boolean = false): Boolean =
        text.codePoints().anyMatch { cp ->
            when {
                cp == '\n'.code -> !allowNewlines
                cp == ZWNJ || cp == ZWJ -> false
                else -> Character.getType(cp).let { it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt() }
            }
        }

    /** [text] with every run of spaces collapsed to one. */
    fun collapseSpaces(text: String): String = text.replace(Regex(" {2,}"), " ")
}
