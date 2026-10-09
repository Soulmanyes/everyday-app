package app.everyday.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Serializable
data class HealthResponse(val status: String)

/** Dependencies of the API routes. */
class ApiContext(
    val store: HouseholdStore,
    val chores: ChoreStore,
    val sync: SyncStore,
    val secrets: Secrets,
    val limiter: AttemptLimiter,
    /** One-time token needed to create the household; null disables creation. */
    val setupToken: String?,
    val clock: () -> Instant = Instant::now,
)

const val ADMIN_PIN_HEADER = "X-Admin-Pin"

fun Application.configureRouting(api: ApiContext?) {
    routing {
        get("/health") { call.respond(HealthResponse("ok")) }
        if (api == null) return@routing

        get("/status") { call.respond(StatusResponse(api.store.householdExists())) }

        post("/household") {
            val req = call.receive<CreateHouseholdRequest>()
            val setupToken = api.setupToken
                ?: throw ApiException(HttpStatusCode.Forbidden, "Creating a household is disabled on this server.")
            api.checkSecret(Secrets.constantTimeEquals(req.setupToken.trim(), setupToken), "Wrong setup code.")
            val name = validName(req.householdName, "Household name", 60)
            val adminName = validName(req.adminName, "Your name", 40)
            validCode(req.householdCode)
            validPin(req.adminPin)
            val zone = runCatching { ZoneId.of(req.timezone) }.getOrNull() ?: badRequest("Unknown timezone.")

            val token = api.secrets.newToken()
            val (household, members) = api.store.createHousehold(
                name = name,
                timezone = zone.id,
                codeHash = api.secrets.hash(req.householdCode),
                adminName = adminName,
                adminPinHash = api.secrets.hash(req.adminPin),
                deviceTokenHash = Secrets.tokenHash(token),
                deviceLabel = req.deviceLabel,
            ) ?: throw ApiException(HttpStatusCode.Conflict, "A household already exists on this server.")
            call.respond(HttpStatusCode.Created, SessionResponse(token, household.toDto(), members, memberId = household.adminMemberId?.toString()))
        }

        post("/join") {
            val req = call.receive<JoinRequest>()
            api.ensureNotLocked()
            val household = api.store.getHousehold()
                ?: throw ApiException(HttpStatusCode.NotFound, "No household has been set up yet.")
            api.checkSecret(api.secrets.verify(req.householdCode, household.codeHash), "Wrong household code.")
            val token = api.secrets.newToken()
            // The admin on a new phone (or after reinstalling): nobody else could approve it.
            if (!req.adminPin.isNullOrEmpty()) {
                api.checkSecret(api.secrets.verify(req.adminPin, household.adminPinHash), "Wrong admin PIN.")
                val adminId = household.adminMemberId ?: throw ApiException(HttpStatusCode.Conflict, "The household has no admin.")
                api.store.addAdminDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, adminId)
                call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id), memberId = adminId.toString()))
                return@post
            }
            val name = validName(req.name, "Your name", 40)
            api.store.addPendingDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, name)
            call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id), deviceStatus = "pending"))
        }

        /** Works for pending phones too, so they can see when they've been approved. */
        get("/me") {
            val session = api.authenticate(call)
            val household = api.store.getHousehold(session.householdId) ?: throw unauthorized()
            val isAdmin = session.approved && session.memberId == household.adminMemberId
            call.respond(
                HouseholdResponse(
                    household = household.toDto(),
                    members = api.store.members(household.id),
                    deviceStatus = if (session.approved) "approved" else "pending",
                    memberId = session.memberId?.toString(),
                    pendingRequestIds = if (isAdmin) api.store.joinRequests(household.id).map { it.id } else emptyList(),
                ),
            )
        }

        /** A member picks their own dot colour (no PIN). */
        put("/me/color") {
            val (household, me) = api.member(call)
            val color = validColor(call.receive<ColorRequest>().color)
            val member = withNameCheck { api.store.setColor(household.id, me, color) } ?: throw unauthorized()
            call.respond(member)
        }

        get("/join-requests") {
            val household = api.requireAdmin(call)
            call.respond(api.store.joinRequests(household.id))
        }

        /** Approves a phone as a new member (body without memberId) or as an existing member. */
        post("/join-requests/{id}/approve") {
            val household = api.requireAdmin(call)
            val id = uuidParam(call, "request")
            val memberId = call.receive<ApproveRequest>().memberId?.let { parseUuid(it) ?: badRequest("Bad member id.") }
            val member = withNameCheck { api.store.approve(household.id, id, memberId) }
                ?: throw ApiException(HttpStatusCode.NotFound, "No such request or member.")
            call.respond(member)
        }

        post("/join-requests/{id}/reject") {
            val household = api.requireAdmin(call)
            if (!api.store.reject(household.id, uuidParam(call, "request"))) throw ApiException(HttpStatusCode.NotFound, "No such request.")
            call.respond(HttpStatusCode.NoContent)
        }

        /** Leaving the household: the device's token stops working. */
        delete("/device") {
            val session = api.authenticate(call)
            api.store.revokeDevice(session.deviceId)
            call.respond(HttpStatusCode.NoContent)
        }

        /** Lets the app check the admin PIN before showing member management. */
        post("/admin/verify") {
            api.requireAdmin(call)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/members") {
            val household = api.requireAdmin(call)
            val req = call.receive<MemberRequest>()
            val member = withNameCheck {
                api.store.addMember(household.id, validName(req.displayName, "Name", 40), req.color?.let(::validColor))
            }
            call.respond(HttpStatusCode.Created, member)
        }

        put("/members/{id}") {
            val household = api.requireAdmin(call)
            val id = memberId(call)
            val req = call.receive<MemberRequest>()
            val member = withNameCheck {
                api.store.updateMember(household.id, id, validName(req.displayName, "Name", 40), req.color?.let(::validColor))
            } ?: throw ApiException(HttpStatusCode.NotFound, "No such member.")
            call.respond(member)
        }

        delete("/members/{id}") {
            val household = api.requireAdmin(call)
            val id = memberId(call)
            if (id == household.adminMemberId) badRequest("The admin can't be removed.")
            if (!api.store.deleteMember(household.id, id)) throw ApiException(HttpStatusCode.NotFound, "No such member.")
            call.respond(HttpStatusCode.NoContent)
        }

        /** The 1.1.0 sync, before events. Not 401: that would send old phones back to the join screen. */
        post("/sync") {
            throw ApiException(HttpStatusCode.Gone, "Update the app to keep syncing.")
        }

        /** Pushes the phone's changed rows for one member and returns everything that member can see. */
        post("/v2/sync") {
            val (household, me) = api.member(call)
            val req = call.receive<SyncRequest>()
            if (req.memberId != me.toString()) throw notYours()
            when (val result = api.sync.sync(household.id, req)) {
                is SyncStore.Result.Ok -> call.respond(result.response)
                SyncStore.Result.UnknownMember -> badRequest("Unknown member.")
            }
        }

        get("/chores") {
            val household = api.household(call)
            call.respond(api.chores.snapshot(household.id, api.today(household)))
        }

        /** Creates or updates a chore. The id is generated by the phone. */
        put("/chores/{id}") {
            val household = api.household(call)
            val id = uuidParam(call, "chore")
            val req = call.receive<ChoreRequest>()
            val valid = req.copy(
                title = validName(req.title, "Title", 60),
                notes = req.notes?.trim()?.takeIf { it.isNotEmpty() }?.also {
                    if (it.length > 500) badRequest("Notes are too long (max 500 characters).")
                },
            )
            if (req.weekdays !in 1..127) badRequest("Pick at least one day.")
            val assignees = req.assigneeIds.map { parseUuid(it) ?: badRequest("Bad member id.") }.toSet()
            if (assignees.isEmpty()) badRequest("Assign at least one person.")
            call.respondChores(api.chores.upsert(household.id, id, valid, assignees, api.today(household)))
        }

        delete("/chores/{id}") {
            val household = api.household(call)
            call.respondChores(api.chores.delete(household.id, uuidParam(call, "chore"), api.today(household)))
        }

        post("/chores/{id}/done") {
            val (household, me) = api.member(call)
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
            if (member != me) throw notYours()
            call.respondChores(api.chores.markDone(household.id, id, member, api.today(household)))
        }

        post("/chores/{id}/undo") {
            val (household, me) = api.member(call)
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
            if (member != me) throw notYours()
            call.respondChores(api.chores.undoDone(household.id, id, member, api.today(household)))
        }
    }
}

private fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "This phone is not part of the household.")

private fun ApiContext.authenticate(call: ApplicationCall): DeviceSession {
    val header = call.request.headers[HttpHeaders.Authorization] ?: throw unauthorized()
    val token = header.removePrefix("Bearer ").trim()
    if (token.isEmpty() || token == header) throw unauthorized()
    return store.authenticate(Secrets.tokenHash(token)) ?: throw unauthorized()
}

/** Authenticates the device and checks the admin PIN header. Returns the household. */
private fun ApiContext.requireAdmin(call: ApplicationCall): HouseholdRow {
    val household = household(call)
    val pin = call.request.headers[ADMIN_PIN_HEADER].orEmpty()
    checkSecret(pin.isNotEmpty() && secrets.verify(pin, household.adminPinHash), "Wrong admin PIN.")
    return household
}

private fun ApiContext.ensureNotLocked() {
    limiter.lockedForSeconds()?.let { seconds ->
        val minutes = (seconds + 59) / 60
        throw ApiException(HttpStatusCode.TooManyRequests, "Too many wrong attempts. Try again in $minutes min.")
    }
}

/** Refuses while locked out; counts a failure and rejects if [ok] is false. */
private fun ApiContext.checkSecret(ok: Boolean, message: String) {
    ensureNotLocked()
    if (!ok) {
        limiter.recordFailure()
        throw ApiException(HttpStatusCode.Forbidden, message)
    }
}

