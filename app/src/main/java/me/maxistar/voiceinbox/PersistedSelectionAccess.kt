package me.maxistar.voiceinbox

import android.content.ContentResolver
import android.net.Uri

enum class RequiredDocumentAccess {
    READ,
    WRITE,
}

enum class PersistedSelectionAccessState {
    VALID,
    TEMPORARILY_UNAVAILABLE,
    PERMISSION_REVOKED,
}

data class PersistedSelectionValidation<T>(
    val state: PersistedSelectionAccessState,
    val value: T? = null,
    val error: Throwable? = null,
)

object PersistedSelectionAccessPolicy {
    fun classify(
        validationSucceeded: Boolean,
        hasRequiredPersistedPermission: Boolean,
    ): PersistedSelectionAccessState = when {
        validationSucceeded -> PersistedSelectionAccessState.VALID
        hasRequiredPersistedPermission -> PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE
        else -> PersistedSelectionAccessState.PERMISSION_REVOKED
    }

    fun shouldClearSelection(state: PersistedSelectionAccessState): Boolean =
        state == PersistedSelectionAccessState.PERMISSION_REVOKED
}

open class PersistedSelectionAccess(
    private val resolver: ContentResolver,
) {
    open fun <T> validate(
        uri: Uri,
        requiredAccess: RequiredDocumentAccess,
        validation: () -> T,
    ): PersistedSelectionValidation<T> {
        val result = runCatching(validation)
        val state = PersistedSelectionAccessPolicy.classify(
            validationSucceeded = result.isSuccess,
            hasRequiredPersistedPermission = hasRequiredPersistedPermission(uri, requiredAccess),
        )
        return PersistedSelectionValidation(
            state = state,
            value = result.getOrNull(),
            error = result.exceptionOrNull(),
        )
    }

    private fun hasRequiredPersistedPermission(
        uri: Uri,
        requiredAccess: RequiredDocumentAccess,
    ): Boolean = runCatching {
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && when (requiredAccess) {
                RequiredDocumentAccess.READ -> permission.isReadPermission
                RequiredDocumentAccess.WRITE -> permission.isWritePermission
            }
        }
    }.getOrDefault(false)
}
