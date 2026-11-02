package com.fs20modconverterpro.texture;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Visualização com ajuste inicial, gesto de pinça e zoom pelos controles da tela. */
public final class ZoomableImageView extends ImageView {
    private final Matrix fitMatrix = new Matrix();
    private final Matrix displayMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private float userScale = 1f;

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                userScale = Math.max(0.5f, Math.min(8f, userScale * detector.getScaleFactor()));
                updateMatrix();
                return true;
            }
        });
    }

    public void zoomBy(float factor) {
        userScale = Math.max(0.5f, Math.min(8f, userScale * factor));
        updateMatrix();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        userScale = 1f;
        fitToView();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fitToView();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        return true;
    }

    private void fitToView() {
        Drawable image = getDrawable();
        if (image == null || image.getIntrinsicWidth() <= 0 || image.getIntrinsicHeight() <= 0
                || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min((float) getWidth() / image.getIntrinsicWidth(),
                (float) getHeight() / image.getIntrinsicHeight());
        float dx = (getWidth() - image.getIntrinsicWidth() * scale) / 2f;
        float dy = (getHeight() - image.getIntrinsicHeight() * scale) / 2f;
        fitMatrix.setScale(scale, scale);
        fitMatrix.postTranslate(dx, dy);
        updateMatrix();
    }

    private void updateMatrix() {
        displayMatrix.set(fitMatrix);
        displayMatrix.postScale(userScale, userScale, getWidth() / 2f, getHeight() / 2f);
        setImageMatrix(displayMatrix);
    }
}
