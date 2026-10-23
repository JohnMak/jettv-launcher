package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.Before;
import org.junit.Test;

public class LampControllerTest {
    /** Executor that queues tasks until {@link #drain()}; models a held worker / main looper. */
    private static final class Manual implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void drain() { while (!tasks.isEmpty()) tasks.poll().run(); }
    }

    private static final class FakeShell implements LampShell {
        final List<String> commands = new ArrayList<>();
        final Queue<FetchResult<String>> scripted = new ArrayDeque<>();
        boolean closed;
        @Override public FetchResult<String> exec(String command) {
            commands.add(command);
            FetchResult<String> r = scripted.poll();
            if (r == null) throw new AssertionError("unscripted command: " + command);
            return r;
        }
        @Override public void close() { closed = true; }
        void ok(String s) { scripted.add(FetchResult.ok(s)); }
        void level(int n) { ok("return_data : {" + n + " }"); }
        void fail(LampError kind) { scripted.add(LampError.fail(kind, "x")); }
    }

    private FakeShell shell;
    private Manual worker;
    private Manual main;
    private List<LampState> states;
    private LampController c;

    @Before public void setUp() {
        shell = new FakeShell();
        worker = new Manual();
        main = new Manual();
        states = new ArrayList<>();
        c = new LampController(shell, worker, main, states::add);
    }

    private void settle() {
        while (!worker.tasks.isEmpty() || !main.tasks.isEmpty()) { worker.drain(); main.drain(); }
    }

    private void refreshTo(int level) {
        shell.level(level);
        c.refresh();
        settle();
        states.clear();
        shell.commands.clear();
    }

    private LampState last() { return states.get(states.size() - 1); }

    @Test public void refreshEmitsLoadingThenReady() {
        shell.level(7);
        c.refresh();
        assertEquals(LampState.Kind.LOADING, last().kind);
        settle();
        assertEquals(2, states.size());
        assertEquals(LampState.Kind.READY, last().kind);
        assertEquals(7, last().level);
        assertEquals(GmpfCommands.GET_LEVEL, shell.commands.get(0));
    }

    @Test public void refreshErrorMessagePerKind() {
        LampError[] kinds = {LampError.UNAUTHORIZED, LampError.UNREACHABLE, LampError.TOOL_MISSING, LampError.COMMAND_FAILED};
        String[] msgs = {LampController.MSG_UNAUTHORIZED, LampController.MSG_UNREACHABLE,
                LampController.MSG_TOOL_MISSING, LampController.MSG_FAILED};
        for (int i = 0; i < kinds.length; i++) {
            states.clear();
            shell.fail(kinds[i]);
            c.refresh();
            settle();
            assertEquals(LampState.Kind.ERROR, last().kind);
            assertEquals(msgs[i], last().message);
        }
    }

    @Test public void unparsableOutputIsCommandFailed() {
        shell.ok("garbage");
        c.refresh();
        settle();
        assertEquals(LampController.MSG_FAILED, last().message);
    }

    @Test public void stepIgnoredBeforeFirstRefresh() {
        c.step(1);
        settle();
        assertTrue(states.isEmpty());
        assertTrue(shell.commands.isEmpty());
    }

    @Test public void stepEmitsOptimisticStateThenReadback() {
        refreshTo(5);
        shell.ok("");
        shell.level(6);
        c.step(1);
        assertEquals(6, last().level); // before any worker work
        assertTrue(shell.commands.isEmpty());
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 6", shell.commands.get(0));
        assertEquals(GmpfCommands.GET_LEVEL, shell.commands.get(1));
        assertEquals(6, last().level);
    }

    @Test public void rapidStepsCoalesceIntoOneWrite() {
        refreshTo(3);
        shell.ok("");
        shell.level(8);
        for (int i = 0; i < 5; i++) c.step(1);
        assertEquals(8, last().level);
        assertEquals(5, states.size());
        settle();
        assertEquals(2, shell.commands.size());
        assertEquals("gmpfUnit setDlpLumensLevel 8", shell.commands.get(0));
        assertEquals(8, last().level);
    }

    @Test public void clampsAtBounds() {
        refreshTo(10);
        shell.ok("");
        shell.level(10);
        c.step(1);
        assertEquals(10, last().level);
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 10", shell.commands.get(0));

        refreshTo(1);
        shell.ok("");
        shell.level(1);
        c.step(-1);
        assertEquals(1, last().level);
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 1", shell.commands.get(0));
    }

    @Test public void readbackMismatchEmitsRealValue() {
        refreshTo(5);
        shell.ok("");
        shell.level(4);
        c.step(1);
        settle();
        assertEquals(4, last().level);
        shell.ok("");
        shell.level(5);
        c.step(1); // continues from the real value
        assertEquals(5, last().level);
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 5", shell.commands.get(2));
    }

    @Test public void setFailureEmitsErrorAndKeepsLastGoodLevel() {
        refreshTo(5);
        shell.fail(LampError.UNREACHABLE);
        c.step(1);
        settle();
        assertEquals(LampState.Kind.ERROR, last().kind);
        assertEquals(LampController.MSG_UNREACHABLE, last().message);
        shell.ok("");
        shell.level(6);
        c.step(1);
        assertEquals(6, last().level); // 5 + 1, not 6 + 1
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 6", shell.commands.get(1));
    }

    @Test public void releaseClosesShellOnWorker() {
        c.release();
        assertTrue(!shell.closed);
        settle();
        assertTrue(shell.closed);
    }
}
