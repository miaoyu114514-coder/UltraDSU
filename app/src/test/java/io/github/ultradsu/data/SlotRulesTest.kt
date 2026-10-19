package io.github.ultradsu.data

import org.junit.Assert.*
import org.junit.Test

class SlotRulesTest {
    @Test fun `Android 10 only permits the legacy slot`() {
        assertNull(SlotRules.validate("dsu", "8", 29, emptyList()))
        assertNotNull(SlotRules.validate("pixel", "8", 29, emptyList()))
        assertNull(SlotRules.validate("pixel", "8", 30, emptyList()))
    }
    @Test fun `slot identifiers cannot traverse paths or inject commands`() {
        listOf("", "../dsu", "x/y", ".", "a b", "x;reboot", "x\nreboot", "-x", "槽一", "x".repeat(33)).forEach {
            assertFalse(it, SlotRules.validName(it))
        }
        listOf("dsu", "Pixel_16", "gsi-a", "1", "x".repeat(32)).forEach { assertTrue(it, SlotRules.validName(it)) }
    }
    @Test fun `duplicate slots are rejected independently of display names`() {
        val old = DsuSlot(slotName = "pixel", displayName = "My Android")
        assertNotNull(SlotRules.validate("pixel", "8", 30, listOf(old)))
        assertNull(SlotRules.validate("pixel_2", "8", 30, listOf(old)))
    }
    @Test fun `userdata input is bounded and never overflows a 32 bit integer`() {
        listOf("0", "-1", "513", "8.5", "NaN", "9999999999999999").forEach {
            assertNotNull(SlotRules.validate("dsu", it, 30, emptyList()))
        }
        val slot = DsuSlot(slotName = "test", displayName = "", userdataGiB = 512)
        assertEquals(549755813888L, slot.userdataBytes)
    }
    @Test fun `handoff carries named slot and long userdata sizes only on supported versions`() {
        val slot = DsuSlot(slotName = "test_gsi", displayName = "", userdataGiB = 16, systemBytes = 5 * GIB)
        val command = Shell.install("content://io.github.ultradsu.images/prepared/x.img.gz", slot, 30)
        assertTrue(command.contains("--es KEY_DSU_SLOT 'test_gsi'"))
        assertTrue(command.contains("--el KEY_USERDATA_SIZE 17179869184"))
        assertTrue(command.contains("--el KEY_SYSTEM_SIZE 5368709120"))
        assertFalse(Shell.install("content://image", slot, 29).contains("KEY_DSU_SLOT"))
        assertFalse(command.contains("--ez KEY_ENABLE_WHEN_COMPLETED true"))
    }
    @Test fun `shell quoting keeps hostile text inside one argument`() {
        assertEquals("'a'\\''; reboot; echo '\\'''", Shell.quote("a'; reboot; echo '"))
    }
}
