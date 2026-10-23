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
    /**
     * Server-wide backstop for codes that match nothing (Phase 9.7): those count per sender in [limiter], and
     * here all together, so guessing spread over many addresses still locks.
     */
    val backstop: AttemptLimiter = AttemptLimiter(maxFailures = 200),
    /** How long an invite to create a household stays valid. */
    val inviteLifetime: java.time.Duration = java.time.Duration.ofDays(7),
    /** One-time invites into a household (Phase 9.5); they last [inviteLifetime] too. */
    val memberInvites: MemberInviteStore = MemberInviteStore(store.db),
    /** Wrong admin PINs, counted in the database with longer lockouts each time (V17). */
    val pins: PinGuard = PinGuard(store.db, clock = clock),
    val calls: CallStore = CallStore(store.db),
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
                validNewPin(req.adminPin)
                val zone = runCatching { ZoneId.of(req.timezone) }.getOrNull() ?: badRequest("Unknown timezone.")
                // An invite from another household's admin, or the server's own setup code.
                val inviteHash = when {
                    !req.inviteCode.isNullOrBlank() -> {
                        // A locked sender is refused before any hashing.
                        api.ensureNotLocked(senderKey(call))
                        val invite = Codes.normalizeInvite(req.inviteCode)
                        api.checkSender(call, invite != null, INVITE_INVALID)
                        Secrets.tokenHash(invite!!)
                    }
                    !req.setupToken.isNullOrBlank() -> {
                        val setupToken = api.setupToken
                            ?: throw ApiException(HttpStatusCode.Forbidden, "Creating a household with a setup code is disabled on this server.")
                        api.checkSender(call, Secrets.constantTimeEquals(req.setupToken.trim(), setupToken), "Wrong setup code.")
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
                        api.checkSender(call, false, INVITE_INVALID)
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
                // A locked sender is refused before any hashing (PBKDF2 is slow on purpose).
                api.ensureNotLocked(senderKey(call))
                // A member invite (Phase 9.5) comes in the same field, so 2.0 apps can already join with one.
                Codes.normalizeMemberInvite(req.joinCode)?.let { invite ->
                    api.joinWithInvite(call, req, Secrets.tokenHash(invite))
                    return@post
                }
                // The public part finds the household; the secret is checked against its hash. Wrong codes count against
                // the sender, the backstop and (a real join id) the household; those locks never refuse a working code.
                val code = Codes.parseJoinCode(req.joinCode)
                val household = code?.let { api.store.householdByJoinId(it.joinId) }
                if (household?.codeHash == null || !api.secrets.verify(code!!.secret, household.codeHash)) api.wrongJoinCode(call, household)
                val token = api.secrets.newToken()
                // The admin on a new phone (or after reinstalling): nobody else could approve it.
                if (!req.adminPin.isNullOrEmpty()) {
                    val adminId = household.adminMemberId ?: throw ApiException(HttpStatusCode.Conflict, "The household has no admin.")
                    api.checkPin(household, PinGuard.joinKey(household.id), req.adminPin)
                    api.store.addAdminDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, adminId)
                    call.respond(SessionResponse(token, household.toDto(), api.store.members(household.id), memberId = adminId.toString()))
                    return@post
                }
                val name = validName(req.name, "Your name", 40)
                try {
                    api.store.addPendingDevice(household.id, Secrets.tokenHash(token), req.deviceLabel, name)
                } catch (_: HouseholdStore.TooManyPending) {
                    throw tooManyPending()
                }
                api.push?.admin(household.id)
                // No member names until the admin approves the phone.
                call.respond(SessionResponse(token, household.toDto(), emptyList(), deviceStatus = "pending"))
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
                    // A pending phone sees no names until the admin approves it.
                    members = if (session.approved) api.store.members(household.id) else emptyList(),
                    deviceStatus = if (session.approved) "approved" else "pending",
                    memberId = session.memberId?.toString(),
                    pendingRequestIds = if (isAdmin) api.store.joinRequests(household.id).map { it.id } else emptyList(),
                    hasJoinCode = household.codeHash != null,
                    pinLockedAt = household.pinLockedAt?.takeIf { isAdmin }?.toEpochMilli(),
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

            /** The household's member invites that aren't used, cancelled or expired, newest first. */
            get("/member-invites") {
                val household = api.requireAdmin(call).household
                call.respond(MemberInvitesResponse(api.memberInvites.listOpen(household.id, api.clock())))
            }

            /** A one-time invite into the household, shared as a link. The code is shown once. */
            post("/member-invites") {
                val household = api.requireAdmin(call).household
                val now = api.clock()
                val code = Codes.newMemberInvite()
                val invite = api.memberInvites.create(household.id, Secrets.tokenHash(Codes.normalizeMemberInvite(code)!!), now, now.plus(api.inviteLifetime))
                    ?: throw ApiException(HttpStatusCode.Conflict, "This household has too many open invites. Cancel some in Manage members.")
                call.respond(HttpStatusCode.Created, CreatedInviteResponse(invite, code))
            }

            delete("/member-invites/{id}") {
                val household = api.requireAdmin(call).household
                if (!api.memberInvites.cancel(household.id, uuidParam(call, "invite"))) throw ApiException(HttpStatusCode.NotFound, "No such open invite.")
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
                    api.store.updateMember(household.id, id, validName(req.displayName, "Name", 40), req.color?.let(::validColor), req.canCall)
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
            val (household, me) = api.member(call)
            call.respond(api.chores.snapshot(household.id, me, api.today(household)))
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
            call.respondChores(api.chores.upsert(household.id, id, valid, assignees, caller.memberId, api.today(household)), api, caller)
        }

        delete("/chores/{id}") {
            val caller = api.member(call)
            val household = caller.household
            call.respondChores(api.chores.delete(household.id, uuidParam(call, "chore"), caller.memberId, api.today(household)), api, caller)
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

        /** Who a call can ring (Phase 12): members with an approved phone. */
        get("/calls/targets") {
            val caller = api.member(call)
            if (!api.canCall(caller)) throw cantCall()
            call.respond(CallTargetsResponse(api.calls.reachable(caller.household.id)))
        }

        /** Rings other members' phones. The push carries only the call's id; their phones fetch the rest. */
        post("/calls") {
            val caller = api.member(call)
            val req = call.receive<StartCallRequest>()
            val message = validName(req.message, "Message", 100)
            val targets = req.memberIds.map { parseUuid(it) ?: badRequest("Bad member id.") }.toSet()
            if (targets.isEmpty()) badRequest("Pick at least one person.")
            val isAdmin = caller.memberId == caller.household.adminMemberId
            when (val result = api.calls.start(caller.household.id, caller.memberId, isAdmin, targets, message, api.clock())) {
                is CallStore.Start.Ok -> {
                    api.push?.ring(caller.household.id, result.targets, UUID.fromString(result.call.id))
                    call.respond(HttpStatusCode.Created, result.call)
                }
                is CallStore.Start.TooSoon -> throw ApiException(HttpStatusCode.TooManyRequests, "You can ring again in ${result.seconds} s.")
                CallStore.Start.NotAllowed -> throw cantCall()
                CallStore.Start.UnknownMember -> badRequest("Unknown member.")
            }
        }

        /** A call the phone's member made or was rung by. */
        get("/calls/{id}") {
            val caller = api.member(call)
            call.respond(api.calls.get(caller.household.id, uuidParam(call, "call"), caller.memberId) ?: throw noSuchCall())
        }

        post("/calls/{id}/answer") {
            val caller = api.member(call)
            val answer = call.receive<AnswerCallRequest>().answer.takeIf { it == "COMING" || it == "CANT" } ?: badRequest("Unknown answer.")
            val result = api.calls.answer(caller.household.id, uuidParam(call, "call"), caller.memberId, answer, api.clock())
                ?: throw noSuchCall()
            // The caller sees the answer; this member's other phones stop ringing.
            val members = setOf(UUID.fromString(result.callerId), caller.memberId)
            api.push?.callAnswered(caller.household.id, members, exceptDevice = caller.deviceId, callId = UUID.fromString(result.id))
            call.respond(result)
        }
    }
}

