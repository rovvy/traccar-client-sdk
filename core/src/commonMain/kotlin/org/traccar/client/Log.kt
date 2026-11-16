package org.traccar.client

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

internal object Log {

    @Volatile
    var store: LogStore? = null

    @Volatile
    var retention: Int = 5000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Lines logged before the store is open (the service's first lines after a restart by Android or a reboot come
     * before the tracker is built), with their time; written at the next line once it is, at most [EARLY_MAX].
     */
    private val early = ArrayDeque<Pair<Long, String>>()
    private val earlyLock = Mutex()
    private const val EARLY_MAX = 100

    fun log(message: String) {
        val time = Clock.System.now().toEpochMilliseconds()
        scope.launch {
            val target = store
            val waiting = earlyLock.withLock {
                if (target == null) {
                    if (early.size >= EARLY_MAX) early.removeFirst()
                    early.addLast(time to message)
                    null
                } else {
                    early.toList().also { early.clear() }
                }
            } ?: return@launch
            val open = target ?: return@launch
            for ((t, m) in waiting) open.insert(m, t)
            open.insert(message, time)
            open.trim(retention)
        }
    }
}
