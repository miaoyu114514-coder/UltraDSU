package io.github.ultradsu.dsu

import java.util.concurrent.CancellationException

/** A cooperative request: privileged work acknowledges it only after cleanup. */
class OperationCancellation {
    @Volatile var requested: Boolean = false
        private set
    private var listener: (() -> Unit)? = null

    @Synchronized fun request() {
        if (requested) return
        requested = true
        listener?.invoke()
    }
    @Synchronized fun attach(listener: () -> Unit) {
        this.listener = listener
        if (requested) listener()
    }
    @Synchronized fun detach() { listener = null }
    fun check() { if (requested) throw CancellationException("任务已取消") }
}
