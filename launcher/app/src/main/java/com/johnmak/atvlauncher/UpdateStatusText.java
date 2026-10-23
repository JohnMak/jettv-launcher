package com.johnmak.atvlauncher;

/** Pure mapping UpdateState to the value text of the «Проверить обновления» row. */
final class UpdateStatusText {
    /** Resolves a string resource with format args (Context::getString in the app, a stub in tests). */
    interface Strings {
        String get(int resId, Object... args);
    }

    private UpdateStatusText() { }

    static String of(UpdateState s, Strings str) {
        String v = s.version == null ? "" : s.version.toString();
        switch (s.kind) {
            case IDLE: return "";
            case CHECKING: return str.get(R.string.update_checking);
            case UP_TO_DATE: return str.get(R.string.update_up_to_date);
            case DOWNLOADING:
                if (s.progress == UpdateState.NO_PROGRESS) return str.get(R.string.update_downloading_unknown, v);
                return str.get(R.string.update_downloading, v, s.progress);
            case VERIFYING: return str.get(R.string.update_verifying);
            case READY: return str.get(R.string.update_ready, v);
            case INSTALLING: return str.get(R.string.update_installing);
            case ERROR: return str.get(R.string.update_error, str.get(errorRes(s.error)));
            default: throw new IllegalArgumentException(s.kind.name());
        }
    }

    static int errorRes(UpdateError e) {
        switch (e) {
            case NETWORK: return R.string.update_err_network;
            case FEED_INVALID: return R.string.update_err_feed;
            case DOWNLOAD_FAILED: return R.string.update_err_download;
            case VERIFY_FAILED: return R.string.update_err_verify;
            case ADB_UNAUTHORIZED: return R.string.update_err_adb_unauthorized;
            case ADB_UNREACHABLE: return R.string.update_err_adb_unreachable;
            case INSTALL_FAILED: return R.string.update_err_install;
            case INTERNAL: return R.string.update_err_internal;
            default: throw new IllegalArgumentException(e.name());
        }
    }
}
