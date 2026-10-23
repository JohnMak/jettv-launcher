package com.johnmak.atvlauncher;

import android.graphics.Color;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Idle dim and in-launcher screensaver. Owns a black dim view and the {@link Screensaver}, both added on top of {@code root}.
 * Timing comes from {@link IdleTimeline} built from the {@link LauncherSettings} at every start; any key while dimmed or in the screensaver only wakes the screen.
 */
final class IdleController {
    private static final float DIM_ALPHA = 0.6f;
    private static final long FADE_IN_MS = 1000;
    private static final long WAKE_MS = 300;
    private static final int NO_KEY = -1;

    private final Handler main;
    private final Runnable onScreensaverStart;
    private final View dim;
    private final Screensaver screensaver;
    private final Runnable tick = this::onTick;
    private final LauncherSettingsStore settingsStore;
    private LauncherSettings settings = LauncherSettings.DEFAULTS;
    private IdleTimeline timeline = new IdleTimeline(LauncherSettings.DEFAULTS);
    private IdleTimeline.Stage stage = IdleTimeline.Stage.ACTIVE;
    private long lastInput;
    private boolean running;
    private int swallowUpOf = NO_KEY;

    /**
     * @param onScreensaverStart called when the screensaver starts covering the screen
     */
    IdleController(FrameLayout root, Handler main, Runnable onScreensaverStart) {
        this.main = main;
        this.onScreensaverStart = onScreensaverStart;
        settingsStore = new LauncherSettingsStore(root.getContext());
        dim = new View(root.getContext());
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        screensaver = new Screensaver(root.getContext());
        screensaver.setAlpha(0f);
        screensaver.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(dim, lp);
        root.addView(screensaver, lp);
    }

    Screensaver screensaver() {
        return screensaver;
    }

    /** Reads the current settings and starts counting from now; call from onStart. */
    void start() {
        settings = settingsStore.load();
        timeline = new IdleTimeline(settings);
        running = true;
        lastInput = SystemClock.uptimeMillis();
        schedule();
    }

    /** Stops the timers and drops any dim immediately; call from onStop. */
    void stop() {
        running = false;
        main.removeCallbacks(tick);
        swallowUpOf = NO_KEY;
        stage = IdleTimeline.Stage.ACTIVE;
        for (View v : new View[]{dim, screensaver}) {
            v.animate().cancel();
            v.setAlpha(0f);
            v.setVisibility(View.GONE);
        }
    }

    /** True between onStart and onStop: the home screen is up and {@link #idleMillis} is meaningful. */
    boolean isRunning() {
        return running;
    }

    /** Time since the last remote input (or since start). */
    long idleMillis() {
        return SystemClock.uptimeMillis() - lastInput;
    }

    /**
     * Feed every key event first. Returns true when the event must not reach the UI: any key that wakes a dimmed
     * screen, and the key-up that belongs to it.
     */
    boolean consumes(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (stage != IdleTimeline.Stage.ACTIVE) {
            wake();
            if (down) swallowUpOf = e.getKeyCode();
            return true;
        }
        if (!down && e.getKeyCode() == swallowUpOf) {
            swallowUpOf = NO_KEY;
            return true;
        }
        touch();
        return false;
    }

    /** Any other user interaction (touch, mouse): wakes without consuming. */
    void onInteraction() {
        if (stage != IdleTimeline.Stage.ACTIVE) wake();
        else touch();
    }

    private void touch() {
        if (!running) return;
        lastInput = SystemClock.uptimeMillis();
        schedule();
    }

    private void wake() {
        stage = IdleTimeline.Stage.ACTIVE;
        dim.animate().cancel();
        screensaver.animate().cancel();
        dim.animate().alpha(0f).setDuration(WAKE_MS).withEndAction(() -> hideIfActive(dim)).start();
        screensaver.animate().alpha(0f).setDuration(WAKE_MS).withEndAction(() -> hideIfActive(screensaver)).start();
        touch();
    }

    private void hideIfActive(View v) {
        if (stage == IdleTimeline.Stage.ACTIVE) v.setVisibility(View.GONE);
    }

    private void schedule() {
        main.removeCallbacks(tick);
        long wait = timeline.untilNextStage(SystemClock.uptimeMillis() - lastInput);
        if (wait >= 0) main.postDelayed(tick, wait);
    }

    private void onTick() {
        if (!running) return;
        IdleTimeline.Stage next = timeline.at(SystemClock.uptimeMillis() - lastInput);
        if (next != stage) enter(next);
        schedule();
    }

    private void enter(IdleTimeline.Stage next) {
        stage = next;
        switch (next) {
            case DIM:
                dim.animate().cancel();
                dim.setVisibility(View.VISIBLE);
                dim.animate().alpha(DIM_ALPHA).setDuration(FADE_IN_MS).start();
                break;
            case SCREENSAVER:
                screensaver.animate().cancel();
                onScreensaverStart.run();
                screensaver.prepare(settings);
                screensaver.setVisibility(View.VISIBLE);
                screensaver.animate().alpha(1f).setDuration(FADE_IN_MS).start();
                break;
            default:
                throw new IllegalStateException("wake goes through wake()");
        }
    }
}
