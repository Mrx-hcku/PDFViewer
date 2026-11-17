package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Shows a page picture and lets the user draw, place text or place a signature on it. */
public class PageEditorView extends View {
    public static final int MODE_PEN = 0, MODE_TEXT = 1, MODE_SIGN = 2;

    public interface TapListener { void onTap(int mode, float nx, float ny); }

    private Bitmap bmp;
    private final RectF img = new RectF();
    public final List<EditItem> items = new ArrayList<>();
    private int mode = MODE_PEN;
    private int penColor = 0xFF0D1B6E;
    private float penWidth = 0.006f;
    private EditItem current;
    private TapListener tapListener;
    private float downX, downY;
    private final float tapSlop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();

    public PageEditorView(Context c) {
        super(c);
        tapSlop = 12 * c.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.DKGRAY);
    }

    public void setBitmap(Bitmap b) { bmp = b; layoutImage(); invalidate(); }
    public Bitmap getBitmap() { return bmp; }
    public void setMode(int m) { mode = m; }
    public int getMode() { return mode; }
    public void setPenColor(int c) { penColor = c; }
    public void setPenWidth(float w) { penWidth = w; }
    public void setTapListener(TapListener l) { tapListener = l; }

    public void addItem(EditItem it) { items.add(it); invalidate(); }

    public void undo() {
        if (!items.isEmpty()) {
            items.remove(items.size() - 1);
            invalidate();
        }
    }

    public void clearAll() { items.clear(); invalidate(); }

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
    }

    private float vx(float nx) { return img.left + nx * img.width(); }
    private float vy(float ny) { return img.top + ny * img.height(); }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        c.drawBitmap(bmp, null, img, paint);
        for (EditItem it : items) drawItem(c, it);
    }

    private void drawStroke(Canvas c, List<float[]> pts, int color, float widthPx) {
        if (pts.isEmpty()) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(1f, widthPx));
        paint.setColor(color);
        if (pts.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(vx(pts.get(0)[0]), vy(pts.get(0)[1]), Math.max(1f, widthPx) / 2f, paint);
            return;
        }
        path.reset();
        path.moveTo(vx(pts.get(0)[0]), vy(pts.get(0)[1]));
        for (int i = 1; i < pts.size(); i++) path.lineTo(vx(pts.get(i)[0]), vy(pts.get(i)[1]));
        c.drawPath(path, paint);
    }

    private void drawItem(Canvas c, EditItem it) {
        if (it.type == EditItem.STROKE) {
            drawStroke(c, it.pts, it.color, it.width * img.width());
        } else if (it.type == EditItem.SIGN) {
            for (List<float[]> st : it.sigStrokes) {
                List<float[]> mapped = new ArrayList<>();
                for (float[] p : st) {
                    mapped.add(new float[]{it.cx + (p[0] - 0.5f) * it.w, it.cy + (p[1] - 0.5f) * it.h});
                }
                drawStroke(c, mapped, it.color, it.width * img.width());
            }
        } else if (it.type == EditItem.TEXT) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(it.color);
            paint.setTextSize(it.size * img.width());
            Paint.FontMetrics fm = paint.getFontMetrics();
            float tw = paint.measureText(it.text);
            float x = vx(it.cx) - tw / 2f;
            float y = vy(it.cy) + (fm.descent - fm.ascent) / 2f - fm.descent;
            c.drawText(it.text, x, y, paint);
        }
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null || img.width() <= 0) return true;
        float x = e.getX(), y = e.getY();
        float nx = clamp01((x - img.left) / img.width());
        float ny = clamp01((y - img.top) / img.height());

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (mode == MODE_PEN) {
                    current = new EditItem();
                    current.type = EditItem.STROKE;
                    current.color = penColor;
                    current.width = penWidth;
                    current.pts.add(new float[]{nx, ny});
                    items.add(current);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_PEN && current != null) {
                    current.pts.add(new float[]{nx, ny});
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == MODE_PEN) {
                    current = null;
                } else if (Math.abs(x - downX) < tapSlop && Math.abs(y - downY) < tapSlop && tapListener != null) {
                    tapListener.onTap(mode, nx, ny);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                current = null;
                return true;
        }
        return super.onTouchEvent(e);
    }
}
