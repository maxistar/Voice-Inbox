package me.maxistar.voiceinbox.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import me.maxistar.voiceinbox.db.VoiceInboxDatabase
import platform.posix.remove

class IosSqlDelightAudioCatalogFactory {
    fun create(databaseName: String): SqlDelightAudioCatalogRepository {
        val repository = createRepository(databaseName)
        try {
            repository.pendingCount(HEALTH_CHECK_FOLDER)
            return repository
        } catch (_: Throwable) {
            repository.close()
            deleteDatabaseFiles(databaseName)
            return createRepository(databaseName)
        }
    }

    private fun createRepository(databaseName: String): SqlDelightAudioCatalogRepository =
        SqlDelightAudioCatalogRepository(
            NativeSqliteDriver(
                schema = VoiceInboxDatabase.Schema,
                name = databaseName,
            ),
        )

    private fun deleteDatabaseFiles(databaseName: String) {
        remove(databaseName)
        remove("$databaseName-wal")
        remove("$databaseName-shm")
        remove("$databaseName-journal")
    }

    private companion object {
        const val HEALTH_CHECK_FOLDER = "__voice_inbox_health_check__"
    }
}
