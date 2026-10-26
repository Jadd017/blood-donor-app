package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.FirebaseAccountDeleter
import com.jadbarakat.blooddonor.server.auth.FirebaseTokenVerifier
import com.jadbarakat.blooddonor.server.auth.initFirebase
import com.jadbarakat.blooddonor.server.data.ExposedDeviceTokenRepository
import com.jadbarakat.blooddonor.server.data.ExposedMatchingRepository
import com.jadbarakat.blooddonor.server.jobs.startBackgroundJobs
import com.jadbarakat.blooddonor.server.matching.MatchingConfig
import com.jadbarakat.blooddonor.server.api.RequestEvent
import com.jadbarakat.blooddonor.server.data.ExposedPledgeRepository
import com.jadbarakat.blooddonor.server.data.ExposedSafetyRepository
import com.jadbarakat.blooddonor.server.safety.SafetyConfig
import com.jadbarakat.blooddonor.server.matching.HelperCounter
import com.jadbarakat.blooddonor.server.matching.WaveService
import com.jadbarakat.blooddonor.server.push.FcmPushSender
import com.jadbarakat.blooddonor.server.push.RequestNotifier
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.jadbarakat.blooddonor.server.data.ExposedAreaRepository
import com.jadbarakat.blooddonor.server.data.ExposedBloodRequestRepository
import com.jadbarakat.blooddonor.server.data.ExposedHospitalRepository
import com.jadbarakat.blooddonor.server.data.ExposedUserRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.system.exitProcess
import kotlin.time.toJavaDuration

private val log = LoggerFactory.getLogger("com.jadbarakat.blooddonor.server.Main")

/** A startup problem with a clear, human-readable explanation. */
class StartupException(message: String) : Exception(message)

fun main() {
    try {
        start()
    } catch (e: StartupException) {
        log.error("Server failed to start: {}", e.message)
        exitProcess(1)
    }
}

private fun start() {
    val dataSource = createDataSource()
    requirePostgis(dataSource)
    migrate(dataSource)

    val firebase = try {
        initFirebase(credentialsPath = env("FIREBASE_CREDENTIALS_PATH"), projectId = env("FIREBASE_PROJECT_ID"))
    } catch (e: IllegalArgumentException) {
        throw StartupException(e.message ?: "Firebase setup failed")
    }
    val config = try {
        MatchingConfig.fromEnv(System::getenv)
    } catch (e: IllegalArgumentException) {
        throw StartupException("Invalid matching setting: ${e.message}")
    }
    val safetyConfig = try {
        SafetyConfig.fromEnv(System::getenv)
    } catch (e: IllegalArgumentException) {
        throw StartupException("Invalid safety setting: ${e.message}")
    }

    val db = Database.connect(dataSource)
    val clock = Clock.system(ZoneId.of("Asia/Beirut"))
    val requests = ExposedBloodRequestRepository(db)
    val deviceTokens = ExposedDeviceTokenRepository(db)
    val pledges = ExposedPledgeRepository(db)
    val push = FcmPushSender(FirebaseMessaging.getInstance(firebase))
    val waves = WaveService(
        requests = requests,
        matching = ExposedMatchingRepository(db),
        tokens = deviceTokens,
        push = push,
        helpers = HelperCounter { id, now -> pledges.helperCount(id, now, config.arrivalWindow.toJavaDuration()) },
        config = config,
        clock = clock,
    )
    val notifier = RequestNotifier(requests, pledges, deviceTokens, push, clock, config.arrivalWindow.toJavaDuration())
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val connectionLimiter = ConnectionLimiter(max = intEnv("MAX_CONNECTIONS", 500))
    val trustedProxies = System.getenv("TRUSTED_PROXY_IPS").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    embeddedServer(Netty, configure = {
        connector { this.port = port }
        // Slow or oversized requests are cut off instead of tying up the server.
        requestReadTimeoutSeconds = 15
        responseWriteTimeoutSeconds = 15
        maxInitialLineLength = 4096
        maxHeaderSize = 8192
        runningLimit = 32
        channelPipelineConfig = { addConnectionLimits(connectionLimiter, idleSeconds = 60) }
    }) {
        module(
            verifier = FirebaseTokenVerifier.create(firebase),
            users = ExposedUserRepository(db),
            areas = ExposedAreaRepository(db),
            hospitals = ExposedHospitalRepository(db),
            requests = requests,
            deviceTokens = deviceTokens,
            pledges = pledges,
            safety = ExposedSafetyRepository(db),
            accountDeleter = FirebaseAccountDeleter.create(firebase),
            // Wave 1 runs in the background so creating a request stays fast.
            onRequestCreated = { id ->
                launch {
                    try {
                        waves.runDueWaveFor(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("First wave for request {} failed; the background job will retry", id, e)
                    }
                }
            },
            // Push notifications run in the background; a withdrawal or no-show may also free up the next wave.
            onRequestEvent = { event ->
                launch {
                    try {
                        notifier.handle(event)
                        if (event is RequestEvent.DonorWithdrew || event is RequestEvent.HelperNoShow) {
                            waves.runDueWaveFor(event.requestId)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Handling {} failed", event, e)
                    }
                }
            },
            config = config,
            safetyConfig = safetyConfig,
            clock = clock,
            healthCheck = { dataSource.connection.use { it.isValid(2) } },
            trustedProxies = trustedProxies,
        )
        startBackgroundJobs(requests, waves, notifier, clock, config.jobInterval)
    }.start(wait = true)
}

// Settings come from environment variables, never from the code,
// so secrets never end up on GitHub.
private fun env(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: throw StartupException("$name is not set (see server/.env.example)")

fun createDataSource(): HikariDataSource {
    val config = HikariConfig().apply {
        jdbcUrl = env("DB_URL")
        username = env("DB_USER")
        password = env("DB_PASSWORD")
        maximumPoolSize = 5
        // Keep row values (names, phones, emails) out of exception messages and therefore logs.
        addDataSourceProperty("logServerErrorDetail", "false")
    }
    return HikariDataSource(config)
}

/**
 * Runs the Flyway migrations. With DB_MIGRATION_USER set, they run as that role (the schema
 * owner) and the server itself only needs a role that can read and write rows; see SECURITY.md.
 */
private fun migrate(dataSource: DataSource) {
    val migrationUser = System.getenv("DB_MIGRATION_USER")?.takeIf { it.isNotBlank() }
    val flyway = if (migrationUser == null) {
        Flyway.configure().dataSource(dataSource)
    } else {
        Flyway.configure().dataSource(env("DB_URL"), migrationUser, env("DB_MIGRATION_PASSWORD"))
    }
    flyway.load().migrate()
}

private fun intEnv(name: String, default: Int): Int {
    val raw = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
    return raw.toIntOrNull()?.takeIf { it > 0 } ?: throw StartupException("$name must be a positive whole number")
}

/** Migrations use geography columns but must never create extensions themselves. */
private fun requirePostgis(dataSource: DataSource) {
    val installed = dataSource.connection.use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'postgis'").use { it.next() }
        }
    }
    if (!installed) {
        throw StartupException(
            "PostGIS is not installed in this database. " +
                "As a superuser, connect to it and run: CREATE EXTENSION postgis;"
        )
    }
}
