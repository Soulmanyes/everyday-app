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
            call.respond(HttpStatusCode.Created, SessionResponse(token, household.toDto(), members))
        }

        post("/join") {
            val req = call.receive<JoinRequest>()
            api.ensureNotLocked()
            val household = api.store.getHousehold()
                ?: throw ApiException(HttpStatusCode.NotFound, "No household has been set up yet.")
            api.checkSecret(api.secrets.verify(req.householdCode, household.codeHash), "Wrong household code.")
            val token = api.secrets.newToken()
            api.store.addDevice(household.id, Secrets.tokenHash(token), req.deviceLabel)
            call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id)))
        }

        get("/me") {
            val household = api.household(call)
            call.respond(HouseholdResponse(household.toDto(), api.store.members(household.id)))
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
                api.store.addMember(household.id, validName(req.displayName, "Name", 40), req.color)
            }
            call.respond(HttpStatusCode.Created, member)
        }

        put("/members/{id}") {
            val household = api.requireAdmin(call)
            val id = memberId(call)
            val req = call.receive<MemberRequest>()
            val member = withNameCheck {
                api.store.updateMember(household.id, id, validName(req.displayName, "Name", 40), req.color)
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
            val household = api.household(call)
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
            call.respondChores(api.chores.markDone(household.id, id, member, api.today(household)))
        }

        post("/chores/{id}/undo") {
            val household = api.household(call)
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
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
    val session = authenticate(call)
    val household = store.getHousehold(session.householdId) ?: throw unauthorized()
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

private fun ApiContext.household(call: ApplicationCall): HouseholdRow =
    store.getHousehold(authenticate(call).householdId) ?: throw unauthorized()

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
}

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
