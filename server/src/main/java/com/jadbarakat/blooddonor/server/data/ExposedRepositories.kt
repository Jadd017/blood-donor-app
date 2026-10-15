package com.jadbarakat.blooddonor.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.postgresql.util.PSQLException
import java.sql.SQLException

// Table mappings. The schema itself is owned by the Flyway migrations.

private object AreasTable : Table("areas") {
    val id = integer("id")
    val name = text("name")
    val district = text("district")
    val governorate = text("governorate")
}

private object UsersTable : Table("users") {
    val firebaseUid = text("firebase_uid")
    val email = text("email")
    val fullName = text("full_name")
    val phone = text("phone")
    val bloodType = text("blood_type").nullable()
    val sex = text("sex").nullable()
    val areaId = integer("area_id").nullable()
    val lastDonationDate = date("last_donation_date").nullable()
    val isDonor = bool("is_donor")
    val updatedAt = timestampWithTimeZone("updated_at")
}

class ExposedAreaRepository(private val db: Database) : AreaRepository {

    override suspend fun all(): List<Area> = query(db) {
        AreasTable.selectAll()
            .orderBy(AreasTable.id)
            .map { Area(it[AreasTable.id], it[AreasTable.name], it[AreasTable.district], it[AreasTable.governorate]) }
    }

    override suspend fun exists(id: Int): Boolean = query(db) {
        AreasTable.select(AreasTable.id).where { AreasTable.id eq id }.any()
    }
}

class ExposedUserRepository(private val db: Database) : UserRepository {

    override suspend fun findByUid(firebaseUid: String): UserProfile? = query(db) {
        UsersTable.selectAll().where { UsersTable.firebaseUid eq firebaseUid }.singleOrNull()?.toProfile()
    }

    override suspend fun upsert(firebaseUid: String, email: String, input: ProfileInput): UserProfile = try {
        query(db) {
            val updated = UsersTable.update({ UsersTable.firebaseUid eq firebaseUid }) {
                it[UsersTable.email] = email
                it.setProfile(input)
                it[UsersTable.updatedAt] = CurrentTimestampWithTimeZone
            }
            if (updated == 0) {
                UsersTable.insert {
                    it[UsersTable.firebaseUid] = firebaseUid
                    it[UsersTable.email] = email
                    it.setProfile(input)
                }
            }
            UsersTable.selectAll().where { UsersTable.firebaseUid eq firebaseUid }.single().toProfile()
        }
    } catch (e: SQLException) {
        when (uniqueConstraint(e)) {
            "users_phone_key" -> throw phoneTaken()
            "users_email_key" -> throw emailTaken()
            else -> throw e
        }
    }

    private fun UpdateBuilder<*>.setProfile(input: ProfileInput) {
        this[UsersTable.fullName] = input.fullName
        this[UsersTable.phone] = input.phone
        this[UsersTable.bloodType] = input.bloodType
        this[UsersTable.sex] = input.sex
        this[UsersTable.areaId] = input.areaId
        this[UsersTable.lastDonationDate] = input.lastDonationDate
        this[UsersTable.isDonor] = input.isDonor
    }

    private fun ResultRow.toProfile() = UserProfile(
        firebaseUid = this[UsersTable.firebaseUid],
        email = this[UsersTable.email],
        fullName = this[UsersTable.fullName],
        phone = this[UsersTable.phone],
        bloodType = this[UsersTable.bloodType],
        sex = this[UsersTable.sex],
        areaId = this[UsersTable.areaId],
        lastDonationDate = this[UsersTable.lastDonationDate],
        isDonor = this[UsersTable.isDonor],
    )

    /** Name of the violated unique constraint, if [e] is a unique violation (SQLSTATE 23505). */
    private fun uniqueConstraint(e: Throwable): String? =
        generateSequence(e) { it.cause }
            .filterIsInstance<PSQLException>()
            .firstOrNull { it.sqlState == "23505" }
            ?.serverErrorMessage?.constraint
}

/** Runs a blocking JDBC transaction off the request thread. */
private suspend fun <T> query(db: Database, block: () -> T): T =
    withContext(Dispatchers.IO) { transaction(db) { block() } }
