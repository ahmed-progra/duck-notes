package app.duck.notes.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Plants BACKUP_LEGACY_FLAGS, BACKUP_CLOUD_CAPABILITY, BACKUP_PRE28: remove the guarded attribute/exclusion. */
class BackupPrivacyTest {
    private fun root(path: String): Element {
        val file = File("src/main/res/$path")
        assertTrue("Production backup resource is missing: $file", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun children(element: Element, name: String): List<Element> =
        (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            .filter { it.tagName == name }

    @Test fun androidEightDoesNotBackUpThroughAnUnverifiableCloudTransport() {
        val rules = root("xml/backup_rules.xml")
        assertEquals("full-backup-content", rules.tagName)
        assertTrue(children(rules, "include").isEmpty())
        assertEquals(setOf("root", "file", "database", "sharedpref", "external"),
            children(rules, "exclude").onEach { assertEquals(".", it.getAttribute("path")) }
                .map { it.getAttribute("domain") }.toSet())
    }

    @Test fun legacyRulesRequireEncryptedCloudOrDeviceTransferForEveryIncludedPath() {
        val rules = root("xml-v28/backup_rules.xml")
        assertIncludes(rules)
        val perPath = children(rules, "include").groupBy { it.getAttribute("domain") + ":" + it.getAttribute("path") }
        perPath.values.forEach { entries ->
            assertEquals(2, entries.size)
            assertEquals(setOf("clientSideEncryption", "deviceToDeviceTransfer"),
                entries.map { it.getAttribute("requireFlags") }.toSet())
        }
        assertPendingExcluded(rules)
    }

    @Test fun modernRulesGateCloudEncryptionAndPreserveDeviceTransfer() {
        val rules = root("xml/data_extraction_rules.xml")
        val cloud = children(rules, "cloud-backup").single()
        assertEquals("true", cloud.getAttribute("disableIfNoEncryptionCapabilities"))
        val transfer = children(rules, "device-transfer").single()
        assertFalse(transfer.hasAttribute("disableIfNoEncryptionCapabilities"))
        for (section in listOf(cloud, transfer)) {
            assertIncludes(section)
            assertPendingExcluded(section)
        }
    }

    private fun assertIncludes(rules: Element) {
        assertEquals(setOf("database:duck.db", "database:duck.db-wal", "database:duck.db-shm",
            "file:attachments/", "file:datastore/settings.preferences_pb", "file:datastore/welcome.preferences_pb"),
            children(rules, "include").map { it.getAttribute("domain") + ":" + it.getAttribute("path") }.toSet())
    }

    private fun assertPendingExcluded(rules: Element) {
        assertTrue(children(rules, "exclude").any {
            it.getAttribute("domain") == "file" && it.getAttribute("path") == "attachments/.pending/"
        })
    }
}
