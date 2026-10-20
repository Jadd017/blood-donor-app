package com.jadbarakat.blooddonor.server.data

import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime

// Plain SQL: these need row locks, ON CONFLICT and multi-table updates in one transaction.

class ExposedSafetyRepository(private val db: Database) : SafetyRepository {

    override suspend fun strikeTimes(userId: Long): List<Instant> = jdbc(db) { conn ->
        conn.prepareStatement("SELECT created_at FROM strikes WHERE user_id = ? ORDER BY created_at").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getObject(1, OffsetDateTime::class.java).toInstant()) }
            }
        }
    }

    override suspend fun report(
        requestId: Long,
        reporterId: Long,
        reason: String,
        details: String?,
        now: Instant,
        reportsToHide: Int,
    ): ReportResult = jdbc(db) { conn ->
        // Lock the request so the report that crosses the threshold is counted exactly once.
        val (requesterId, alreadyHidden) = conn.prepareStatement(
            "SELECT requester_id, hidden FROM blood_requests WHERE id = ? FOR UPDATE"
        ).use { st ->
            st.setLong(1, requestId)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1).takeUnless { rs.wasNull() } to rs.getBoolean(2)
            }
        }
        val inserted = conn.prepareStatement(
            """
            INSERT INTO reports (request_id, reporter_id, reason, details, created_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT reports_once DO NOTHING
            """.trimIndent()
        ).use { st ->
            st.setLong(1, requestId)
            st.setLong(2, reporterId)
            st.setString(3, reason)
            st.setString(4, details)
            st.setObject(5, now.toOffset())
            st.executeUpdate() == 1
        }
        if (!inserted) return@jdbc ReportResult.AlreadyReported
        if (alreadyHidden) return@jdbc ReportResult.Reported(hidden = false)

        val reporters = conn.prepareStatement("SELECT count(DISTINCT reporter_id) FROM reports WHERE request_id = ?").use { st ->
            st.setLong(1, requestId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (reporters < reportsToHide) return@jdbc ReportResult.Reported(hidden = false)

        conn.prepareStatement(
            """
            UPDATE blood_requests
            SET hidden = true,
                status = CASE WHEN status = 'open' THEN 'cancelled' ELSE status END,
                closed_at = COALESCE(closed_at, ?)
            WHERE id = ?
            """.trimIndent()
        ).use { st ->
            st.setObject(1, now.toOffset())
            st.setLong(2, requestId)
            st.executeUpdate()
        }
        if (requesterId != null) {
            conn.prepareStatement(
                "INSERT INTO strikes (user_id, reason, request_id, created_at) VALUES (?, 'request_hidden', ?, ?) ON CONFLICT DO NOTHING"
            ).use { st ->
                st.setLong(1, requesterId)
                st.setLong(2, requestId)
                st.setObject(3, now.toOffset())
                st.executeUpdate()
            }
        }
        ReportResult.Reported(hidden = true)
    }

    // count(DISTINCT requester_id) is what keeps a single requester from triggering the pause alone.
    override suspend fun pauseIfNoShows(
        donorId: Long,
        since: Instant,
        minRequesters: Int,
        now: Instant,
        pauseUntil: Instant,
    ): Instant? = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            UPDATE users SET notifications_paused_until = ?, updated_at = ?
            WHERE id = ?
              AND (notifications_paused_until IS NULL OR notifications_paused_until <= ?)
              AND (SELECT count(DISTINCT r.requester_id)
                   FROM pledges p JOIN blood_requests r ON r.id = p.request_id
                   WHERE p.donor_id = ? AND p.requester_outcome = 'no_show' AND p.requester_confirmed_at >= ?) >= ?
            """.trimIndent()
        ).use { st ->
            st.setObject(1, pauseUntil.toOffset())
            st.setObject(2, now.toOffset())
            st.setLong(3, donorId)
            st.setObject(4, now.toOffset())
            st.setLong(5, donorId)
            st.setObject(6, since.toOffset())
            st.setInt(7, minRequesters)
            if (st.executeUpdate() == 1) pauseUntil else null
        }
    }

    override suspend fun block(blockerId: Long, blockedId: Long, viaRequestId: Long?, now: Instant) {
        jdbc(db) { conn ->
            conn.prepareStatement(
                """
                INSERT INTO blocks (blocker_id, blocked_id, via_request_id, created_at) VALUES (?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT blocks_once DO NOTHING
                """.trimIndent()
            ).use { st ->
                st.setLong(1, blockerId)
                st.setLong(2, blockedId)
                st.setObject(3, viaRequestId, java.sql.Types.BIGINT)
                st.setObject(4, now.toOffset())
                st.executeUpdate()
            }
        }
    }

    override suspend fun unblock(blockerId: Long, blockedId: Long) {
        jdbc(db) { conn ->
            conn.prepareStatement("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?").use { st ->
                st.setLong(1, blockerId)
                st.setLong(2, blockedId)
                st.executeUpdate()
            }
        }
    }

    override suspend fun blocks(blockerId: Long): List<BlockEntry> = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            SELECT b.blocked_id,
                   CASE WHEN b.via_request_id IS NULL THEN u.full_name END AS full_name,
                   h.name AS hospital_name, r.created_at AS request_created_at, b.created_at
            FROM blocks b
            JOIN users u ON u.id = b.blocked_id
            LEFT JOIN blood_requests r ON r.id = b.via_request_id
            LEFT JOIN hospitals h ON h.id = r.hospital_id
            WHERE b.blocker_id = ?
            ORDER BY b.created_at DESC, b.blocked_id
            """.trimIndent()
        ).use { st ->
            st.setLong(1, blockerId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            BlockEntry(
                                blockedId = rs.getLong("blocked_id"),
                                fullName = rs.getString("full_name"),
                                requestHospitalName = rs.getString("hospital_name"),
                                requestCreatedAt = rs.getObject("request_created_at", OffsetDateTime::class.java)?.toInstant(),
                                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun blockedEitherWay(userId: Long): Set<Long> = jdbc(db) { conn ->
        conn.prepareStatement(
            "SELECT blocked_id FROM blocks WHERE blocker_id = ? UNION SELECT blocker_id FROM blocks WHERE blocked_id = ?"
        ).use { st ->
            st.setLong(1, userId)
            st.setLong(2, userId)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    override suspend fun helpedRequester(donorId: Long, requesterId: Long): Boolean = jdbc(db) { conn ->
        conn.prepareStatement(
            "SELECT 1 FROM pledges p JOIN blood_requests r ON r.id = p.request_id WHERE p.donor_id = ? AND r.requester_id = ? LIMIT 1"
        ).use { st ->
            st.setLong(1, donorId)
            st.setLong(2, requesterId)
            st.executeQuery().use { it.next() }
        }
    }

    override suspend fun deleteAccount(userId: Long, now: Instant): AccountDeletion? = jdbc(db) { conn ->
        val exists = conn.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR UPDATE").use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { it.next() }
        }
        if (!exists) return@jdbc null

        val cancelled = conn.ids(
            "UPDATE blood_requests SET status = 'cancelled', closed_at = ? WHERE requester_id = ? AND status = 'open' RETURNING id",
            now, userId,
        )
        val withdrawn = conn.ids(
            """
            UPDATE pledges SET status = 'withdrawn', updated_at = ?
            WHERE donor_id = ? AND status = 'committed' AND requester_outcome IS NULL
            RETURNING request_id
            """.trimIndent(),
            now, userId,
        )
        for (sql in listOf(
            "DELETE FROM device_tokens WHERE user_id = ?",
            "DELETE FROM blocks WHERE blocker_id = ? OR blocked_id = ?",
            "DELETE FROM reports WHERE reporter_id = ?",
            // The note is free text and may contain the requester's name or phone.
            "UPDATE blood_requests SET note = NULL WHERE requester_id = ?",
            // Requests and pledges stay (ON DELETE SET NULL); notifications, strikes go with the row.
            "DELETE FROM users WHERE id = ?",
        )) {
            conn.prepareStatement(sql).use { st ->
                repeat(sql.count { it == '?' }) { st.setLong(it + 1, userId) }
                st.executeUpdate()
            }
        }
        AccountDeletion(cancelledRequestIds = cancelled, withdrawnRequestIds = withdrawn.distinct())
    }

    /** Runs an UPDATE ... RETURNING <one bigint> with parameters (now, userId). */
    private fun Connection.ids(sql: String, now: Instant, userId: Long): List<Long> =
        prepareStatement(sql).use { st ->
            st.setObject(1, now.toOffset())
            st.setLong(2, userId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
}
