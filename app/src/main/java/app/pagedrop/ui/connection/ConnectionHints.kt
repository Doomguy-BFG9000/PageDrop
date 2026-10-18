package app.pagedrop.ui.connection

import app.pagedrop.transfer.ssh.HostKeyChangedException

/** Turns raw SSH/network exceptions into a short plain-English explanation and fix. */
object ConnectionHints {

    data class Hint(val title: String, val fix: String)

    fun explain(error: Throwable?, port: Int): Hint {
        val msg = (error?.message ?: error?.javaClass?.simpleName ?: "").lowercase()
        val refused = error is java.net.ConnectException && "unreachable" !in msg && "no route" !in msg
        val timedOut = error is java.net.SocketTimeoutException
        val unknownHost = error is java.net.UnknownHostException
        return when {
            error is HostKeyChangedException ->
                Hint(
                    "Your Kindle's identity changed",
                    "PageDrop stopped because the Kindle at ${error.change.host} presented a different key than before. " +
                        "If you reinstalled KOReader or reset the Kindle, trust the new key when asked and try again."
                )
            refused -> refusedHint(port)
            "address cannot be empty" in msg ->
                Hint("No address entered", "Type your Kindle's IP address, or tap Find my Kindle.")
            "auth fail" in msg || "authentication" in msg || "permission denied" in msg ->
                Hint(
                    "The Kindle rejected the login",
                    "If you set up key login, KOReader may have been reinstalled: turn on its \"Login without password\" " +
                        "for a moment and set up key login again. If \"Login without password\" is on, leave the password blank. " +
                        "With USBNetwork, enter the root password you set."
                )
            "refused" in msg -> refusedHint(port)
            timedOut || "timeout" in msg || "timed out" in msg || "no route" in msg || "unreachable" in msg ->
                Hint(
                    "Can't reach the Kindle",
                    "Wake the Kindle, check that it and this phone are on the same Wi-Fi, and that the IP address is right."
                )
            unknownHost || "unknownhost" in msg || "unresolved" in msg || "unknown host" in msg ->
                Hint("That address isn't valid", "Use the Kindle's IP address, like 192.168.1.42.")
            "algorithm negotiation" in msg || "kex" in msg ->
                Hint("Couldn't agree on encryption with the Kindle", "Update KOReader on the Kindle and try again.")
            else ->
                Hint("Couldn't connect", "Check the address and port, make sure the Kindle is awake and on the same Wi-Fi.")
        }
    }

    private fun refusedHint(port: Int) = Hint(
        "Nothing is listening on port $port",
        if (port == 2222) {
            "Open KOReader on the Kindle and turn on its SSH server (Tools cog > Network > SSH server; on older versions Tools > More tools). " +
                "SSH only runs while KOReader is open. If you use USBNetwork instead, pick port 22."
        } else {
            "Make sure USBNetwork SSH is enabled. If you use KOReader's SSH server, open KOReader, " +
                "turn on Tools cog > Network > SSH server, and pick port 2222."
        }
    )
}
