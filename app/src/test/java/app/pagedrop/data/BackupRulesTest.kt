package app.pagedrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Credentials, pins, tokens and browser data must never leave the phone in a backup or device transfer. */
class BackupRulesTest {
    private val required = setOf(
        "sharedpref:${app.pagedrop.security.KindleSecrets.PREFS}.xml",
        "sharedpref:kindle_settings.xml",
        "sharedpref:${app.pagedrop.transfer.ssh.HostKeyStore.PREFS}.xml",
        "sharedpref:${app.pagedrop.server.ServerSettings.PREFS}.xml",
        "sharedpref:${app.pagedrop.server.ServerSecrets.PREFS}.xml",
        "sharedpref:pagedrop_discover_secrets.xml",
        "root:app_webview",
    )

    private fun excludes(file: String, section: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file"))
        val parent = doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
            .map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }.toSet()
    }

    @Test
    fun legacyBackupRules_excludeSecrets() {
        assertTrue(excludes("backup_rules.xml", "full-backup-content").containsAll(required))
    }

    @Test
    fun cloudBackupAndDeviceTransfer_excludeSecrets() {
        val cloud = excludes("data_extraction_rules.xml", "cloud-backup")
        assertTrue(cloud.containsAll(required))
        assertEquals(cloud, excludes("data_extraction_rules.xml", "device-transfer"))
        assertEquals(cloud, excludes("backup_rules.xml", "full-backup-content"))
    }
}
