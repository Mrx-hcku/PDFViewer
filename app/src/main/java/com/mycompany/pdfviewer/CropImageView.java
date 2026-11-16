package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Shows a bitmap with a draggable crop box. */
public class CropImageView extends View {
    private static final int NONE = 0, MOVE = 1, TL = 2, TR = 3, BL = 4, BR = 5;

    private Bitmap bmp;
    private final RectF img = new RectF();
    private final RectF crop = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float slop, minSize, handleR;
    private int mode = NONE;
    private float lastX, lastY;

    public CropImageView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        slop = 32 * d;
        minSize = 60 * d;
        handleR = 9 * d;
        setBackgroundColor(Color.BLACK);
        dimPaint.setColor(0xAA000000);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * d);
        handlePaint.setColor(0xFFFF9800);
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        layoutImage();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutImage();
    }

    private void layoutImage() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        float s = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
        float w = bmp.getWidth() * s, h = bmp.getHeight() * s;
        float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
        img.set(l, t, l + w, t + h);
        crop.set(img.left + w * 0.1f, img.top + h * 0.1f, img.right - w * 0.1f, img.bottom - h * 0.1f);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        c.drawBitmap(bmp, null, img, bitmapPaint);
        c.drawRect(img.left, img.top, img.right, crop.top, dimPaint);
        c.drawRect(img.left, crop.bottom, img.right, img.bottom, dimPaint);
        c.drawRect(img.left, crop.top, crop.left, crop.bottom, dimPaint);
        c.drawRect(crop.right, crop.top, img.right, crop.bottom, dimPaint);
        c.drawRect(crop, borderPaint);
        c.drawCircle(crop.left, crop.top, handleR, handlePaint);
        c.drawCircle(crop.right, crop.top, handleR, handlePaint);
        c.drawCircle(crop.left, crop.bottom, handleR, handlePaint);
        c.drawCircle(crop.right, crop.bottom, handleR, handlePaint);
    }

    private boolean near(float x, float y, float px, float py) {
        return Math.abs(x - px) < slop && Math.abs(y - py) < slop;
    }

    private int hit(float x, float y) {
        if (near(x, y, crop.left, crop.top)) return TL;
        if (near(x, y, crop.right, crop.top)) return TR;
        if (near(x, y, crop.left, crop.bottom)) return BL;
        if (near(x, y, crop.right, crop.bottom)) return BR;
        if (crop.contains(x, y)) return MOVE;
        return NONE;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hit(x, y);
                lastX = x;
                lastY = y;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = x - lastX, dy = y - lastY;
                lastX = x;
                lastY = y;
                switch (mode) {
                    case MOVE: {
                        float w = crop.width(), h = crop.height();
                        float l = clamp(crop.left + dx, img.left, img.right - w);
                        float t = clamp(crop.top + dy, img.top, img.bottom - h);
                        crop.set(l, t, l + w, t + h);
                        break;
                    }
                    case TL:
                        crop.left = clamp(crop.left + dx, img.left, crop.right - minSize);
                        crop.top = clamp(crop.top + dy, img.top, crop.bottom - minSize);
                        break;
                    case TR:
                        crop.right = clamp(crop.right + dx, crop.left + minSize, img.right);
                        crop.top = clamp(crop.top + dy, img.top, crop.bottom - minSize);
                        break;
                    case BL:
                        crop.left = clamp(crop.left + dx, img.left, crop.right - minSize);
                        crop.bottom = clamp(crop.bottom + dy, crop.top + minSize, img.bottom);
                        break;
                    case BR:
                        crop.right = clamp(crop.right + dx, crop.left + minSize, img.right);
                        crop.bottom = clamp(crop.bottom + dy, crop.top + minSize, img.bottom);
                        break;
                    default:
                        break;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
        }
        return super.onTouchEvent(e);
    }

    public Bitmap getCroppedBitmap() {
        float s = bmp.getWidth() / img.width();
        int l = Math.max(0, Math.round((crop.left - img.left) * s));
        int t = Math.max(0, Math.round((crop.top - img.top) * s));
        int w = Math.min(bmp.getWidth() - l, Math.round(crop.width() * s));
        int h = Math.min(bmp.getHeight() - t, Math.round(crop.height() * s));
        return Bitmap.createBitmap(bmp, l, t, Math.max(1, w), Math.max(1, h));
    }
}
