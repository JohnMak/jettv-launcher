package com.johnmak.atvlauncher;

/** Two-press confirmation: the first press arms a key, a second press on the same key confirms; any other key re-arms. */
final class CleanupConfirm {
    private String armed;

    /** True when this press confirms (and disarms); otherwise arms {@code key} and returns false. */
    boolean press(String key) {
        if (key.equals(armed)) {
            armed = null;
            return true;
        }
        armed = key;
        return false;
    }

    boolean isArmed(String key) { return key.equals(armed); }

    void disarm() { armed = null; }
}
