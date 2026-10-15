package com.jadbarakat.blooddonor.server.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneNumbersTest {

    private fun normalize(raw: String) = PhoneNumbers.normalizeLebaneseMobile(raw)

    @Test
    fun `old 03 numbers`() {
        assertEquals("+9613123456", normalize("03 123456"))
        assertEquals("+9613123456", normalize("03-123-456"))
    }

    @Test
    fun `local 7x and 81 numbers`() {
        assertEquals("+96170123456", normalize("70 123 456"))
        assertEquals("+96176123456", normalize("76123456"))
        assertEquals("+96181123456", normalize("81 123 456"))
    }

    @Test
    fun `international forms`() {
        assertEquals("+96171123456", normalize("+961 71 123456"))
        assertEquals("+96176123456", normalize("0096176123456"))
        assertEquals("+9613123456", normalize("+961 3 123 456"))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(normalize(""))
        assertNull(normalize("hello"))
        assertNull(normalize("70 12"))
        assertNull(normalize("70 123 456 789"))
    }

    @Test
    fun `landlines are rejected`() {
        assertNull(normalize("01 123456")) // Beirut landline
        assertNull(normalize("+961 4 123456")) // Matn landline
    }

    @Test
    fun `foreign mobiles are rejected`() {
        assertNull(normalize("+33 6 12 34 56 78"))
        assertNull(normalize("+1 650 253 0000"))
    }
}
