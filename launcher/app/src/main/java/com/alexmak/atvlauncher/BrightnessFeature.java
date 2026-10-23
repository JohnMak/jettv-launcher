package com.alexmak.atvlauncher;

import java.util.Collections;
import java.util.List;

/**
 * The brightness feature as MainActivity sees it: either fully present (sun item, popup, controller) or
 * {@link #ABSENT}, which has no top-bar item and does nothing.
 */
interface BrightnessFeature {
    /** The top-bar items this feature contributes: one sun item, or none. */
    List<TopBarItem> topItems();

    void show();

    void hide();

    void setAdjusting(boolean adjusting);

    void step(int delta);

    void disconnect();

    void release();

    BrightnessFeature ABSENT = new BrightnessFeature() {
        @Override public List<TopBarItem> topItems() { return Collections.emptyList(); }
        @Override public void show() {}
        @Override public void hide() {}
        @Override public void setAdjusting(boolean adjusting) {}
        @Override public void step(int delta) {}
        @Override public void disconnect() {}
        @Override public void release() {}
    };
}
