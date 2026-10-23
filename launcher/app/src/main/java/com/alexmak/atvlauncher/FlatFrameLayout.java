package com.alexmak.atvlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A FrameLayout whose children never overlap visibly, so animating its alpha is applied to each child directly
 * instead of rendering the whole (large) group into an offscreen buffer.
 */
public class FlatFrameLayout extends FrameLayout {
    public FlatFrameLayout(Context context) {
        super(context);
    }

    public FlatFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
