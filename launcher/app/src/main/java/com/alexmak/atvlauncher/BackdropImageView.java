package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * The backdrop picture with its overlay drawn in the same pass (so both fade together), no extra bitmap:
 * the {@link BackdropVignette} (one dithered elliptical radial gradient) over the picture.
 */
public class BackdropImageView extends ImageView {
    private final Paint vignette = new Paint(Paint.DITHER_FLAG);
    private final Matrix ellipse = new Matrix();
    private final RadialGradient gradient = new RadialGradient(0, 0, 1,
            BackdropVignette.colors(), BackdropVignette.positions(), Shader.TileMode.CLAMP);

    public BackdropImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        vignette.setShader(gradient);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // unit circle -> ellipse through the corners (radius sqrt(2) * half the sides), centred
        ellipse.setScale(w / 2f * (float) Math.sqrt(2), h / 2f * (float) Math.sqrt(2));
        ellipse.postTranslate(w / 2f, h / 2f);
        gradient.setLocalMatrix(ellipse);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), vignette);
    }
}
