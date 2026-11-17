package com.mycompany.pdfviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Full-screen editor on top of a page picture: pen, text (for filling forms) and signature. */
public final class PageEditorDialog {
    private PageEditorDialog() {}

    public interface OnSave { void onSave(List<EditItem> items); }

    private static final float[] TEXT_SIZES = {0.016f, 0.022f, 0.032f};
    private static final String[] TEXT_LABELS = {"Aa S", "Aa M", "Aa L"};
    private static final float[] PEN_WIDTHS = {0.003f, 0.006f, 0.012f};
    private static final String[] PEN_LABELS = {"Thin", "Medium", "Thick"};
    private static final int[] COLORS = {0xFF111111, 0xFF0D1B6E, 0xFFD32F2F};
    private static final String[] COLOR_LABELS = {"Black", "Blue", "Red"};

    private static class Signature {
        List<List<float[]>> strokes = new ArrayList<>();
        float aspect = 0.35f;   // height / width
    }

    private static SharedPreferences prefs(Activity a) {
        return a.getSharedPreferences("viewer_prefs", Activity.MODE_PRIVATE);
    }

    private static Signature loadSignature(Activity a) {
        String raw = prefs(a).getString("signature", null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Signature s = new Signature();
            s.aspect = (float) o.getDouble("a");
            JSONArray strokes = o.getJSONArray("s");
            for (int i = 0; i < strokes.length(); i++) {
                JSONArray st = strokes.getJSONArray(i);
                List<float[]> pts = new ArrayList<>();
                for (int k = 0; k < st.length(); k++) {
                    JSONArray p = st.getJSONArray(k);
                    pts.add(new float[]{(float) p.getDouble(0), (float) p.getDouble(1)});
                }
                s.strokes.add(pts);
            }
            return s.strokes.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static Button button(Activity a, String text, View.OnClickListener l) {
        Button b = new Button(a, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(l);
        return b;
    }

    // ================= main editor =================
    public static void show(final Activity act, final Bitmap page, final OnSave cb) {
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final PageEditorView view = new PageEditorView(act);
        view.setBitmap(page);

        final int[] textIdx = {1}, penIdx = {1}, colorIdx = {1};
        view.setPenColor(COLORS[colorIdx[0]]);
        view.setPenWidth(PEN_WIDTHS[penIdx[0]]);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF212121);

        // ---- toolbar ----
        HorizontalScrollView hs = new HorizontalScrollView(act);
        LinearLayout tools = new LinearLayout(act);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tools);

        final Button bPen = button(act, "\u270F Pen", null);
        final Button bText = button(act, "T Text", null);
        final Button bSign = button(act, "\u270D Sign", null);
        final Button bColor = button(act, "Color: " + COLOR_LABELS[colorIdx[0]], null);
        final Button bWidth = button(act, "Line: " + PEN_LABELS[penIdx[0]], null);
        final Button bSize = button(act, TEXT_LABELS[textIdx[0]], null);
        Button bNewSign = button(act, "New sign", v -> showSignaturePad(act, null));
        Button bUndo = button(act, "\u21B6 Undo", v -> view.undo());

        final Runnable refreshModes = () -> {
            bPen.setAlpha(view.getMode() == PageEditorView.MODE_PEN ? 1f : 0.5f);
            bText.setAlpha(view.getMode() == PageEditorView.MODE_TEXT ? 1f : 0.5f);
            bSign.setAlpha(view.getMode() == PageEditorView.MODE_SIGN ? 1f : 0.5f);
        };
        refreshModes.run();

        bPen.setOnClickListener(v -> { view.setMode(PageEditorView.MODE_PEN); refreshModes.run(); });
        bText.setOnClickListener(v -> {
            view.setMode(PageEditorView.MODE_TEXT);
            refreshModes.run();
            Toast.makeText(act, "Tap on the page where the text should go", Toast.LENGTH_SHORT).show();
        });
        bSign.setOnClickListener(v -> {
            view.setMode(PageEditorView.MODE_SIGN);
            refreshModes.run();
            if (loadSignature(act) == null) {
                showSignaturePad(act, null);
            } else {
                Toast.makeText(act, "Tap on the page where the signature should go", Toast.LENGTH_SHORT).show();
            }
        });
        bColor.setOnClickListener(v -> {
            colorIdx[0] = (colorIdx[0] + 1) % COLORS.length;
            view.setPenColor(COLORS[colorIdx[0]]);
            bColor.setText("Color: " + COLOR_LABELS[colorIdx[0]]);
        });
        bWidth.setOnClickListener(v -> {
            penIdx[0] = (penIdx[0] + 1) % PEN_WIDTHS.length;
            view.setPenWidth(PEN_WIDTHS[penIdx[0]]);
            bWidth.setText("Line: " + PEN_LABELS[penIdx[0]]);
        });
        bSize.setOnClickListener(v -> {
            textIdx[0] = (textIdx[0] + 1) % TEXT_SIZES.length;
            bSize.setText(TEXT_LABELS[textIdx[0]]);
        });

        tools.addView(bPen);
        tools.addView(bText);
        tools.addView(bSign);
        tools.addView(bColor);
        tools.addView(bWidth);
        tools.addView(bSize);
        tools.addView(bNewSign);
        tools.addView(bUndo);

        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- bottom bar ----
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));
        Button cancel = button(act, "Cancel", v -> dialog.dismiss());
        Button save = button(act, "Save to PDF", v -> {
            if (view.items.isEmpty()) {
                Toast.makeText(act, "Nothing added yet", Toast.LENGTH_SHORT).show();
                return;
            }
            List<EditItem> out = new ArrayList<>(view.items);
            dialog.dismiss();
            cb.onSave(out);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(cancel, lp);
        bar.addView(save, lp);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- taps for text / signature ----
        view.setTapListener((mode, nx, ny) -> {
            if (mode == PageEditorView.MODE_TEXT) {
                askText(act, text -> {
                    EditItem it = new EditItem();
                    it.type = EditItem.TEXT;
                    it.text = text;
                    it.size = TEXT_SIZES[textIdx[0]];
                    it.color = COLORS[colorIdx[0]];
                    it.cx = nx;
                    it.cy = ny;
                    view.addItem(it);
                });
            } else if (mode == PageEditorView.MODE_SIGN) {
                Signature sig = loadSignature(act);
                if (sig == null) {
                    showSignaturePad(act, null);
                    return;
                }
                EditItem it = new EditItem();
                it.type = EditItem.SIGN;
                it.sigStrokes = sig.strokes;
                it.color = 0xFF0D1B6E;
                it.width = 0.0035f;
                it.w = 0.28f;
                Bitmap b = view.getBitmap();
                it.h = (it.w * b.getWidth() * sig.aspect) / b.getHeight();
                it.cx = nx;
                it.cy = ny;
                view.addItem(it);
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    private interface TextCb { void onText(String t); }

    private static void askText(Activity act, final TextCb cb) {
        final EditText in = new EditText(act);
        in.setHint("Type text (Hindi or English)");
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setSingleLine(true);
        new AlertDialog.Builder(act)
                .setTitle("Add text")
                .setView(in)
                .setPositiveButton("Add", (d, w) -> {
                    String t = in.getText().toString().trim();
                    if (!t.isEmpty()) cb.onText(t);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================= signature pad =================
    private static void showSignaturePad(final Activity act, final Runnable afterSave) {
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final int bw = 1400, bh = 500;
        Bitmap blank = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        blank.eraseColor(Color.WHITE);

        final PageEditorView pad = new PageEditorView(act);
        pad.setBitmap(blank);
        pad.setMode(PageEditorView.MODE_PEN);
        pad.setPenColor(0xFF0D1B6E);
        pad.setPenWidth(0.006f);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF212121);

        TextView title = new TextView(act);
        title.setText("Draw your signature");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 8));
        root.addView(title);

        root.addView(pad, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 260)));

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(act, 8), dp(act, 12), dp(act, 8), dp(act, 6));
        Button clear = button(act, "Clear", v -> pad.clearAll());
        Button cancel = button(act, "Cancel", v -> dialog.dismiss());
        Button save = button(act, "Save signature", v -> {
            if (saveSignature(act, pad.items, bw, bh)) {
                dialog.dismiss();
                Toast.makeText(act, "Signature saved. Tap on the page to place it.", Toast.LENGTH_LONG).show();
                if (afterSave != null) afterSave.run();
            } else {
                Toast.makeText(act, "Please draw your signature first", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(clear, lp);
        bar.addView(cancel, lp);
        bar.addView(save, lp);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(root);
        dialog.show();
    }

    private static boolean saveSignature(Activity act, List<EditItem> items, int bw, int bh) {
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        int count = 0;
        for (EditItem it : items) {
            if (it.type != EditItem.STROKE) continue;
            for (float[] p : it.pts) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
                count++;
            }
        }
        if (count < 2) return false;
        float w = Math.max(0.001f, maxX - minX), h = Math.max(0.001f, maxY - minY);
        try {
            JSONArray strokes = new JSONArray();
            for (EditItem it : items) {
                if (it.type != EditItem.STROKE) continue;
                JSONArray st = new JSONArray();
                for (float[] p : it.pts) {
                    JSONArray pt = new JSONArray();
                    pt.put((double) ((p[0] - minX) / w));
                    pt.put((double) ((p[1] - minY) / h));
                    st.put(pt);
                }
                strokes.put(st);
            }
            JSONObject o = new JSONObject();
            o.put("a", (double) ((h * bh) / (w * bw)));
            o.put("s", strokes);
            prefs(act).edit().putString("signature", o.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
