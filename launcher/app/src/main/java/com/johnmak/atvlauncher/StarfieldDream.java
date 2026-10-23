package com.johnmak.atvlauncher;

import android.service.dreams.DreamService;

/** System screensaver (dream) showing the same screensaver (starfield or NASA video, per settings) + clock as the in-launcher one. */
public final class StarfieldDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        Screensaver screensaver = new Screensaver(this);
        screensaver.prepare(new LauncherSettingsStore(this).load());
        setContentView(screensaver);
    }
}
