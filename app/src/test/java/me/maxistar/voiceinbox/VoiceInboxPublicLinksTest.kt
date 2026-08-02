package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInboxPublicLinksTest {
    @Test
    fun publicLinksUseCanonicalVoiceInboxOrigin() {
        assertEquals("https://voiceinbox.simpleditor.org/", VoiceInboxPublicLinks.WEBSITE)
        assertEquals("https://voiceinbox.simpleditor.org/docs/", VoiceInboxPublicLinks.DOCUMENTATION)
        assertEquals("https://voiceinbox.simpleditor.org/legal/", VoiceInboxPublicLinks.LEGAL)
    }
}