private fun memberId(call: ApplicationCall): UUID = uuidParam(call, "member")

private fun uuidParam(call: ApplicationCall, what: String): UUID =
    call.parameters["id"]?.let(::parseUuid) ?: badRequest("Bad $what id.")

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

/** The household of an approved phone. Pending phones are refused. */
private fun ApiContext.household(call: ApplicationCall): HouseholdRow = member(call).first

/** The household and member of an approved phone: member-scoped requests act as this member. */
private fun ApiContext.member(call: ApplicationCall): Pair<HouseholdRow, UUID> {
    val session = authenticate(call)
    val memberId = session.memberId?.takeIf { session.approved } ?: throw notApproved()
    val household = store.getHousehold(session.householdId) ?: throw unauthorized()
    return household to memberId
}

private fun notApproved() = ApiException(HttpStatusCode.Forbidden, "This phone is waiting for the admin to approve it.")

private fun notYours() = ApiException(HttpStatusCode.Forbidden, "This phone can only act as its own member.")

/** Today's date in the household timezone; chore completions are per day there, not per UTC day. */
private fun ApiContext.today(household: HouseholdRow): LocalDate = LocalDate.ofInstant(clock(), ZoneId.of(household.timezone))

private suspend fun ApplicationCall.respondChores(result: ChoreStore.Result) = when (result) {
    is ChoreStore.Result.Ok -> respond(result.snapshot)
    ChoreStore.Result.NotFound -> throw ApiException(HttpStatusCode.NotFound, "No such chore.")
    is ChoreStore.Result.Invalid -> badRequest(result.message)
}

private inline fun <T> withNameCheck(block: () -> T): T = try {
    block()
} catch (_: HouseholdStore.NameTaken) {
    throw ApiException(HttpStatusCode.Conflict, "Someone in the household already has that name.")
} catch (_: HouseholdStore.ColorTaken) {
    throw ApiException(HttpStatusCode.Conflict, "Someone in the household already has that colour.")
}

private fun validColor(color: String): String = color.takeIf { it in MEMBER_COLORS } ?: badRequest("Unknown colour.")

private fun validName(value: String, field: String, max: Int): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) badRequest("$field can't be empty.")
    if (trimmed.length > max) badRequest("$field is too long (max $max characters).")
    return trimmed
}

private fun validCode(code: String) {
    if (code.length < 6) badRequest("The household code must be at least 6 characters.")
    if (code.length > 64) badRequest("The household code is too long.")
}

private fun validPin(pin: String) {
    if (!pin.matches(Regex("\\d{4,8}"))) badRequest("The admin PIN must be 4 to 8 digits.")
}
