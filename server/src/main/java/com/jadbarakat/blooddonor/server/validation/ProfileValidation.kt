package com.jadbarakat.blooddonor.server.validation

import com.jadbarakat.blooddonor.server.api.ProfileRequest
import com.jadbarakat.blooddonor.server.api.badRequest
import com.jadbarakat.blooddonor.server.data.ProfileInput
import java.time.LocalDate
import java.time.format.DateTimeParseException

val BLOOD_TYPES = setOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
val SEXES = setOf("male", "female")

/** Older donations don't matter for eligibility; the bound also keeps absurd dates out of the database. */
const val MAX_DONATION_AGE_YEARS = 10L

/** Checks everything that doesn't need the database. Throws ApiException (400) on bad input. */
fun ProfileRequest.validate(today: LocalDate): ProfileInput {
    val name = TextRules.collapseSpaces(fullName.trim())
    if (name.length !in 2..60) {
        throw badRequest("invalid_full_name", "fullName must be 2 to 60 characters")
    }
    if (TextRules.hasHiddenCharacters(name)) {
        throw badRequest("invalid_full_name", "fullName must not contain control or invisible characters")
    }
    val normalizedPhone = PhoneNumbers.normalizeLebaneseMobile(phone)
        ?: throw badRequest("invalid_phone", "phone must be a Lebanese mobile number")
    if (bloodType != null && bloodType !in BLOOD_TYPES) {
        throw badRequest("invalid_blood_type", "bloodType must be one of ${BLOOD_TYPES.joinToString()}")
    }
    if (sex != null && sex !in SEXES) {
        throw badRequest("invalid_sex", "sex must be male or female")
    }
    val donationDate = lastDonationDate?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            throw badRequest("invalid_last_donation_date", "lastDonationDate must be a date like 2026-01-31")
        }
    }
    if (donationDate != null && donationDate.isAfter(today)) {
        throw badRequest("invalid_last_donation_date", "lastDonationDate cannot be in the future")
    }
    if (donationDate != null && donationDate.isBefore(today.minusYears(MAX_DONATION_AGE_YEARS))) {
        throw badRequest("invalid_last_donation_date", "lastDonationDate can be at most $MAX_DONATION_AGE_YEARS years ago")
    }
    return ProfileInput(
        fullName = name,
        phone = normalizedPhone,
        bloodType = bloodType,
        sex = sex,
        areaId = areaId,
        lastDonationDate = donationDate,
        isDonor = isDonor,
    )
}
