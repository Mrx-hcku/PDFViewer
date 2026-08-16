package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends AppCompatActivity {

    private RecyclerView pageList;
    private TextView pageIndicator;
    private LinearLayoutManager layoutManager;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int pageCount = 0;
    private int[] pageHeights; // pre-computed display height per page (width-fitted)
    private int screenWidth;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LruCache<Integer, Bitmap> bitmapCache;
    private final Object rendererLock = new Object();
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        pageList = findViewById(R.id.pageList);
        pageIndicator = findViewById(R.id.pageIndicator);
        layoutManager = new LinearLayoutManager(this);
        pageList.setLayoutManager(layoutManager);

        screenWidth = getResources().getDisplayMetrics().widthPixels;

        // Cache size: keep only a handful of pages in memory at once
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024;
        int cacheSize = (int) (maxMemory / 16); // ~1/16th of available memory, in KB
        bitmapCache = new LruCache<Integer, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key, Bitmap oldValue, Bitmap newValue) {
                if (oldValue != null && !oldValue.isRecycled()) oldValue.recycle();
            }
        };

        pageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                int pos = layoutManager.findFirstVisibleItemPosition();
                if (pos >= 0) pageIndicator.setText((pos + 1) + "/" + pageCount);
            }
        });

        String uriString = getIntent().getStringExtra("pdf_uri");
        if (uriString != null) openPdf(Uri.parse(uriString));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_viewer, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { finish(); return true; }
        if (id == R.id.action_jump) { showJumpDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showJumpDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Jump to page (1-" + pageCount + ")")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString()) - 1;
                        if (page >= 0 && page < pageCount)
                            pageList.smoothScrollToPosition(page);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openPdf(Uri uri) {
        renderExecutor.execute(() -> {
            try {
                ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
                if (fd == null) return;
                synchronized (rendererLock) {
                    fileDescriptor = fd;
                    pdfRenderer = new PdfRenderer(fileDescriptor);
                    pageCount = pdfRenderer.getPageCount();
                    pageHeights = new int[pageCount];
                    for (int i = 0; i < pageCount; i++) {
                        PdfRenderer.Page page = pdfRenderer.openPage(i);
                        float ratio = (float) page.getHeight() / page.getWidth();
                        pageHeights[i] = Math.round(screenWidth * ratio);
                        page.close();
                    }
                }
                mainHandler.post(() -> {
                    if (destroyed) return;
                    pageList.setAdapter(new PdfPageAdapter());
                    pageIndicator.setText("1/" + pageCount);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!destroyed) {
                        Toast.makeText(PdfViewerActivity.this,
                                "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void renderPage(int position, ZoomableImageView imageView) {
        Bitmap cached = bitmapCache.get(position);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageBitmap(null);
        imageView.setTag(position);

        renderExecutor.execute(() -> {
            if (destroyed) return;
            Bitmap bitmap;
            synchronized (rendererLock) {
                if (pdfRenderer == null || destroyed) return;
                try {
                    PdfRenderer.Page page = pdfRenderer.openPage(position);
                    bitmap = Bitmap.createBitmap(screenWidth, pageHeights[position], Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xFFFFFFFF);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    page.close();
                } catch (Exception e) {
                    return;
                }
            }
            bitmapCache.put(position, bitmap);
            Bitmap finalBitmap = bitmap;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (imageView.getTag() != null && (int) imageView.getTag() == position) {
                    imageView.setImageBitmap(finalBitmap);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        renderExecutor.execute(() -> {
            synchronized (rendererLock) {
                try {
                    if (pdfRenderer != null) pdfRenderer.close();
                    if (fileDescriptor != null) fileDescriptor.close();
                } catch (IOException ignored) {}
            }
        });
        renderExecutor.shutdown();
        bitmapCache.evictAll();
    }

    private class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ZoomableImageView v = (ZoomableImageView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pdf_page, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            lp.height = pageHeights[position];
            holder.image.setLayoutParams(lp);
            renderPage(position, holder.image);
        }

        @Override
        public void onViewRecycled(@NonNull VH holder) {
            super.onViewRecycled(holder);
            holder.image.setTag(null);
        }

        @Override
        public int getItemCount() {
            return pageCount;
        }

        class VH extends RecyclerView.ViewHolder {
            ZoomableImageView image;
            VH(View itemView) {
                super(itemView);
                image = (ZoomableImageView) itemView;
            }
        }
    }
}
