package com.jadbarakat.blooddonor.server.validation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteRulesTest {

    @Test
    fun `phone numbers are detected in common formats`() {
        for (note in listOf(
            "call 03123456",
            "call 03 123 456",
            "call 70-123-456",
            "whatsapp +961 71 123456",
            "0096176123456",
            "رقمي ٠٣١٢٣٤٥٦", // Arabic-Indic digits
            "3.123.456",
        )) {
            assertTrue(NoteRules.containsPhoneNumber(note), note)
        }
    }

    @Test
    fun `ordinary numbers are allowed`() {
        for (note in listOf(
            "Room 412, 3rd floor",
            "Surgery at 14:30 on 16/06",
            "Patient is 45 years old, needs 2 units",
            "Hb 6.5",
        )) {
            assertFalse(NoteRules.containsPhoneNumber(note), note)
        }
    }

    @Test
    fun `links are detected`() {
        for (note in listOf(
            "see https://example.org/x",
            "http://bit.ly/abc",
            "www.hospital.lb",
            "gofundme.com/patient",
            "wa.me/96170123456",
            "t.me/somechannel",
            "example . com",
        )) {
            assertTrue(NoteRules.containsUrl(note), note)
        }
    }

    @Test
    fun `ordinary text is not a link`() {
        for (note in listOf(
            "Ask for Dr. Haddad at the ICU",
            "Patient in room 4. Come to the blood bank.",
            "Mon.-Fri. only",
            "e.g. O- or O+",
        )) {
            assertFalse(NoteRules.containsUrl(note), note)
        }
    }
}
