package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.auth.UserPrincipal
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.UserRepository
import com.jadbarakat.blooddonor.server.validation.validate
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.LocalDate

const val FIREBASE_AUTH = "firebase"

fun Route.apiV1(users: UserRepository, areas: AreaRepository, clock: Clock) {
    route("/api/v1") {
        get("/areas") {
            call.respond(areas.all().map { it.toResponse() })
        }

        authenticate(FIREBASE_AUTH) {
            get("/me") {
                val profile = users.findByUid(call.user().uid)
                    ?: throw ApiException(HttpStatusCode.NotFound, "profile_not_found", "No profile yet, create it with PUT /me")
                call.respond(profile.toResponse())
            }

            put("/me") {
                val user = call.user()
                val input = call.receive<ProfileRequest>().validate(LocalDate.now(clock))
                if (input.areaId != null && !areas.exists(input.areaId)) {
                    throw badRequest("unknown_area", "areaId ${input.areaId} does not exist")
                }
                // uid and email come only from the verified token.
                call.respond(users.upsert(user.uid, user.email, input).toResponse())
            }
        }
    }
}

private fun RoutingCall.user(): UserPrincipal = principal<UserPrincipal>()!!
