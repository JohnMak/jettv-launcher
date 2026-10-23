package com.alexmak.atvlauncher;

/**
 * Pure key/mode logic of the top-bar brightness item. The owner reports focus gained or lost on the sun item and
 * the D-pad keys; the model answers with the single action to execute. Adjust mode exists only while the sun is
 * focused: OK enters it, OK or BACK leave it, LEFT/RIGHT step the level and UP/DOWN are swallowed.
 */
final class BrightnessKeyModel {
    enum Key { OK, BACK, LEFT, RIGHT, UP, DOWN }

    enum Kind {
        /** Show the popup under the sun and refresh the level. */
        SHOW,
        /** Hide the popup; adjust mode (if any) ends with it. */
        HIDE,
        ENTER_ADJUST,
        EXIT_ADJUST,
        /** Move the level by {@link Action#delta}. */
        STEP,
        /** The key is handled and must do nothing else. */
        CONSUME,
        /** Not ours: normal handling continues. */
        PASS
    }

    static final class Action {
        static final Action SHOW = new Action(Kind.SHOW, 0);
        static final Action HIDE = new Action(Kind.HIDE, 0);
        static final Action ENTER_ADJUST = new Action(Kind.ENTER_ADJUST, 0);
        static final Action EXIT_ADJUST = new Action(Kind.EXIT_ADJUST, 0);
        static final Action CONSUME = new Action(Kind.CONSUME, 0);
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

    private boolean onSun;
    private boolean adjusting;

    boolean isAdjusting() {
        return adjusting;
    }

    /** Reports whether the sun item is focused now; only a change produces an action (SHOW or HIDE), otherwise PASS. */
    Action onFocus(boolean focused) {
        if (focused == onSun) return Action.PASS;
        onSun = focused;
        if (focused) return Action.SHOW;
        adjusting = false;
        return Action.HIDE;
    }

    Action onKey(Key key) {
        if (!onSun) return Action.PASS;
        if (!adjusting) {
            if (key != Key.OK) return Action.PASS;
            adjusting = true;
            return Action.ENTER_ADJUST;
        }
        switch (key) {
            case OK:
            case BACK:
                adjusting = false;
                return Action.EXIT_ADJUST;
            case LEFT:
                return Action.step(-1);
            case RIGHT:
                return Action.step(1);
            default:
                return Action.CONSUME;
        }
    }
}
