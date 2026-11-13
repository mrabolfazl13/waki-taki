package com.securetalk.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached channel metadata (no message content is ever stored — voice only, no chat). */
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val memberCount: Int,
    val isLocal: Boolean,
    val epoch: Long,
    val isOwner: Boolean,
    val lastActiveAt: Long,
)

@Entity(tableName = "members", primaryKeys = ["channelId", "userId"])
data class MemberEntity(
    val channelId: String,
    val userId: String,
    val deviceId: String,
    val displayName: String,
    val role: String,
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val userId: String,
    val name: String,
    /** X25519 identity public point + Ed25519 verify key, hex-encoded. */
    val identityPub: String,
    val signingPub: String,
    val signedPrekeyPub: String,
    val prekeySignature: String,
    val registeredAt: Long,
)

/** Own identity non-secret fields (private keys live in the Keystore-sealed SecretStore). */
@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val key: String,
    val value: String,
)
