package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** Matching line icons for the bottom navigation bar (the bar colours them itself). */
public final class NavIcons {
    private NavIcons() {}

    public static final int PDFS = 0, TOOLS = 1, RECENT = 2, FAVORITES = 3;

    public static Drawable make(Context c, int type) {
        final int px = 96;                 // drawn big, shown at 24dp
        final float s = px / 24f;
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(1.9f * s);
        p.setColor(Color.WHITE);

        Path path = new Path();
        RectF r = new RectF();

        switch (type) {
            case PDFS:
                path.moveTo(6 * s, 3 * s);
                path.lineTo(14 * s, 3 * s);
                path.lineTo(19 * s, 8 * s);
                path.lineTo(19 * s, 21 * s);
                path.lineTo(6 * s, 21 * s);
                path.close();
                cv.drawPath(path, p);
                path.reset();
                path.moveTo(14 * s, 3 * s);
                path.lineTo(14 * s, 8 * s);
                path.lineTo(19 * s, 8 * s);
                cv.drawPath(path, p);
                cv.drawLine(9 * s, 13 * s, 16 * s, 13 * s, p);
                cv.drawLine(9 * s, 17 * s, 16 * s, 17 * s, p);
                break;

            case TOOLS:
                r.set(4 * s, 4 * s, 10 * s, 10 * s);
                cv.drawRoundRect(r, 1.8f * s, 1.8f * s, p);
                r.set(14 * s, 4 * s, 20 * s, 10 * s);
                cv.drawRoundRect(r, 1.8f * s, 1.8f * s, p);
                r.set(4 * s, 14 * s, 10 * s, 20 * s);
                cv.drawRoundRect(r, 1.8f * s, 1.8f * s, p);
                r.set(14 * s, 14 * s, 20 * s, 20 * s);
                cv.drawRoundRect(r, 1.8f * s, 1.8f * s, p);
                break;

            case RECENT:
                cv.drawCircle(12 * s, 12 * s, 9 * s, p);
                path.moveTo(12 * s, 7 * s);
                path.lineTo(12 * s, 12 * s);
                path.lineTo(16 * s, 14.5f * s);
                cv.drawPath(path, p);
                break;

            case FAVORITES:
                double cx = 12, cy = 12.6, outer = 9.6, inner = 4.3;
                for (int i = 0; i < 10; i++) {
                    double ang = Math.toRadians(-90 + i * 36);
                    double rad = (i % 2 == 0) ? outer : inner;
                    float x = (float) (cx + rad * Math.cos(ang)) * s;
                    float y = (float) (cy + rad * Math.sin(ang)) * s;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                cv.drawPath(path, p);
                break;
        }
        return new BitmapDrawable(c.getResources(), bmp);
    }
}
