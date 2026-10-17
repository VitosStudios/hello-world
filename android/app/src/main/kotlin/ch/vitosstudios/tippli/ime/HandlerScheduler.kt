package ch.vitosstudios.tippli.ime

import android.os.Handler
import android.os.SystemClock
import ch.vitosstudios.tippli.core.input.Cancellable
import ch.vitosstudios.tippli.core.input.Clock
import ch.vitosstudios.tippli.core.input.Scheduler

/** [Scheduler] on the UI thread's [Handler]; [Clock] from `SystemClock.uptimeMillis`. */
class HandlerScheduler(private val handler: Handler) : Scheduler, Clock {

    override fun now(): Long = SystemClock.uptimeMillis()

    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
