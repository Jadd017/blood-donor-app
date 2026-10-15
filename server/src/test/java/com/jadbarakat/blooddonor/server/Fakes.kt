package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.TokenVerifier
import com.jadbarakat.blooddonor.server.auth.VerifiedToken
import com.jadbarakat.blooddonor.server.data.Area
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.ProfileInput
import com.jadbarakat.blooddonor.server.data.UserProfile
import com.jadbarakat.blooddonor.server.data.UserRepository
import com.jadbarakat.blooddonor.server.data.emailTaken
import com.jadbarakat.blooddonor.server.data.phoneTaken

/** Accepts any token registered in [tokens]; everything else is invalid. */
class FakeTokenVerifier(private val tokens: Map<String, VerifiedToken>) : TokenVerifier {
    override suspend fun verify(idToken: String): VerifiedToken? = tokens[idToken]
}

class FakeAreaRepository(private val areas: List<Area>) : AreaRepository {
    override suspend fun all() = areas
    override suspend fun exists(id: Int) = areas.any { it.id == id }
}

/** Mirrors the unique constraints of the real users table. */
class InMemoryUserRepository : UserRepository {
    val rows = mutableMapOf<String, UserProfile>()

    override suspend fun findByUid(firebaseUid: String) = rows[firebaseUid]

    override suspend fun upsert(firebaseUid: String, email: String, input: ProfileInput): UserProfile {
        val others = rows.values.filter { it.firebaseUid != firebaseUid }
        if (others.any { it.phone == input.phone }) throw phoneTaken()
        if (others.any { it.email == email }) throw emailTaken()
        val profile = UserProfile(
            firebaseUid = firebaseUid,
            email = email,
            fullName = input.fullName,
            phone = input.phone,
            bloodType = input.bloodType,
            sex = input.sex,
            areaId = input.areaId,
            lastDonationDate = input.lastDonationDate,
            isDonor = input.isDonor,
        )
        rows[firebaseUid] = profile
        return profile
    }
}
