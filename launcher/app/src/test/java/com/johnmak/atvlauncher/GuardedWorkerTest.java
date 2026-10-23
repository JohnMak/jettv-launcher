package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class GuardedWorkerTest {
    @Test
    public void runsTaskAndReportsAcceptance() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        List<Throwable> failures = new ArrayList<>();
        GuardedWorker w = new GuardedWorker(ex, failures::add);
        int[] ran = {0};
        assertTrue(w.submit(() -> ran[0]++));
        ex.shutdown();
        assertTrue(ex.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, ran[0]);
        assertTrue(failures.isEmpty());
    }

    @Test
    public void submitAfterShutdownOrCloseIsDroppedWithoutThrowing() {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        GuardedWorker w = new GuardedWorker(ex, t -> {});
        ex.shutdownNow();
        assertFalse(w.submit(() -> {})); // executor rejects: no RejectedExecutionException escapes
        ExecutorService ex2 = Executors.newSingleThreadExecutor();
        GuardedWorker w2 = new GuardedWorker(ex2, t -> {});
        w2.close();
        assertFalse(w2.submit(() -> {}));
        assertTrue(ex2.isShutdown());
    }

    @Test
    public void taskFailureBecomesAReportedStateNotAnUncaughtException() throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        List<Throwable> failures = new ArrayList<>();
        GuardedWorker w = new GuardedWorker(ex, failures::add);
        assertTrue(w.submit(() -> { throw new IllegalStateException("boom"); }));
        ex.shutdown();
        assertTrue(ex.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, failures.size());
        assertEquals("boom", failures.get(0).getMessage());
    }
}