private fun ApiContext.canCall(caller: Caller): Boolean =
    caller.memberId == caller.household.adminMemberId || store.members(caller.household.id).any { it.id == caller.memberId.toString() && it.canCall }

private fun cantCall() = ApiException(HttpStatusCode.Forbidden, "The admin hasn't let you call. Ask them to turn on Can call for you.")

private fun noSuchCall() = ApiException(HttpStatusCode.NotFound, "No such call, or it's too old to answer.")

private fun unauthorized() = ApiException(HttpStatusCode.Unauthorized, "This phone is not part of the household.")

private fun ApiContext.authenticate(call: ApplicationCall): DeviceSession {
    val header = call.request.headers[HttpHeaders.Authorization] ?: throw unauthorized()
    val token = header.removePrefix("Bearer ").trim()
    if (token.isEmpty() || token == header) throw unauthorized()
    return store.authenticate(Secrets.tokenHash(token)) ?: throw unauthorized()
}

/** Authenticates the device and checks the admin PIN header. Wrong PINs lock only this phone (V17). */
private fun ApiContext.requireAdmin(call: ApplicationCall): Caller {
    val caller = member(call)
    checkPin(caller.household, PinGuard.deviceKey(caller.deviceId), call.request.headers[ADMIN_PIN_HEADER].orEmpty())
    return caller
}

/**
 * Checks the admin PIN against [key]'s lock in [PinGuard]. A wrong PIN counts; when that locks the key, the
 * admin's phones are pushed and learn about it from /me.
 */
