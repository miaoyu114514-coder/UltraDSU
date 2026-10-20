package io.github.ultradsu.dsu

import org.junit.Assert.*
import org.junit.Test

class OperationProgressTest {
    @Test fun `bridge preserves exact byte counts instead of rounded MiB`() {
        val progress = OperationProgress.fromBridge("""{"message":"写入 system","bytes":8388609,"totalBytes":16777218}""")
        assertEquals("写入 system", progress.message)
        assertEquals(8388609L, progress.bytes)
        assertEquals(0.5f, progress.fraction!!, 0f)
    }
    @Test fun `allocation text has no invented percentage`() {
        val progress = OperationProgress.fromBridge("分配 userdata，可能需要数分钟")
        assertNull(progress.fraction)
        assertEquals("分配 userdata，可能需要数分钟", progress.message)
    }
    @Test fun `invalid or exceeded totals stay indeterminate`() {
        assertNull(OperationProgress("读取", 10, 0).fraction)
        assertNull(OperationProgress("读取", 11, 10).fraction)
        assertNull(OperationProgress("读取", -1, 10).fraction)
        assertEquals(0f, OperationProgress("读取", 0, 10).fraction!!, 0f)
    }
    @Test fun `large images do not overflow the percentage`() {
        val total = 512L * 1024 * 1024 * 1024
        assertEquals(0.75f, OperationProgress("写入", total * 3 / 4, total).fraction!!, 0f)
    }
    @Test fun `failed and cancelled tasks do not become successful completion`() {
        val active = PreparationState(busy = true, bytes = 20, totalBytes = 100)
        assertEquals(0.2f, active.copy(busy = false, outcome = TaskOutcome.FAILED).fraction!!, 0f)
        assertEquals(0.2f, active.copy(busy = false, outcome = TaskOutcome.CANCELLED).fraction!!, 0f)
        assertEquals(1f, active.copy(busy = false, outcome = TaskOutcome.SUCCESS).fraction!!, 0f)
    }
}
