package app.everyday.server

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** A device's authenticated identity. [memberId] is set once the admin has approved the phone. */
data class DeviceSession(val deviceId: UUID, val householdId: UUID, val memberId: UUID?, val approved: Boolean)

data class HouseholdRow(
    val id: UUID,
    val name: String,
    val timezone: String,
    val codeHash: String,
    val adminMemberId: UUID?,
    val adminPinHash: String,
) {
    fun toDto() = HouseholdDto(id.toString(), name, timezone, adminMemberId?.toString())
}

/** SQL for households, members and devices. Every method runs in its own transaction. */
class HouseholdStore(private val db: DataSource) {

    fun householdExists(): Boolean = db.transaction { conn ->
        conn.prepareStatement("SELECT 1 FROM household").executeQuery().use { it.next() }
    }

    fun getHousehold(): HouseholdRow? = db.transaction { conn -> conn.household() }

    fun getHousehold(id: UUID): HouseholdRow? = db.transaction { conn -> conn.household(id) }

    /** Creates a household with its admin member and first device. Returns null on a unique violation. */
    fun createHousehold(
        name: String,
        timezone: String,
        codeHash: String,
        adminName: String,
        adminPinHash: String,
        deviceTokenHash: String,
        deviceLabel: String?,
    ): Pair<HouseholdRow, List<MemberDto>>? = try {
        db.transaction { conn ->
            val householdId = conn.prepareStatement(
                "INSERT INTO household (name, timezone, code_hash, admin_pin_hash) VALUES (?, ?, ?, ?) RETURNING id",
            ).run {
                setString(1, name); setString(2, timezone); setString(3, codeHash); setString(4, adminPinHash)
                executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
            }
            val admin = conn.insertMember(householdId, adminName, null)
            conn.prepareStatement("UPDATE household SET admin_member_id = ? WHERE id = ?").run {
                setObject(1, UUID.fromString(admin.id)); setObject(2, householdId); executeUpdate()
            }
            // The creator's phone belongs to the admin from the start.
            conn.insertDevice(householdId, deviceTokenHash, deviceLabel, UUID.fromString(admin.id), name = null)
            conn.household(householdId)!! to conn.members(householdId)
        }
    } catch (e: SQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) null else throw e
    }

    /** The admin's own new phone, approved at once (the admin PIN was checked). */
    fun addAdminDevice(householdId: UUID, tokenHash: String, label: String?, adminId: UUID) = db.transaction { conn ->
        conn.insertDevice(householdId, tokenHash, label, adminId, name = null)
    }

    /** A joining phone: pending until the admin approves it as [name] or links it to a member. */
    fun addPendingDevice(householdId: UUID, tokenHash: String, label: String?, name: String) = db.transaction { conn ->
        conn.insertDevice(householdId, tokenHash, label, memberId = null, name = name)
    }

    fun joinRequests(householdId: UUID): List<JoinRequestDto> = db.transaction { conn ->
        conn.prepareStatement(
            "SELECT id, requested_name, label, created_at FROM device WHERE household_id = ? AND status = 'pending' AND NOT revoked ORDER BY created_at",
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            JoinRequestDto(
                                id = rs.getString(1),
                                name = rs.getString(2).orEmpty(),
                                deviceLabel = rs.getString(3),
                                createdAt = rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant().toEpochMilli(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Approves a pending phone: as a new member with the requested name ([memberId] null), or linked to
     * an existing member. Returns the member, or null if there's no such request or member.
     * Throws [NameTaken] if a new member's name is already in use.
     */
    fun approve(householdId: UUID, deviceId: UUID, memberId: UUID?): MemberDto? = nameMustBeFree {
        db.transaction { conn ->
            val name = conn.prepareStatement(
                "SELECT requested_name FROM device WHERE id = ? AND household_id = ? AND status = 'pending' AND NOT revoked FOR UPDATE",
            ).run {
                setObject(1, deviceId); setObject(2, householdId)
                executeQuery().use { rs -> if (rs.next()) rs.getString(1).orEmpty() else null }
            } ?: return@transaction null
            val member = if (memberId == null) {
                conn.insertMember(householdId, name.ifBlank { "New member" }, null)
            } else {
                conn.members(householdId).firstOrNull { it.id == memberId.toString() } ?: return@transaction null
            }
            conn.prepareStatement("UPDATE device SET status = 'approved', member_id = ? WHERE id = ?").run {
                setObject(1, UUID.fromString(member.id)); setObject(2, deviceId); executeUpdate()
            }
            member
        }
    }

    /** Rejects a pending phone: its token stops working. Returns false if there was no such request. */
    fun reject(householdId: UUID, deviceId: UUID): Boolean = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE device SET revoked = TRUE, push_token = NULL WHERE id = ? AND household_id = ? AND status = 'pending' AND NOT revoked",
        ).run { setObject(1, deviceId); setObject(2, householdId); executeUpdate() > 0 }
    }

    /** Looks up a device by token hash and records that it was seen. Null if unknown or revoked. */
    fun authenticate(tokenHash: String): DeviceSession? = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE device SET last_seen_at = now() WHERE token_hash = ? AND NOT revoked RETURNING id, household_id, member_id, status",
        ).run {
            setString(1, tokenHash)
            executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    DeviceSession(
                        deviceId = rs.getObject(1, UUID::class.java),
                        householdId = rs.getObject(2, UUID::class.java),
                        memberId = rs.getObject(3, UUID::class.java),
                        approved = rs.getString(4) == "approved",
                    )
                }
            }
        }
    }

    fun revokeDevice(deviceId: UUID) = db.transaction { conn ->
        conn.prepareStatement("UPDATE device SET revoked = TRUE, push_token = NULL WHERE id = ?").run { setObject(1, deviceId); executeUpdate() }
    }

    fun members(householdId: UUID): List<MemberDto> = db.transaction { conn -> conn.members(householdId) }

    /** Throws [NameTaken] if another member already has this name. */
    fun addMember(householdId: UUID, name: String, color: String?): MemberDto = nameMustBeFree {
        db.transaction { conn -> conn.insertMember(householdId, name, color) }
    }

    /**
     * Null if there is no such member. [color] null keeps the current colour. Throws [NameTaken] or
     * [ColorTaken] if another member already has this name or colour.
     */
    fun updateMember(householdId: UUID, memberId: UUID, name: String, color: String?): MemberDto? = nameMustBeFree {
        db.transaction { conn ->
            conn.prepareStatement(
                """
                UPDATE member SET display_name = ?, color = coalesce(?, color), updated_at = now()
                WHERE id = ? AND household_id = ? AND NOT deleted
                RETURNING id, display_name, color
                """.trimIndent(),
            ).run {
                setString(1, name); setString(2, color); setObject(3, memberId); setObject(4, householdId)
                executeQuery().use { rs -> if (rs.next()) rs.toMember() else null }
            }
        }
    }

    /**
     * Soft-deletes a member (a soft delete doesn't trigger ON DELETE CASCADE) with their chore assignments,
     * their reminders, their place on others' events, and their events except shared ones that members
     * still show (those live on read-only).
     * Returns false if there was no such member.
     */
    fun deleteMember(householdId: UUID, memberId: UUID): Boolean = db.transaction { conn ->
        val deleted = conn.prepareStatement(
            "UPDATE member SET deleted = TRUE, updated_at = now() WHERE id = ? AND household_id = ? AND NOT deleted",
        ).run { setObject(1, memberId); setObject(2, householdId); executeUpdate() > 0 }
        if (deleted) {
            conn.prepareStatement("UPDATE chore_assignment SET deleted = TRUE, updated_at = now() WHERE member_id = ? AND NOT deleted").run {
                setObject(1, memberId); executeUpdate()
            }
            conn.prepareStatement("UPDATE event_member SET deleted = TRUE, updated_at = now() WHERE member_id = ? AND NOT deleted").run {
                setObject(1, memberId); executeUpdate()
            }
            conn.prepareStatement("UPDATE reminder SET deleted = TRUE, updated_at = now() WHERE owner_member_id = ? AND NOT deleted").run {
                setObject(1, memberId); executeUpdate()
            }
            // Their phones return to the join screen.
            conn.prepareStatement("UPDATE device SET revoked = TRUE, push_token = NULL WHERE member_id = ? AND NOT revoked").run {
                setObject(1, memberId); executeUpdate()
            }
            conn.deleteOrphanedEvents()
        }
        deleted
    }

    /** A member picks their own colour. Null if there is no such member; throws [ColorTaken] if it's in use. */
    fun setColor(householdId: UUID, memberId: UUID, color: String): MemberDto? = nameMustBeFree {
        db.transaction { conn ->
            conn.prepareStatement(
                "UPDATE member SET color = ?, updated_at = now() WHERE id = ? AND household_id = ? AND NOT deleted RETURNING id, display_name, color",
            ).run {
                setString(1, color); setObject(2, memberId); setObject(3, householdId)
                executeQuery().use { rs -> if (rs.next()) rs.toMember() else null }
            }
        }
    }

    object NameTaken : RuntimeException("name taken")

    object ColorTaken : RuntimeException("colour taken")

    /** Turns unique violations into [NameTaken] or [ColorTaken], depending on the index that was hit. */
    private fun <T> nameMustBeFree(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        when {
            e.sqlState != UNIQUE_VIOLATION -> throw e
            e.message.orEmpty().contains("member_color_unique") -> throw ColorTaken
            else -> throw NameTaken
        }
    }

    private fun Connection.household(id: UUID? = null): HouseholdRow? {
        val sql = "SELECT id, name, timezone, code_hash, admin_member_id, admin_pin_hash FROM household" +
            if (id != null) " WHERE id = ?" else ""
        return prepareStatement(sql).run {
            if (id != null) setObject(1, id)
            executeQuery().use { rs ->
                if (!rs.next()) null else HouseholdRow(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    timezone = rs.getString("timezone"),
                    codeHash = rs.getString("code_hash"),
                    adminMemberId = rs.getObject("admin_member_id", UUID::class.java),
                    adminPinHash = rs.getString("admin_pin_hash"),
                )
            }
        }
    }

    private fun Connection.members(householdId: UUID): List<MemberDto> =
        prepareStatement(
            "SELECT id, display_name, color FROM member WHERE household_id = ? AND NOT deleted ORDER BY created_at",
        ).run {
            setObject(1, householdId)
            executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMember()) } }
        }

    /** New members get [color] if given, else the first palette colour nobody in the household has. */
    private fun Connection.insertMember(householdId: UUID, name: String, requested: String?): MemberDto {
        val used = prepareStatement("SELECT color FROM member WHERE household_id = ? AND NOT deleted AND color IS NOT NULL").run {
            setObject(1, householdId)
            executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        val color = requested ?: MEMBER_COLORS.firstOrNull { it !in used }
        val member = prepareStatement(
            "INSERT INTO member (household_id, display_name, color) VALUES (?, ?, ?) RETURNING id, display_name, color",
        ).run {
            setObject(1, householdId); setString(2, name); setString(3, color)
            executeQuery().use { it.next(); it.toMember() }
        }
        prepareStatement("INSERT INTO member_settings (member_id) VALUES (?)").run {
            setObject(1, UUID.fromString(member.id)); executeUpdate()
        }
        return member
    }

    /** A device with [memberId] is approved as that member; without one it's a pending request named [name]. */
    private fun Connection.insertDevice(householdId: UUID, tokenHash: String, label: String?, memberId: UUID?, name: String?) {
        prepareStatement(
            "INSERT INTO device (household_id, token_hash, label, member_id, status, requested_name) VALUES (?, ?, ?, ?, ?, ?)",
        ).run {
            setObject(1, householdId); setString(2, tokenHash); setString(3, label?.take(100)); setObject(4, memberId)
            setString(5, if (memberId != null) "approved" else "pending"); setString(6, name)
            executeUpdate()
        }
    }

    private fun ResultSet.toMember() = MemberDto(
        id = getObject("id", UUID::class.java).toString(),
        displayName = getString("display_name"),
        color = getString("color"),
    )

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
