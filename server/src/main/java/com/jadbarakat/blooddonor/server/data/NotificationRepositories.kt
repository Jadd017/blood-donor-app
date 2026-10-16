package com.jadbarakat.blooddonor.server.data

import com.jadbarakat.blooddonor.server.matching.DonorCandidate
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Types
import java.time.Instant
import java.time.LocalDate

// Plain SQL here: these queries need PostGIS functions and ON CONFLICT.

class ExposedDeviceTokenRepository(private val db: Database) : DeviceTokenRepository {

    override suspend fun upsert(userId: Long, token: String, now: Instant) {
        jdbc(db) { conn ->
            conn.prepareStatement(
                """
                INSERT INTO device_tokens (user_id, token, updated_at) VALUES (?, ?, ?)
                ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, updated_at = EXCLUDED.updated_at
                """.trimIndent()
            ).use { st ->
                st.setLong(1, userId)
                st.setString(2, token)
                st.setObject(3, now.toOffset())
                st.executeUpdate()
            }
        }
    }

    override suspend fun delete(userId: Long, token: String) {
        jdbc(db) { conn ->
            conn.prepareStatement("DELETE FROM device_tokens WHERE user_id = ? AND token = ?").use { st ->
                st.setLong(1, userId)
                st.setString(2, token)
                st.executeUpdate()
            }
        }
    }

    override suspend fun tokensFor(userIds: Collection<Long>): Map<Long, List<String>> {
        if (userIds.isEmpty()) return emptyMap()
        return jdbc(db) { conn ->
            conn.prepareStatement("SELECT user_id, token FROM device_tokens WHERE user_id = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("bigint", userIds.toTypedArray()))
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) }
                }
            }.groupBy({ it.first }, { it.second })
        }
    }

    override suspend fun deleteTokens(tokens: Collection<String>) {
        if (tokens.isEmpty()) return
        jdbc(db) { conn ->
            conn.prepareStatement("DELETE FROM device_tokens WHERE token = ANY (?)").use { st ->
                st.setArray(1, conn.createArrayOf("text", tokens.toTypedArray()))
                st.executeUpdate()
            }
        }
    }
}

class ExposedMatchingRepository(private val db: Database) : MatchingRepository {

    override suspend fun requestsAwaitingWaves(now: Instant, totalWaves: Int): List<BloodRequest> =
        openRequestsAwaitingWaves(db, now, totalWaves)

    override suspend fun claimWave(requestId: Long, fromWave: Int, toWave: Int, now: Instant): Boolean = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            UPDATE blood_requests SET waves_sent = ?
            WHERE id = ? AND waves_sent = ? AND status = 'open' AND needed_by > ?
            """.trimIndent()
        ).use { st ->
            st.setInt(1, toWave)
            st.setLong(2, requestId)
            st.setInt(3, fromWave)
            st.setObject(4, now.toOffset())
            st.executeUpdate() == 1
        }
    }

    override suspend fun candidates(
        request: BloodRequest,
        donorTypes: Set<String>,
        radiusMeters: Double?,
        since: Instant,
    ): List<DonorCandidate> = jdbc(db) { conn ->
        // Distance is from the donor's area centroid to the hospital. Donors without an area
        // are only reached by the nationwide wave (radius null).
        conn.prepareStatement(
            """
            SELECT u.id, u.is_donor, u.blood_type, u.sex, u.last_donation_date,
                   ST_Distance(a.location, h.location) AS distance_m,
                   (SELECT count(*) FROM request_notifications n
                    WHERE n.user_id = u.id AND n.sent_at >= ?) AS recent
            FROM users u
            JOIN hospitals h ON h.id = ?
            LEFT JOIN areas a ON a.id = u.area_id
            WHERE u.is_donor
              AND u.blood_type = ANY (?)
              AND u.id <> ?
              AND EXISTS (SELECT 1 FROM device_tokens t WHERE t.user_id = u.id)
              AND NOT EXISTS (SELECT 1 FROM request_notifications n WHERE n.request_id = ? AND n.user_id = u.id)
              AND (CAST(? AS double precision) IS NULL OR ST_DWithin(a.location, h.location, ?))
            """.trimIndent()
        ).use { st ->
            st.setObject(1, since.toOffset())
            st.setInt(2, request.hospitalId)
            st.setArray(3, conn.createArrayOf("text", donorTypes.toTypedArray()))
            st.setLong(4, request.requesterId)
            st.setLong(5, request.id)
            st.setObject(6, radiusMeters, Types.DOUBLE)
            st.setObject(7, radiusMeters, Types.DOUBLE)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val distance = rs.getDouble("distance_m").takeUnless { rs.wasNull() }
                        add(
                            DonorCandidate(
                                userId = rs.getLong("id"),
                                isDonor = rs.getBoolean("is_donor"),
                                bloodType = rs.getString("blood_type"),
                                sex = rs.getString("sex"),
                                lastDonationDate = rs.getObject("last_donation_date", LocalDate::class.java),
                                hasDeviceToken = true, // guaranteed by the EXISTS above
                                distanceMeters = distance ?: Double.MAX_VALUE,
                                notificationsLast24h = rs.getInt("recent"),
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun recordNotifications(requestId: Long, userIds: Collection<Long>, wave: Int, now: Instant) {
        if (userIds.isEmpty()) return
        jdbc(db) { conn ->
            conn.prepareStatement(
                """
                INSERT INTO request_notifications (request_id, user_id, wave, sent_at) VALUES (?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT request_notifications_once DO NOTHING
                """.trimIndent()
            ).use { st ->
                for (userId in userIds) {
                    st.setLong(1, requestId)
                    st.setLong(2, userId)
                    st.setInt(3, wave)
                    st.setObject(4, now.toOffset())
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }
}
