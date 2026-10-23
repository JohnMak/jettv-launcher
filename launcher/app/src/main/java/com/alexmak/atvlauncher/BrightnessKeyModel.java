package com.alexmak.atvlauncher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure key/mode logic of the top-bar brightness item. The owner reports focus gained or lost on the sun item and
 * the D-pad keys; the model answers with the actions to execute in order. Adjust mode exists only while the sun is
 * focused: DOWN enters it, LEFT/RIGHT step the level, UP or BACK leave it and DOWN leaves it and lets the owner move focus
 * down to the content; the owner then reports the real focus again (the move may fail), which hides the popup. The sun has no OK action.
 */
final class BrightnessKeyModel {
    enum Key { BACK, LEFT, RIGHT, UP, DOWN }

    enum Kind {
        /** Show the popup under the sun and refresh the level. */
        SHOW,
        /** Hide the popup; adjust mode (if any) ends with it. */
        HIDE,
        ENTER_ADJUST,
        EXIT_ADJUST,
        /** Move the level by {@link Action#delta}. */
        STEP,
        /** Normal handling of the key continues after the other actions of the list ran. */
        PASS
    }

    static final class Action {
        static final Action SHOW = new Action(Kind.SHOW, 0);
        static final Action HIDE = new Action(Kind.HIDE, 0);
        static final Action ENTER_ADJUST = new Action(Kind.ENTER_ADJUST, 0);
        static final Action EXIT_ADJUST = new Action(Kind.EXIT_ADJUST, 0);
        static final Action PASS = new Action(Kind.PASS, 0);

        final Kind kind;
        /** Position delta for STEP, 0 otherwise. */
        final int delta;

        private Action(Kind kind, int delta) {
            this.kind = kind;
            this.delta = delta;
        }

        static Action step(int delta) {
            return new Action(Kind.STEP, delta);
        }
    }

    private static final List<Action> PASS_ONLY = Collections.singletonList(Action.PASS);
    private static final List<Action> ENTER = Collections.singletonList(Action.ENTER_ADJUST);
    private static final List<Action> EXIT = Collections.singletonList(Action.EXIT_ADJUST);
    private static final List<Action> LEAVE_DOWN =
            Collections.unmodifiableList(Arrays.asList(Action.EXIT_ADJUST, Action.PASS));
    private static final List<Action> STEP_LEFT = Collections.singletonList(Action.step(-1));
    private static final List<Action> STEP_RIGHT = Collections.singletonList(Action.step(1));

    private boolean onSun;
    private boolean adjusting;

    /** Reports whether the sun item is focused now; only a change produces an action (SHOW or HIDE), otherwise PASS. */
    Action onFocus(boolean focused) {
        if (focused == onSun) return Action.PASS;
        onSun = focused;
        if (focused) return Action.SHOW;
        adjusting = false;
        return Action.HIDE;
    }

    /** Immutable actions for the key, in execution order; a list containing PASS leaves the key to normal handling. */
    List<Action> onKey(Key key) {
        if (!onSun) return PASS_ONLY;
        if (!adjusting) {
            if (key != Key.DOWN) return PASS_ONLY;
            adjusting = true;
            return ENTER;
        }
        switch (key) {
            case LEFT:
                return STEP_LEFT;
            case RIGHT:
                return STEP_RIGHT;
            case UP:
            case BACK:
                adjusting = false;
                return EXIT;
            default: // DOWN: leave adjust; whether the popup hides depends on the real focus the owner reports next
                adjusting = false;
                return LEAVE_DOWN;
        }
    }
}
