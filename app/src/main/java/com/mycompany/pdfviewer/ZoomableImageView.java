package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {

    private Matrix matrix = new Matrix();
    private float scale = 1f;
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 4f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastX, lastY;

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable d = getDrawable();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (d != null && d.getIntrinsicWidth() > 0) {
            int height = Math.round((float) width * d.getIntrinsicHeight() / d.getIntrinsicWidth());
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToWidth();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        scale = 1f;
        requestLayout();
        post(this::fitToWidth);
    }

    private void fitToWidth() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() == 0) return;
        float fitScale = (float) getWidth() / d.getIntrinsicWidth();
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        scale = 1f;
        setImageMatrix(matrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(scale > MIN_SCALE);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && scale > MIN_SCALE) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    matrix.postTranslate(dx, dy);
                    constrainMatrix();
                    setImageMatrix(matrix);
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (scale <= MIN_SCALE && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }

    private void constrainMatrix() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);

        float dx = 0, dy = 0;
        if (rect.width() <= getWidth()) {
            dx = (getWidth() - rect.width()) / 2 - rect.left;
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < getWidth()) {
            dx = getWidth() - rect.right;
        }

        if (rect.height() <= getHeight()) {
            dy = (getHeight() - rect.height()) / 2 - rect.top;
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < getHeight()) {
            dy = getHeight() - rect.bottom;
        }

        matrix.postTranslate(dx, dy);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScale = scale * detector.getScaleFactor();
            newScale = Math.max(MIN_SCALE, Math.min(newScale, MAX_SCALE));
            float factor = newScale / scale;
            scale = newScale;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            constrainMatrix();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (scale > MIN_SCALE) {
                fitToWidth();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            } else {
                float target = 2.5f;
                float factor = target / scale;
                scale = target;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                constrainMatrix();
                setImageMatrix(matrix);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
    }
}
