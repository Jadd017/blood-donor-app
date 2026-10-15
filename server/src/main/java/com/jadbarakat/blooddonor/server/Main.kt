package com.jadbarakat.blooddonor.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun main() {
    val dataSource = createDataSource()

    embeddedServer(Netty, port = 8080) {
        routing {
            get("/health") {
                val dbOk = dataSource.connection.use { it.isValid(2) }
                call.respondText(
                    if (dbOk) "Server OK, database OK"
                    else "Server OK, database NOT reachable"
                )
            }
        }
    }.start(wait = true)
}

// Database settings come from environment variables, never from the code,
// so your password never ends up on GitHub.
fun createDataSource(): HikariDataSource {
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: error("DB_URL is not set")
        username = System.getenv("DB_USER") ?: error("DB_USER is not set")
        password = System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD is not set")
        maximumPoolSize = 5
    }
    return HikariDataSource(config)
}