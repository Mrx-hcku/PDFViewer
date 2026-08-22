package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends AppCompatActivity {

    private RecyclerView pageList;
    private TextView pageIndicator;
    private LinearLayoutManager layoutManager;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int pageCount = 0;
    private int[] pageHeights; // display height per page (width-fitted, unscaled)
    private int screenWidth;
    private static final int RENDER_SCALE = 2; // supersample so pinch-zoom stays sharp

    private Uri currentUri;
    private boolean nightMode = false;
    private static final ColorMatrixColorFilter NIGHT_FILTER = new ColorMatrixColorFilter(new float[]{
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
    });

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LruCache<Integer, Bitmap> bitmapCache;
    private final Object rendererLock = new Object();
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        PDFBoxResourceLoader.init(getApplicationContext());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        pageList = findViewById(R.id.pageList);
        pageIndicator = findViewById(R.id.pageIndicator);
        layoutManager = new LinearLayoutManager(this);
        pageList.setLayoutManager(layoutManager);

        screenWidth = getResources().getDisplayMetrics().widthPixels;

        long maxMemory = Runtime.getRuntime().maxMemory() / 1024;
        int cacheSize = (int) (maxMemory / 16);
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

        Uri uri = resolveIncomingUri();
        if (uri != null) {
            currentUri = uri;
            openPdf(uri);
        } else {
            Toast.makeText(this, "No PDF to open", Toast.LENGTH_LONG).show();
        }
    }

    private Uri resolveIncomingUri() {
        String uriString = getIntent().getStringExtra("pdf_uri");
        if (uriString != null) return Uri.parse(uriString);

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData();
        }
        return null;
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
        if (id == R.id.action_search_text) { showSearchDialog(); return true; }
        if (id == R.id.action_night_mode) {
            nightMode = !nightMode;
            item.setChecked(nightMode);
            if (pageList.getAdapter() != null) pageList.getAdapter().notifyDataSetChanged();
            return true;
        }
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

    private void showSearchDialog() {
        if (currentUri == null) return;
        EditText input = new EditText(this);
        input.setHint("Search text in this PDF");
        new AlertDialog.Builder(this)
                .setTitle("Search in PDF")
                .setView(input)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = input.getText().toString().trim();
                    if (query.length() > 0) runTextSearch(query);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runTextSearch(String query) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Searching...");
        progress.setCancelable(false);
        progress.show();

        renderExecutor.execute(() -> {
            Integer foundPage = null;
            try (InputStream is = getContentResolver().openInputStream(currentUri)) {
                if (is == null) throw new IOException("Cannot open PDF");
                PDDocument document = PDDocument.load(is);
                try {
                    int total = document.getNumberOfPages();
                    String lowerQuery = query.toLowerCase(Locale.getDefault());
                    for (int i = 0; i < total; i++) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        stripper.setStartPage(i + 1);
                        stripper.setEndPage(i + 1);
                        String pageText = stripper.getText(document);
                        if (pageText != null && pageText.toLowerCase(Locale.getDefault()).contains(lowerQuery)) {
                            foundPage = i;
                            break;
                        }
                    }
                } finally {
                    document.close();
                }
            } catch (Exception ignored) {}

            final Integer resultPage = foundPage;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (resultPage != null) {
                    pageList.smoothScrollToPosition(resultPage);
                    Toast.makeText(PdfViewerActivity.this, "Found on page " + (resultPage + 1), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(PdfViewerActivity.this, "Not found in this PDF", Toast.LENGTH_SHORT).show();
                }
            });
        });
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

    private void renderPage(int position, ImageView imageView) {
        Bitmap cached = bitmapCache.get(position);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageBitmap(null);
        imageView.setTag(position);

        renderExecutor.execute(() -> {
            if (destroyed) return;
            Bitmap bitmap = null;
            synchronized (rendererLock) {
                if (pdfRenderer == null || destroyed) return;
                int w = screenWidth * RENDER_SCALE;
                int h = pageHeights[position] * RENDER_SCALE;
                try {
                    bitmap = renderAtSize(position, w, h);
                } catch (OutOfMemoryError oom) {
                    // Tier 2: native (1x) resolution.
                    System.gc();
                    try {
                        bitmap = renderAtSize(position, screenWidth, pageHeights[position]);
                    } catch (OutOfMemoryError oom2) {
                        // Tier 3: half resolution — aspect ratio stays correct,
                        // page just looks a bit softer. Last resort before giving up.
                        System.gc();
                        try {
                            int halfW = screenWidth / 2;
                            int halfH = pageHeights[position] / 2;
                            bitmap = renderAtSize(position, halfW, halfH);
                        } catch (OutOfMemoryError oom3) {
                            bitmap = null;
                        } catch (Exception ignored) {
                            bitmap = null;
                        }
                    } catch (Exception ignored) {
                        bitmap = null;
                    }
                } catch (Exception e) {
                    bitmap = null;
                }
            }
            if (bitmap == null) {
                mainHandler.post(() -> {
                    if (!destroyed) Toast.makeText(PdfViewerActivity.this,
                            "Page " + (position + 1) + " ke liye memory kam pad gayi", Toast.LENGTH_SHORT).show();
                });
                return;
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

    /** Must be called while holding rendererLock. */
    private Bitmap renderAtSize(int position, int w, int h) throws Exception {
        PdfRenderer.Page page = pdfRenderer.openPage(position);
        try {
            // RGB_565 halves memory vs ARGB_8888 — PDF pages are opaque, no alpha needed.
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            bitmap.eraseColor(0xFFFFFFFF);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } finally {
            page.close();
        }
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
            ImageView v = (ImageView) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pdf_page, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            lp.height = pageHeights[position];
            holder.image.setLayoutParams(lp);
            holder.image.setColorFilter(nightMode ? NIGHT_FILTER : null);
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
            ImageView image;
            VH(View itemView) {
                super(itemView);
                image = (ImageView) itemView;
            }
        }
    }
}
