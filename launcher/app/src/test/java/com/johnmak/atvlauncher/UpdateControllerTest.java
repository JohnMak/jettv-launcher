package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpdateControllerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final long T0 = 1_000_000_000L;
    private static final long INTERVAL = UpdatePolicy.CHECK_INTERVAL_MS;
    private static final long IDLE = UpdatePolicy.AUTO_INSTALL_IDLE_MS;

    private static final class Manual implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void drain() { while (!tasks.isEmpty()) tasks.poll().run(); }
    }

    private static final class FakeStore implements UpdateController.Store {
        long last;
        UpdateController.Pending pending;
        final List<String> order;
        FakeStore(List<String> order) { this.order = order; }
        @Override public long lastCheckAt() { return last; }
        @Override public void saveLastCheckAt(long atMs) { last = atMs; }
        @Override public UpdateController.Pending pending() { return pending; }
        @Override public void savePending(UpdateController.Pending p) { pending = p; order.add("savePending"); }
        @Override public void clearPending() { pending = null; }
        String failedVersion;
        int failedCount;
        @Override public int installFailures(String version) { return version.equals(failedVersion) ? failedCount : 0; }
        @Override public void saveInstallFailures(String version, int count) { failedVersion = version; failedCount = count; }
    }

    private final List<String> order = new ArrayList<>();
    private final Manual worker = new Manual();
    private final Manual main = new Manual();
    private final List<String> logs = new ArrayList<>();
    private final List<UpdateState> states = new ArrayList<>();
    private FakeStore store;
    private long now = T0;
    private int feedCalls;
    private int clears;
    private int downloads;
    private FetchResult<Release> feedResult;
    private FetchResult<ApkVerifier.Facts> archive;
    private FetchResult<String> installResult;
    private final List<VerifiedApk> installed = new ArrayList<>();
    private UpdateController c;

    private static SemVer v(String s) { return SemVer.parse(s).value(); }

    private Release release(String version) {
        return new Release(v(version), "https://github.com/x/" + version + ".apk", "https://github.com/x/" + version + ".sha256");
    }

    @Before public void setUp() throws Exception {
        store = new FakeStore(order);
        feedResult = FetchResult.ok(release("1.2.3"));
        archive = FetchResult.ok(new ApkVerifier.Facts("pkg", "1.2.3", 1_002_003, Collections.singleton("k")));
        installResult = FetchResult.ok("installed");
        c = newController("1.2.2");
    }

    private UpdateController newController(String versionName) {
        UpdateController.Downloader downloader = new UpdateController.Downloader() {
            @Override public FetchResult<UpdateController.Download> download(Release r, IntConsumer progress) {
                downloads++;
                progress.accept(50);
                progress.accept(100);
                try {
                    File apk = tmp.newFile();
                    File sha = tmp.newFile();
                    Files.write(apk.toPath(), "apk".getBytes(StandardCharsets.UTF_8));
                    Files.write(sha.toPath(), ApkVerifierTest.sha256("apk".getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                    return FetchResult.ok(new UpdateController.Download(apk, sha));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            @Override public void clear() { clears++; }
        };
        ApkVerifier verifier = new ApkVerifier(new ApkVerifier.Inspector() {
            @Override public FetchResult<ApkVerifier.Facts> archive(File f) { return archive; }
            @Override public ApkVerifier.Facts installed() {
                return new ApkVerifier.Facts("pkg", "1.2.2", 1_002_002, Collections.singleton("k"));
            }
        });
        AdbSession session = new AdbSession() {
            @Override public FetchResult<String> exec(ShellCommand command) { throw new AssertionError("updater never execs"); }
            @Override public FetchResult<String> install(VerifiedApk apk) {
                order.add("install");
                installed.add(apk);
                return installResult;
            }
            @Override public void disconnect() { }
        };
        UpdateController ctl = new UpdateController(SemVer.ofVersionName(versionName).value(), versionName,
                () -> { feedCalls++; return feedResult; }, downloader, verifier, session, store, () -> now,
                worker, main, logs::add);
        ctl.addListener(states::add);
        return ctl;
    }

    private void settle() {
        while (!worker.tasks.isEmpty() || !main.tasks.isEmpty()) { worker.drain(); main.drain(); }
    }

    private UpdateState.Kind kind() { return c.state().kind; }

    private List<UpdateState.Kind> kinds() {
        List<UpdateState.Kind> k = new ArrayList<>();
        for (UpdateState s : states) k.add(s.kind);
        return k;
    }

    @Test public void startChecksDownloadsVerifiesAndHoldsTheUpdateUntilIdle() {
        c.onTick(true, 0, false);
        assertEquals(UpdateState.Kind.CHECKING, kind());
        settle();
        assertEquals(UpdateState.Kind.READY, kind());
        assertEquals(v("1.2.3"), c.state().version);
        assertEquals(java.util.Arrays.asList(UpdateState.Kind.CHECKING, UpdateState.Kind.DOWNLOADING, UpdateState.Kind.DOWNLOADING,
                UpdateState.Kind.DOWNLOADING, UpdateState.Kind.VERIFYING, UpdateState.Kind.READY), kinds());
        assertEquals(50, states.get(2).progress);
        assertEquals(T0, store.last);
        assertTrue(installed.isEmpty());
    }

    @Test public void readyUpdateInstallsOnlyWhenForegroundAndIdleTenMinutes() {
        c.onTick(true, 0, false);
        settle();
        c.onTick(true, IDLE - 1, false);
        c.onTick(false, IDLE * 3, false);
        settle();
        assertTrue(installed.isEmpty());
        c.onTick(true, IDLE, false);
        assertEquals(UpdateState.Kind.INSTALLING, kind());
        settle();
        assertEquals(1, installed.size());
        assertEquals(v("1.2.3"), installed.get(0).version());
    }

    @Test public void pendingIsWrittenBeforeTheInstallStarts() {
        c.onTick(true, 0, false);
        settle();
        now = T0 + 5;
        c.onTick(true, IDLE, false);
        assertEquals("1.2.3", store.pending.version);
        assertEquals(T0 + 5, store.pending.atMs);
        assertEquals(java.util.Collections.singletonList("savePending"), order); // before the worker ran install
        settle();
        assertEquals(java.util.Arrays.asList("savePending", "install"), order);
        assertEquals(UpdateState.Kind.IDLE, kind()); // the process survived: nothing is in flight any more
    }

    @Test public void manualCheckInstallsRightAfterVerification() {
        c.checkNow();
        settle();
        assertEquals(1, installed.size());
        assertTrue(kinds().contains(UpdateState.Kind.READY));
    }

    @Test public void manualCheckDuringAnAutomaticPassUpgradesItWithoutASecondPass() {
        c.onTick(true, 0, false);
        c.checkNow();
        settle();
        assertEquals(1, feedCalls);
        assertEquals(1, installed.size());
    }

    @Test public void manualCheckOnReadyInstallsImmediately() {
        c.onTick(true, 0, false);
        settle();
        c.checkNow();
        assertEquals(UpdateState.Kind.INSTALLING, kind());
        settle();
        assertEquals(1, installed.size());
    }

    @Test public void manualCheckWhileInstallingIsIgnored() {
        c.checkNow();
        while (kind() != UpdateState.Kind.INSTALLING) { worker.tasks.poll().run(); main.drain(); }
        c.checkNow();
        settle();
        assertEquals(1, installed.size());
        assertEquals(1, feedCalls);
    }

    @Test public void tickWhileBusyOrWithinIntervalDoesNotCheckAgain() {
        c.onTick(true, 0, false);
        c.onTick(true, 0, false);
        settle();
        assertEquals(1, feedCalls);
        feedResult = FetchResult.ok(release("1.2.2"));
        c = newController("1.2.2");
        c.onTick(true, 0, false);
        settle();
        assertEquals(UpdateState.Kind.UP_TO_DATE, kind());
        int calls = feedCalls;
        now += INTERVAL - 1;
        c.onTick(true, 0, false);
        settle();
        assertEquals(calls, feedCalls);
        now += 1;
        c.onTick(true, 0, false);
        settle();
        assertEquals(calls + 1, feedCalls);
    }

    @Test public void sameOrOlderReleaseIsUpToDate() {
        feedResult = FetchResult.ok(release("1.2.2"));
        c.checkNow();
        settle();
        assertEquals(UpdateState.Kind.UP_TO_DATE, kind());
        assertTrue(installed.isEmpty());
    }

    @Test public void devBuildOfTheSameBaseIsUpToDate() {
        c = newController("1.2.3-dev.4+gabc");
        c.checkNow();
        settle();
        assertEquals(UpdateState.Kind.UP_TO_DATE, kind());
    }

    @Test public void feedErrorIsTypedAndRetriedByTheNextStart() {
        feedResult = UpdateError.fail(UpdateError.NETWORK, "down");
        c.onTick(true, 0, false);
        settle();
        assertEquals(UpdateState.Kind.ERROR, kind());
        assertEquals(UpdateError.NETWORK, c.state().error);
        assertEquals(0, store.last); // not recorded: the next tick may retry
        c.onTick(true, 0, false);
        settle();
        assertEquals(2, feedCalls);
    }

    @Test public void verificationFailureNeverReachesInstall() {
        archive = FetchResult.ok(new ApkVerifier.Facts("pkg", "1.2.3", 1_002_003, Collections.singleton("other")));
        int before = clears;
        c.checkNow();
        settle();
        assertEquals(UpdateState.Kind.ERROR, kind());
        assertEquals(UpdateError.VERIFY_FAILED, c.state().error);
        assertTrue(installed.isEmpty());
        assertTrue(clears > before);
    }

    @Test public void installFailureIsMappedAndClearsPending() {
        installResult = ShellError.fail(ShellError.UNAUTHORIZED, "dialog");
        c.checkNow();
        settle();
        assertEquals(UpdateState.Kind.ERROR, kind());
        assertEquals(UpdateError.ADB_UNAUTHORIZED, c.state().error);
        assertNull(store.pending);
        assertTrue(!logs.isEmpty());
    }

    @Test public void secondInstallFailureOfAVersionStopsRetriesAndNewerVersionResets() {
        installResult = ShellError.fail(ShellError.COMMAND_FAILED, "INSTALL_FAILED_X");
        c.checkNow();
        settle();
        assertEquals(UpdateError.INSTALL_FAILED, c.state().error);
        assertEquals(1, store.installFailures("1.2.3"));
        int clearsAfterFirst = clears;
        c.checkNow(); // retry reuses the verified APK instead of downloading again
        settle();
        assertEquals(1, downloads);
        assertEquals(2, installed.size());
        assertEquals(UpdateError.INSTALL_FAILED_REPEATEDLY, c.state().error);
        assertEquals(2, store.installFailures("1.2.3"));
        assertTrue(clears > clearsAfterFirst); // the useless file is dropped once we give up
        c.checkNow(); // same version again: no download, no install
        settle();
        assertEquals(UpdateError.INSTALL_FAILED_REPEATEDLY, c.state().error);
        assertEquals(1, downloads);
        assertEquals(2, installed.size());
        // a newer version resets the counter
        feedResult = FetchResult.ok(release("1.2.4"));
        archive = FetchResult.ok(new ApkVerifier.Facts("pkg", "1.2.4", 1_002_004, Collections.singleton("k")));
        installResult = FetchResult.ok("installed");
        c.checkNow();
        settle();
        assertEquals(2, downloads);
        assertEquals(3, installed.size());
        assertEquals(0, store.installFailures("1.2.4"));
    }

    @Test public void screensaverOpensTheInstallWindowForAReadyUpdate() {
        c.onTick(true, 0, false);
        settle();
        c.onTick(false, 0, false);
        settle();
        assertTrue(installed.isEmpty());
        c.onTick(false, 0, true);
        assertEquals(UpdateState.Kind.INSTALLING, kind());
        settle();
        assertEquals(1, installed.size());
    }

    @Test public void freshStartAfterInstallClearsPendingQuietly() {
        store.pending = new UpdateController.Pending("1.2.3", T0 - 1000);
        c = newController("1.2.3");
        assertNull(store.pending);
        assertTrue(logs.isEmpty());
        assertTrue(c.processStartedByUpdate());
        assertFalse(newController("1.2.2").processStartedByUpdate());
    }

    @Test public void stalePendingOfAnotherVersionIsDroppedWithALog() {
        store.pending = new UpdateController.Pending("1.2.3", T0 - 10 * 60 * 1000 - 1);
        c = newController("1.2.2");
        assertNull(store.pending);
        assertEquals(1, logs.size());
    }

    @Test public void freshPendingOfAnotherVersionIsKept() {
        store.pending = new UpdateController.Pending("1.2.3", T0 - 1000);
        c = newController("1.2.2");
        assertEquals("1.2.3", store.pending.version);
        assertTrue(logs.isEmpty());
    }

    @Test public void listenersCanBeRemoved() {
        UpdateController.Listener extra = s -> { throw new AssertionError("removed listener called"); };
        c.addListener(extra);
        c.removeListener(extra);
        c.checkNow();
        settle();
        assertTrue(!states.isEmpty());
    }

    @Test public void collaboratorBugDoesNotWedgeTheStateMachine() throws IOException {
        feedResult = null; // the fake feed returns null -> NPE inside the worker
        c.checkNow();
        settle();
        assertEquals(UpdateState.Kind.ERROR, kind());
        assertEquals(UpdateError.INTERNAL, c.state().error);
        feedResult = FetchResult.ok(release("1.2.3"));
        c.checkNow();
        settle();
        assertEquals(1, installed.size());
    }
}
