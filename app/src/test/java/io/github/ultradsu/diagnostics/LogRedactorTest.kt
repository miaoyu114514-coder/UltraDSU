package io.github.ultradsu.diagnostics

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    @Test fun removesPathsUrisIdentifiersAndKnownPrivateNames() {
        val filter = LogRedactor()
        filter.remember("my_custom_slot", "family-image.img", "Display name")
        val result = filter.redact("my_custom_slot Display name family-image.img content://provider/private/image\n" +
            "failed /metadata/vold/metadata_encryption/dsu/private/key errno=1\n" +
            "source=C:\\Users\\someone\\Downloads\\private.img\n" +
            "serialno=ABC123 imei:123456 token=abc.def email=user@example.com 192.168.1.5\n" +
            "keyblob=secretvalue 0123456789abcdef0123456789abcdef")
        listOf("my_custom_slot", "family-image", "Display name", "provider", "someone", "private/key", "ABC123", "123456", "abc.def", "user@example", "192.168", "secretvalue", "0123456789abcdef").forEach {
            assertFalse("Leaked $it: $result", result.contains(it))
        }
        assertTrue(result.contains("errno=1"))
    }
    @Test fun keepsUsefulFailureStagesAndCodes() {
        val result = LogRedactor().redact("createPartition userdata error=1 elapsedMs=1550\nread_key failed in mountFstab\n  at io.github.ultradsu.dsu.RootClient.bridge(RootClient.kt:42)")
        assertTrue(result.contains("userdata error=1"))
        assertTrue(result.contains("read_key failed"))
        assertTrue(result.contains("RootClient.kt:42"))
    }
    @Test fun exportCanRedactIdentitiesDiscoveredAfterIngestion() {
        val filter = LogRedactor()
        val old = filter.redact("failed on later_discovered_slot")
        filter.remember("later_discovered_slot")
        assertFalse(filter.redact(old).contains("later_discovered_slot"))
    }
    @Test fun shortSlotNamesAreRedactedWithoutDestroyingWholeWords() {
        val filter = LogRedactor()
        filter.remember("a", "b2")
        val result = filter.redact("slot a failed; slot b2 failed; userdata available")
        assertFalse(result.contains("slot a "))
        assertFalse(result.contains("slot b2 "))
        assertTrue(result.contains("userdata available"))
    }
    @Test(timeout = 2000) fun longDiagnosticLinesWithoutEmailDoNotBacktrackQuadratically() {
        val filter = LogRedactor()
        repeat(10) { assertEquals(15000, filter.redact("x".repeat(15000)).length) }
        assertFalse(filter.redact("x".repeat(15000) + "@example.com").contains("example.com"))
    }
}
