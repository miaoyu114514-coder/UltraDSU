package io.github.ultradsu.dsu

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class OperationCancellationTest {
    @Test fun earlyRequestReachesTheLaterProcessAndCannotBeLost() {
        val signal = OperationCancellation()
        signal.request()
        var calls = 0
        signal.attach { calls++ }
        signal.request()
        assertEquals(1, calls)
        assertThrows(CancellationException::class.java) { signal.check() }
    }
    @Test fun detachedProcessDoesNotReceiveLaterRequests() {
        val signal = OperationCancellation()
        var calls = 0
        signal.attach { calls++ }
        signal.detach(); signal.request()
        assertEquals(0, calls)
        signal.attach { calls++ }
        assertEquals(1, calls)
    }
}
