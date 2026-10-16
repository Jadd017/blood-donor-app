package com.jadbarakat.blooddonor.server

import com.jadbarakat.blooddonor.server.auth.FirebaseTokenVerifier
import com.jadbarakat.blooddonor.server.jobs.startExpiryJob
import com.jadbarakat.blooddonor.server.data.ExposedAreaRepository
import com.jadbarakat.blooddonor.server.data.ExposedBloodRequestRepository
import com.jadbarakat.blooddonor.server.data.ExposedHospitalRepository
import com.jadbarakat.blooddonor.server.data.ExposedUserRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.time.Clock
import javax.sql.DataSource
import kotlin.system.exitProcess

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
    Flyway.configure().dataSource(dataSource).load().migrate()

    val verifier = try {
        FirebaseTokenVerifier.create(
            credentialsPath = env("FIREBASE_CREDENTIALS_PATH"),
            projectId = env("FIREBASE_PROJECT_ID"),
        )
    } catch (e: IllegalArgumentException) {
        throw StartupException(e.message ?: "Firebase setup failed")
    }

    val db = Database.connect(dataSource)
    val requests = ExposedBloodRequestRepository(db)
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        module(
            verifier = verifier,
            users = ExposedUserRepository(db),
            areas = ExposedAreaRepository(db),
            hospitals = ExposedHospitalRepository(db),
            requests = requests,
            healthCheck = { dataSource.connection.use { it.isValid(2) } },
        )
        startExpiryJob(requests, Clock.systemUTC())
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
    }
    return HikariDataSource(config)
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
