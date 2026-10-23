package com.johnmak.atvlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Grants the missing rights through the shared {@link AdbSession} on the worker executor, then re-probes and reports
 * to the main executor. One service, one path: each right maps to exactly one {@link ShellCommand.Pm} command.
 */
final class RightsFixer {
    /** What is still missing after the run and the errors of the commands that failed. */
    static final class Outcome {
        final List<RequiredRights.Right> stillMissing;
        final List<FetchResult<String>> errors;

        Outcome(List<RequiredRights.Right> stillMissing, List<FetchResult<String>> errors) {
            this.stillMissing = stillMissing;
            this.errors = errors;
        }

        /** Kind of the first failed command (null when none failed or the failure had no shell kind). */
        ShellError firstError() {
            return errors.isEmpty() ? null : ShellError.of(errors.get(0));
        }
    }

    interface Listener {
        void onDone(Outcome outcome);
    }

    private final AdbSession session;
    private final PackageName own;
    private final RequiredRights.Probe probe;
    private final Executor worker;
    private final Executor main;

    RightsFixer(AdbSession session, PackageName own, RequiredRights.Probe probe, Executor worker, Executor main) {
        this.session = session;
        this.own = own;
        this.probe = probe;
        this.worker = worker;
        this.main = main;
    }

    /** Grants every missing right except HOME. */
    void grantMissing(Listener listener) {
        worker.execute(() -> {
            List<FetchResult<String>> errors = new ArrayList<>();
            for (RequiredRights.Right r : RequiredRights.withoutHome(RequiredRights.missing(probe))) {
                FetchResult<String> res = session.exec(commandFor(r));
                if (!res.isOk()) errors.add(res);
            }
            finish(listener, errors);
        });
    }

    /** Makes the launcher the default home app. */
    void setHome(Listener listener) {
        worker.execute(() -> {
            List<FetchResult<String>> errors = new ArrayList<>();
            FetchResult<String> res = session.exec(ShellCommand.Pm.setHome(own));
            if (!res.isOk()) errors.add(res);
            finish(listener, errors);
        });
    }

    private void finish(Listener listener, List<FetchResult<String>> errors) {
        Outcome outcome = new Outcome(RequiredRights.missing(probe), errors);
        main.execute(() -> listener.onDone(outcome));
    }

    private ShellCommand commandFor(RequiredRights.Right right) {
        switch (right) {
            case FINE_LOCATION: return ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.ACCESS_FINE_LOCATION);
            case TV_LISTINGS: return ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.READ_TV_LISTINGS);
            case SECURE_SETTINGS: return ShellCommand.Pm.grant(own, ShellCommand.GrantablePermission.WRITE_SECURE_SETTINGS);
            case USAGE_STATS: return ShellCommand.Pm.allowUsageStats(own);
            default: throw new IllegalArgumentException("not grantable: " + right);
        }
    }
}
