package com.tavern.app.backup

import kotlinx.coroutines.sync.Mutex

/** Serializes backup, restore, and core replacement on the shared user-data tree. */
object BackupCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withDataLock(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
