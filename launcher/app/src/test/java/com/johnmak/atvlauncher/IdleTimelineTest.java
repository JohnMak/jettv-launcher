package com.johnmak.atvlauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IdleTimelineTest {
    private static IdleTimeline of(int minutes, int seconds) {
        return new IdleTimeline(new LauncherSettings(minutes, LauncherSettings.ScreensaverType.STARFIELD, seconds, true, "", 100, 100, false, ArtPreset.ALL));
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
        IdleTimeline t = of(5, 15);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(14_999));
        assertEquals(IdleTimeline.Stage.DIM, t.at(15_000));
        assertEquals(IdleTimeline.Stage.DIM, t.at(299_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(300_000));
        assertEquals(285_000, t.untilNextStage(15_000));
    }

    @Test
    public void dimDelayZeroIsSkipped() {
        IdleTimeline t = of(1, 0);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(59_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(60_000));
        assertEquals(60_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(60_000));
    }

    @Test
    public void screensaverDelayZeroNeverStarts() {
        IdleTimeline t = of(0, 30);
        assertEquals(IdleTimeline.Stage.DIM, t.at(10_000_000));
        assertEquals(30_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(30_000));
    }

    @Test
    public void bothDelaysZeroStayActive() {
        IdleTimeline t = of(0, 0);
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(10_000_000));
        assertEquals(-1, t.untilNextStage(0));
    }

    @Test
    public void screensaverBeforeDimSkipsTheDim() {
        IdleTimeline t = of(1, 120); // dim would start after the screensaver
        assertEquals(IdleTimeline.Stage.ACTIVE, t.at(59_999));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(60_000));
        assertEquals(IdleTimeline.Stage.SCREENSAVER, t.at(130_000));
        assertEquals(60_000, t.untilNextStage(0));
        assertEquals(-1, t.untilNextStage(60_000));
    }
}
