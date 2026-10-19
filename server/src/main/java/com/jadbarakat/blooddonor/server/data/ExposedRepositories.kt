package com.jadbarakat.blooddonor.server.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
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
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Table mappings. The schema itself is owned by the Flyway migrations.

private object AreasTable : Table("areas") {
    val id = integer("id")
    val name = text("name")
    val district = text("district")
    val governorate = text("governorate")
}

private object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
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

private object HospitalsTable : Table("hospitals") {
    val id = integer("id")
    val name = text("name")
    val areaId = integer("area_id")
    val isActive = bool("is_active")
}

private object BloodRequestsTable : Table("blood_requests") {
    val id = long("id").autoIncrement()
    val requesterId = long("requester_id")
    val hospitalId = integer("hospital_id")
    val bloodType = text("blood_type")
    val component = text("component")
    val unitsNeeded = integer("units_needed")
    val neededBy = timestampWithTimeZone("needed_by")
    val note = text("note").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val closedAt = timestampWithTimeZone("closed_at").nullable()
    val wavesSent = short("waves_sent")
}

private object RequestNotificationsTable : Table("request_notifications") {
    val requestId = long("request_id")
    val userId = long("user_id")
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

    override suspend fun findById(id: Long): UserProfile? = query(db) {
        UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toProfile()
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
        id = this[UsersTable.id],
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

class ExposedHospitalRepository(private val db: Database) : HospitalRepository {

    override suspend fun all(areaId: Int?): List<Hospital> = query(db) {
        HospitalsTable.selectAll()
            .where {
                if (areaId == null) HospitalsTable.isActive eq true
                else (HospitalsTable.isActive eq true) and (HospitalsTable.areaId eq areaId)
            }
            .orderBy(HospitalsTable.name)
            .map { it.toHospital() }
    }

    override suspend fun findActive(id: Int): Hospital? = query(db) {
        HospitalsTable.selectAll()
            .where { (HospitalsTable.id eq id) and (HospitalsTable.isActive eq true) }
            .singleOrNull()
            ?.toHospital()
    }

    override suspend fun location(id: Int): GeoPoint? = jdbc(db) { conn ->
        conn.prepareStatement("SELECT ST_Y(location::geometry), ST_X(location::geometry) FROM hospitals WHERE id = ?").use { st ->
            st.setInt(1, id)
            st.executeQuery().use { rs -> if (rs.next()) GeoPoint(rs.getDouble(1), rs.getDouble(2)) else null }
        }
    }

    private fun ResultRow.toHospital() =
        Hospital(this[HospitalsTable.id], this[HospitalsTable.name], this[HospitalsTable.areaId])
}

class ExposedBloodRequestRepository(private val db: Database) : BloodRequestRepository {

    override suspend fun create(requesterId: Long, input: NewBloodRequest, now: Instant): BloodRequest = query(db) {
        val id = BloodRequestsTable.insert {
            it[BloodRequestsTable.requesterId] = requesterId
            it[hospitalId] = input.hospitalId
            it[bloodType] = input.bloodType
            it[component] = input.component
            it[unitsNeeded] = input.unitsNeeded
            it[neededBy] = now.plusSeconds(input.neededInHours * 3600L).toOffset()
            it[note] = input.note
            it[status] = RequestStatus.OPEN
            it[createdAt] = now.toOffset()
        }[BloodRequestsTable.id]
        selectById(id)!!
    }

    override suspend fun findById(id: Long): BloodRequest? = query(db) { selectById(id) }

    override suspend fun listByRequester(requesterId: Long): List<BloodRequest> = query(db) {
        requestsWithHospital.selectAll()
            .where { BloodRequestsTable.requesterId eq requesterId }
            .orderBy(BloodRequestsTable.createdAt to SortOrder.DESC, BloodRequestsTable.id to SortOrder.DESC)
            .map { it.toRequest() }
    }

    override suspend fun close(id: Long, status: String, now: Instant): BloodRequest? = query(db) {
        val updated = BloodRequestsTable.update({
            (BloodRequestsTable.id eq id) and
                (BloodRequestsTable.status eq RequestStatus.OPEN) and
                (BloodRequestsTable.neededBy greater now.toOffset())
        }) {
            it[BloodRequestsTable.status] = status
            it[closedAt] = now.toOffset()
        }
        if (updated == 0) null else selectById(id)
    }

    override suspend fun expireOverdue(now: Instant): List<Long> = jdbc(db) { conn ->
        // closed_at = needed_by: the moment it actually expired, not when the job noticed.
        conn.prepareStatement(
            """
            UPDATE blood_requests SET status = 'expired', closed_at = needed_by
            WHERE status = 'open' AND needed_by <= ?
            RETURNING id
            """.trimIndent()
        ).use { st ->
            st.setObject(1, now.toOffset())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    private fun selectById(id: Long): BloodRequest? =
        requestsWithHospital.selectAll().where { BloodRequestsTable.id eq id }.singleOrNull()?.toRequest()

    override suspend fun isNotified(requestId: Long, userId: Long): Boolean = query(db) {
        RequestNotificationsTable.select(RequestNotificationsTable.requestId)
            .where { (RequestNotificationsTable.requestId eq requestId) and (RequestNotificationsTable.userId eq userId) }
            .any()
    }

    override suspend fun listNotifiedTo(userId: Long): List<BloodRequest> = query(db) {
        requestsWithHospital
            .join(RequestNotificationsTable, JoinType.INNER, BloodRequestsTable.id, RequestNotificationsTable.requestId)
            .selectAll()
            .where { RequestNotificationsTable.userId eq userId }
            .orderBy(BloodRequestsTable.createdAt to SortOrder.DESC, BloodRequestsTable.id to SortOrder.DESC)
            .map { it.toRequest() }
    }
}

/** blood_requests with their hospital and the hospital's area. */
private val requestsWithHospital
    get() = BloodRequestsTable
        .join(HospitalsTable, JoinType.INNER, BloodRequestsTable.hospitalId, HospitalsTable.id)
        .join(AreasTable, JoinType.INNER, HospitalsTable.areaId, AreasTable.id)

private fun ResultRow.toRequest() = BloodRequest(
    id = this[BloodRequestsTable.id],
    requesterId = this[BloodRequestsTable.requesterId],
    hospitalId = this[BloodRequestsTable.hospitalId],
    hospitalName = this[HospitalsTable.name],
    areaName = this[AreasTable.name],
    bloodType = this[BloodRequestsTable.bloodType],
    component = this[BloodRequestsTable.component],
    unitsNeeded = this[BloodRequestsTable.unitsNeeded],
    neededBy = this[BloodRequestsTable.neededBy].toInstant(),
    note = this[BloodRequestsTable.note],
    status = this[BloodRequestsTable.status],
    createdAt = this[BloodRequestsTable.createdAt].toInstant(),
    closedAt = this[BloodRequestsTable.closedAt]?.toInstant(),
    wavesSent = this[BloodRequestsTable.wavesSent].toInt(),
)


internal fun Instant.toOffset(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/** Open requests, not past needed_by at [now], with fewer than [totalWaves] waves sent; oldest first. */
internal suspend fun openRequestsAwaitingWaves(db: Database, now: Instant, totalWaves: Int): List<BloodRequest> = query(db) {
    requestsWithHospital.selectAll()
        .where {
            (BloodRequestsTable.status eq RequestStatus.OPEN) and
                (BloodRequestsTable.neededBy greater now.toOffset()) and
                (BloodRequestsTable.wavesSent less totalWaves.toShort())
        }
        .orderBy(BloodRequestsTable.createdAt)
        .map { it.toRequest() }
}

/** Runs a blocking JDBC transaction off the request thread. */
private suspend fun <T> query(db: Database, block: () -> T): T =
    withContext(Dispatchers.IO) { transaction(db) { block() } }

/** Like [query], with the transaction's plain JDBC connection. */
internal suspend fun <T> jdbc(db: Database, block: (Connection) -> T): T =
    withContext(Dispatchers.IO) { transaction(db) { block(connection.connection as Connection) } }
