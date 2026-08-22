package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PdfViewerActivity extends AppCompatActivity {

    private PDFView pdfView;
    private Uri currentUri;
    private int pageCount = 0;

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
                    .enableSwipe(true)
                    .swipeHorizontal(false)
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
        if (id == R.id.action_edit_pdf) { showEditPdfDialog(); return true; }
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
                    if (!query.isEmpty()) runTextSearch(query);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditPdfDialog() {
        if (currentUri == null) return;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText targetInput = new EditText(this);
        targetInput.setHint("Target text to replace");
        layout.addView(targetInput);

        final EditText newInput = new EditText(this);
        newInput.setHint("New replacement text");
        layout.addView(newInput);

        new AlertDialog.Builder(this)
                .setTitle("Edit PDF via Cloud")
                .setView(layout)
                .setPositiveButton("Update", (dialog, which) -> {
                    String targetText = targetInput.getText().toString().trim();
                    String newText = newInput.getText().toString().trim();
                    if (!targetText.isEmpty() && !newText.isEmpty()) {
                        uploadAndEditPdf(targetText, newText);
                    } else {
                        Toast.makeText(this, "Both fields are required", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void uploadAndEditPdf(String targetText, String newText) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Editing PDF on cloud...");
        progress.setCancelable(false);
        progress.show();

        searchExecutor.execute(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(currentUri);
                if (inputStream == null) {
                    mainHandler.post(() -> {
                        progress.dismiss();
                        Toast.makeText(this, "Failed to read PDF file", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                File tempFile = new File(getCacheDir(), "upload_temp.pdf");
                try (OutputStream outputStream = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                inputStream.close();

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("targetText", targetText)
                        .addFormDataPart("newText", newText)
                        .addFormDataPart("pdf", tempFile.getName(),
                                RequestBody.create(tempFile, MediaType.parse("application/pdf")))
                        .build();

                Request request = new Request.Builder()
                        .url("https://pdf-backend-2-d94s.onrender.com/edit-pdf")
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String fileName = "edited_" + System.currentTimeMillis() + ".pdf";
                        File cacheFile = new File(getCacheDir(), fileName);

                        // Save to cache for immediate viewing inside the app
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(response.body().bytes());
                        }

                        // Save to public Downloads folder using MediaStore or File API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                            Uri downloadUri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
                            if (downloadUri != null) {
                                try (InputStream cacheIn = getContentResolver().openInputStream(Uri.fromFile(cacheFile));
                                     OutputStream fos = getContentResolver().openOutputStream(downloadUri)) {
                                    if (cacheIn != null && fos != null) {
                                        byte[] buffer = new byte[4096];
                                        int bytesRead;
                                        while ((bytesRead = cacheIn.read(buffer)) != -1) {
                                            fos.write(buffer, 0, bytesRead);
                                        }
                                    }
                                }
                            }
                        } else {
                            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            File destFile = new File(downloadsDir, fileName);
                            try (InputStream cacheIn = getContentResolver().openInputStream(Uri.fromFile(cacheFile));
                                 OutputStream fos = new FileOutputStream(destFile)) {
                                if (cacheIn != null) {
                                    byte[] buffer = new byte[4096];
                                    int bytesRead;
                                    while ((bytesRead = cacheIn.read(buffer)) != -1) {
                                        fos.write(buffer, 0, bytesRead);
                                    }
                                }
                            }
                        }

                        mainHandler.post(() -> {
                            progress.dismiss();
                            Toast.makeText(this, "Saved to Downloads as: " + fileName, Toast.LENGTH_LONG).show();
                            currentUri = Uri.fromFile(cacheFile);
                            displayPdf(currentUri);
                        });
                    } else {
                        mainHandler.post(() -> {
                            progress.dismiss();
                            Toast.makeText(this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
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
            
