package pro.sitescope.app;

import java.util.concurrent.TimeUnit;

/** One monotonic start clock shared by all workers of a scan. */
public final class ProbeStartGate {
    public interface Control {
        boolean isCancelled();
        boolean isPaused();
    }
    private final long delayNanos;
    private boolean started;
    private long lastStart;

    public ProbeStartGate(long delayMs) {
        if (delayMs < 0) throw new IllegalArgumentException("Задержка не может быть отрицательной");
        delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
    }

    public synchronized void awaitTurn(Control control) throws InterruptedException {
        for (;;) {
            if (Thread.currentThread().isInterrupted() || control.isCancelled()) throw new InterruptedException();
            if (!control.isPaused()) {
                long remaining = started ? delayNanos - (System.nanoTime() - lastStart) : 0;
                if (remaining <= 0) {
                    lastStart = System.nanoTime();
                    started = true;
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, 50_000_000L));
            } else { wait(50L); }
        }
    }
}
