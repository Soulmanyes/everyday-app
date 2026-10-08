package app.everyday.server

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** A device's authenticated identity. */
data class DeviceSession(val deviceId: UUID, val householdId: UUID)

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

    /** Creates the one household with its admin member and first device. Returns null if one already exists. */
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
            conn.insertDevice(householdId, deviceTokenHash, deviceLabel)
            conn.household(householdId)!! to conn.members(householdId)
        }
    } catch (e: SQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) null else throw e
    }

    fun addDevice(householdId: UUID, tokenHash: String, label: String?) = db.transaction { conn ->
        conn.insertDevice(householdId, tokenHash, label)
    }

    /** Looks up a device by token hash and records that it was seen. Null if unknown or revoked. */
    fun authenticate(tokenHash: String): DeviceSession? = db.transaction { conn ->
        conn.prepareStatement(
            "UPDATE device SET last_seen_at = now() WHERE token_hash = ? AND NOT revoked RETURNING id, household_id",
        ).run {
            setString(1, tokenHash)
            executeQuery().use { rs ->
                if (rs.next()) DeviceSession(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java)) else null
            }
        }
    }

    fun revokeDevice(deviceId: UUID) = db.transaction { conn ->
        conn.prepareStatement("UPDATE device SET revoked = TRUE WHERE id = ?").run { setObject(1, deviceId); executeUpdate() }
    }

    fun members(householdId: UUID): List<MemberDto> = db.transaction { conn -> conn.members(householdId) }

    /** Throws [NameTaken] if another member already has this name. */
    fun addMember(householdId: UUID, name: String, color: String?): MemberDto = nameMustBeFree {
        db.transaction { conn -> conn.insertMember(householdId, name, color) }
    }

    /** Null if there is no such member. Throws [NameTaken] if another member already has this name. */
    fun updateMember(householdId: UUID, memberId: UUID, name: String, color: String?): MemberDto? = nameMustBeFree {
        db.transaction { conn ->
            conn.prepareStatement(
                """
                UPDATE member SET display_name = ?, color = ?, updated_at = now()
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
     * Soft-deletes a member and their chore assignments (a soft delete doesn't trigger ON DELETE CASCADE).
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
        }
        deleted
    }

    object NameTaken : RuntimeException("name taken")

    private fun <T> nameMustBeFree(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) throw NameTaken else throw e
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

    private fun Connection.insertMember(householdId: UUID, name: String, color: String?): MemberDto {
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

    private fun Connection.insertDevice(householdId: UUID, tokenHash: String, label: String?) {
        prepareStatement("INSERT INTO device (household_id, token_hash, label) VALUES (?, ?, ?)").run {
            setObject(1, householdId); setString(2, tokenHash); setString(3, label?.take(100)); executeUpdate()
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
