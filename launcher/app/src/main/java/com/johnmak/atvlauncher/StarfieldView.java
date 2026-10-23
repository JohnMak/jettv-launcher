package com.johnmak.atvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * OLED screensaver background: pure black, thousands of clustered, multi-size, softly coloured stars flying
 * towards the viewer (~30 fps). Stars are soft gaussian sprites ({@link StarSprite}) drawn with bilinear filtering
 * at float positions, so sub-pixel motion is smooth, batched per size/colour/brightness. Animates only while
 * attached and shown. The clock lives in {@link ScreensaverOverlay}.
 */
public final class StarfieldView extends View {
    private static final long FRAME_MS = 33L;
    /** Point sizes in dp per size class (index = StarfieldModel size class). */
    private static final float[] SIZE_DP = {0.8f, 1.5f, 2.4f, 3.5f};
    /** Star colours per colour class: white, bluish, warm, faint orange-red (all low saturation). */
    private static final int[] COLORS = {0xFFFFFF, 0xCFE0FF, 0xFFF0D0, 0xFFC4A8};
    private static final int BUCKETS =
            StarfieldModel.SIZE_CLASSES * StarfieldModel.COLOR_CLASSES * StarfieldModel.BRIGHTNESS_LEVELS;

    private final StarfieldModel model;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** One ALPHA_8 gaussian sprite per size class, built once; drawn tinted by the paint colour. */
    private final Bitmap[] sprites = new Bitmap[StarfieldModel.SIZE_CLASSES];
    private final float[] spriteAlpha = new float[StarfieldModel.SIZE_CLASSES];
    /** Reused per-frame buffers: star positions sorted by bucket (counting sort), no allocation. */
    private final float[] points;
    private final int[] bucketStart = new int[BUCKETS + 1];
    private final int[] bucketFill = new int[BUCKETS];
    private final int[] starBucket;

    private boolean running;
    private long lastFrameMs;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameMs >= FRAME_MS) {
                float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
                lastFrameMs = now;
                model.step(dt);
                invalidate();
            }
            postOnAnimation(this);
        }
    };

    public StarfieldView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        for (int k = 0; k < sprites.length; k++) {
            float sizePx = SIZE_DP[k] * density;
            float sigma = StarSprite.sigmaPx(StarSprite.footprintPx(sizePx));
            int side = StarSprite.side(sigma);
            sprites[k] = Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
            sprites[k].copyPixelsFromBuffer(ByteBuffer.wrap(StarSprite.alpha(sigma)));
            spriteAlpha[k] = StarSprite.alphaScale(sizePx);
        }
        long seed = System.nanoTime();
        model = new StarfieldModel(seed);
        points = new float[model.count() * 2];
        starBucket = new int[model.count()];
        setBackgroundColor(Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        int n = model.count();
        Arrays.fill(bucketStart, 0);
        for (int i = 0; i < n; i++) {
            int bucket = (model.sizeClass(i) * StarfieldModel.COLOR_CLASSES + model.colorClass(i))
                    * StarfieldModel.BRIGHTNESS_LEVELS + model.brightnessLevel(i);
            starBucket[i] = bucket;
            bucketStart[bucket + 1]++;
        }
        for (int b = 0; b < BUCKETS; b++) {
            bucketStart[b + 1] += bucketStart[b];
            bucketFill[b] = bucketStart[b];
        }
        for (int i = 0; i < n; i++) {
            int at = bucketFill[starBucket[i]]++ * 2;
            points[at] = model.screenX(i, w);
            points[at + 1] = model.screenY(i, h);
        }
        for (int b = 0; b < BUCKETS; b++) {
            int count = bucketStart[b + 1] - bucketStart[b];
            if (count == 0) continue;
            int level = b % StarfieldModel.BRIGHTNESS_LEVELS;
            int color = (b / StarfieldModel.BRIGHTNESS_LEVELS) % StarfieldModel.COLOR_CLASSES;
            int size = b / (StarfieldModel.BRIGHTNESS_LEVELS * StarfieldModel.COLOR_CLASSES);
            Bitmap sprite = sprites[size];
            float half = sprite.getWidth() / 2f;
            paint.setColor(COLORS[color] | 0xFF000000);
            paint.setAlpha(Math.round(StarfieldModel.levelBrightness(level) * spriteAlpha[size] * 255f));
            for (int k = bucketStart[b] * 2, end = k + count * 2; k < end; k += 2) {
                canvas.drawBitmap(sprite, points[k] - half, points[k + 1] - half, paint);
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateRunning();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        updateRunning();
    }

    private void updateRunning() {
        boolean shouldRun = isAttachedToWindow() && isShown();
        if (shouldRun == running) return;
        running = shouldRun;
        if (running) {
            lastFrameMs = SystemClock.uptimeMillis();
            postOnAnimation(frame);
        } else {
            removeCallbacks(frame);
        }
    }
}
