package com.johnmak.atvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Self-update of the launcher: Idle, then Checking, then UpToDate or Downloading, Verifying, Ready, Installing, or Error.
 * One instance per process ({@link #instance}), so a manual {@link #checkNow} and the automatic path never race:
 * both go through the same state machine. Public methods and listener callbacks run on the main thread; all I/O runs
 * on the single worker. The automatic path installs a Ready update only when {@link UpdatePolicy} allows it; the
 * manual path installs right after verification.
 */
final class UpdateController {
    private static final String TAG = "AtvUpdate";
    private static final long PENDING_STALE_MS = 10L * 60 * 1000;
    /** A version whose install failed this many times is not downloaded or installed again. */
    static final int MAX_INSTALL_FAILURES = 2;

    interface Listener {
        void onState(UpdateState state);
    }

    /** Downloads a release into the update cache. Blocking, worker thread only. */
    interface Downloader {
        FetchResult<Download> download(Release release, IntConsumer progressPercent);

        /** Deletes everything left from earlier downloads. */
        void clear();
    }

    /** The downloaded files of one release. */
    static final class Download {
        final File apk;
        final File sha256;

        Download(File apk, File sha256) {
            this.apk = apk;
            this.sha256 = sha256;
        }
    }

    /** Persistent updater data. */
    interface Store {
        long lastCheckAt();

        void saveLastCheckAt(long atMs);

        /** null when no install is in flight. */
        Pending pending();

        void savePending(Pending pending);

        void clearPending();

        /** Failed installs recorded for {@code version}; 0 when the recorded failures belong to another version. */
        int installFailures(String version);

        /** Replaces the record: failures are counted for one version at a time, so a newer version starts at 0. */
        void saveInstallFailures(String version, int count);
    }

    /** An install that was started: the version it should leave behind and when. */
    static final class Pending {
        final String version;
        final long atMs;

        Pending(String version, long atMs) {
            this.version = version;
            this.atMs = atMs;
        }
    }

    private static UpdateController instance;

    /** The process-wide controller; created on first use. */
    static synchronized UpdateController instance(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            String userAgent = HttpText.USER_AGENT;
            FetchResult<SemVer> current = SemVer.ofVersionName(BuildConfig.VERSION_NAME);
            if (!current.isOk()) throw new IllegalStateException("build version: " + current.error());
            Handler handler = new Handler(Looper.getMainLooper());
            ExecutorService worker = Executors.newSingleThreadExecutor();
            long elapsed = SystemClock.elapsedRealtime();
            long bootUntil = UpdatePolicy.isBootStart(elapsed)
                    ? System.currentTimeMillis() + UpdatePolicy.BOOT_WINDOW_MS - elapsed : 0;
            instance = new UpdateController(current.value(), BuildConfig.VERSION_NAME,
                    ReleaseFeed.github(userAgent),
                    new ApkDownloader(new File(app.getCacheDir(), "update"), userAgent),
                    new ApkVerifier(new PackageInspector(app)),
                    LoopbackAdbSession.shared(app), new UpdatePrefs(app),
                    System::currentTimeMillis, bootUntil, worker, handler::post, msg -> Log.w(TAG, msg));
            startTicking(instance, handler);
        }
        return instance;
    }

    /**
     * Own main-looper tick, started once with the controller, so checks and downloads go on while other apps are in
     * the foreground. The first tick is the process-start evaluation.
     */
    private static void startTicking(UpdateController controller, Handler handler) {
        Runnable tick = new Runnable() {
            @Override public void run() {
                long next = controller.onTick(ScreensaverState.launcherForeground(),
                        ScreensaverState.launcherIdleMs(), ScreensaverState.showing());
                handler.postDelayed(this, next);
            }
        };
        handler.post(tick);
    }

    private final SemVer current;
    private final ReleaseFeed.Source feed;
    private final Downloader downloader;
    private final ApkVerifier verifier;
    private final AdbSession session;
    private final Store store;
    private final LongSupplier clock;
    /** End of the boot window in {@code clock} time; 0 when this process did not start at device boot. */
    private final long bootUntilMs;
    private final Executor worker;
    private final Executor main;
    private final Consumer<String> log;
    private final List<Listener> listeners = new ArrayList<>();

    // Main-thread state.
    private UpdateState state = UpdateState.IDLE;
    private VerifiedApk ready;
    private boolean installWhenReady;
    private boolean evaluated;
    private boolean bootAnswered;
    private boolean startedByUpdate;

    UpdateController(SemVer current, String currentVersionName, ReleaseFeed.Source feed, Downloader downloader,
                     ApkVerifier verifier, AdbSession session, Store store, LongSupplier clock,
                     long bootUntilMs, Executor worker, Executor main, Consumer<String> log) {
        this.current = current;
        this.feed = feed;
        this.downloader = downloader;
        this.verifier = verifier;
        this.session = session;
        this.store = store;
        this.clock = clock;
        this.bootUntilMs = bootUntilMs;
        this.worker = worker;
        this.main = main;
        this.log = log;
        resolvePending(currentVersionName);
        worker.execute(downloader::clear);
    }

    /** An install started by the previous process either left us at its version (silently done) or went stale (logged). */
    private void resolvePending(String currentVersionName) {
        Pending pending = store.pending();
        if (pending == null) return;
        if (pending.version.equals(currentVersionName)) {
            startedByUpdate = true;
            store.clearPending();
        } else if (clock.getAsLong() - pending.atMs > PENDING_STALE_MS) {
            log.accept("install of " + pending.version + " did not take effect; running " + currentVersionName);
            store.clearPending();
        }
    }

    // ------------------------------------------------------------ UI API

    UpdateState state() { return state; }

    /** True when this process was started by our own silent update install (its pending record matched the running version). */
    boolean processStartedByUpdate() { return startedByUpdate; }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** User asked: check now and install as soon as the update is verified (or right away if it already is). */
    void checkNow() {
        switch (state.kind) {
            case READY:
                install();
                break;
            case CHECKING:
            case DOWNLOADING:
            case VERIFYING:
                installWhenReady = true; // the running automatic pass becomes a manual one
                break;
            case INSTALLING:
                break;
            default:
                installWhenReady = true;
                startPipeline();
        }
    }

    /**
     * Periodic evaluation: starts a due check and installs a Ready update once the policy allows. {@code idleMs} is
     * the time since the last remote input in the launcher.
     */
    long onTick(boolean launcherForeground, long idleMs, boolean screensaverShowing) {
        long now = clock.getAsLong();
        boolean boot = bootInProgress(now);
        UpdatePolicy.Trigger trigger = boot ? UpdatePolicy.Trigger.BOOT
                : evaluated ? UpdatePolicy.Trigger.TICK : UpdatePolicy.Trigger.PROCESS_START;
        evaluated = true;
        if (UpdatePolicy.shouldCheck(state.kind, trigger, store.lastCheckAt(), now)) {
            if (boot) installWhenReady = true; // boot: install right after verification, like a manual check
            startPipeline();
        } else if (UpdatePolicy.shouldAutoInstall(state.kind, launcherForeground, idleMs, screensaverShowing)) {
            install();
        }
        return UpdatePolicy.nextTickDelayMs(bootInProgress(now));
    }

    private boolean bootInProgress(long now) {
        return !bootAnswered && UpdatePolicy.bootActive(now, bootUntilMs);
    }

    // ------------------------------------------------------------ pipeline

    private void startPipeline() {
        setState(UpdateState.CHECKING);
        VerifiedApk kept = ready; // verified earlier, install failed: reused instead of downloading again
        worker.execute(() -> runPipeline(kept));
    }

    /** Worker: feed, download, verify; every outcome is posted to main. */
    private void runPipeline(VerifiedApk kept) {
        try {
            FetchResult<Release> latest = feed.latest();
            if (!latest.isOk()) {
                publishError(latest);
                return;
            }
            store.saveLastCheckAt(clock.getAsLong());
            Release release = latest.value();
            if (release.version.compareTo(current) <= 0) {
                publish(UpdateState.UP_TO_DATE);
                return;
            }
            SemVer version = release.version;
            if (store.installFailures(version.toString()) >= MAX_INSTALL_FAILURES) {
                publish(UpdateState.error(UpdateError.INSTALL_FAILED_REPEATEDLY, version + " failed to install " + MAX_INSTALL_FAILURES + " times"));
                return;
            }
            if (kept != null && kept.version().equals(version) && kept.file().isFile()) {
                main.execute(() -> onVerified(kept));
                return;
            }
            publish(UpdateState.downloading(version, UpdateState.NO_PROGRESS));
            FetchResult<Download> download = downloader.download(release, p -> publish(UpdateState.downloading(version, p)));
            if (!download.isOk()) {
                publishError(download);
                return;
            }
            publish(UpdateState.verifying(version));
            FetchResult<VerifiedApk> verified = verifier.verify(download.value().apk, download.value().sha256, version);
            if (!verified.isOk()) {
                downloader.clear();
                publishError(verified);
                return;
            }
            main.execute(() -> onVerified(verified.value()));
        } catch (RuntimeException e) { // collaborator contract breach: still must not wedge the state machine
            publishError(UpdateError.fail(UpdateError.INTERNAL, e.toString()));
        }
    }

    private void publish(UpdateState s) {
        main.execute(() -> setState(s));
    }

    private void publishError(FetchResult<?> failed) {
        UpdateError kind = UpdateError.of(failed);
        publish(UpdateState.error(kind != null ? kind : UpdateError.INTERNAL, failed.error()));
    }

    private void onVerified(VerifiedApk apk) {
        ready = apk;
        setState(UpdateState.ready(apk.version()));
        if (installWhenReady) install();
    }

    // ------------------------------------------------------------ install

    private void install() {
        if (state.kind != UpdateState.Kind.READY) return;
        VerifiedApk apk = ready;
        installWhenReady = false;
        // Written before the call: the installation replaces this very process.
        store.savePending(new Pending(apk.version().toString(), clock.getAsLong()));
        setState(UpdateState.installing(apk.version()));
        worker.execute(() -> {
            FetchResult<String> result;
            try {
                result = session.install(apk);
            } catch (RuntimeException e) {
                result = ShellError.fail(ShellError.COMMAND_FAILED, e.toString());
            }
            FetchResult<String> outcome = result;
            main.execute(() -> onInstallFinished(apk, outcome));
        });
    }

    /** Only reached if the process survived the installation, or it failed. */
    private void onInstallFinished(VerifiedApk apk, FetchResult<String> result) {
        if (result.isOk()) {
            ready = null;
            setState(UpdateState.IDLE);
            return;
        }
        store.clearPending();
        String version = apk.version().toString();
        int failures = store.installFailures(version) + 1;
        store.saveInstallFailures(version, failures);
        ShellError shell = ShellError.of(result);
        UpdateError kind = UpdateError.fromShell(shell != null ? shell : ShellError.COMMAND_FAILED);
        if (failures >= MAX_INSTALL_FAILURES) {
            ready = null;
            worker.execute(downloader::clear);
            setState(UpdateState.error(UpdateError.INSTALL_FAILED_REPEATEDLY, result.error()));
        } else {
            setState(UpdateState.error(kind, result.error())); // the verified APK stays for the retry
        }
    }

    // ------------------------------------------------------------ state

    private void setState(UpdateState next) {
        state = next;
        if (UpdatePolicy.bootAnswered(next.kind, next.error)) bootAnswered = true;
        if (next.kind == UpdateState.Kind.UP_TO_DATE || next.kind == UpdateState.Kind.ERROR
                || next.kind == UpdateState.Kind.IDLE) {
            installWhenReady = false;
        }
        if (next.kind == UpdateState.Kind.ERROR) log.accept("update failed: " + next.error + " " + next.message);
        for (Listener l : new ArrayList<>(listeners)) l.onState(next);
    }
}