private fun ApiContext.checkPin(household: HouseholdRow, key: String, pin: String) {
    pins.lockedForSeconds(key)?.let { throw tooManyAttempts(it) }
    if (pin.isNotEmpty() && secrets.verify(pin, household.adminPinHash)) {
        pins.clear(key)
        return
    }
    if (pins.recordFailure(household.id, key)) push?.admin(household.id)
    throw ApiException(HttpStatusCode.Forbidden, "Wrong admin PIN.")
}

private fun tooManyAttempts(seconds: Long): ApiException {
    val minutes = (seconds + 59) / 60
    val wait = if (minutes > 90) "${(minutes + 30) / 60} h" else "$minutes min"
    return ApiException(HttpStatusCode.TooManyRequests, "Too many wrong attempts. Try again in $wait.")
}

private fun ApiContext.ensureNotLocked(key: String, limiter: AttemptLimiter = this.limiter) {
    limiter.lockedForSeconds(key)?.let { throw tooManyAttempts(it) }
}

/**
 * A join code that doesn't work: counts against the sender and the backstop first (so guessing at a locked
 * household still locks the guesser), then, when its join id is a real household's (a wrong secret), that household.
 */
private fun ApiContext.wrongJoinCode(call: ApplicationCall, household: HouseholdRow?): Nothing {
    ensureSenderNotLocked(call)
    limiter.recordFailure(senderKey(call))
    backstop.recordFailure()
    household?.let { ensureNotLocked(it.id.toString()) }
    household?.let { limiter.recordFailure(it.id.toString()) }
    throw ApiException(HttpStatusCode.Forbidden, WRONG_JOIN_CODE)
}

/** The lock key of the phone sending [call]: codes that match nothing count per sender. */
private fun senderKey(call: ApplicationCall) = "ip:" + clientIp(call)

/** Refuses while this sender, or the whole server (the backstop), is locked out for codes that don't work. */
private fun ApiContext.ensureSenderNotLocked(call: ApplicationCall) {
    ensureNotLocked(senderKey(call))
    ensureNotLocked(AttemptLimiter.GLOBAL, backstop)
}

/**
 * A code that matched nothing counts against the sender and the backstop. The backstop never refuses a code that
 * works, so it can't be used to stop everyone joining; guessing one is hopeless (invites are 60 or 80 random bits).
 */
private fun ApiContext.checkSender(call: ApplicationCall, ok: Boolean, message: String) {
    if (ok) return
    ensureSenderNotLocked(call)
    limiter.recordFailure(senderKey(call))
    backstop.recordFailure()
    throw ApiException(HttpStatusCode.Forbidden, message)
}

/**
 * Joining with a member invite: like the join code, pending until the admin approves, or approved at once as
 * the admin with their PIN. Either way the invite is used up. A bad invite counts globally, like other invites;
 * a wrong PIN counts against the household and leaves the invite usable.
 */
private suspend fun ApiContext.joinWithInvite(call: ApplicationCall, req: JoinRequest, inviteHash: String) {
    val household = memberInvites.find(inviteHash, clock())?.let { store.getHousehold(it.first) }
    checkSender(call, household != null, MEMBER_INVITE_INVALID)
    household!!
    val adminId = if (!req.adminPin.isNullOrEmpty()) {
        val adminId = household.adminMemberId ?: throw ApiException(HttpStatusCode.Conflict, "The household has no admin.")
        checkPin(household, PinGuard.joinKey(household.id), req.adminPin)
        adminId
    } else {
        null
    }
    val name = if (adminId == null) validName(req.name, "Your name", 40) else null
    val token = secrets.newToken()
    try {
        store.joinWithInvite(household.id, inviteHash, Secrets.tokenHash(token), req.deviceLabel, adminId, name, clock())
    } catch (_: HouseholdStore.InviteInvalid) {
        // Someone else used it (or it was cancelled) a moment ago.
        checkSender(call, false, MEMBER_INVITE_INVALID)
    }
    if (adminId != null) {
        call.respond(SessionResponse(token, household.toDto(), store.members(household.id), memberId = adminId.toString()))
    } else {
        push?.admin(household.id)
        // No member names until the admin approves the phone.
        call.respond(SessionResponse(token, household.toDto(), emptyList(), deviceStatus = "pending"))
    }
}

private const val WRONG_JOIN_CODE = "Wrong join code."
private const val MEMBER_INVITE_INVALID = "That invite link isn't valid. It may have been used, cancelled or expired: ask for a new one."
private const val INVITE_INVALID = "That invite isn't valid. It may have been used, cancelled or expired."

private fun tooManyPending() = ApiException(
    HttpStatusCode.Conflict,
    "Too many people are already waiting to join. Ask the admin to approve or turn them down first.",
)

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

/** New PINs need 6 to 8 digits; households made before keep their 4- or 5-digit ones. */
private fun validNewPin(pin: String) {
    if (!pin.matches(Regex("\\d{6,8}"))) badRequest("The admin PIN must be 6 to 8 digits.")
}
