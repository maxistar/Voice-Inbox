package me.maxistar.voiceinbox.core

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import me.maxistar.voiceinbox.db.VoiceInboxDatabase

class AndroidSqlDelightAudioCatalogFactory(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    fun create(databaseName: String = DATABASE_NAME): SqlDelightAudioCatalogRepository =
        SqlDelightAudioCatalogRepository(
            AndroidSqliteDriver(
                schema = VoiceInboxDatabase.Schema,
                context = applicationContext,
                name = databaseName,
            ),
        )

    companion object {
        const val DATABASE_NAME = "audio-catalog.db"
    }
}
