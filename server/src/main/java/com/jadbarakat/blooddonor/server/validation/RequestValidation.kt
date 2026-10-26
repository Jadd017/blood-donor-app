package com.jadbarakat.blooddonor.server.validation

import com.jadbarakat.blooddonor.server.api.CreateBloodRequestBody
import com.jadbarakat.blooddonor.server.api.badRequest
import com.jadbarakat.blooddonor.server.data.NewBloodRequest

val COMPONENTS = setOf("whole_blood", "red_cells", "platelets", "plasma")
val UNITS_RANGE = 1..10
val NEEDED_IN_HOURS_RANGE = 6..72
const val MAX_NOTE_LENGTH = 200

/** Checks everything that doesn't need the database. Throws ApiException (400) on bad input. */
fun CreateBloodRequestBody.validate(): NewBloodRequest {
    if (bloodType !in BLOOD_TYPES) {
        throw badRequest("invalid_blood_type", "bloodType must be one of ${BLOOD_TYPES.joinToString()}")
    }
    if (component !in COMPONENTS) {
        throw badRequest("invalid_component", "component must be one of ${COMPONENTS.joinToString()}")
    }
    if (unitsNeeded !in UNITS_RANGE) {
        throw badRequest("invalid_units", "unitsNeeded must be 1 to 10")
    }
    if (neededInHours !in NEEDED_IN_HOURS_RANGE) {
        throw badRequest("invalid_needed_by", "neededInHours must be 6 to 72")
    }
    val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanNote != null) {
        if (cleanNote.length > MAX_NOTE_LENGTH) {
            throw badRequest("invalid_note", "note must be at most $MAX_NOTE_LENGTH characters")
        }
        if (TextRules.hasHiddenCharacters(cleanNote, allowNewlines = true)) {
            throw badRequest("invalid_note", "note must not contain control or invisible characters")
        }
        if (NoteRules.containsPhoneNumber(cleanNote)) {
            throw badRequest("note_contains_phone", "note must not contain phone numbers")
        }
        if (NoteRules.containsUrl(cleanNote)) {
            throw badRequest("note_contains_url", "note must not contain links")
        }
    }
    return NewBloodRequest(hospitalId, bloodType, component, unitsNeeded, neededInHours, cleanNote)
}

/**
 * Notes are shown to strangers, so contact details stay out of them: donors reach
 * the requester through the app, not through a number pasted in a note.
 */
object NoteRules {
    // 7+ digits (any script, e.g. Arabic-Indic too), optionally separated by spaces, dashes, dots, slashes or brackets.
    private val phone = Regex("""(?:\p{Nd}[\s\-./()]*){6}\p{Nd}""")
    private val url = Regex(
        """(?i)(https?://|www\.|\b[\p{L}\p{N}-]+\s*\.\s*(com|net|org|lb|io|me|co|info|biz|app|link|ly|gl|gg|to|xyz|site|online)\b)""",
    )

    fun containsPhoneNumber(text: String): Boolean = phone.containsMatchIn(text)

    fun containsUrl(text: String): Boolean = url.containsMatchIn(text)
}
