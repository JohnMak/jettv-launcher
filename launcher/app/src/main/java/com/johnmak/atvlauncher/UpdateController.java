package com.johnmak.atvlauncher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
            instance = new UpdateController(current.value(), BuildConfig.VERSION_NAME,
                    ReleaseFeed.github(userAgent),
                    new ApkDownloader(new File(app.getCacheDir(), "update"), userAgent),
                    new ApkVerifier(new PackageInspector(app)),
                    LoopbackAdbSession.shared(app), new UpdatePrefs(app),
                    System::currentTimeMillis, worker, handler::post, msg -> Log.w(TAG, msg));
        }
        return instance;
    }

    private final SemVer current;
    private final ReleaseFeed.Source feed;
    private final Downloader downloader;
    private final ApkVerifier verifier;
    private final AdbSession session;
    private final Store store;
    private final LongSupplier clock;
    private final Executor worker;
    private final Executor main;
    private final Consumer<String> log;
    private final List<Listener> listeners = new ArrayList<>();

    // Main-thread state.
    private UpdateState state = UpdateState.IDLE;
    private VerifiedApk ready;
    private boolean installWhenReady;
    private boolean evaluated;
    private String updatedTo;

    UpdateController(SemVer current, String currentVersionName, ReleaseFeed.Source feed, Downloader downloader,
                     ApkVerifier verifier, AdbSession session, Store store, LongSupplier clock,
                     Executor worker, Executor main, Consumer<String> log) {
        this.current = current;
        this.feed = feed;
        this.downloader = downloader;
        this.verifier = verifier;
        this.session = session;
        this.store = store;
        this.clock = clock;
        this.worker = worker;
        this.main = main;
        this.log = log;
        resolvePending(currentVersionName);
        worker.execute(downloader::clear);
    }

    /** An install started by the previous process either left us at its version (announce it) or went stale. */
    private void resolvePending(String currentVersionName) {
        Pending pending = store.pending();
        if (pending == null) return;
        if (pending.version.equals(currentVersionName)) {
            updatedTo = pending.version;
            store.clearPending();
        } else if (clock.getAsLong() - pending.atMs > PENDING_STALE_MS) {
            log.accept("install of " + pending.version + " did not take effect; running " + currentVersionName);
            store.clearPending();
        }
    }

    // ------------------------------------------------------------ UI API

    UpdateState state() { return state; }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** The version this process was just updated to, once; null if the last start was not an update. */
    String consumeUpdatedVersion() {
        String v = updatedTo;
        updatedTo = null;
        return v;
    }

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
     * Called by the launcher on start and periodically: starts a due check and installs a Ready update once the
     * policy allows. {@code idleMs} is the time since the last remote input.
     */
    void onTick(boolean launcherForeground, long idleMs) {
        UpdatePolicy.Trigger trigger = evaluated ? UpdatePolicy.Trigger.TICK : UpdatePolicy.Trigger.PROCESS_START;
        evaluated = true;
        if (UpdatePolicy.shouldCheck(state.kind, trigger, store.lastCheckAt(), clock.getAsLong())) startPipeline();
        else if (UpdatePolicy.shouldAutoInstall(state.kind, launcherForeground, idleMs)) install();
    }

    // ------------------------------------------------------------ pipeline

    private void startPipeline() {
        setState(UpdateState.CHECKING);
        worker.execute(this::runPipeline);
    }

    /** Worker: feed, download, verify; every outcome is posted to main. */
    private void runPipeline() {
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
            main.execute(() -> onInstallFinished(outcome));
        });
    }

    /** Only reached if the process survived the installation, or it failed. */
    private void onInstallFinished(FetchResult<String> result) {
        ready = null;
        if (result.isOk()) {
            setState(UpdateState.IDLE);
            return;
        }
        store.clearPending();
        ShellError shell = ShellError.of(result);
        UpdateError kind = UpdateError.fromShell(shell != null ? shell : ShellError.COMMAND_FAILED);
        worker.execute(downloader::clear);
        setState(UpdateState.error(kind, result.error()));
    }

    // ------------------------------------------------------------ state

    private void setState(UpdateState next) {
        state = next;
        if (next.kind == UpdateState.Kind.UP_TO_DATE || next.kind == UpdateState.Kind.ERROR
                || next.kind == UpdateState.Kind.IDLE) {
            installWhenReady = false;
        }
        if (next.kind == UpdateState.Kind.ERROR) log.accept("update failed: " + next.error + " " + next.message);
        for (Listener l : new ArrayList<>(listeners)) l.onState(next);
    }
}
