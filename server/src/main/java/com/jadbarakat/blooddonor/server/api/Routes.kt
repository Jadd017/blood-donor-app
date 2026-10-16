package com.jadbarakat.blooddonor.server.api

import com.jadbarakat.blooddonor.server.auth.UserPrincipal
import com.jadbarakat.blooddonor.server.data.AreaRepository
import com.jadbarakat.blooddonor.server.data.BloodRequest
import com.jadbarakat.blooddonor.server.data.BloodRequestRepository
import com.jadbarakat.blooddonor.server.data.HospitalRepository
import com.jadbarakat.blooddonor.server.data.RequestStatus
import com.jadbarakat.blooddonor.server.data.UserProfile
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
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.LocalDate

const val FIREBASE_AUTH = "firebase"

fun Route.apiV1(
    users: UserRepository,
    areas: AreaRepository,
    hospitals: HospitalRepository,
    requests: BloodRequestRepository,
    clock: Clock,
) {
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

            get("/hospitals") {
                call.requireProfile(users)
                val areaId = call.request.queryParameters["areaId"]?.let {
                    it.toIntOrNull() ?: throw badRequest("invalid_area_id", "areaId must be a number")
                }
                call.respond(hospitals.all(areaId).map { it.toResponse() })
            }

            route("/requests") {
                post {
                    val profile = call.requireProfile(users)
                    val input = call.receive<CreateBloodRequestBody>().validate()
                    if (hospitals.findActive(input.hospitalId) == null) {
                        throw badRequest("unknown_hospital", "hospitalId ${input.hospitalId} does not exist")
                    }
                    val created = requests.create(profile.id, input, clock.instant())
                    call.respond(HttpStatusCode.Created, created.toResponse())
                }

                get("/mine") {
                    val profile = call.requireProfile(users)
                    val now = clock.instant()
                    call.respond(requests.listByRequester(profile.id).map { it.effective(now).toResponse() })
                }

                get("/{id}") {
                    val request = call.ownedRequest(users, requests)
                    call.respond(request.effective(clock.instant()).toResponse())
                }

                post("/{id}/fulfill") {
                    call.closeOwnedRequest(users, requests, clock, RequestStatus.FULFILLED)
                }

                post("/{id}/cancel") {
                    call.closeOwnedRequest(users, requests, clock, RequestStatus.CANCELLED)
                }
            }
        }
    }
}

private fun RoutingCall.user(): UserPrincipal = principal<UserPrincipal>()!!

private suspend fun RoutingCall.requireProfile(users: UserRepository): UserProfile =
    users.findByUid(user().uid)
        ?: throw ApiException(HttpStatusCode.Conflict, "profile_required", "Create your profile first with PUT /me")

/** The request in the {id} path segment. Someone else's request is reported as missing, not forbidden. */
private suspend fun RoutingCall.ownedRequest(users: UserRepository, requests: BloodRequestRepository): BloodRequest {
    val profile = requireProfile(users)
    val request = parameters["id"]?.toLongOrNull()?.let { requests.findById(it) }
    if (request == null || request.requesterId != profile.id) {
        throw ApiException(HttpStatusCode.NotFound, "request_not_found", "No such request")
    }
    return request
}

private suspend fun RoutingCall.closeOwnedRequest(
    users: UserRepository,
    requests: BloodRequestRepository,
    clock: Clock,
    status: String,
) {
    val request = ownedRequest(users, requests)
    val closed = requests.close(request.id, status, clock.instant())
        ?: throw ApiException(HttpStatusCode.Conflict, "request_not_open", "Only open requests can be changed")
    respond(closed.toResponse())
}
