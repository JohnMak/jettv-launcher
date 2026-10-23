package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.junit.Before;
import org.junit.Test;

public class BrightnessControllerTest {
    /** Executor that queues tasks until {@link #drain()}; models a held worker / main looper. */
    private static final class Manual implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void drain() { while (!tasks.isEmpty()) tasks.poll().run(); }
    }

    private static final class FakeShell implements GmpfShell {
        final List<String> commands = new ArrayList<>();
        final Queue<FetchResult<String>> scripted = new ArrayDeque<>();
        boolean closed;
        int disconnects;
        RuntimeException throwOnce;
        @Override public FetchResult<String> exec(GmpfCommand command) {
            commands.add(command.shellLine());
            String c = command.shellLine();
            if (throwOnce != null) {
                RuntimeException e = throwOnce;
                throwOnce = null;
                throw e;
            }
            FetchResult<String> r = scripted.poll();
            if (r == null) throw new AssertionError("unscripted command: " + c);
            return r;
        }
        @Override public void disconnect() { disconnects++; }
        @Override public void close() { closed = true; }
        void ok(String s) { scripted.add(FetchResult.ok(s)); }
        void level(int n) { ok("return_data : {" + n + " }"); }
        void fail(ShellError kind) { scripted.add(ShellError.fail(kind, "x")); }
    }

    private FakeShell shell;
    private Manual worker;
    private Manual main;
    private List<BrightnessState> states;
    private BrightnessController c;

    @Before public void setUp() {
        shell = new FakeShell();
        worker = new Manual();
        main = new Manual();
        states = new ArrayList<>();
        c = new BrightnessController(shell, worker, main, states::add);
    }

    private void settle() {
        while (!worker.tasks.isEmpty() || !main.tasks.isEmpty()) { worker.drain(); main.drain(); }
    }

    /** Scripts the three getters (lamp, brightness, contrast). */
    private void device(int lamp, int brightness, int contrast) {
        shell.level(lamp);
        shell.level(brightness);
        shell.level(contrast);
    }

    private void refreshTo(int lamp, int brightness, int contrast) {
        device(lamp, brightness, contrast);
        c.refresh();
        settle();
        states.clear();
        shell.commands.clear();
    }

    /** Refreshes to the exact device values of scale position p. */
    private void refreshToPosition(int p) {
        BrightnessScale.Position pos = BrightnessScale.Position.of(p).value();
        int pic = BrightnessScale.picture(pos);
        refreshTo(BrightnessScale.lamp(pos).value(), pic, pic);
    }

    private void scriptWrites(int count) { for (int i = 0; i < count; i++) shell.ok(""); }

    private static final List<String> READBACK = Arrays.asList(
            GmpfCommand.getLevel().shellLine(), GmpfCommand.getBrightness().shellLine(), GmpfCommand.getContrast().shellLine());

    private void assertCommands(String... writes) {
        List<String> expected = new ArrayList<>(Arrays.asList(writes));
        expected.addAll(READBACK);
        assertEquals(expected, shell.commands);
    }

    private BrightnessState last() { return states.get(states.size() - 1); }

    @Test public void refreshEmitsLoadingThenReadyPosition() {
        device(7, 50, 50);
        c.refresh();
        assertEquals(BrightnessState.Kind.LOADING, last().kind);
        settle();
        assertEquals(2, states.size());
        assertEquals(BrightnessState.Kind.READY, last().kind);
        assertEquals(9, last().position);
        assertEquals(READBACK, shell.commands);
    }

    @Test public void refreshOffGridPictureMapsByLamp() {
        device(10, 50, 30);
        c.refresh();
        settle();
        assertEquals(12, last().position);
    }

    @Test public void refreshErrorKindPerShellError() {
        ShellError[] kinds = {ShellError.UNAUTHORIZED, ShellError.UNREACHABLE, ShellError.TOOL_MISSING, ShellError.COMMAND_FAILED};
        for (int i = 0; i < kinds.length; i++) {
            states.clear();
            shell.fail(kinds[i]);
            c.refresh();
            settle();
            assertEquals(BrightnessState.Kind.ERROR, last().kind);
            assertEquals(kinds[i], last().error);
        }
    }

    @Test public void unparsableOrOutOfRangeOutputIsCommandFailed() {
        shell.ok("garbage");
        c.refresh();
        settle();
        assertEquals(ShellError.COMMAND_FAILED, last().error);
        shell.level(5);
        shell.level(101);
        c.refresh();
        settle();
        assertEquals(ShellError.COMMAND_FAILED, last().error);
        shell.level(11);
        c.refresh();
        settle();
        assertEquals(ShellError.COMMAND_FAILED, last().error);
    }

    @Test public void stepWithoutConfirmedPositionRefreshesInsteadOfWriting() {
        shell.fail(ShellError.UNAUTHORIZED); // first refresh failed, e.g. RSA dialog still open
        c.refresh();
        settle();
        assertEquals(BrightnessState.Kind.ERROR, last().kind);
        shell.commands.clear();
        device(7, 50, 50);
        c.step(1);
        c.step(1); // no duplicate refresh while one is in flight
        assertEquals(1, worker.tasks.size());
        settle();
        assertEquals(READBACK, shell.commands); // read only, nothing written
        assertEquals(BrightnessState.Kind.READY, last().kind);
        assertEquals(9, last().position);
    }

    @Test public void refreshWhileRefreshInFlightIsNotQueuedTwice() {
        device(7, 50, 50);
        c.refresh();
        c.refresh();
        assertEquals(1, worker.tasks.size());
        settle();
        assertEquals(READBACK, shell.commands);
        c.refresh(); // an earlier refresh has completed: a new one runs again
        assertEquals(1, worker.tasks.size());
        device(7, 50, 50);
        settle();
    }

    @Test public void failedRefreshForgetsCachedDeviceValues() {
        refreshToPosition(7); // lamp 5, picture 50
        shell.fail(ShellError.UNREACHABLE);
        c.refresh();
        settle();
        assertEquals(BrightnessState.Kind.ERROR, last().kind);
        shell.commands.clear();
        scriptWrites(3);
        device(6, 50, 50);
        c.step(1); // device values are unknown again: everything is rewritten, picture first when increasing
        settle();
        assertCommands("gmpfUnit setBrightness 0 50", "gmpfUnit setContrast 0 50", "gmpfUnit setDlpLumensLevel 6");
    }

    @Test public void partialWriteFailureReadsBackAndEmitsRealState() {
        refreshToPosition(13); // lamp 10, picture 57
        shell.ok("");                      // lamp lands (9)
        shell.fail(ShellError.UNREACHABLE); // brightness fails
        device(9, 57, 57);                 // what the device really holds
        c.step(-2);
        settle();
        assertEquals(Arrays.asList("gmpfUnit setDlpLumensLevel 9", "gmpfUnit setBrightness 0 50"),
                shell.commands.subList(0, 2));
        assertEquals(READBACK, shell.commands.subList(2, 5));
        assertEquals(BrightnessState.Kind.READY, last().kind);
        assertEquals(BrightnessScale.fromDevice(LampLevel.of(9).value(), 57, 57).value(), last().position);
        assertEquals(BrightnessState.Kind.ERROR, states.get(states.size() - 2).kind); // the failure is still reported
    }

    @Test public void stepInLampZoneWritesLampOnlyThenReadsBack() {
        refreshToPosition(7);
        scriptWrites(1);
        device(6, 50, 50);
        c.step(1);
        assertEquals(8, last().position); // before any worker work
        assertTrue(shell.commands.isEmpty());
        settle();
        assertCommands("gmpfUnit setDlpLumensLevel 6");
        assertEquals(8, last().position);
    }

    @Test public void stepDownIntoLeftZoneWritesPictureOnly() {
        refreshToPosition(3);
        scriptWrites(2);
        device(1, 42, 42);
        c.step(-1);
        settle();
        assertCommands("gmpfUnit setBrightness 0 42", "gmpfUnit setContrast 0 42");
        assertEquals(2, last().position);
    }

    @Test public void stepUpOutOfLeftZoneWritesNeutralPictureOnly() {
        refreshToPosition(2);
        scriptWrites(2);
        device(1, 50, 50);
        c.step(1);
        settle();
        assertCommands("gmpfUnit setBrightness 0 50", "gmpfUnit setContrast 0 50");
        assertEquals(3, last().position);
    }

    @Test public void stepIntoRightZoneWritesPictureOnly() {
        refreshToPosition(12);
        scriptWrites(2);
        device(10, 57, 57);
        c.step(1);
        settle();
        assertCommands("gmpfUnit setBrightness 0 57", "gmpfUnit setContrast 0 57");
        assertEquals(13, last().position);
    }

    @Test public void decreasingPositionWritesLampBeforePicture() {
        refreshToPosition(13); // lamp 10, picture 57
        scriptWrites(3);
        device(9, 50, 50);
        c.step(-2); // F11: lamp 9, picture 50
        settle();
        assertCommands("gmpfUnit setDlpLumensLevel 9", "gmpfUnit setBrightness 0 50", "gmpfUnit setContrast 0 50");
        assertEquals(11, last().position);
    }

    @Test public void increasingPositionWritesPictureBeforeLamp() {
        refreshToPosition(2); // lamp 1, picture 42
        scriptWrites(3);
        device(2, 50, 50);
        c.step(2); // F4: lamp 2, picture 50
        settle();
        assertCommands("gmpfUnit setBrightness 0 50", "gmpfUnit setContrast 0 50", "gmpfUnit setDlpLumensLevel 2");
        assertEquals(4, last().position);
    }

    @Test public void offGridContrastIsNormalizedOnNextWrite() {
        refreshTo(10, 50, 30); // F12, contrast off-grid
        scriptWrites(1);
        device(10, 50, 50);
        c.step(0);
        settle();
        assertCommands("gmpfUnit setContrast 0 50");
    }

    @Test public void rapidStepsCoalesceIntoOneWriteSet() {
        refreshToPosition(6);
        scriptWrites(1);
        device(9, 50, 50);
        for (int i = 0; i < 5; i++) c.step(1);
        assertEquals(11, last().position);
        assertEquals(5, states.size());
        settle();
        assertCommands("gmpfUnit setDlpLumensLevel 9");
        assertEquals(11, last().position);
    }

    @Test public void clampsAtBounds() {
        refreshToPosition(15);
        device(10, 72, 72);
        c.step(1);
        assertEquals(15, last().position);
        settle();
        assertCommands(); // already at the target: nothing to write, only the readback

        states.clear();
        shell.commands.clear();
        shell.scripted.clear();
        refreshToPosition(0);
        device(1, 27, 27);
        c.step(-1);
        assertEquals(0, last().position);
        settle();
        assertCommands();
    }

    @Test public void readbackMismatchEmitsRealPositionAndContinuesFromIt() {
        refreshToPosition(7);
        scriptWrites(1);
        device(5, 50, 50); // device kept lamp 5 (F7) instead of 6
        c.step(1);
        settle();
        assertEquals(7, last().position);
        scriptWrites(1);
        device(6, 50, 50);
        c.step(1); // continues from the real value
        assertEquals(8, last().position);
        settle();
        assertEquals("gmpfUnit setDlpLumensLevel 6", shell.commands.get(4));
    }

    @Test public void writeFailureEmitsErrorKeepsLastGoodPositionAndReadsBack() {
        refreshToPosition(13); // lamp 10, picture 57
        shell.ok("");          // lamp ok
        shell.fail(ShellError.UNREACHABLE); // brightness fails
        device(10, 57, 57);    // readback after the failure
        c.step(-2);            // F11
        settle();
        assertEquals(BrightnessState.Kind.READY, last().kind);
        assertEquals(BrightnessState.Kind.ERROR, states.get(states.size() - 2).kind);
        assertEquals(ShellError.UNREACHABLE, states.get(states.size() - 2).error);
        shell.commands.clear();
        scriptWrites(2);
        device(10, 50, 50);
        c.step(-1); // 13 - 1 = 12, not 11 - 1
        assertEquals(12, last().position);
        settle();
        // the readback after the failure refreshed the cache: lamp already right, only the picture is written
        assertCommands("gmpfUnit setBrightness 0 50", "gmpfUnit setContrast 0 50");
    }

    @Test public void releaseClosesShellOnWorker() {
        c.release();
        assertTrue(!shell.closed);
        settle();
        assertTrue(shell.closed);
    }

    @Test public void runtimeExceptionInWriteDoesNotWedgeLaterWrites() {
        refreshToPosition(7);
        shell.throwOnce = new IllegalStateException("boom");
        device(5, 50, 50); // readback after the failure
        c.step(1);
        settle();
        assertEquals(BrightnessState.Kind.READY, last().kind);
        assertEquals(BrightnessState.Kind.ERROR, states.get(states.size() - 2).kind);
        shell.commands.clear();
        scriptWrites(1); // the readback refreshed the cache: only the lamp differs
        device(6, 50, 50);
        c.step(1);
        settle();
        // the loop is not stuck in "applying"
        assertCommands("gmpfUnit setDlpLumensLevel 6");
        assertEquals(BrightnessState.Kind.READY, last().kind);
    }

    @Test public void runtimeExceptionInRefreshStillPostsResult() {
        shell.throwOnce = new IllegalStateException("boom");
        c.refresh();
        settle();
        assertEquals(BrightnessState.Kind.ERROR, last().kind);
        assertEquals(ShellError.COMMAND_FAILED, last().error);
    }

    @Test public void disconnectDropsConnectionOnWorkerAndControllerStaysUsable() {
        refreshToPosition(7);
        c.disconnect();
        assertEquals(0, shell.disconnects);
        settle();
        assertEquals(1, shell.disconnects);
        assertTrue(!shell.closed); // only release() is final
        device(5, 50, 50);
        c.refresh(); // lazy reconnect happens inside the next exec
        settle();
        assertEquals(BrightnessState.Kind.READY, last().kind);
    }
}
