package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends AppCompatActivity {

    private PDFView pdfView;
    private Uri currentUri;
    private int pageCount = 0;
    private int current radicales = 0;

    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        PDFBoxResourceLoader.init(getApplicationContext());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        pdfView = findViewById(R.id.pdfView);

        Uri uri = resolveIncomingUri();
        if (uri != null) {
            currentUri = uri;
            displayPdf(uri);
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

    private void displayPdf(Uri uri) {
        try {
            pdfView.fromUri(uri)
                    .enableSwipe(true) // Allow horizontal/vertical swipe
                    .swipeHorizontal(false) // Vertical scroll like real readers
                    .enableDoubletap(true)
                    .defaultPage(0)
                    .enableAnnotationRendering(true)
                    .scrollHandle(new DefaultScrollHandle(this))
                    .enableAntialiasing(true)
                    .onLoad(nbPages -> {
                        pageCount = nbPages;
                        setTitle("1 / " + pageCount);
                    })
                    .onPageChange((page, pageCount) -> {
                        setTitle((page + 1) + " / " + pageCount);
                    })
                    .onError(t -> Toast.makeText(PdfViewerActivity.this, "Error loading PDF: " + t.getMessage(), Toast.LENGTH_LONG).show())
                    .load();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        // Night mode can be handled via themes or library settings if configured
        return super.onOptionsItemSelected(item);
    }

    private void showJumpDialog() {
        if (pageCount == 0) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Jump to page (1-" + pageCount + ")")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString()) - 1;
                        if (page >= 0 && page < pageCount) {
                            pdfView.jumpTo(page);
                        }
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

        searchExecutor.execute(() -> {
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
                    pdfView.jumpTo(resultPage);
                    Toast.makeText(PdfViewerActivity.this, "Found on page " + (resultPage + 1), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(PdfViewerActivity.this, "Not found in this PDF", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        searchExecutor.shutdownNow();
    }
}
