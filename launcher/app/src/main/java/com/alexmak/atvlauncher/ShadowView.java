package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;

/**
 * Soft shadow under a tile: sits behind the tile with exactly its bounds, draws a pre-rendered blurred
 * rounded rect around and below them (parents must not clip children). The blur is rendered once per tile
 * size ({@link ShadowGeometry}) and shared; a frame only blits that bitmap, so the shadow follows the
 * tile's scaling for free when the same transform is applied to this view.
 */
public final class ShadowView extends View {
    private static final int CACHE_BYTES = 2 * 1024 * 1024;
    private static final LruCache<ShadowGeometry, Bitmap> CACHE = new LruCache<ShadowGeometry, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(ShadowGeometry key, Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Drops the shared pre-rendered shadows; they are rendered again on demand. */
    static void evictCache() {
        CACHE.evictAll();
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float density;
    private float radiusPx;
    private ShadowGeometry geometry;
    private Bitmap bitmap;

    public ShadowView(Context context) {
        this(context, null);
    }

    public ShadowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(Color.argb(ShadowGeometry.ALPHA, 0, 0, 0));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Corner radius of the tile this shadow sits under. */
    void setRadius(float px) {
        radiusPx = px;
        geometry = null;
        bitmap = null;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        geometry = null;
        bitmap = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        if (geometry == null) {
            geometry = ShadowGeometry.of(getWidth(), getHeight(), radiusPx, density);
            bitmap = shadowBitmap(geometry);
        }
        canvas.drawBitmap(bitmap, geometry.drawLeft(), geometry.drawTop(), paint);
    }

    private static Bitmap shadowBitmap(ShadowGeometry g) {
        Bitmap cached = CACHE.get(g);
        if (cached != null) return cached;
        Bitmap bmp = Bitmap.createBitmap(g.bitmapWidth(), g.bitmapHeight(), Bitmap.Config.ALPHA_8);
        Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        blurPaint.setColor(Color.BLACK);
        blurPaint.setMaskFilter(new BlurMaskFilter(g.blur, BlurMaskFilter.Blur.NORMAL));
        new Canvas(bmp).drawRoundRect(g.blur, g.blur, g.blur + g.width, g.blur + g.height,
                g.radius, g.radius, blurPaint);
        CACHE.put(g, bmp);
        return bmp;
    }
}
