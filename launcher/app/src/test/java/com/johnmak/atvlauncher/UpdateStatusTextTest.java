package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UpdateStatusTextTest {
    private static final UpdateStatusText.Strings STUB = (id, args) -> {
        StringBuilder b = new StringBuilder("#" + id);
        for (Object a : args) b.append('|').append(a);
        return b.toString();
    };

    private static SemVer v(String s) { return SemVer.parse(s).value(); }

    private static String text(UpdateState s) { return UpdateStatusText.of(s, STUB); }

    @Test
    public void mapsEveryKind() {
        assertEquals("", text(UpdateState.IDLE));
        assertEquals("#" + R.string.update_checking, text(UpdateState.CHECKING));
        assertEquals("#" + R.string.update_up_to_date, text(UpdateState.UP_TO_DATE));
        assertEquals("#" + R.string.update_downloading + "|0.1.1|42", text(UpdateState.downloading(v("0.1.1"), 42)));
        assertEquals("#" + R.string.update_downloading_unknown + "|0.1.1",
                text(UpdateState.downloading(v("0.1.1"), UpdateState.NO_PROGRESS)));
        assertEquals("#" + R.string.update_verifying, text(UpdateState.verifying(v("0.1.1"))));
        assertEquals("#" + R.string.update_ready + "|0.1.1", text(UpdateState.ready(v("0.1.1"))));
        assertEquals("#" + R.string.update_installing, text(UpdateState.installing(v("0.1.1"))));
    }

    @Test
    public void mapsEveryErrorKind() {
        for (UpdateError e : UpdateError.values()) {
            assertEquals("#" + R.string.update_error + "|#" + UpdateStatusText.errorRes(e),
                    text(UpdateState.error(e, "x")));
        }
        assertEquals(R.string.update_err_adb_unauthorized, UpdateStatusText.errorRes(UpdateError.ADB_UNAUTHORIZED));
    }
}
