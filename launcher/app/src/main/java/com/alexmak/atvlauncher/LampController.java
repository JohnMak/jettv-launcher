package com.alexmak.atvlauncher;

import java.util.concurrent.Executor;

/**
 * Drives the lamp brightness control: reads the level, and applies steps optimistically with coalescing (while a
 * write is in flight only the latest target is kept). Never changes the lamp mode. Public methods and listener
 * callbacks run on the main thread; all shell I/O runs on the single-thread {@code worker}.
 */
final class LampController {
    interface Listener {
        void onState(LampState state);
    }

    static final String MSG_UNAUTHORIZED = "Разрешите отладку на экране проектора";
    static final String MSG_UNREACHABLE = "ADB проектора недоступен";
    static final String MSG_TOOL_MISSING = "Управление лампой недоступно";
    static final String MSG_FAILED = "Не удалось изменить яркость";

    private final LampShell shell;
    private final Executor worker;
    private final Executor main;
    private final Listener listener;

    // Main-thread state.
    private LampLevel confirmed;
    private LampLevel target;
    private boolean released;

    // Shared with the worker; guarded by lock.
    private final Object lock = new Object();
    private LampLevel pending;
    private boolean applying;

    LampController(LampShell shell, Executor worker, Executor main, Listener listener) {
        this.shell = shell;
        this.worker = worker;
        this.main = main;
        this.listener = listener;
    }

    /** The single place mapping a failed lamp result to UI text. */
    static String messageFor(FetchResult<?> failed) {
        LampError kind = LampError.of(failed);
        if (kind == null) return MSG_FAILED;
        switch (kind) {
            case UNAUTHORIZED: return MSG_UNAUTHORIZED;
            case UNREACHABLE: return MSG_UNREACHABLE;
            case TOOL_MISSING: return MSG_TOOL_MISSING;
            default: return MSG_FAILED;
        }
    }

    void refresh() {
        if (released) return;
        if (confirmed == null) listener.onState(LampState.loading());
        worker.execute(() -> {
            FetchResult<LampLevel> r = readLevel();
            main.execute(() -> onRefreshed(r));
        });
    }

    /** Moves the level by delta (clamped). Ignored until a level has been read. */
    void step(int delta) {
        if (released || confirmed == null) return;
        LampLevel base = target != null ? target : confirmed;
        LampLevel next = base.step(delta);
        target = next;
        listener.onState(LampState.ready(next.value()));
        boolean start;
        synchronized (lock) {
            pending = next;
            start = !applying;
            applying = true;
        }
        if (start) worker.execute(this::applyLoop);
    }

    void release() {
        released = true;
        worker.execute(shell::close);
    }

    private FetchResult<LampLevel> readLevel() {
        FetchResult<String> raw = shell.exec(GmpfCommands.GET_LEVEL);
        if (!raw.isOk()) return FetchResult.err(raw.error(), LampError.of(raw) != null ? LampError.of(raw) : LampError.COMMAND_FAILED);
        FetchResult<Integer> value = GmpfParser.parseValue(raw.value());
        if (!value.isOk()) return LampError.fail(LampError.COMMAND_FAILED, value.error());
        FetchResult<LampLevel> level = LampLevel.of(value.value());
        if (!level.isOk()) return LampError.fail(LampError.COMMAND_FAILED, level.error());
        return level;
    }

    /** Worker: applies the latest pending target until none is left. */
    private void applyLoop() {
        while (true) {
            LampLevel t;
            synchronized (lock) {
                t = pending;
                pending = null;
                if (t == null) {
                    applying = false;
                    return;
                }
            }
            FetchResult<String> set = shell.exec(GmpfCommands.setLevel(t));
            if (!set.isOk()) {
                main.execute(() -> onApplyFailed(t, set));
                continue;
            }
            synchronized (lock) {
                if (pending != null) continue; // a newer target supersedes: skip the readback
            }
            FetchResult<LampLevel> actual = readLevel();
            main.execute(() -> onApplied(t, actual));
        }
    }

    private void onRefreshed(FetchResult<LampLevel> r) {
        if (released) return;
        if (!r.isOk()) {
            listener.onState(LampState.error(messageFor(r)));
            return;
        }
        if (target != null && !target.equals(confirmed)) return; // a write is outstanding; it reports the real value
        confirmed = r.value();
        target = confirmed;
        listener.onState(LampState.ready(confirmed.value()));
    }

    private void onApplied(LampLevel applied, FetchResult<LampLevel> actual) {
        if (released || !applied.equals(target)) return; // superseded by a newer step
        if (!actual.isOk()) {
            target = confirmed;
            listener.onState(LampState.error(messageFor(actual)));
            return;
        }
        confirmed = actual.value();
        target = confirmed;
        listener.onState(LampState.ready(confirmed.value()));
    }

    private void onApplyFailed(LampLevel attempted, FetchResult<String> failed) {
        if (released) return;
        if (attempted.equals(target)) target = confirmed;
        listener.onState(LampState.error(messageFor(failed)));
    }
}
