package com.johnmak.atvlauncher;

import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/** Shared tile rendering and geometry for every screen that draws a glass highlight. */
final class Tiles {
    static final float FOCUS_SCALE = 1.08f;

    private Tiles() {
    }

    /** Sets the bitmap and clips the image to rounded corners. */
    static void bindImage(ImageView iv, Bitmap bitmap, float radiusPx) {
        iv.setImageBitmap(bitmap);
        round(iv, radiusPx);
    }

    static void round(View v, float radiusPx) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
    }

    /** Clips a view to a rectangle with individual corner radii, order: top-left, top-right, bottom-right, bottom-left. */
    static void roundCorners(View v, float tl, float tr, float br, float bl) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                Path p = new Path();
                p.addRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        new float[] {tl, tl, tr, tr, br, br, bl, bl}, Path.Direction.CW);
                outline.setPath(p);
            }
        });
    }

    /** Selects a feed card: its poster and its source-app badge grow and light up together. */
    static void setCardSelected(View card, boolean selected, boolean animate) {
        setSelected(card.findViewById(R.id.card_image), card.findViewById(R.id.shadow), selected, animate);
        setSelected(card.findViewById(R.id.card_badge), null, selected, animate);
    }

    /** Selected tile or card: grows and shines at full brightness; unselected ones are dimmed. Its shadow (if any) grows with it. */
    static void setSelected(DimImageView target, ShadowView shadow, boolean selected, boolean animate) {
        target.setFocused(selected, animate);
        scale(target, selected, animate);
        if (shadow != null) scale(shadow, selected, animate);
    }

    private static void scale(View v, boolean selected, boolean animate) {
        float s = selected ? FOCUS_SCALE : 1f;
        v.animate().cancel();
        if (animate) {
            v.animate().scaleX(s).scaleY(s).setDuration(200).start();
        } else {
            v.setScaleX(s);
            v.setScaleY(s);
        }
    }

    /** Glass margin around a top-bar item (dp): wide enough to hold the gap between items. */
    static final int TOP_GLASS_PAD_X_DP = 12;
    static final int TOP_GLASS_PAD_Y_DP = 2;

    /** Expands an icon rect to the round top-bar glass frame (top-bar items, configure tile); returns the corner radius. */
    static float topGlass(RectF r, float density) {
        r.inset(-TOP_GLASS_PAD_X_DP * density, -TOP_GLASS_PAD_Y_DP * density);
        return r.height() / 2;
    }

    /** Expands a tile rect to the scaled-tile glass frame and returns the glass corner radius. */
    static float grow(RectF r, float density, float tileRadiusDp) {
        float growX = r.width() * (FOCUS_SCALE - 1f) / 2 + 4 * density;
        float growY = r.height() * (FOCUS_SCALE - 1f) / 2 + 4 * density;
        r.inset(-growX, -growY);
        return tileRadiusDp * density * FOCUS_SCALE + 4 * density;
    }

    /** Expands an unscaled tile rect to the glass frame (a small margin all round); returns the glass corner radius. */
    static float surround(RectF r, float density, float tileRadiusDp) {
        r.inset(-4 * density, -4 * density);
        return tileRadiusDp * density + 4 * density;
    }

    /** Bounds of a view in the coordinates of {@code root} (translations of ancestors excluded). */
    static boolean locate(View v, View root, RectF out) {
        if (v.getWidth() == 0) return false;
        float x = 0;
        float y = 0;
        View cur = v;
        while (cur != root) {
            x += cur.getLeft();
            y += cur.getTop();
            if (!(cur.getParent() instanceof View)) return false;
            cur = (View) cur.getParent();
        }
        out.set(x, y, x + v.getWidth(), y + v.getHeight());
        return true;
    }
}
