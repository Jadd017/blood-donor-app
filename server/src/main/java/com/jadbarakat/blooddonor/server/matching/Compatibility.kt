package com.jadbarakat.blooddonor.server.matching

import com.jadbarakat.blooddonor.server.validation.BLOOD_TYPES

/**
 * Which donor blood types can give a component to a patient.
 *
 * - Whole blood / red cells: red-cell compatibility. The donor's ABO antigens must all be
 *   present in the patient (O gives to everyone, AB receives from everyone), and an
 *   Rh-negative patient only receives Rh-negative blood.
 * - Plasma: the reverse ABO rule (AB plasma gives to everyone, O plasma only to O); Rh is ignored.
 * - Platelets: plasma-compatible ABO, and an Rh-negative patient only receives Rh-negative platelets.
 */
object Compatibility {

    fun canDonate(donor: String, patient: String, component: String): Boolean {
        val d = BloodType.parse(donor)
        val p = BloodType.parse(patient)
        return when (component) {
            WHOLE_BLOOD, RED_CELLS -> d.antigens.all { it in p.antigens } && (d.rhNegative || !p.rhNegative)
            PLASMA -> plasmaAboCompatible(d, p)
            PLATELETS -> plasmaAboCompatible(d, p) && (d.rhNegative || !p.rhNegative)
            else -> throw IllegalArgumentException("Unknown component: $component")
        }
    }

    /** Every donor type that can give [component] to a [patient] of this type. */
    fun donorTypesFor(patient: String, component: String): Set<String> =
        BLOOD_TYPES.filter { canDonate(it, patient, component) }.toSet()

    // Donor plasma carries antibodies against the antigens the donor lacks; the patient
    // must have none of those antigens.
    private fun plasmaAboCompatible(donor: BloodType, patient: BloodType): Boolean =
        patient.antigens.all { it in donor.antigens }

    private data class BloodType(val antigens: Set<Char>, val rhNegative: Boolean) {
        companion object {
            fun parse(type: String): BloodType {
                require(type in BLOOD_TYPES) { "Unknown blood type: $type" }
                val abo = type.dropLast(1)
                val antigens = if (abo == "O") emptySet() else abo.toSet()
                return BloodType(antigens, rhNegative = type.endsWith('-'))
            }
        }
    }

    const val WHOLE_BLOOD = "whole_blood"
    const val RED_CELLS = "red_cells"
    const val PLATELETS = "platelets"
    const val PLASMA = "plasma"
}
