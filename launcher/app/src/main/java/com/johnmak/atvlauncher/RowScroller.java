package com.johnmak.atvlauncher;

import android.view.animation.AnimationUtils;

import androidx.recyclerview.widget.RecyclerView;

/**
 * One continuous, retargetable horizontal scroll of a RecyclerView: a new key press only moves the target, the motion
 * never restarts (unlike smoothScrollBy). Frames come from postOnAnimation; the maths is {@link RowScrollModel}.
 */
final class RowScroller {
    private static final long MAX_FRAME_MS = 64;

    private final RecyclerView row;
    private final Runnable onSettled;
    private final RowScrollModel model = new RowScrollModel();
    /** Pixels of model.pos() already handed to the row. */
    private int applied;
    private long lastFrameMs;
    private boolean running;
    private final Runnable frame = this::onFrame;

    /** @param onSettled runs on the main thread when the row has reached its target */
    RowScroller(RecyclerView row, Runnable onSettled) {
        this.row = row;
        this.onSettled = onSettled;
    }

    /** Pixels the row still has to scroll from where it is drawn now. */
    double pending() {
        return model.target() - applied;
    }

    void retargetBy(double dx) {
        if (dx == 0) return;
        model.retargetBy(dx);
        if (!running) {
            running = true;
            lastFrameMs = 0;
            row.postOnAnimation(frame);
        }
    }

    /** Stops without finishing; the caller positions the row itself. */
    void cancel() {
        row.removeCallbacks(frame);
        running = false;
        model.reset();
        applied = 0;
    }

    private void onFrame() {
        long now = AnimationUtils.currentAnimationTimeMillis();
        long dt = lastFrameMs == 0 ? 16 : Math.max(1, Math.min(MAX_FRAME_MS, now - lastFrameMs));
        lastFrameMs = now;
        model.step(dt);
        int want = (int) Math.round(model.pos());
        int delta = want - applied;
        boolean blocked = delta != 0 && !row.canScrollHorizontally(delta > 0 ? 1 : -1);
        if (delta != 0 && !blocked) {
            row.scrollBy(delta, 0);
            applied = want;
        }
        if (blocked || model.settled()) {
            cancel();
            onSettled.run();
            return;
        }
        row.postOnAnimation(frame);
    }
}
