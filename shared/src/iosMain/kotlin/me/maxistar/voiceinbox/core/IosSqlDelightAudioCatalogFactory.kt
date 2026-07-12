package me.maxistar.voiceinbox.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import me.maxistar.voiceinbox.db.VoiceInboxDatabase

class IosSqlDelightAudioCatalogFactory {
    fun create(databaseName: String): SqlDelightAudioCatalogRepository =
        SqlDelightAudioCatalogRepository(
            NativeSqliteDriver(
                schema = VoiceInboxDatabase.Schema,
                name = databaseName,
            ),
        )
}
