package com.mycompany.pdfviewer;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Grid of page thumbnails. Tap one to jump to that page. */
public final class ThumbnailsDialog {
    private ThumbnailsDialog() {}

    public interface OnPick { void onPick(int page); }

    public static void show(final Activity act, Uri uri, final int pageCount, final int currentPage, final OnPick cb) {
        final ParcelFileDescriptor pfd;
        final PdfRenderer renderer;
        try {
            pfd = act.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new Exception("Cannot open PDF");
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            Toast.makeText(act, "Cannot show thumbnails: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        final int total = renderer.getPageCount();
        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(Integer key, Bitmap value) { return value.getByteCount(); }
        };
        final float d = act.getResources().getDisplayMetrics().density;
        final int cols = 3;
        final int cellW = (int) (act.getResources().getDisplayMetrics().widthPixels / (float) cols - 16 * d);
        final int cellH = (int) (cellW * 1.4f);

        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);

        TextView title = new TextView(act);
        title.setText("Pages (" + total + ")  \u2022  tap to open");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (10 * d));
        root.addView(title);

        RecyclerView rv = new RecyclerView(act);
        rv.setLayoutManager(new GridLayoutManager(act, cols));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                FrameLayout cell = new FrameLayout(act);
                cell.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));

                ImageView iv = new ImageView(act);
                iv.setId(View.generateViewId());
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackgroundColor(0xFF2A2A2A);
                cell.addView(iv, new FrameLayout.LayoutParams(cellW, cellH));

                TextView num = new TextView(act);
                num.setTextColor(Color.WHITE);
                num.setTextSize(12);
                num.setBackgroundColor(0xAA000000);
                num.setPadding((int) (6 * d), (int) (2 * d), (int) (6 * d), (int) (2 * d));
                FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START);
                cell.addView(num, nlp);
                return new RecyclerView.ViewHolder(cell) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                final FrameLayout cell = (FrameLayout) holder.itemView;
                final ImageView iv = (ImageView) cell.getChildAt(0);
                TextView num = (TextView) cell.getChildAt(1);
                num.setText(String.valueOf(position + 1));
                cell.setBackgroundColor(position == currentPage ? 0xFFFF9800 : Color.TRANSPARENT);
                iv.setTag(position);

                Bitmap cached = cache.get(position);
                if (cached != null) {
                    iv.setImageBitmap(cached);
                } else {
                    iv.setImageBitmap(null);
                    final int pos = position;
                    ex.execute(() -> {
                        try {
                            PdfRenderer.Page p = renderer.openPage(pos);
                            Bitmap b;
                            try {
                                int w = 320;
                                int h = Math.max(1, p.getHeight() * w / p.getWidth());
                                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                                b.eraseColor(Color.WHITE);
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            } finally {
                                p.close();
                            }
                            cache.put(pos, b);
                            final Bitmap fb = b;
                            iv.post(() -> {
                                Object tag = iv.getTag();
                                if (tag instanceof Integer && (Integer) tag == pos) iv.setImageBitmap(fb);
                            });
                        } catch (Throwable ignored) {
                            // closed or failed: leave the grey box
                        }
                    });
                }
                cell.setOnClickListener(v -> {
                    dialog.dismiss();
                    cb.onPick(pos(holder));
                });
            }

            private int pos(RecyclerView.ViewHolder h) {
                int p = h.getBindingAdapterPosition();
                return p < 0 ? 0 : p;
            }

            @Override
            public int getItemCount() { return total; }
        });
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setOnDismissListener(di -> {
            ex.execute(() -> {
                try { renderer.close(); } catch (Throwable ignored) {}
                try { pfd.close(); } catch (Throwable ignored) {}
            });
            ex.shutdown();
        });

        dialog.setContentView(root);
        dialog.show();
        rv.scrollToPosition(Math.max(0, Math.min(currentPage, total - 1)));
    }
}
