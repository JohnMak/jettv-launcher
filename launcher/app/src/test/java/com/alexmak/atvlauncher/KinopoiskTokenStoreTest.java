package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KinopoiskTokenStoreTest {
    private static final String TOKEN = "0a1b2c3d-4e5f-6789-abcd-ef0123456789";

    @Test
    public void uuidIsAcceptedAndTrimmed() {
        FetchResult<String> r = KinopoiskTokenStore.validate("  " + TOKEN + "\n");
        assertTrue(r.isOk());
        assertEquals(TOKEN, r.value());
        assertTrue(KinopoiskTokenStore.validate(TOKEN.toUpperCase()).isOk());
    }

    @Test
    public void everythingElseIsRejected() {
        assertFalse(KinopoiskTokenStore.validate(null).isOk());
        assertFalse(KinopoiskTokenStore.validate("").isOk());
        assertFalse(KinopoiskTokenStore.validate("   ").isOk());
        assertFalse(KinopoiskTokenStore.validate("not-a-token").isOk());
        assertFalse(KinopoiskTokenStore.validate(TOKEN.replace("-", "")).isOk());
        assertFalse(KinopoiskTokenStore.validate(TOKEN + "0").isOk());
        assertFalse(KinopoiskTokenStore.validate("0a1b2c3d-4e5f-6789-abcd-ef012345678g").isOk());
    }

    @Test
    public void maskShowsOnlyTheLastFour() {
        assertEquals("••••6789", KinopoiskTokenStore.mask(TOKEN));
        assertEquals("", KinopoiskTokenStore.mask(""));
    }
}
