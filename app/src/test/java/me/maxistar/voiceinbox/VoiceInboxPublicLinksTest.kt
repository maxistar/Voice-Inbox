package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInboxPublicLinksTest {
    @Test
    fun settingsLinksUseCanonicalVoiceInboxOrigin() {
        assertEquals("https://voiceinbox.simpleditor.org/", VoiceInboxPublicLinks.WEBSITE)
        assertEquals("https://voiceinbox.simpleditor.org/legal/", VoiceInboxPublicLinks.LEGAL)
    }
}
