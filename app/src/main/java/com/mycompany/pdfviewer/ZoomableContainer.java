package com.mycompany.pdfviewer;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Wraps a single child (the page-list RecyclerView) and lets the user pinch-zoom
 * and pan across the WHOLE rendered document, the way real market PDF-viewer apps
 * work — not one page zoomed in isolation.
 *
 * At scale 1x, single-finger vertical drags pass straight through to the child so
 * the RecyclerView scrolls normally. Once zoomed in (scale > 1x), single-finger
 * drags pan the zoomed content instead, and pinching keeps adjusting scale/focus.
 * Double-tap toggles between 1x and a fixed zoomed-in level.
 */
public class ZoomableContainer extends FrameLayout {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private float scale = 1f;
    private float panX = 0f, panY = 0f;
    private float lastTouchX, lastTouchY;
    private boolean isPanning = false;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private View content;

    public ZoomableContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) content = getChildAt(0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Once zoomed in, we intercept single/multi-finger moves ourselves to pan/zoom.
        // At 1x, let the child (RecyclerView) handle its own vertical scroll normally.
        if (ev.getPointerCount() > 1) return true;
        if (scale > MIN_SCALE) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE && isPanning) return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isPanning = scale > MIN_SCALE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(scale > MIN_SCALE);
                break;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 1 && scale > MIN_SCALE) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    panX += dx;
                    panY += dy;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    constrainAndApply();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                isPanning = false;
                if (scale <= MIN_SCALE && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                break;
        }
        return true;
    }

    private void constrainAndApply() {
        if (content == null) return;
        float scaledWidth = content.getWidth() * scale;
        float scaledHeight = content.getHeight() * scale;

        float maxPanX = Math.max(0, (scaledWidth - getWidth()) / 2f);
        float maxPanY = Math.max(0, (scaledHeight - getHeight()) / 2f);

        if (panX > maxPanX) panX = maxPanX;
        if (panX < -maxPanX) panX = -maxPanX;
        if (panY > maxPanY) panY = maxPanY;
        if (panY < -maxPanY) panY = -maxPanY;

        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setTranslationX(panX);
        content.setTranslationY(panY);
    }

    private void resetZoom() {
        scale = 1f;
        panX = 0f;
        panY = 0f;
        constrainAndApply();
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScale = scale * detector.getScaleFactor();
            newScale = Math.max(MIN_SCALE, Math.min(newScale, MAX_SCALE));
            scale = newScale;
            if (scale <= MIN_SCALE) {
                scale = MIN_SCALE;
                panX = 0f;
                panY = 0f;
            }
            constrainAndApply();
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (scale > MIN_SCALE) {
                resetZoom();
            } else {
                scale = DOUBLE_TAP_SCALE;
                panX = 0f;
                panY = 0f;
                constrainAndApply();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
    }

    public boolean isZoomed() {
        return scale > MIN_SCALE;
    }
}
