package app.pagedrop.transfer.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleDiscoveryTest {

    @Test
    fun subnetHosts_coversSlash24WithoutSelf() {
        val hosts = KindleDiscovery.subnetHosts("192.168.1.20")
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.20" in hosts)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun subnetHosts_slash22Covers1021Others() {
        val hosts = KindleDiscovery.subnetHosts("192.168.5.20", 22)
        assertEquals(1021, hosts.size)
        assertTrue("192.168.4.1" in hosts)
        assertTrue("192.168.7.254" in hosts)
        assertFalse("192.168.4.0" in hosts)
        assertFalse("192.168.7.255" in hosts)
        assertFalse("192.168.5.20" in hosts)
    }

    @Test
    fun subnetHosts_widerThanSlash22IsCapped() {
        val hosts = KindleDiscovery.subnetHosts("10.1.130.9", 16)
        assertEquals(1021, hosts.size)
        assertTrue("10.1.128.1" in hosts)
        assertTrue("10.1.131.254" in hosts)
        assertFalse("10.1.132.1" in hosts)
    }

    @Test
    fun subnetHosts_smallerSubnetKeepsRealRange() {
        val hosts = KindleDiscovery.subnetHosts("192.168.1.70", 26)
        assertEquals(61, hosts.size)
        assertTrue("192.168.1.65" in hosts)
        assertTrue("192.168.1.126" in hosts)
        assertFalse("192.168.1.64" in hosts)
        assertFalse("192.168.1.127" in hosts)
    }

    @Test
    fun subnetHosts_malformedAddressIsEmpty() {
        assertTrue(KindleDiscovery.subnetHosts("not-an-ip").isEmpty())
        assertTrue(KindleDiscovery.subnetHosts("10.0.0").isEmpty())
        assertTrue(KindleDiscovery.subnetHosts("10.0.0.300").isEmpty())
    }

    @Test
    fun looksLikeKindle_flagsDropbearAndKoreader() {
        assertTrue(KindleDiscovery.looksLikeKindle("SSH-2.0-dropbear_2020.81"))
        assertTrue(KindleDiscovery.looksLikeKindle("SSH-2.0-KOReader"))
        assertFalse(KindleDiscovery.looksLikeKindle("SSH-2.0-OpenSSH_9.6"))
        assertFalse(KindleDiscovery.looksLikeKindle(""))
    }

    @Test
    fun connectTimeout_waitsLongerForKoreaderPort() {
        assertEquals(KindleDiscovery.KOREADER_CONNECT_TIMEOUT_MS, KindleDiscovery.connectTimeoutFor(2222))
        assertEquals(KindleDiscovery.CONNECT_TIMEOUT_MS, KindleDiscovery.connectTimeoutFor(22))
        // A caller asking for an even longer timeout keeps it.
        assertEquals(5_000, KindleDiscovery.connectTimeoutFor(2222, 5_000))
        assertTrue(KindleDiscovery.KOREADER_CONNECT_TIMEOUT_MS >= 2_000)
    }
}
