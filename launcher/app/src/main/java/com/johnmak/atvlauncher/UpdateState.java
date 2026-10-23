package com.johnmak.atvlauncher;

/** What the updater is doing, as the UI sees it. Immutable. */
final class UpdateState {
    enum Kind { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, VERIFYING, READY, INSTALLING, ERROR }

    static final int NO_PROGRESS = -1;

    final Kind kind;
    /** The release being fetched / ready / installed; null otherwise. */
    final SemVer version;
    /** 0..100 while DOWNLOADING, {@link #NO_PROGRESS} when the size is unknown or not downloading. */
    final int progress;
    /** Set only for ERROR. */
    final UpdateError error;
    /** Human-readable cause for ERROR (logs, details line). */
    final String message;

    private UpdateState(Kind kind, SemVer version, int progress, UpdateError error, String message) {
        this.kind = kind;
        this.version = version;
        this.progress = progress;
        this.error = error;
        this.message = message;
    }

    static final UpdateState IDLE = new UpdateState(Kind.IDLE, null, NO_PROGRESS, null, null);
    static final UpdateState CHECKING = new UpdateState(Kind.CHECKING, null, NO_PROGRESS, null, null);
    static final UpdateState UP_TO_DATE = new UpdateState(Kind.UP_TO_DATE, null, NO_PROGRESS, null, null);

    static UpdateState downloading(SemVer v, int progress) {
        return new UpdateState(Kind.DOWNLOADING, v, progress, null, null);
    }

    static UpdateState verifying(SemVer v) { return new UpdateState(Kind.VERIFYING, v, NO_PROGRESS, null, null); }

    static UpdateState ready(SemVer v) { return new UpdateState(Kind.READY, v, NO_PROGRESS, null, null); }

    static UpdateState installing(SemVer v) { return new UpdateState(Kind.INSTALLING, v, NO_PROGRESS, null, null); }

    static UpdateState error(UpdateError error, String message) {
        return new UpdateState(Kind.ERROR, null, NO_PROGRESS, error, message);
    }

    /** A pipeline step is running: no new check or install may start. */
    boolean isBusy() {
        return kind == Kind.CHECKING || kind == Kind.DOWNLOADING || kind == Kind.VERIFYING || kind == Kind.INSTALLING;
    }
}
