package com.securetalk.core.network.impl

import com.securetalk.core.common.domain.ChannelManager
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.ChannelInfo
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.InvitePayload
import com.securetalk.core.common.model.Member
import com.securetalk.core.common.model.MemberRole
import com.securetalk.core.common.model.PresenceState
import com.securetalk.core.common.model.UserId
import com.securetalk.core.database.dao.ChannelDao
import com.securetalk.core.database.dao.MemberDao
import com.securetalk.core.database.entity.ChannelEntity
import com.securetalk.core.database.entity.MemberEntity
import com.securetalk.core.network.api.SecureTalkApi
import com.securetalk.core.network.api.CreateChannelRequest
import com.securetalk.core.network.api.JoinRequest
import com.securetalk.core.network.api.ValidateInviteRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

private fun parseRole(raw: String): MemberRole = when (raw.lowercase()) {
    "owner" -> MemberRole.OWNER
    "admin" -> MemberRole.ADMIN
    else -> MemberRole.MEMBER
}

/**
 * [ChannelManager] implementation for ONLINE mode: REST (§5) + Room cache.
 * The UI reads channels/members from Room, refreshed from the API.
 */
class ChannelManagerImpl(
    private val api: SecureTalkApi,
    private val channelDao: ChannelDao,
    private val memberDao: MemberDao,
    private val ownUserId: () -> String,
    private val isLocalMode: () -> Boolean = { false },
) : ChannelManager {

    override val channels: Flow<List<ChannelInfo>> = channelDao.observeAll().map { list ->
        list.map { it.toInfo() }
    }

    private val membersCache = MutableStateFlow<Map<String, List<Member>>>(emptyMap())

    override suspend fun refresh(): Result<Unit> = try {
        val channels = api.listChannels()
        channelDao.upsertAll(
            channels.map {
                ChannelEntity(
                    id = it.id,
                    name = it.name,
                    memberCount = it.member_count,
                    isLocal = false,
                    epoch = it.epoch,
                    isOwner = it.owner_id == ownUserId(),
                    lastActiveAt = System.currentTimeMillis(),
                )
            },
        )
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun createChannel(name: String): Result<ChannelInfo> = try {
        val created = api.createChannel(CreateChannelRequest(name))
        val info = ChannelInfo(
            id = ChannelId(created.id),
            name = created.name,
            memberCount = created.member_count,
            isLocal = false,
            epoch = created.epoch,
            isOwner = created.owner_id == ownUserId(),
        )
        channelDao.upsert(
            ChannelEntity(info.id.value, info.name, info.memberCount, false, info.epoch, info.isOwner, System.currentTimeMillis()),
        )
        Result.success(info)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun joinChannel(inviteCode: String): Result<ChannelInfo> = try {
        // invites/validate first, then join by code
        val validated = api.validateInvite(ValidateInviteRequest(inviteCode))
        if (!validated.valid) {
            return Result.failure(IllegalArgumentException("Invite invalid or expired"))
        }
        val channelId = validated.channel_id
            ?: return Result.failure(IllegalArgumentException("Invite missing channel"))
        val joined = api.joinChannel(channelId, JoinRequest(inviteCode))
        val info = ChannelInfo(ChannelId(joined.id), joined.name, joined.member_count, false, joined.epoch, false)
        channelDao.upsert(
            ChannelEntity(info.id.value, info.name, info.memberCount, false, info.epoch, info.isOwner, System.currentTimeMillis()),
        )
        Result.success(info)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun leaveChannel(id: ChannelId): Result<Unit> = try {
        api.leaveChannel(id.value)
        channelDao.delete(id.value)
        memberDao.deleteAllForChannel(id.value)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun deleteChannel(id: ChannelId): Result<Unit> = try {
        val response = api.deleteChannel(id.value)
        if (response.isSuccessful) {
            channelDao.delete(id.value)
            memberDao.deleteAllForChannel(id.value)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Delete failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun createInvite(id: ChannelId): Result<InvitePayload> = try {
        val invite = api.createInvite(id.value)
        Result.success(InvitePayload(invite.invite_code, invite.qr_payload))
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun validateInvite(code: String): Result<Boolean> = try {
        Result.success(api.validateInvite(ValidateInviteRequest(code)).valid)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun members(id: ChannelId): Result<List<Member>> = try {
        val remote = api.channelMembers(id.value).map {
            Member(
                userId = UserId(it.user_id),
                deviceId = DeviceId(it.device_id),
                displayName = it.display_name,
                role = parseRole(it.role),
            )
        }
        memberDao.deleteAllForChannel(id.value)
        memberDao.upsertAll(
            remote.map {
                MemberEntity(id.value, it.userId.value, it.deviceId.value, it.displayName, it.role.name)
            },
        )
        membersCache.value = membersCache.value + (id.value to remote)
        Result.success(remote)
    } catch (e: Exception) {
        // offline: fall back to cache
        val cached = memberDao.getForChannel(id.value).map {
            Member(UserId(it.userId), DeviceId(it.deviceId), it.displayName, parseRole(it.role))
        }
        Result.success(cached)
    }

    override suspend fun removeMember(id: ChannelId, userId: DeviceId): Result<Unit> = try {
        val response = api.removeMember(id.value, userId.value)
        if (response.isSuccessful) {
            memberDao.delete(id.value, userId.value)
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Remove failed: ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun ChannelEntity.toInfo() = ChannelInfo(
        id = ChannelId(id),
        name = name,
        memberCount = memberCount,
        isLocal = isLocal,
        epoch = epoch,
        isOwner = isOwner,
    )

    companion object {
        fun presenceOf(state: String): PresenceState = when (state) {
            "speaking" -> PresenceState.SPEAKING
            "online" -> PresenceState.ONLINE
            "idle" -> PresenceState.IDLE
            else -> PresenceState.OFFLINE
        }
    }
}
