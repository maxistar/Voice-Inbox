package me.maxistar.voiceinbox

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AudioCatalogRepository(
    private val helper: AudioCatalogDatabase,
) {
    fun newEntries(folderUri: String): List<AudioCatalogEntry> =
        query(
            folderUri,
            setOf(AudioFileState.PENDING, AudioFileState.PROCESSING),
            QueryOrder.DISPLAY,
        )

    fun processedEntries(folderUri: String): List<AudioCatalogEntry> =
        query(folderUri, setOf(AudioFileState.PROCESSED, AudioFileState.FAILED), QueryOrder.DISPLAY)

    fun missingEntries(folderUri: String): List<AudioCatalogEntry> =
        query(folderUri, setOf(AudioFileState.MISSING), QueryOrder.DISPLAY)

    fun entry(id: Long): AudioCatalogEntry? {
        helper.readableDatabase.query(
            AudioCatalogDatabase.TABLE_FILES,
            COLUMNS,
            "${AudioCatalogDatabase.COLUMN_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
        ).use { cursor -> return cursor.takeIf(Cursor::moveToFirst)?.toEntry() }
    }

    fun reconcile(folderUri: String, scannedFiles: List<ScannedAudioFile>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val existing = query(database, folderUri, AudioFileState.entries.toSet())
                .associateBy(AudioCatalogEntry::documentUri)
            val seenUris = HashSet<String>()

            scannedFiles.forEach { scanned ->
                require(scanned.folderUri == folderUri)
                seenUris += scanned.documentUri
                val current = existing[scanned.documentUri]
                if (current == null) {
                    database.insertOrThrow(
                        AudioCatalogDatabase.TABLE_FILES,
                        null,
                        scanned.toValues(AudioFileState.PENDING),
                    )
                } else {
                    val nextState = AudioCatalogRules.rediscoveredState(current, scanned)
                    database.update(
                        AudioCatalogDatabase.TABLE_FILES,
                        scanned.toValues(
                            state = nextState,
                            stateBeforeMissing = null,
                            clearOutcome = nextState == AudioFileState.PENDING,
                        ),
                        "${AudioCatalogDatabase.COLUMN_ID} = ?",
                        arrayOf(current.id.toString()),
                    )
                }
            }

            existing.values
                .filter { it.documentUri !in seenUris && it.state != AudioFileState.MISSING }
                .forEach { entry ->
                    database.update(
                        AudioCatalogDatabase.TABLE_FILES,
                        ContentValues().apply {
                            put(AudioCatalogDatabase.COLUMN_STATE, AudioFileState.MISSING.name)
                            put(AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING, entry.state.name)
                        },
                        "${AudioCatalogDatabase.COLUMN_ID} = ?",
                        arrayOf(entry.id.toString()),
                    )
                }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun pendingCount(folderUri: String): Int =
        query(folderUri, setOf(AudioFileState.PENDING), QueryOrder.DISPLAY).size

    fun recoverInterrupted() {
        helper.writableDatabase.update(
            AudioCatalogDatabase.TABLE_FILES,
            ContentValues().apply {
                put(AudioCatalogDatabase.COLUMN_STATE, AudioFileState.PENDING.name)
                putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
                putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
                putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
            },
            "${AudioCatalogDatabase.COLUMN_STATE} = ?",
            arrayOf(AudioFileState.PROCESSING.name),
        )
    }

    fun claimPending(folderUri: String, specificId: Long? = null): AudioCatalogEntry? =
        claim(folderUri, AudioFileState.PENDING, specificId)

    fun claimFailed(folderUri: String, id: Long): AudioCatalogEntry? =
        claim(folderUri, AudioFileState.FAILED, id)

    fun markProcessed(id: Long, processedAtMillis: Long, transcriptText: String) {
        updateOutcome(id, AudioFileState.PROCESSED, null, processedAtMillis, transcriptText)
    }

    fun markFailed(id: Long, message: String) {
        updateOutcome(id, AudioFileState.FAILED, message, null, null)
    }

    fun markPending(id: Long) {
        helper.writableDatabase.update(
            AudioCatalogDatabase.TABLE_FILES,
            ContentValues().apply {
                put(AudioCatalogDatabase.COLUMN_STATE, AudioFileState.PENDING.name)
                putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
                putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
                putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
            },
            "${AudioCatalogDatabase.COLUMN_ID} = ? AND " +
                "${AudioCatalogDatabase.COLUMN_STATE} = ?",
            arrayOf(id.toString(), AudioFileState.PROCESSING.name),
        )
    }

    fun resetForRetry(id: Long): Boolean =
        helper.writableDatabase.update(
            AudioCatalogDatabase.TABLE_FILES,
            ContentValues().apply {
                put(AudioCatalogDatabase.COLUMN_STATE, AudioFileState.PENDING.name)
                putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
                putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
                putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
            },
            "${AudioCatalogDatabase.COLUMN_ID} = ? AND " +
                "${AudioCatalogDatabase.COLUMN_STATE} = ?",
            arrayOf(id.toString(), AudioFileState.FAILED.name),
        ) == 1

    private fun claim(
        folderUri: String,
        requiredState: AudioFileState,
        specificId: Long?,
    ): AudioCatalogEntry? {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val candidate = if (specificId == null) {
                query(database, folderUri, setOf(requiredState), QueryOrder.PROCESSING).firstOrNull()
            } else {
                query(database, folderUri, setOf(requiredState), QueryOrder.PROCESSING)
                    .firstOrNull { it.id == specificId }
            } ?: return null
            val updated = database.update(
                AudioCatalogDatabase.TABLE_FILES,
                ContentValues().apply {
                    put(AudioCatalogDatabase.COLUMN_STATE, AudioFileState.PROCESSING.name)
                    putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
                    putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
                    putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
                },
                "${AudioCatalogDatabase.COLUMN_ID} = ? AND " +
                    "${AudioCatalogDatabase.COLUMN_STATE} = ?",
                arrayOf(candidate.id.toString(), requiredState.name),
            )
            if (updated != 1) return null
            database.setTransactionSuccessful()
            return candidate.copy(state = AudioFileState.PROCESSING, lastError = null)
        } finally {
            database.endTransaction()
        }
    }

    private fun updateOutcome(
        id: Long,
        state: AudioFileState,
        error: String?,
        processedAtMillis: Long?,
        transcriptText: String?,
    ) {
        helper.writableDatabase.update(
            AudioCatalogDatabase.TABLE_FILES,
            ContentValues().apply {
                put(AudioCatalogDatabase.COLUMN_STATE, state.name)
                if (error == null) putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
                else put(AudioCatalogDatabase.COLUMN_LAST_ERROR, error)
                if (processedAtMillis == null) putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
                else put(AudioCatalogDatabase.COLUMN_PROCESSED_AT, processedAtMillis)
                if (transcriptText == null) putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
                else put(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT, transcriptText)
            },
            "${AudioCatalogDatabase.COLUMN_ID} = ?",
            arrayOf(id.toString()),
        )
    }

    private fun query(
        folderUri: String,
        states: Set<AudioFileState>,
        order: QueryOrder,
    ): List<AudioCatalogEntry> = query(helper.readableDatabase, folderUri, states, order)

    private fun query(
        database: SQLiteDatabase,
        folderUri: String,
        states: Set<AudioFileState>,
        order: QueryOrder = QueryOrder.DISPLAY,
    ): List<AudioCatalogEntry> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(",") { "?" }
        val arguments = arrayOf(folderUri) + states.map(AudioFileState::name)
        database.query(
            AudioCatalogDatabase.TABLE_FILES,
            COLUMNS,
            "${AudioCatalogDatabase.COLUMN_FOLDER_URI} = ? AND " +
                "${AudioCatalogDatabase.COLUMN_STATE} IN ($placeholders)",
            arguments,
            null,
            null,
            order.sql,
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toEntry())
            }
        }
    }

    private fun Cursor.toEntry(): AudioCatalogEntry = AudioCatalogEntry(
        id = getLong(getColumnIndexOrThrow(AudioCatalogDatabase.COLUMN_ID)),
        folderUri = getString(getColumnIndexOrThrow(AudioCatalogDatabase.COLUMN_FOLDER_URI)),
        documentUri = getString(getColumnIndexOrThrow(AudioCatalogDatabase.COLUMN_DOCUMENT_URI)),
        displayName = getString(getColumnIndexOrThrow(AudioCatalogDatabase.COLUMN_DISPLAY_NAME)),
        mimeType = nullableString(AudioCatalogDatabase.COLUMN_MIME_TYPE),
        fingerprint = AudioFileFingerprint(
            sizeBytes = nullableLong(AudioCatalogDatabase.COLUMN_SIZE_BYTES),
            modifiedMillis = nullableLong(AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS),
        ),
        state = AudioFileState.valueOf(
            getString(getColumnIndexOrThrow(AudioCatalogDatabase.COLUMN_STATE)),
        ),
        stateBeforeMissing = nullableString(AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING)
            ?.let(AudioFileState::valueOf),
        lastError = nullableString(AudioCatalogDatabase.COLUMN_LAST_ERROR),
        processedAtMillis = nullableLong(AudioCatalogDatabase.COLUMN_PROCESSED_AT),
        transcriptText = nullableString(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT),
    )

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun ScannedAudioFile.toValues(
        state: AudioFileState,
        stateBeforeMissing: AudioFileState? = null,
        clearOutcome: Boolean = false,
    ) = ContentValues().apply {
        put(AudioCatalogDatabase.COLUMN_FOLDER_URI, folderUri)
        put(AudioCatalogDatabase.COLUMN_DOCUMENT_URI, documentUri)
        put(AudioCatalogDatabase.COLUMN_DISPLAY_NAME, displayName)
        if (mimeType == null) putNull(AudioCatalogDatabase.COLUMN_MIME_TYPE)
        else put(AudioCatalogDatabase.COLUMN_MIME_TYPE, mimeType)
        if (fingerprint.sizeBytes == null) putNull(AudioCatalogDatabase.COLUMN_SIZE_BYTES)
        else put(AudioCatalogDatabase.COLUMN_SIZE_BYTES, fingerprint.sizeBytes)
        if (fingerprint.modifiedMillis == null) {
            putNull(AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS)
        } else {
            put(AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS, fingerprint.modifiedMillis)
        }
        put(AudioCatalogDatabase.COLUMN_STATE, state.name)
        if (stateBeforeMissing == null) putNull(AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING)
        else put(AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING, stateBeforeMissing.name)
        if (clearOutcome) {
            putNull(AudioCatalogDatabase.COLUMN_LAST_ERROR)
            putNull(AudioCatalogDatabase.COLUMN_PROCESSED_AT)
            putNull(AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT)
        }
    }

    private enum class QueryOrder(val sql: String) {
        DISPLAY(
            "CASE WHEN ${AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS} IS NULL THEN 1 ELSE 0 END, " +
                "${AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS} DESC, " +
                "${AudioCatalogDatabase.COLUMN_DISPLAY_NAME} COLLATE NOCASE ASC, " +
                "${AudioCatalogDatabase.COLUMN_DOCUMENT_URI} ASC",
        ),
        PROCESSING(
            "CASE WHEN ${AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS} IS NULL THEN 1 ELSE 0 END, " +
                "${AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS} ASC, " +
                "${AudioCatalogDatabase.COLUMN_DISPLAY_NAME} COLLATE NOCASE ASC, " +
                "${AudioCatalogDatabase.COLUMN_DOCUMENT_URI} ASC",
        ),
    }

    companion object {
        private val COLUMNS = arrayOf(
            AudioCatalogDatabase.COLUMN_ID,
            AudioCatalogDatabase.COLUMN_FOLDER_URI,
            AudioCatalogDatabase.COLUMN_DOCUMENT_URI,
            AudioCatalogDatabase.COLUMN_DISPLAY_NAME,
            AudioCatalogDatabase.COLUMN_MIME_TYPE,
            AudioCatalogDatabase.COLUMN_SIZE_BYTES,
            AudioCatalogDatabase.COLUMN_MODIFIED_MILLIS,
            AudioCatalogDatabase.COLUMN_STATE,
            AudioCatalogDatabase.COLUMN_STATE_BEFORE_MISSING,
            AudioCatalogDatabase.COLUMN_LAST_ERROR,
            AudioCatalogDatabase.COLUMN_PROCESSED_AT,
            AudioCatalogDatabase.COLUMN_TRANSCRIPT_TEXT,
        )
    }
}
