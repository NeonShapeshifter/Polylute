package com.lostf1sh.pixelplayeross.presentation.viewmodel

internal enum class WritePermissionOwner { LYRICS, METADATA, BATCH_METADATA, BATCH_GENRE }

/** The launcher returns only a Boolean, so exactly one owner may hold it until that result arrives. */
internal class WritePermissionRouter {
    private var pending: WritePermissionOwner? = null

    val isPending: Boolean @Synchronized get() = pending != null

    @Synchronized fun claim(owner: WritePermissionOwner): Boolean {
        if (pending != null) return false
        pending = owner
        return true
    }

    @Synchronized fun take(): WritePermissionOwner? = pending.also { pending = null }
}
