package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RightsPromptTest {
    @Test public void opensOnlyOnceWhenRightsAreMissingAndTheProcessWasNotStartedByAnUpdate() {
        assertEquals(RightsPrompt.Decision.OPEN, RightsPrompt.decide(false, true, false));
        assertEquals(RightsPrompt.Decision.NOTHING, RightsPrompt.decide(true, true, false));
        assertEquals(RightsPrompt.Decision.NOTHING, RightsPrompt.decide(false, false, false));
        assertEquals(RightsPrompt.Decision.NOTHING, RightsPrompt.decide(false, false, true));
    }

    @Test public void updateStartedProcessNeverOpensSettingsAndConsumesThePrompt() {
        assertEquals(RightsPrompt.Decision.SKIP_AFTER_UPDATE, RightsPrompt.decide(false, true, true));
        assertEquals(RightsPrompt.Decision.NOTHING, RightsPrompt.decide(true, true, true));
    }
}
