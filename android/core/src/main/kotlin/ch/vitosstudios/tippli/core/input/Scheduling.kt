package ch.vitosstudios.tippli.core.input

/** Monotonic time source in milliseconds (injectable for tests). */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() / 1_000_000L }
    }
}

/** Handle of a scheduled task. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Runs tasks later on the UI thread. Android implements it with a Handler,
 * tests with [ManualScheduler].
 */
fun interface Scheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
}

/** A deterministic scheduler + clock for unit tests: time only moves on [advanceBy]. */
class ManualScheduler(startTime: Long = 0L) : Scheduler, Clock {
    private data class Task(val due: Long, val seq: Long, val task: () -> Unit, var cancelled: Boolean = false)

    private var time = startTime
    private var seq = 0L
    private val tasks = ArrayList<Task>()

    override fun now(): Long = time

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(time + delayMs.coerceAtLeast(0), seq++, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    /** Moves time forward, running every task that becomes due (in order). */
    fun advanceBy(ms: Long) {
        val target = time + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= target }.minWithOrNull(compareBy({ it.due }, { it.seq })) ?: break
            tasks.remove(next)
            time = next.due
            next.task()
        }
        tasks.removeAll { it.cancelled }
        time = target
    }

    val pendingCount: Int get() = tasks.count { !it.cancelled }
}
