package com.jadbarakat.blooddonor.server.matching

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every patient x donor combination for every component, written out by hand from the
 * transfusion rules (rows: patient, columns: donor, x = compatible).
 */
class CompatibilityTest {

    private val types = listOf("O-", "O+", "A-", "A+", "B-", "B+", "AB-", "AB+")

    //                       donor: O- O+ A- A+ B- B+ AB- AB+
    private val redCells = mapOf(
        "O-" to "x . . . . . . .",
        "O+" to "x x . . . . . .",
        "A-" to "x . x . . . . .",
        "A+" to "x x x x . . . .",
        "B-" to "x . . . x . . .",
        "B+" to "x x . . x x . .",
        "AB-" to "x . x . x . x .",
        "AB+" to "x x x x x x x x",
    )

    // Reverse ABO, Rh ignored: AB plasma gives to everyone, O plasma only to O.
    private val plasma = mapOf(
        "O-" to "x x x x x x x x",
        "O+" to "x x x x x x x x",
        "A-" to ". . x x . . x x",
        "A+" to ". . x x . . x x",
        "B-" to ". . . . x x x x",
        "B+" to ". . . . x x x x",
        "AB-" to ". . . . . . x x",
        "AB+" to ". . . . . . x x",
    )

    // Plasma ABO, and Rh-negative patients get only Rh-negative platelets.
    private val platelets = mapOf(
        "O-" to "x . x . x . x .",
        "O+" to "x x x x x x x x",
        "A-" to ". . x . . . x .",
        "A+" to ". . x x . . x x",
        "B-" to ". . . . x . x .",
        "B+" to ". . . . x x x x",
        "AB-" to ". . . . . . x .",
        "AB+" to ". . . . . . x x",
    )

    private fun checkAll(component: String, table: Map<String, String>) {
        var checked = 0
        for (patient in types) {
            val row = table.getValue(patient).split(" ")
            for ((i, donor) in types.withIndex()) {
                assertEquals(row[i] == "x", Compatibility.canDonate(donor, patient, component), "$component: $donor -> $patient")
                checked++
            }
        }
        assertEquals(64, checked)
    }

    @Test
    fun `red cells follow red-cell compatibility`() = checkAll("red_cells", redCells)

    @Test
    fun `whole blood follows red-cell compatibility`() = checkAll("whole_blood", redCells)

    @Test
    fun `plasma follows reversed ABO and ignores Rh`() = checkAll("plasma", plasma)

    @Test
    fun `platelets follow plasma ABO and protect Rh-negative patients`() = checkAll("platelets", platelets)

    @Test
    fun `donor types for a patient`() {
        assertEquals(setOf("O-"), Compatibility.donorTypesFor("O-", "red_cells"))
        assertEquals(setOf("AB-", "AB+"), Compatibility.donorTypesFor("A+", "plasma").filter { it.startsWith("AB") }.toSet())
        assertEquals(types.toSet(), Compatibility.donorTypesFor("AB+", "whole_blood"))
        assertEquals(setOf("AB-"), Compatibility.donorTypesFor("AB-", "platelets"))
    }

    @Test
    fun `unknown values are rejected`() {
        assertFailsWith<IllegalArgumentException> { Compatibility.canDonate("C+", "O+", "red_cells") }
        assertFailsWith<IllegalArgumentException> { Compatibility.canDonate("O+", "O+", "blood") }
    }
}
