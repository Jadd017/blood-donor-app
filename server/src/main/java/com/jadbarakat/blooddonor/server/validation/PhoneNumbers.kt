package com.jadbarakat.blooddonor.server.validation

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType

object PhoneNumbers {
    private val util = PhoneNumberUtil.getInstance()

    /**
     * Parses [raw] as a Lebanese mobile number ("03 123456", "70 123 456",
     * "+961 71 123456", "0096176123456", ...) and returns it in E.164 form
     * (+961...), or null if it is invalid, foreign or not a mobile number.
     */
    fun normalizeLebaneseMobile(raw: String): String? {
        val number = try {
            util.parse(raw, "LB")
        } catch (e: NumberParseException) {
            return null
        }
        if (!util.isValidNumberForRegion(number, "LB")) return null
        if (util.getNumberType(number) != PhoneNumberType.MOBILE) return null
        return util.format(number, PhoneNumberFormat.E164)
    }
}
