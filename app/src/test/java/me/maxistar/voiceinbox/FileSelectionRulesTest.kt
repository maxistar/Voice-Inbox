package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FileSelectionRulesTest {
    @Test
    fun outputPickerAcceptsPlainTextAndMarkdown() {
        assertArrayEquals(
            arrayOf("text/plain", "text/markdown", "text/x-markdown"),
            FileSelectionRules.outputMimeTypes,
        )
    }

    @Test
    fun outputCreationUsesMarkdownAndSuggestedInboxName() {
        assertEquals("text/markdown", FileSelectionRules.CREATED_OUTPUT_MIME_TYPE)
        assertEquals("Voice Inbox.md", FileSelectionRules.DEFAULT_OUTPUT_FILE_NAME)
    }

    @Test
    fun providerDisplayNameWins() {
        assertEquals("notes.txt", FileSelectionRules.displayName("notes.txt", "fallback"))
    }

    @Test
    fun displayNameFallsBackToPathThenGenericName() {
        assertEquals("fallback.txt", FileSelectionRules.displayName(null, "fallback.txt"))
        assertEquals("document", FileSelectionRules.displayName(null, null))
    }

    @Test
    fun storedOutputIsRestoredOnlyWithAppendAccess() {
        assertEquals("content://output", FileSelectionRules.restoredOutputUri("content://output", true))
        assertNull(FileSelectionRules.restoredOutputUri("content://output", false))
        assertNull(FileSelectionRules.restoredOutputUri(null, true))
    }

    @Test
    fun supportedAudioUsesMimeOrKnownExtensionAndRejectsDirectories() {
        assertEquals(true, FileSelectionRules.isSupportedAudio("audio/wav", "recording.bin"))
        assertEquals(true, FileSelectionRules.isSupportedAudio(null, "recording.M4A"))
        assertEquals(false, FileSelectionRules.isSupportedAudio("text/plain", "notes.txt"))
        assertEquals(
            false,
            FileSelectionRules.isSupportedAudio(
                "vnd.android.document/directory",
                "nested.wav",
            ),
        )
    }
}
