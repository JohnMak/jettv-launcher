package com.alexmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IdleTimelineTest {
    private static IdleTimeline of(boolean screensaver, int minutes, boolean dim, int seconds) {
        return new IdleTimeline(new LauncherSettings(screensaver, minutes, LauncherSettings.ScreensaverType.STARFIELD, dim, seconds, true, "", 100, 100));
    }

    @Test
    public void defaultsChangeStageExactlyAtTheThresholds() {
        IdleTimeline t = new IdleTimeline(LauncherSettings.DEFAULTS);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(0));
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(29_999));
        assertEquals(IdleTimeline.Stage.DIM, t.at(30_000));
        assertEquals(IdleTimeline.Stage.DIM, t.at(119_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(120_000));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(10_000_000));
    }

    @Test
    public void timeUntilTheNextStage() {
        IdleTimeline t = new IdleTimeline(LauncherSettings.DEFAULTS);
        assertEquals(30_000, t.untilNextStage(0));
        assertEquals(1, t.untilNextStage(29_999));
        assertEquals(90_000, t.untilNextStage(30_000));
        assertEquals(1, t.untilNextStage(119_999));
        assertEquals(-1, t.untilNextStage(120_000));
    }

    @Test
    public void thresholdsComeFromTheSettings() {
        IdleTimeline t = of(true, 5, true, 15);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(14_999));
        assertEquals(IdleTimeline.Stage.DIM, t.at(15_000));
        assertEquals(IdleTimeline.Stage.DIM, t.at(299_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(300_000));
        assertEquals(285_000, t.untilNextStage(15_000));
    }

    @Test
    public void disabledDimIsSkipped() {
        IdleTimeline t = of(true, 1, false, 30);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(59_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(60_000));
        assertEquals(60_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(60_000));
    }

    @Test
    public void disabledScreensaverNeverStarts() {
        IdleTimeline t = of(false, 1, true, 30);
        assertEquals(IdleTimeline.Stage.DIM, t.at(10_000_000));
        assertEquals(30_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(30_000));
    }

    @Test
    public void bothDisabledStayActive() {
        IdleTimeline t = of(false, 1, false, 30);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(10_000_000));
        assertEquals(-1, t.untilNextStage(0));
    }

    @Test
    public void screensaverBeforeDimSkipsTheDim() {
        IdleTimeline t = of(true, 1, true, 120); // dim would start after the screensaver
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(59_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(60_000));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(130_000));
        assertEquals(60_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(60_000));
    }
}
