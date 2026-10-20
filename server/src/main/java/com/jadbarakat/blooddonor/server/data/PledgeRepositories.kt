package com.jadbarakat.blooddonor.server.data

import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// Plain SQL: commit needs a row lock and ON CONFLICT.

class ExposedPledgeRepository(private val db: Database) : PledgeRepository {

    override suspend fun commit(requestId: Long, donorId: Long, now: Instant, maxCommitted: Int): CommitResult = jdbc(db) { conn ->
        // Lock the donor's row so two commits by the same donor run one after the other.
        conn.prepareStatement("SELECT 1 FROM users WHERE id = ? FOR UPDATE").use { st ->
            st.setLong(1, donorId)
            st.executeQuery().close()
        }
        val existing = selectPledge(conn, requestId, donorId)
        when (existing?.status) {
            PledgeStatus.COMMITTED -> return@jdbc CommitResult.Committed(existing, isNew = false)
            PledgeStatus.DONATED, PledgeStatus.NOT_DONATED -> return@jdbc CommitResult.AlreadyConfirmed
        }
        // Pledges on closed requests don't count: the donor may never confirm them.
        val active = conn.prepareStatement(
            """
            SELECT count(*) FROM pledges p JOIN blood_requests r ON r.id = p.request_id
            WHERE p.donor_id = ? AND p.status = 'committed' AND r.status = 'open' AND r.needed_by > ?
            """.trimIndent()
        ).use { st ->
            st.setLong(1, donorId)
            st.setObject(2, now.toOffset())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        if (active >= maxCommitted) return@jdbc CommitResult.LimitReached

        val pledge = conn.prepareStatement(
            """
            INSERT INTO pledges (request_id, donor_id, status, committed_at, created_at, updated_at)
            VALUES (?, ?, 'committed', ?, ?, ?)
            ON CONFLICT ON CONSTRAINT pledges_once
            DO UPDATE SET status = 'committed', committed_at = EXCLUDED.committed_at, updated_at = EXCLUDED.updated_at,
                reminder_sent_at = NULL
            RETURNING $PLEDGE_COLUMNS
            """.trimIndent()
        ).use { st ->
            st.setLong(1, requestId)
            st.setLong(2, donorId)
            repeat(3) { st.setObject(3 + it, now.toOffset()) }
            st.executeQuery().use { rs -> rs.next(); rs.toPledge() }
        }
        CommitResult.Committed(pledge, isNew = true)
    }

    override suspend fun withdraw(requestId: Long, donorId: Long, now: Instant): Boolean = jdbc(db) { conn ->
        conn.prepareStatement(
            "UPDATE pledges SET status = 'withdrawn', updated_at = ? " +
                "WHERE request_id = ? AND donor_id = ? AND status = 'committed' AND requester_outcome IS NULL"
        ).use { st ->
            st.setObject(1, now.toOffset())
            st.setLong(2, requestId)
            st.setLong(3, donorId)
            st.executeUpdate() == 1
        }
    }

    override suspend fun find(requestId: Long, donorId: Long): Pledge? = jdbc(db) { selectPledge(it, requestId, donorId) }

    override suspend fun helpers(requestId: Long): List<Helper> = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            SELECT p.id, p.donor_id, u.full_name, u.blood_type, u.phone, p.status, p.committed_at, p.requester_outcome
            FROM pledges p LEFT JOIN users u ON u.id = p.donor_id
            WHERE p.request_id = ? AND p.status <> 'withdrawn'
            ORDER BY p.committed_at, p.id
            """.trimIndent()
        ).use { st ->
            st.setLong(1, requestId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Helper(
                                pledgeId = rs.getLong("id"),
                                donorId = rs.getLong("donor_id"),
                                // A helper who deleted their account keeps their place, without name or phone.
                                fullName = rs.getString("full_name") ?: DELETED_HELPER_NAME,
                                bloodType = rs.getString("blood_type"),
                                phone = rs.getString("phone").orEmpty(),
                                status = rs.getString("status"),
                                committedAt = rs.getObject("committed_at", OffsetDateTime::class.java).toInstant(),
                                requesterOutcome = rs.getString("requester_outcome"),
                            )
                        )
                    }
                }
            }
        }
    }

    // Mirrors HelperRules.counts.
    override suspend fun helperCount(requestId: Long, now: Instant, arrivalWindow: Duration): Int = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            SELECT count(*) FROM pledges p JOIN blood_requests r ON r.id = p.request_id
            WHERE p.request_id = ?
              AND (p.requester_outcome = 'donated'
                   OR (p.requester_outcome IS NULL AND p.status IN ('committed', 'donated')
                       AND ? < LEAST(p.committed_at + make_interval(secs => CAST(? AS double precision)), r.needed_by)))
            """.trimIndent()
        ).use { st ->
            st.setLong(1, requestId)
            st.setObject(2, now.toOffset())
            st.setLong(3, arrivalWindow.seconds)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    override suspend fun committedDonorIds(requestId: Long): List<Long> = jdbc(db) { conn ->
        conn.prepareStatement("SELECT donor_id FROM pledges WHERE request_id = ? AND status = 'committed'").use { st ->
            st.setLong(1, requestId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    override suspend fun committedRequestIds(donorId: Long): Set<Long> = jdbc(db) { conn ->
        conn.prepareStatement("SELECT request_id FROM pledges WHERE donor_id = ? AND status = 'committed'").use { st ->
            st.setLong(1, donorId)
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } }
        }
    }

    override suspend fun confirmDonation(
        requestId: Long,
        donorId: Long,
        donated: Boolean,
        today: LocalDate,
        now: Instant,
    ): Pledge? = jdbc(db) { conn ->
        val pledge = conn.prepareStatement(
            """
            UPDATE pledges SET status = ?, updated_at = ?
            WHERE request_id = ? AND donor_id = ? AND status = 'committed'
            RETURNING $PLEDGE_COLUMNS
            """.trimIndent()
        ).use { st ->
            st.setString(1, if (donated) PledgeStatus.DONATED else PledgeStatus.NOT_DONATED)
            st.setObject(2, now.toOffset())
            st.setLong(3, requestId)
            st.setLong(4, donorId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toPledge() else null }
        } ?: return@jdbc null
        if (donated) updateLastDonation(conn, donorId, today, now)
        pledge
    }

    override suspend fun confirmHelper(
        requestId: Long,
        pledgeId: Long,
        outcome: String,
        today: LocalDate,
        now: Instant,
    ): HelperConfirmResult = jdbc(db) { conn ->
        val donated = outcome == RequesterOutcome.DONATED
        // Conditional update: of two parallel confirmations only one wins.
        val pledge = conn.prepareStatement(
            """
            UPDATE pledges SET requester_outcome = ?, requester_confirmed_at = ?, confirmed_donation = ?,
                status = CASE WHEN ? AND status = 'committed' THEN 'donated' ELSE status END,
                updated_at = ?
            WHERE id = ? AND request_id = ? AND requester_outcome IS NULL AND status <> 'withdrawn'
            RETURNING $PLEDGE_COLUMNS
            """.trimIndent()
        ).use { st ->
            st.setString(1, outcome)
            st.setObject(2, now.toOffset())
            st.setBoolean(3, donated)
            st.setBoolean(4, donated)
            st.setObject(5, now.toOffset())
            st.setLong(6, pledgeId)
            st.setLong(7, requestId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toPledge() else null }
        }
        if (pledge == null) {
            val existing = conn.prepareStatement("SELECT $PLEDGE_COLUMNS FROM pledges WHERE id = ? AND request_id = ?").use { st ->
                st.setLong(1, pledgeId)
                st.setLong(2, requestId)
                st.executeQuery().use { rs -> if (rs.next()) rs.toPledge() else null }
            }
            return@jdbc when {
                existing == null -> HelperConfirmResult.NotFound
                existing.requesterOutcome != null -> HelperConfirmResult.AlreadyConfirmed
                else -> HelperConfirmResult.Withdrawn
            }
        }
        if (donated) updateLastDonation(conn, pledge.donorId, today, now)
        HelperConfirmResult.Confirmed(pledge)
    }

    // Setting reminder_sent_at in the same statement is what makes each reminder go out once.
    override suspend fun claimDueReminders(now: Instant, arrivalWindow: Duration): List<HelperReminder> = jdbc(db) { conn ->
        conn.prepareStatement(
            """
            UPDATE pledges p SET reminder_sent_at = ?
            FROM blood_requests r, users u
            WHERE r.id = p.request_id AND u.id = p.donor_id
              AND p.reminder_sent_at IS NULL AND p.requester_outcome IS NULL
              AND p.status IN ('committed', 'donated') AND r.status <> 'cancelled' AND r.requester_id IS NOT NULL
              AND LEAST(p.committed_at + make_interval(secs => CAST(? AS double precision)), r.needed_by) <= ?
            RETURNING p.id, p.request_id, r.requester_id, u.full_name
            """.trimIndent()
        ).use { st ->
            st.setObject(1, now.toOffset())
            st.setLong(2, arrivalWindow.seconds)
            st.setObject(3, now.toOffset())
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(HelperReminder(rs.getLong("id"), rs.getLong("request_id"), rs.getLong("requester_id"), rs.getString("full_name")))
                    }
                }
            }
        }
    }

    override suspend fun noShowCount(donorId: Long): Int = jdbc(db) { conn ->
        conn.prepareStatement("SELECT count(*) FROM pledges WHERE donor_id = ? AND requester_outcome = 'no_show'").use { st ->
            st.setLong(1, donorId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    // GREATEST ignores NULL, so a donor with no date gets today.
    private fun updateLastDonation(conn: Connection, donorId: Long, today: LocalDate, now: Instant) {
        conn.prepareStatement(
            "UPDATE users SET last_donation_date = GREATEST(last_donation_date, ?), updated_at = ? WHERE id = ?"
        ).use { st ->
            st.setObject(1, today)
            st.setObject(2, now.toOffset())
            st.setLong(3, donorId)
            st.executeUpdate()
        }
    }

    private fun selectPledge(conn: Connection, requestId: Long, donorId: Long): Pledge? =
        conn.prepareStatement("SELECT $PLEDGE_COLUMNS FROM pledges WHERE request_id = ? AND donor_id = ?").use { st ->
            st.setLong(1, requestId)
            st.setLong(2, donorId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toPledge() else null }
        }

    private fun ResultSet.toPledge() = Pledge(
        id = getLong("id"),
        requestId = getLong("request_id"),
        donorId = getLong("donor_id"), // NULL (deleted account) reads as 0, i.e. DELETED_USER_ID
        status = getString("status"),
        committedAt = getObject("committed_at", OffsetDateTime::class.java).toInstant(),
        requesterOutcome = getString("requester_outcome"),
        requesterConfirmedAt = getObject("requester_confirmed_at", OffsetDateTime::class.java)?.toInstant(),
        confirmedDonation = getBoolean("confirmed_donation"),
    )

    private companion object {
        const val PLEDGE_COLUMNS =
            "id, request_id, donor_id, status, committed_at, requester_outcome, requester_confirmed_at, confirmed_donation"
    }
}
