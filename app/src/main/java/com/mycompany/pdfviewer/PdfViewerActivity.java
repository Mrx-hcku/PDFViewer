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
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private boolean isNightMode = false;

    // Unity Ads Credentials
    private static final String GAME_ID = "800356656";
    private static final boolean TEST_MODE = false;
    private static final String REWARDED_PLACEMENT = "Rewarded_Android";
    private boolean rewardedReady = false;

    // Groq API Key Configured Safely
    private static final String GROQ_API_KEY = "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";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
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
            displayPdf(uri, 0);
        } else {
            Toast.makeText(this, "No PDF to open", Toast.LENGTH_LONG).show();
        }

        initRewardedAd();
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

    private void displayPdf(Uri uri, int pageIndex) {
        try {
            pdfView.fromUri(uri)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .defaultPage(pageIndex)
                    .nightMode(isNightMode)
                    .enableAnnotationRendering(true)
                    .scrollHandle(new DefaultScrollHandle(this))
                    .enableAntialiasing(true)
                    .onLoad(nbPages -> {
                        pageCount = nbPages;
                        setTitle((pageIndex + 1) + " / " + pageCount);
                    })
                    .onPageChange((page, totalCount) -> {
                        setTitle((page + 1) + " / " + totalCount);
                    })
                    .onError(t -> Toast.makeText(PdfViewerActivity.this, "Error loading PDF: " + t.getMessage(), Toast.LENGTH_LONG).show())
                    .load();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initRewardedAd() {
        if (!UnityAds.isInitialized()) {
            UnityAds.initialize(this, GAME_ID, TEST_MODE, new IUnityAdsInitializationListener() {
                @Override public void onInitializationComplete() { loadRewardedAd(); }
                @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {}
            });
        } else {
            loadRewardedAd();
        }
    }

    private void loadRewardedAd() {
        UnityAds.load(REWARDED_PLACEMENT, new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String placementId) { rewardedReady = true; }
            @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) { rewardedReady = false; }
        });
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
        
        // Night Mode Toggle Fix
        if (id == R.id.action_night_mode) {
            toggleNightMode();
            return true;
        }

        // AI Summary (Ad with Fallback)
        if (id == R.id.action_ai_summary) {
            triggerAiWithAd(this::requestAiSummary);
            return true;
        }

        // Explain Word/Line (Ad with Fallback)
        if (id == R.id.action_ai_explain) {
            triggerAiWithAd(this::showPageLinesForSelection);
            return true;
        }

        // Edit PDF (Ad with Fallback)
        if (id == R.id.action_edit_pdf) {
            triggerAiWithAd(this::showEditPdfDialog);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void toggleNightMode() {
        isNightMode = !isNightMode;
        int currentPage = pdfView != null ? pdfView.getCurrentPage() : 0;
        if (currentUri != null) {
            displayPdf(currentUri, currentPage);
        }
        Toast.makeText(this, "Night Mode: " + (isNightMode ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
    }

    // Smart Fallback Method: Agar ad ready hai toh dikhao, warna direct feature run karo
    private void triggerAiWithAd(Runnable onActionAllowed) {
        if (!rewardedReady) {
            // Ad available nahi hai, toh user ko block kiye bina direct feature run kar do
            Toast.makeText(this, "Ad not available, opening feature directly...", Toast.LENGTH_SHORT).show();
            loadRewardedAd(); // Background mein agle baar ke liye load karte raho
            mainHandler.post(onActionAllowed);
            return;
        }

        UnityAds.show(this, REWARDED_PLACEMENT, new IUnityAdsShowListener() {
            @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                // Ad show hone mein error aayi toh bhi fallback karke feature open kar do
                rewardedReady = false;
                loadRewardedAd();
                mainHandler.post(onActionAllowed);
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override
            public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                rewardedReady = false;
                loadRewardedAd();
                // Ad complete ho ya skip/error, user ko feature access mil jana chahiye
                mainHandler.post(onActionAllowed);
            }
        });
    }

    private void requestAiSummary() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("AI is summarizing content...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(currentUri)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PDDocument document = PDDocument.load(is);
                PDFTextStripper stripper = new PDFTextStripper();
                int currentPageNum = pdfView.getCurrentPage() + 1;
                stripper.setStartPage(currentPageNum);
                stripper.setEndPage(currentPageNum);
                String extractedText = stripper.getText(document);
                document.close();

                String prompt = "Summarize the following current page content clearly:\n\n" + extractedText;
                String aiResponse = callGroqApi(prompt);

                mainHandler.post(() -> {
                    progress.dismiss();
                    showResultDialog("AI Summary (Page " + currentPageNum + ")", aiResponse);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showPageLinesForSelection() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Extracting lines for selection...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(currentUri)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PDDocument document = PDDocument.load(is);
                PDFTextStripper stripper = new PDFTextStripper();
                int currentPageNum = pdfView.getCurrentPage() + 1;
                stripper.setStartPage(currentPageNum);
                stripper.setEndPage(currentPageNum);
                String pageText = stripper.getText(document);
                document.close();

                final String[] lines = pageText.split("\\r?\\n");
                java.util.List<String> lineList = new java.util.ArrayList<>();
                for (String l : lines) {
                    if (!l.trim().isEmpty()) lineList.add(l.trim());
                }

                mainHandler.post(() -> {
                    progress.dismiss();
                    if (lineList.isEmpty()) {
                        Toast.makeText(this, "No text found on this page to select.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    String[] lineArray = lineList.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle("Tap a line to explain (Page " + currentPageNum + ")")
                            .setItems(lineArray, (dialog, which) -> {
                                String selectedLine = lineArray[which];
                                requestAiExplanation(selectedLine);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "Error extracting text: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void requestAiExplanation(String query) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("AI is explaining selected line...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
            String prompt = "Explain the following text/line clearly and simply:\n\n" + query;
            String aiResponse = callGroqApi(prompt);
            mainHandler.post(() -> {
                progress.dismiss();
                showResultDialog("AI Explanation", aiResponse);
            });
        });
    }

    private String callGroqApi(String prompt) {
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", "llama-3.3-70b-versatile");

            JSONArray messages = new JSONArray();
            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", prompt);
            messages.put(msg);

            jsonBody.put("messages", messages);

            RequestBody body = RequestBody.create(
                    jsonBody.toString(),
                    MediaType.parse("application/json; charset=utf-8")
            );

            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + GROQ_API_KEY)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String resStr = response.body().string();
                    JSONObject rootObj = new JSONObject(resStr);
                    JSONArray choices = rootObj.getJSONArray("choices");
                    if (choices.length() > 0) {
                        return choices.getJSONObject(0).getJSONObject("message").getString("content").trim();
                    }
                }
            }
        } catch (Exception e) {
            return "Failed to connect to AI: " + e.getMessage();
        }
        return "No response from AI.";
    }

    private void showResultDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
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
                        if (page >= 0 && page < pageCount) pdfView.jumpTo(page);
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
        progress.setMessage("Editing PDF on cloud with exact alignment...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(currentUri);
                if (inputStream == null) {
                    mainHandler.post(() -> { progress.dismiss(); Toast.makeText(this, "Failed to read PDF file", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                File tempFile = new File(getCacheDir(), "upload_temp.pdf");
                try (OutputStream outputStream = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) { outputStream.write(buffer, 0, bytesRead); }
                }
                inputStream.close();

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build();

                // exactFit=true maintains precise alignment for edited words
                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("targetText", targetText)
                        .addFormDataPart("newText", newText)
                        .addFormDataPart("exactFit", "true")
                        .addFormDataPart("pdf", tempFile.getName(), RequestBody.create(tempFile, MediaType.parse("application/pdf")))
                        .build();

                Request request = new Request.Builder()
                        .url("https://pdf-backend-2-d94s.onrender.com/edit-pdf")
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String fileName = "edited_" + System.currentTimeMillis() + ".pdf";
                        File cacheFile = new File(getCacheDir(), fileName);
                        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                            fos.write(response.body().bytes());
                        }

                        mainHandler.post(() -> {
                            progress.dismiss();
                            Toast.makeText(this, "Saved & Updated successfully!", Toast.LENGTH_LONG).show();
                            currentUri = Uri.fromFile(cacheFile);
                            displayPdf(currentUri, pdfView.getCurrentPage());
                        });
                    } else {
                        mainHandler.post(() -> { progress.dismiss(); Toast.makeText(this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show(); });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> { progress.dismiss(); Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

    private void runTextSearch(String query) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Searching...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
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
                } finally { document.close(); }
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
        backgroundExecutor.shutdownNow();
    }
}
