package app.everyday.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
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
    val rateLimits: RateLimits = RateLimits(),
    /** Instant updates; null on a server without Firebase (and in most tests). */
    val push: Push? = null,
    val invites: InviteStore = InviteStore(store.db),
    /** How long an invite to create a household stays valid. */
    val inviteLifetime: java.time.Duration = java.time.Duration.ofDays(7),
)

const val ADMIN_PIN_HEADER = "X-Admin-Pin"

fun Application.configureRouting(api: ApiContext?) {
    routing {
        get("/health") { call.respond(HealthResponse("ok")) }
        if (api == null) return@routing

        get("/status") { call.respond(StatusResponse(api.store.householdExists())) }

        // Per-IP limits on top of the global wrong-attempt lockout (see RateLimits).
        rateLimit(SECRETS_LIMIT) {
            post("/household") {
                val req = call.receive<CreateHouseholdRequest>()
                if (req.householdCode != null) throw updateApp("Update the app to set up a household.")
                val name = validName(req.householdName, "Household name", 60)
                val adminName = validName(req.adminName, "Your name", 40)
                validPin(req.adminPin)
                val zone = runCatching { ZoneId.of(req.timezone) }.getOrNull() ?: badRequest("Unknown timezone.")
                // An invite from another household's admin, or the server's own setup code.
                val inviteHash = when {
                    !req.inviteCode.isNullOrBlank() -> {
                        api.ensureNotLocked()
                        val invite = Codes.normalizeInvite(req.inviteCode)
                        api.checkSecret(invite != null, INVITE_INVALID)
                        Secrets.tokenHash(invite!!)
                    }
                    !req.setupToken.isNullOrBlank() -> {
                        val setupToken = api.setupToken
                            ?: throw ApiException(HttpStatusCode.Forbidden, "Creating a household with a setup code is disabled on this server.")
                        api.checkSecret(Secrets.constantTimeEquals(req.setupToken.trim(), setupToken), "Wrong setup code.")
                        null
                    }
                    else -> badRequest("Enter the invite code you were given.")
                }

                val token = api.secrets.newToken()
                val pinHash = api.secrets.hash(req.adminPin)
                val (code, created) = api.withNewJoinCode { code, codeHash ->
                    try {
                        api.store.createHousehold(
                            name = name,
                            timezone = zone.id,
                            joinId = code.joinId,
                            codeHash = codeHash,
                            adminName = adminName,
                            adminPinHash = pinHash,
                            deviceTokenHash = Secrets.tokenHash(token),
                            deviceLabel = req.deviceLabel,
                            inviteHash = inviteHash,
                            now = api.clock(),
                        )
                    } catch (_: HouseholdStore.InviteInvalid) {
                        api.checkSecret(false, INVITE_INVALID)
                        error("unreachable")
                    }
                }
                val (household, members) = created
                call.respond(
                    HttpStatusCode.Created,
                    SessionResponse(token, household.toDto(), members, memberId = household.adminMemberId?.toString(), joinCode = code.toString()),
                )
            }

            post("/join") {
                val req = call.receive<JoinRequest>()
                if (req.joinCode == null) throw updateApp("Update the app to join a household.")
                api.ensureNotLocked()
                // The public part finds the household; wrong secrets count against that household only.
                val code = Codes.parseJoinCode(req.joinCode)
                val household = code?.let { api.store.householdByJoinId(it.joinId) }
                api.checkSecret(household != null, WRONG_JOIN_CODE)
                household!!
                val key = household.id.toString()
                api.ensureNotLocked(key)
                api.checkSecret(household.codeHash != null && api.secrets.verify(code!!.secret, household.codeHash), WRONG_JOIN_CODE, key)
                val token = api.secrets.newToken()
                // The admin on a new phone (or after reinstalling): nobody else could approve it.
                if (!req.adminPin.isNullOrEmpty()) {
                    api.checkSecret(api.secrets.verify(req.adminPin, household.adminPinHash), "Wrong admin PIN.", key)
                    val adminId = household.adminMemberId ?: throw ApiException(HttpStatusCode.Conflict, "The household has no admin.")
                    api.store.addAdminDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, adminId)
                    call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id), memberId = adminId.toString()))
                    return@post
                }
                val name = validName(req.name, "Your name", 40)
                api.store.addPendingDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, name)
                api.push?.admin(household.id)
                call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id), deviceStatus = "pending"))
            }
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
                    hasJoinCode = household.codeHash != null,
                ),
            )
        }

        /** A member picks their own dot colour (no PIN). */
        put("/me/color") {
            val caller = api.member(call)
            val color = validColor(call.receive<ColorRequest>().color)
            val member = withNameCheck { api.store.setColor(caller.household.id, caller.memberId, color) } ?: throw unauthorized()
            api.pushOthers(caller)
            call.respond(member)
        }

        /** Registers the phone for instant updates. Pending phones too: they're pushed when approved. */
        put("/me/push-token") {
            val session = api.authenticate(call)
            val token = call.receive<PushTokenRequest>().token?.trim()?.takeIf { it.isNotEmpty() }
            if (token != null && token.length > 4096) badRequest("Bad push token.")
            api.push?.store?.setToken(session.deviceId, token)
            call.respond(HttpStatusCode.NoContent)
        }

        /** Leaving the household: the device's token stops working. */
        delete("/device") {
            val session = api.authenticate(call)
            api.store.revokeDevice(session.deviceId)
            call.respond(HttpStatusCode.NoContent)
        }

        rateLimit(ADMIN_LIMIT) {
            get("/join-requests") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                call.respond(api.store.joinRequests(household.id))
            }

            /** Approves a phone as a new member (body without memberId) or as an existing member. */
            post("/join-requests/{id}/approve") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                val id = uuidParam(call, "request")
                val memberId = call.receive<ApproveRequest>().memberId?.let { parseUuid(it) ?: badRequest("Bad member id.") }
                val member = withNameCheck { api.store.approve(household.id, id, memberId) }
                    ?: throw ApiException(HttpStatusCode.NotFound, "No such request or member.")
                // The approved phone moves on from its waiting screen; the others may have a new member.
                api.push?.let { push -> push.tokens(push.store.deviceTokens(household.id, id)) }
                api.pushOthers(admin)
                call.respond(member)
            }

            post("/join-requests/{id}/reject") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                val id = uuidParam(call, "request")
                // Read before rejecting: a rejected phone keeps no token. The push sends it back to the join screen.
                val tokens = api.push?.store?.deviceTokens(household.id, id).orEmpty()
                if (!api.store.reject(household.id, id)) throw ApiException(HttpStatusCode.NotFound, "No such request.")
                api.push?.tokens(tokens)
                call.respond(HttpStatusCode.NoContent)
            }

            /** A new join code; the old one stops working. Shown once: the server keeps only a hash. */
            post("/household/join-code") {
                val household = api.requireAdmin(call).household
                val (code, _) = api.withNewJoinCode { code, codeHash -> api.store.setJoinCode(household.id, code.joinId, codeHash) }
                call.respond(JoinCodeResponse(code.toString()))
            }

            /** Deletes the household and everything in it. Its other phones are sent back to the welcome screen. */
            delete("/household") {
                val admin = api.requireAdmin(call)
                val tokens = api.push?.store?.householdTokens(admin.household.id, exceptDevice = admin.deviceId).orEmpty()
                if (!api.store.deleteHousehold(admin.household.id)) throw unauthorized()
                api.push?.tokens(tokens)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/invites") {
                val household = api.requireAdmin(call).household
                call.respond(InvitesResponse(api.invites.list(household.id), api.invites.remaining(household.id, api.clock())))
            }

            /** An invite for a friend to create their own household. The code is shown once. */
            post("/invites") {
                val household = api.requireAdmin(call).household
                val now = api.clock()
                val code = Codes.newInvite()
                val invite = api.invites.create(household.id, Secrets.tokenHash(Codes.normalizeInvite(code)!!), now, now.plus(api.inviteLifetime))
                    ?: throw ApiException(HttpStatusCode.Conflict, "This household has used all its invites.")
                call.respond(HttpStatusCode.Created, CreatedInviteResponse(invite, code))
            }

            delete("/invites/{id}") {
                val household = api.requireAdmin(call).household
                if (!api.invites.cancel(household.id, uuidParam(call, "invite"))) throw ApiException(HttpStatusCode.NotFound, "No such unused invite.")
                call.respond(HttpStatusCode.NoContent)
            }

            /** Lets the app check the admin PIN before showing member management. */
            post("/admin/verify") {
                api.requireAdmin(call)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/members") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                val req = call.receive<MemberRequest>()
                val member = withNameCheck {
                    api.store.addMember(household.id, validName(req.displayName, "Name", 40), req.color?.let(::validColor))
                }
                api.pushOthers(admin)
                call.respond(HttpStatusCode.Created, member)
            }

            put("/members/{id}") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                val id = memberId(call)
                val req = call.receive<MemberRequest>()
                val member = withNameCheck {
                    api.store.updateMember(household.id, id, validName(req.displayName, "Name", 40), req.color?.let(::validColor))
                } ?: throw ApiException(HttpStatusCode.NotFound, "No such member.")
                api.pushOthers(admin)
                call.respond(member)
            }

            delete("/members/{id}") {
                val admin = api.requireAdmin(call)
                val household = admin.household
                val id = memberId(call)
                if (id == household.adminMemberId) badRequest("The admin can't be removed.")
                // Read before removing: their phones keep no token. The push sends them back to the join screen.
                val tokens = api.push?.store?.memberTokens(household.id, id).orEmpty()
                if (!api.store.deleteMember(household.id, id)) throw ApiException(HttpStatusCode.NotFound, "No such member.")
                api.push?.tokens(tokens)
                api.pushOthers(admin)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        /** The 1.1.0 sync, before events. Not 401: that would send old phones back to the join screen. */
        post("/sync") {
            throw ApiException(HttpStatusCode.Gone, "Update the app to keep syncing.")
        }

        /** Pushes the phone's changed rows for one member and returns everything that member can see. */
        post("/v2/sync") {
            val caller = api.member(call)
            val req = call.receive<SyncRequest>()
            if (req.memberId != caller.memberId.toString()) throw notYours()
            when (val result = api.sync.sync(caller.household.id, req)) {
                is SyncStore.Result.Ok -> {
                    // Only when something was written: pushed phones sync too, and a sync with nothing new must not push again.
                    if (result.changed) api.pushOthers(caller)
                    call.respond(result.response)
                }
                SyncStore.Result.UnknownMember -> badRequest("Unknown member.")
            }
        }

        get("/chores") {
            val household = api.household(call)
            call.respond(api.chores.snapshot(household.id, api.today(household)))
        }

        /** Creates or updates a chore. The id is generated by the phone. */
        put("/chores/{id}") {
            val caller = api.member(call)
            val household = caller.household
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
            call.respondChores(api.chores.upsert(household.id, id, valid, assignees, api.today(household)), api, caller)
        }

        delete("/chores/{id}") {
            val caller = api.member(call)
            val household = caller.household
            call.respondChores(api.chores.delete(household.id, uuidParam(call, "chore"), api.today(household)), api, caller)
        }

        post("/chores/{id}/done") {
            val caller = api.member(call)
            val (household, me) = caller
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
            if (member != me) throw notYours()
            call.respondChores(api.chores.markDone(household.id, id, member, api.today(household)), api, caller)
        }

        post("/chores/{id}/undo") {
            val caller = api.member(call)
            val (household, me) = caller
            val id = uuidParam(call, "chore")
            val member = parseUuid(call.receive<ChoreDoneRequest>().memberId) ?: badRequest("Bad member id.")
            if (member != me) throw notYours()
            call.respondChores(api.chores.undoDone(household.id, id, member, api.today(household)), api, caller)
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

/** Authenticates the device and checks the admin PIN header. */
private fun ApiContext.requireAdmin(call: ApplicationCall): Caller {
    val caller = member(call)
    val pin = call.request.headers[ADMIN_PIN_HEADER].orEmpty()
    val key = caller.household.id.toString()
    ensureNotLocked(key)
    checkSecret(pin.isNotEmpty() && secrets.verify(pin, caller.household.adminPinHash), "Wrong admin PIN.", key)
    return caller
}

private fun ApiContext.ensureNotLocked(key: String = AttemptLimiter.GLOBAL) {
    limiter.lockedForSeconds(key)?.let { seconds ->
        val minutes = (seconds + 59) / 60
        throw ApiException(HttpStatusCode.TooManyRequests, "Too many wrong attempts. Try again in $minutes min.")
    }
}

/** Refuses while [key] is locked out; counts a failure for [key] and rejects if [ok] is false. */
private fun ApiContext.checkSecret(ok: Boolean, message: String, key: String = AttemptLimiter.GLOBAL) {
    ensureNotLocked(key)
    if (!ok) {
        limiter.recordFailure(key)
        throw ApiException(HttpStatusCode.Forbidden, message)
    }
}

private const val WRONG_JOIN_CODE = "Wrong join code."
private const val INVITE_INVALID = "That invite isn't valid. It may have been used, cancelled or expired."

/** Apps from before 2.0. Not 401, which would send a joined phone back to the welcome screen. */
private fun updateApp(message: String) = ApiException(HttpStatusCode.Gone, message)

/** Runs [block] with a fresh join code and its hash, trying another code if the join id is taken. */
private fun <T> ApiContext.withNewJoinCode(block: (Codes.JoinCode, String) -> T): Pair<Codes.JoinCode, T> {
    repeat(5) {
        val code = Codes.newJoinCode()
        try {
            return code to block(code, secrets.hash(code.secret))
        } catch (_: HouseholdStore.JoinIdTaken) {
            // Another household has this join id: try another.
        }
    }
    error("No free join id")
}

private fun memberId(call: ApplicationCall): UUID = uuidParam(call, "member")

private fun uuidParam(call: ApplicationCall, what: String): UUID =
    call.parameters["id"]?.let(::parseUuid) ?: badRequest("Bad $what id.")

private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

/** An approved phone making a request: member-scoped requests act as [memberId]. */
private data class Caller(val household: HouseholdRow, val memberId: UUID, val deviceId: UUID)

/** The household of an approved phone. Pending phones are refused. */
private fun ApiContext.household(call: ApplicationCall): HouseholdRow = member(call).household

/** The household, member and device of an approved phone. */
private fun ApiContext.member(call: ApplicationCall): Caller {
    val session = authenticate(call)
    val memberId = session.memberId?.takeIf { session.approved } ?: throw notApproved()
    val household = store.getHousehold(session.householdId) ?: throw unauthorized()
    return Caller(household, memberId, session.deviceId)
}

/** Tells every other phone in the household to sync. The caller's own phone already has the change. */
private fun ApiContext.pushOthers(caller: Caller) {
    push?.household(caller.household.id, exceptDevice = caller.deviceId)
}

private fun notApproved() = ApiException(HttpStatusCode.Forbidden, "This phone is waiting for the admin to approve it.")

private fun notYours() = ApiException(HttpStatusCode.Forbidden, "This phone can only act as its own member.")

/** Today's date in the household timezone; chore completions are per day there, not per UTC day. */
private fun ApiContext.today(household: HouseholdRow): LocalDate = LocalDate.ofInstant(clock(), ZoneId.of(household.timezone))

private suspend fun ApplicationCall.respondChores(result: ChoreStore.Result, api: ApiContext? = null, caller: Caller? = null) = when (result) {
    is ChoreStore.Result.Ok -> {
        if (api != null && caller != null) api.pushOthers(caller)
        respond(result.snapshot)
    }
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

private fun validPin(pin: String) {
    if (!pin.matches(Regex("\\d{4,8}"))) badRequest("The admin PIN must be 4 to 8 digits.")
}
