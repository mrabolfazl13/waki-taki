package com.securetalk.core.network.impl

import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.ChannelInfo
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.InvitePayload
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.MemberRole
import com.securetalk.core.common.model.UserId
import com.securetalk.core.database.dao.ChannelDao
import com.securetalk.core.database.dao.MemberDao
import com.securetalk.core.database.entity.ChannelEntity
import com.securetalk.core.database.entity.MemberEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * [ChannelManager] for LOCAL (offline) mode: channels are coordinated by the
 * local host (which holds no keys, §2.2) and cached in Room on this device.
 * Invites are QR payloads + invite codes generated locally and validated by the
 * host's roster.
 */
class LocalChannelManager(
    private val channelDao: ChannelDao,
    private val memberDao: MemberDao,
    private val ownUserId: () -> String,
    private val ownDisplayName: () -> String,
    private val ownDeviceId: () -> String,
) : ChannelManager {

    override val channels: Flow<List<ChannelInfo>> = channelDao.observeAll().map { list ->
        list.map { it.toInfo() }
    }

    override suspend fun refresh(): Result<Unit> = Result.success(Unit) // Room is the source of truth locally

    override suspend fun createChannel(name: String): Result<ChannelInfo> {
        val id = UUID.randomUUID().toString()
        val info = ChannelInfo(ChannelId(id), name, 1, isLocal = true, epoch = 0, isOwner = true)
        channelDao.upsert(
            ChannelEntity(id, name, 1, true, 0, true, System.currentTimeMillis()),
        )
        memberDao.upsertAll(
            listOf(
                MemberEntity(id, ownUserId(), ownDeviceId(), ownDisplayName(), MemberRole.OWNER.name),
            ),
        )
        return Result.success(info)
    }

    override suspend fun joinChannel(inviteCode: String): Result<ChannelInfo> {
        // Invite payload format: "securetalk://join?c=<code>&ch=<channelId>&n=<name>"
        val parsed = QrPayload.parse(inviteCode)
            ?: return Result.failure(IllegalArgumentException("Invalid local invite payload"))
        val channel = parsed.channelId ?: return Result.failure(IllegalArgumentException("Payload missing channel"))
        val existing = channelDao.get(channel)
        if (existing != null) return Result.success(existing.toInfo())
        val info = ChannelInfo(
            ChannelId(channel),
            parsed.channelName ?: "Local channel",
            1,
            isLocal = true,
        )
        channelDao.upsert(
            ChannelEntity(channel, info.name, 1, true, 0, false, System.currentTimeMillis()),
        )
        memberDao.upsertAll(
            listOf(
                MemberEntity(channel, ownUserId(), ownDeviceId(), ownDisplayName(), MemberRole.MEMBER.name),
            ),
        )
        return Result.success(info)
    }

    override suspend fun leaveChannel(id: ChannelId): Result<Unit> {
        channelDao.delete(id.value)
        memberDao.deleteAllForChannel(id.value)
        return Result.success(Unit)
    }

    override suspend fun deleteChannel(id: ChannelId): Result<Unit> {
        channelDao.delete(id.value)
        memberDao.deleteAllForChannel(id.value)
        return Result.success(Unit)
    }

    override suspend fun createInvite(id: ChannelId): Result<InvitePayload> {
        val channel = channelDao.get(id.value)
            ?: return Result.failure(IllegalArgumentException("Channel not found"))
        val code = UUID.randomUUID().toString().substring(0, 8).uppercase()
        val payload = QrPayload.encode(code, id.value, channel.name)
        return Result.success(InvitePayload(code, payload))
    }

    override suspend fun validateInvite(code: String): Result<Boolean> = Result.success(code.isNotBlank())

    override suspend fun members(id: ChannelId): Result<List<Member>> = try {
        Result.success(
            memberDao.getForChannel(id.value).map {
                Member(UserId(it.userId), DeviceId(it.deviceId), it.displayName, runCatching {
                    MemberRole.valueOf(it.role)
                }.getOrDefault(MemberRole.MEMBER))
            },
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun removeMember(id: ChannelId, userId: DeviceId): Result<Unit> {
        memberDao.delete(id.value, userId.value)
        return Result.success(Unit)
    }

    private fun ChannelEntity.toInfo() =
        ChannelInfo(ChannelId(id), name, memberCount, isLocal, epoch, isOwner)
}

/** QR payload encoding/parsing (ZXing renders it; format defined here). */
object QrPayload {
    private const val PREFIX = "securetalk://join"

    fun encode(code: String, channelId: String?, channelName: String?): String {
        val params = buildList {
            add("c=$code")
            channelId?.let { add("ch=$it") }
            channelName?.let { add("n=${java.net.URLEncoder.encode(it, "UTF-8")}") }
        }
        return "$PREFIX?${params.joinToString("&")}"
    }

    fun parse(payload: String): ParsedInvite? {
        if (!payload.startsWith(PREFIX)) return null
        val query = payload.removePrefix(PREFIX).removePrefix("?")
        val params = query.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null else it.substring(0, idx) to java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }.toMap()
        val code = params["c"] ?: return null
        return ParsedInvite(code, params["ch"], params["n"])
    }

    data class ParsedInvite(val code: String, val channelId: String?, val channelName: String?)
}
