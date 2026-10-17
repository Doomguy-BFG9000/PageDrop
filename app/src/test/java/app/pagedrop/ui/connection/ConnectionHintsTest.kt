package app.pagedrop.ui.connection

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ConnectionHintsTest {

    @Test
    fun authFailure_suggestsBlankPassword() {
        val hint = ConnectionHints.explain(RuntimeException("Auth fail"), 2222)
        assertTrue(hint.fix.contains("blank"))
    }

    @Test
    fun refused_mentionsPort() {
        val hint = ConnectionHints.explain(ConnectException("Connection refused"), 2222)
        assertTrue(hint.title.contains("2222"))
    }

    @Test
    fun refused_tellsUserToOpenKoreaderSshServer() {
        val hint = ConnectionHints.explain(ConnectException("Connection refused"), 2222)
        assertTrue(hint.fix.contains("KOReader"))
        assertTrue(hint.fix.contains("More tools > SSH server"))
    }

    @Test
    fun timeout_suggestsSameWifi() {
        val hint = ConnectionHints.explain(SocketTimeoutException("timeout: socket is not established"), 22)
        assertTrue(hint.fix.contains("Wi-Fi"))
    }

    @Test
    fun unknownError_hasGenericHint() {
        val hint = ConnectionHints.explain(null, 22)
        assertTrue(hint.title.isNotBlank() && hint.fix.isNotBlank())
    }
}
