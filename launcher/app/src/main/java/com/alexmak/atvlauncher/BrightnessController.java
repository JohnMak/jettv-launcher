package com.alexmak.atvlauncher;

import java.util.concurrent.Executor;

/**
 * Drives the unified brightness scale: reads lamp level, brightness and contrast, and applies position steps
 * optimistically with coalescing (while a write is in flight only the latest target position is kept). Only the
 * parts of the lamp/picture pair that differ from the device are written; never changes the lamp or picture mode.
 * Public methods and listener callbacks run on the main thread; all shell I/O runs on the single-thread
 * {@code worker}.
 */
final class BrightnessController {
    interface Listener {
        void onState(LampState state);
    }

    static final String MSG_UNAUTHORIZED = "Разрешите отладку на экране проектора";
    static final String MSG_UNREACHABLE = "ADB проектора недоступен";
    static final String MSG_TOOL_MISSING = "Управление лампой недоступно";
    static final String MSG_FAILED = "Не удалось изменить яркость";

    private static final int UNKNOWN = -1;

    private final LampShell shell;
    private final Executor worker;
    private final Executor main;
    private final Listener listener;

    // Main-thread state.
    private BrightnessScale.Position confirmed;
    private BrightnessScale.Position target;
    private boolean released;

    // Worker-thread state: what the device is known to hold (last read or last successful write).
    private int deviceLamp = UNKNOWN;
    private int deviceBrightness = UNKNOWN;
    private int deviceContrast = UNKNOWN;
    private BrightnessScale.Position devicePosition;

    // Shared with the worker; guarded by lock.
    private final Object lock = new Object();
    private BrightnessScale.Position pending;
    private boolean applying;

    BrightnessController(LampShell shell, Executor worker, Executor main, Listener listener) {
        this.shell = shell;
        this.worker = worker;
        this.main = main;
        this.listener = listener;
    }

    /** The single place mapping a failed shell result to UI text. */
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
            FetchResult<BrightnessScale.Position> r = readDevice();
            main.execute(() -> onRefreshed(r));
        });
    }

    /** Moves the position by delta (clamped). Ignored until a position has been read. */
    void step(int delta) {
        if (released || confirmed == null) return;
        BrightnessScale.Position base = target != null ? target : confirmed;
        BrightnessScale.Position next = base.step(delta);
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

    private FetchResult<Integer> readValue(String command, int min, int max, String what) {
        FetchResult<String> raw = shell.exec(command);
        if (!raw.isOk()) {
            return FetchResult.err(raw.error(), LampError.of(raw) != null ? LampError.of(raw) : LampError.COMMAND_FAILED);
        }
        FetchResult<Integer> value = GmpfParser.parseValue(raw.value());
        if (!value.isOk()) return LampError.fail(LampError.COMMAND_FAILED, value.error());
        if (value.value() < min || value.value() > max) {
            return LampError.fail(LampError.COMMAND_FAILED, what + " must be " + min + ".." + max + ": " + value.value());
        }
        return value;
    }

    /** Worker: reads lamp, brightness and contrast; on success records them as the known device state. */
    private FetchResult<BrightnessScale.Position> readDevice() {
        FetchResult<Integer> lamp = readValue(GmpfCommands.GET_LEVEL, LampLevel.MIN_LEVEL, LampLevel.MAX_LEVEL, "lamp level");
        if (!lamp.isOk()) return FetchResult.err(lamp.error(), LampError.of(lamp));
        FetchResult<Integer> brightness = readValue(GmpfCommands.GET_BRIGHTNESS,
                GmpfCommands.PICTURE_MIN, GmpfCommands.PICTURE_MAX, "brightness");
        if (!brightness.isOk()) return FetchResult.err(brightness.error(), LampError.of(brightness));
        FetchResult<Integer> contrast = readValue(GmpfCommands.GET_CONTRAST,
                GmpfCommands.PICTURE_MIN, GmpfCommands.PICTURE_MAX, "contrast");
        if (!contrast.isOk()) return FetchResult.err(contrast.error(), LampError.of(contrast));
        deviceLamp = lamp.value();
        deviceBrightness = brightness.value();
        deviceContrast = contrast.value();
        devicePosition = BrightnessScale.fromDevice(LampLevel.of(deviceLamp).value(), deviceBrightness, deviceContrast);
        return FetchResult.ok(devicePosition);
    }

    /**
     * Worker: writes the parts of target that differ from the device. When the position decreases the lamp goes
     * first, when it increases the picture goes first, so the image never overshoots on the way.
     */
    private FetchResult<String> write(BrightnessScale.Position t) {
        boolean lampFirst = t.value() < devicePosition.value();
        FetchResult<String> r = lampFirst ? writeLamp(t) : writePicture(t);
        if (!r.isOk()) return r;
        return lampFirst ? writePicture(t) : writeLamp(t);
    }

    private FetchResult<String> writeLamp(BrightnessScale.Position t) {
        LampLevel lamp = BrightnessScale.lamp(t);
        if (lamp.value() == deviceLamp) return FetchResult.ok("");
        FetchResult<String> r = shell.exec(GmpfCommands.setLevel(lamp));
        if (r.isOk()) deviceLamp = lamp.value();
        return r;
    }

    private FetchResult<String> writePicture(BrightnessScale.Position t) {
        int picture = BrightnessScale.picture(t);
        if (picture != deviceBrightness) {
            FetchResult<String> r = shell.exec(GmpfCommands.setBrightness(picture));
            if (!r.isOk()) return r;
            deviceBrightness = picture;
        }
        if (picture != deviceContrast) {
            FetchResult<String> r = shell.exec(GmpfCommands.setContrast(picture));
            if (!r.isOk()) return r;
            deviceContrast = picture;
        }
        return FetchResult.ok("");
    }

    /** Worker: applies the latest pending target until none is left. */
    private void applyLoop() {
        while (true) {
            BrightnessScale.Position t;
            synchronized (lock) {
                t = pending;
                pending = null;
                if (t == null) {
                    applying = false;
                    return;
                }
            }
            FetchResult<String> set = write(t);
            if (!set.isOk()) {
                // A failed write may have landed partially: forget what we knew so the next apply rewrites all.
                deviceLamp = UNKNOWN;
                deviceBrightness = UNKNOWN;
                deviceContrast = UNKNOWN;
                main.execute(() -> onApplyFailed(t, set));
                continue;
            }
            devicePosition = t;
            synchronized (lock) {
                if (pending != null) continue; // a newer target supersedes: skip the readback
            }
            FetchResult<BrightnessScale.Position> actual = readDevice();
            main.execute(() -> onApplied(t, actual));
        }
    }

    private void onRefreshed(FetchResult<BrightnessScale.Position> r) {
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

    private void onApplied(BrightnessScale.Position applied, FetchResult<BrightnessScale.Position> actual) {
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

    private void onApplyFailed(BrightnessScale.Position attempted, FetchResult<String> failed) {
        if (released) return;
        if (attempted.equals(target)) target = confirmed;
        listener.onState(LampState.error(messageFor(failed)));
    }
}
