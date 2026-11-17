package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Clean line icons for the Tools screen, drawn in code (no image files needed). */
public class ToolIconView extends View {
    public static final int MERGE = 0, SPLIT = 1, COMPRESS = 2, IMAGE_TO_PDF = 3,
            PDF_TO_IMAGE = 4, LOCK = 5, UNLOCK = 6, SCAN = 7;

    private final int type;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private float s = 1f;

    public ToolIconView(Context c, int type, int color) {
        super(c);
        this.type = type;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, stroke);
    }

    private void poly(Canvas c, float... p) {
        path.reset();
        path.moveTo(p[0] * s, p[1] * s);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i] * s, p[i + 1] * s);
        c.drawPath(path, stroke);
    }

    private void box(Canvas c, float l, float t, float r, float b, float radius) {
        rect.set(l * s, t * s, r * s, b * s);
        c.drawRoundRect(rect, radius * s, radius * s, stroke);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        s = Math.min(getWidth(), getHeight()) / 24f;
        stroke.setStrokeWidth(1.9f * s);

        switch (type) {
            case MERGE:
                box(c, 2, 3, 9, 10, 1.5f);
                box(c, 2, 14, 9, 21, 1.5f);
                box(c, 14, 8, 22, 16, 1.5f);
                poly(c, 9, 6.5f, 12, 6.5f, 12, 12, 14, 12);
                poly(c, 9, 17.5f, 12, 17.5f, 12, 12);
                break;

            case SPLIT:
                box(c, 2, 8, 10, 16, 1.5f);
                box(c, 15, 3, 22, 10, 1.5f);
                box(c, 15, 14, 22, 21, 1.5f);
                poly(c, 10, 12, 12.5f, 12, 12.5f, 6.5f, 15, 6.5f);
                poly(c, 12.5f, 12, 12.5f, 17.5f, 15, 17.5f);
                break;

            case COMPRESS:
                line(c, 4, 12, 20, 12);
                line(c, 12, 3, 12, 9);
                poly(c, 9, 6.5f, 12, 9.5f, 15, 6.5f);
                line(c, 12, 21, 12, 15);
                poly(c, 9, 17.5f, 12, 14.5f, 15, 17.5f);
                break;

            case IMAGE_TO_PDF:
                box(c, 3, 5, 21, 19, 2.5f);
                poly(c, 3.5f, 16, 9, 10.5f, 13, 14.5f, 15.5f, 12, 20.5f, 17);
                c.drawCircle(16.5f * s, 9f * s, 1.6f * s, fill);
                break;

            case PDF_TO_IMAGE:
                box(c, 5, 3, 19, 21, 2.5f);
                poly(c, 7, 17.5f, 10.5f, 13, 13, 16, 14.5f, 14.5f, 17, 17.5f);
                c.drawCircle(14.5f * s, 8f * s, 1.6f * s, fill);
                break;

            case LOCK:
                box(c, 5, 11, 19, 21, 2.5f);
                path.reset();
                path.moveTo(8 * s, 11 * s);
                path.lineTo(8 * s, 7.5f * s);
                rect.set(8 * s, 3.5f * s, 16 * s, 11.5f * s);
                path.arcTo(rect, 180, 180);
                path.lineTo(16 * s, 11 * s);
                c.drawPath(path, stroke);
                c.drawCircle(12 * s, 16 * s, 1.5f * s, fill);
                break;

            case UNLOCK:
                box(c, 5, 11, 19, 21, 2.5f);
                path.reset();
                path.moveTo(8 * s, 11 * s);
                path.lineTo(8 * s, 7.5f * s);
                rect.set(8 * s, 3.5f * s, 16 * s, 11.5f * s);
                path.arcTo(rect, 180, 180);
                c.drawPath(path, stroke);
                c.drawCircle(12 * s, 16 * s, 1.5f * s, fill);
                break;

            case SCAN:
                poly(c, 3, 8, 3, 3, 8, 3);
                poly(c, 16, 3, 21, 3, 21, 8);
                poly(c, 3, 16, 3, 21, 8, 21);
                poly(c, 16, 21, 21, 21, 21, 16);
                line(c, 6.5f, 12, 17.5f, 12);
                break;
        }
    }
}
