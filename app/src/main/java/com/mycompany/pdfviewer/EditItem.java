package com.mycompany.pdfviewer;

import java.util.ArrayList;
import java.util.List;

/** One thing the user added on a page: a pen stroke, a typed text, or a signature. Positions are 0..1 of the page. */
public class EditItem {
    public static final int STROKE = 0, TEXT = 1, SIGN = 2;

    public int type;
    public int color;
    public float width;                                   // stroke width, as fraction of page width

    public final List<float[]> pts = new ArrayList<>();   // STROKE: (x, y) points in page coords
    public List<List<float[]>> sigStrokes;                // SIGN: strokes in 0..1 box coords

    public String text;                                   // TEXT
    public float size;                                    // TEXT: text height as fraction of page width

    public float cx, cy, w, h;                            // TEXT / SIGN: centre and size in page coords
}
