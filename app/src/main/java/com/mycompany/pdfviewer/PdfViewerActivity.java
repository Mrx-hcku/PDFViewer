package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
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
    private static final String BANNER_PLACEMENT = "Banner_Android";
    private static final String INTERSTITIAL_PLACEMENT = "Interstitial_Android";
    private boolean exitAdReady = false;
    private boolean exiting = false;
    private boolean bannerClosed = false;
    private final long openedAt = System.currentTimeMillis();
    private boolean rewardedReady = false;

    // ---- In-PDF text search with highlight ----
    private static class SearchMatch {
        int page;
        final java.util.List<android.graphics.RectF> rects = new java.util.ArrayList<>(); // 0..1 of page size
    }
    private java.util.List<SearchMatch> searchMatches = new java.util.ArrayList<>();
    private int currentMatchIndex = -1;
    private String lastQuery = "";
    private final android.graphics.Paint hlPaint = new android.graphics.Paint();
    private com.unity3d.services.banners.BannerView bannerView;
    private android.widget.FrameLayout bannerContainer;

    // Groq key is injected at build time from GitHub Secrets (see build.yml). Never hardcode it here.
    private static final String GROQ_API_KEY = BuildConfig.GROQ_API_KEY;
    private static final int MAX_AI_CHARS = 6000; // free tier limit is ~8K tokens/min (Hindi uses many tokens)
    // Groq free-tier models, strongest first. Next one is tried if a model is missing (404) or rate-limited (429).
    private static final String[] GROQ_MODELS = {
            "openai/gpt-oss-120b",
            "qwen/qwen3.8-27b",
            "openai/gpt-oss-20b"
    };

    private static final OkHttpClient GROQ_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);
        setupBannerContainer();

        PDFBoxResourceLoader.init(getApplicationContext());

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        pdfView = findViewById(R.id.pdfView);

        Uri uri = resolveIncomingUri();
        if (uri != null) {
            currentUri = uri;
            docKey = Integer.toHexString(uri.toString().hashCode());
            docName = PdfUtils.displayName(this, uri);
            int startPage = vp().getInt("last_" + docKey, 0);
            if (startPage > 0) {
                Toast.makeText(this, "Continuing from page " + (startPage + 1), Toast.LENGTH_SHORT).show();
            }
            displayPdf(uri, startPage);
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
                    .password(pdfPassword)
                    .enableAnnotationRendering(true)
                    .scrollHandle(new DefaultScrollHandle(this))
                    .enableAntialiasing(true)
                    .onDrawAll((canvas, pw, ph, page) -> drawHighlights(canvas, pw, ph, page))
                    .onLongPress(e -> onPdfLongPress(e))
                    .onLoad(nbPages -> {
                        pageCount = nbPages;
                        setTitle((pageIndex + 1) + " / " + pageCount);
                    })
                    .onPageChange((page, totalCount) -> {
                        setTitle((page + 1) + " / " + totalCount);
                        vp().edit().putInt("last_" + docKey, page).apply();
                    })
                    .onError(t -> {
                        if (t instanceof com.shockwave.pdfium.PdfPasswordException) {
                            askPdfPassword(uri, pageIndex);
                        } else {
                            Toast.makeText(PdfViewerActivity.this, "Error loading PDF: " + t.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .load();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initRewardedAd() {
        if (!UnityAds.isInitialized()) {
            UnityAds.initialize(this, GAME_ID, TEST_MODE, new IUnityAdsInitializationListener() {
                @Override public void onInitializationComplete() {
                    loadRewardedAd();
                    loadExitInterstitial();
                    runOnUiThread(() -> loadBanner());
                }
                @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {}
            });
        } else {
            loadRewardedAd();
            loadExitInterstitial();
            loadBanner();
        }
    }

    // Puts a banner strip at the bottom of the viewer without touching activity_pdf_viewer.xml
    private void setupBannerContainer() {
        android.view.ViewGroup content = findViewById(android.R.id.content);
        android.view.View root = content.getChildAt(0);
        content.removeView(root);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(root, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        selectionBar = buildSelectionBar();
        wrapper.addView(selectionBar, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        readBar = buildReadBar();
        wrapper.addView(readBar, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        bannerContainer = new android.widget.FrameLayout(this);
        bannerContainer.setVisibility(android.view.View.INVISIBLE);   // keeps its height: no re-layout when the ad loads
        wrapper.addView(bannerContainer, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dpPx(50)));

        content.addView(wrapper, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private boolean bannerRequested = false;

    private void loadBanner() {
        if (bannerRequested || destroyed || bannerClosed) return;
        bannerRequested = true;
        // wait until the first pages are drawn before creating the ad view
        mainHandler.postDelayed(this::loadBannerNow, 2500);
    }

    private void loadBannerNow() {
        if (destroyed || bannerClosed || bannerView != null || bannerContainer == null) return;
        bannerView = new com.unity3d.services.banners.BannerView(
                this, BANNER_PLACEMENT, new com.unity3d.services.banners.UnityBannerSize(320, 50));
        bannerView.setListener(new com.unity3d.services.banners.BannerView.IListener() {
            @Override public void onBannerLoaded(com.unity3d.services.banners.BannerView b) {
                runOnUiThread(() -> { if (!destroyed) bannerContainer.setVisibility(android.view.View.VISIBLE); });
            }
            @Override public void onBannerShown(com.unity3d.services.banners.BannerView b) {}
            @Override public void onBannerClick(com.unity3d.services.banners.BannerView b) {}
            @Override public void onBannerFailedToLoad(com.unity3d.services.banners.BannerView b,
                                                      com.unity3d.services.banners.BannerErrorInfo info) {
                runOnUiThread(() -> { if (!destroyed) bannerContainer.setVisibility(android.view.View.GONE); });
            }
            @Override public void onBannerLeftApplication(com.unity3d.services.banners.BannerView b) {}
        });
        bannerContainer.addView(bannerView, new android.widget.FrameLayout.LayoutParams(
                dpPx(320), dpPx(50), android.view.Gravity.CENTER_HORIZONTAL));
        bannerView.load();

        // small close button so the reader can dismiss the banner
        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(11);
        close.setGravity(android.view.Gravity.CENTER);
        close.setBackgroundColor(0x99000000);
        int sz = dpPx(22);
        android.widget.FrameLayout.LayoutParams clp = new android.widget.FrameLayout.LayoutParams(
                sz, sz, android.view.Gravity.TOP | android.view.Gravity.END);
        close.setOnClickListener(v -> {
            bannerClosed = true;
            bannerContainer.setVisibility(android.view.View.GONE);
            bannerContainer.removeAllViews();
            if (bannerView != null) {
                bannerView.destroy();
                bannerView = null;
            }
        });
        bannerContainer.addView(close, clp);
    }

    // ---------- sometimes show a video ad when the reader closes the PDF ----------
    private void loadExitInterstitial() {
        UnityAds.load(INTERSTITIAL_PLACEMENT, new IUnityAdsLoadListener() {
            @Override public void onUnityAdsAdLoaded(String placementId) { exitAdReady = true; }
            @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                exitAdReady = false;
            }
        });
    }

    @Override
    public void onBackPressed() {
        exitViewer();
    }

    private void exitViewer() {
        if (exiting) return;
        exiting = true;
        android.content.SharedPreferences ap = getSharedPreferences("ad_prefs", MODE_PRIVATE);
        int closes = ap.getInt("close_count", 0) + 1;
        ap.edit().putInt("close_count", closes).apply();
        long now = System.currentTimeMillis();
        long last = ap.getLong("last_interstitial", 0L);
        // every 3rd close, at least 3 minutes after the previous video ad, and only if the reader stayed 20+ seconds
        boolean due = closes % 3 == 0 && now - last > 180000L && now - openedAt > 20000L;
        if (due && exitAdReady && UnityAds.isInitialized()) {
            UnityAds.show(this, INTERSTITIAL_PLACEMENT, new IUnityAdsShowListener() {
                @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                    finish();
                }
                @Override public void onUnityAdsShowStart(String placementId) {}
                @Override public void onUnityAdsShowClick(String placementId) {}
                @Override public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                    getSharedPreferences("ad_prefs", MODE_PRIVATE).edit()
                            .putLong("last_interstitial", System.currentTimeMillis()).apply();
                    finish();
                }
            });
        } else {
            finish();
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
        menu.add(Menu.NONE, M_BOOKMARK, 100, "Bookmark this page");
        menu.add(Menu.NONE, M_BOOKMARKS, 101, "My bookmarks");
        menu.add(Menu.NONE, M_ANNOTATE, 102, "Highlight / Add note");
        menu.add(Menu.NONE, M_OCR, 103, "OCR: read scanned page");
        menu.add(Menu.NONE, M_CROP, 104, "Crop & share page");
        menu.add(Menu.NONE, M_KEYPOINTS, 105, "AI: important points");
        menu.add(Menu.NONE, M_QUIZ, 106, "AI: make MCQ quiz");
        menu.add(Menu.NONE, M_SAVE_COPY, 107, "Save PDF copy to Downloads");
        menu.add(Menu.NONE, M_DRAW, 90, "Write / Draw / Sign / Add text");
        menu.add(Menu.NONE, M_THUMBS, 91, "Page thumbnails");
        menu.add(Menu.NONE, M_READ, 92, "Read aloud");
        menu.add(Menu.NONE, M_SHARE_PDF, 108, "Share PDF");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { exitViewer(); return true; }
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

        if (id == M_BOOKMARK) { toggleBookmark(); return true; }
        if (id == M_BOOKMARKS) { showBookmarks(); return true; }
        if (id == M_ANNOTATE) { showAnnotateDialog(); return true; }
        if (id == M_OCR) { runOcrPage(); return true; }
        if (id == M_CROP) { startCropShare(); return true; }
        if (id == M_KEYPOINTS) {
            triggerAiWithAd(() -> requestAiTask("AI Key Points",
                    "List the most important points of the following content as short bullet points. Reply in the same language as the text."));
            return true;
        }
        if (id == M_QUIZ) {
            triggerAiWithAd(() -> requestAiTask("AI Quiz",
                    "Create 5 multiple-choice questions (MCQs) from the following content. Give 4 options (A-D) for each. "
                    + "At the end write an answer key with a one-line explanation. Reply in the same language as the text."));
            return true;
        }
        if (id == M_SAVE_COPY) { saveCopyToDownloads(); return true; }
        if (id == M_DRAW) { startPageEditor(); return true; }
        if (id == M_THUMBS) {
            ThumbnailsDialog.show(this, currentUri, pageCount, pdfView.getCurrentPage(), p -> pdfView.jumpTo(p, true));
            return true;
        }
        if (id == M_READ) { startReadAloud(); return true; }
        if (id == M_SHARE_PDF) { sharePdf(); return true; }

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
        requestAiTask("AI Summary",
                "Summarize the following page content clearly. Reply in the same language as the text.");
    }

            private void showPageLinesForSelection() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Reading page...");
        progress.setCancelable(false);
        progress.show();

        final int pageNum = pdfView.getCurrentPage() + 1;

        backgroundExecutor.execute(() -> {
            try {
                String pageText = getPageTextWithOcr(pageNum - 1);
                final List<String> lineList = new ArrayList<>();
                for (String l : pageText.split("\\r?\\n")) {
                    if (!l.trim().isEmpty()) lineList.add(l.trim());
                }
                mainHandler.post(() -> {
                    if (destroyed) return;
                    progress.dismiss();
                    if (lineList.isEmpty()) {
                        Toast.makeText(this, "No text found on this page.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    final String[] lineArray = lineList.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle("Tap a line to explain (Page " + pageNum + ")")
                            .setItems(lineArray, (dialog, which) -> requestAiExplanation(lineArray[which]))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    progress.dismiss();
                    Toast.makeText(this, "Error reading page: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

        private void requestAiExplanation(String query) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("AI is explaining selected line...");
        progress.setCancelable(false);
        progress.show();

        backgroundExecutor.execute(() -> {
            String prompt = "Explain the following text/line clearly and simply. "
                    + "Reply in the same language as the text.\n\n" + query;
            final String aiResponse = callGroqApi(prompt);
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                showResultDialog("AI Explanation", aiResponse);
            });
        });
    }

        private String callGroqApi(String prompt) {
        if (GROQ_API_KEY == null || GROQ_API_KEY.isEmpty()) {
            return "AI key is missing in this build.";
        }
        String result = "";
        for (String model : GROQ_MODELS) {
            result = callGroqModel(model, prompt);
            if (!result.startsWith("AI error (HTTP 404") && !result.startsWith("AI error (HTTP 429")) return result;
        }
        return result;
    }

    private String callGroqModel(String model, String prompt) {
        try {
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", model);

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

            try (Response response = GROQ_CLIENT.newCall(request).execute()) {
                String resStr = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    return "AI error (HTTP " + response.code() + "): " + extractApiError(resStr);
                }
                JSONArray choices = new JSONObject(resStr).getJSONArray("choices");
                if (choices.length() > 0) {
                    return choices.getJSONObject(0).getJSONObject("message").getString("content").trim();
                }
            }
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException e) {
            return "No internet connection or the request timed out.";
        } catch (Exception e) {
            return "Failed to connect to AI: " + e.getMessage();
        }
        return "AI returned an empty response.";
    }

    private String extractApiError(String body) {
        try {
            return new JSONObject(body).getJSONObject("error").getString("message");
        } catch (Exception e) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }

            private void showResultDialog(String title, String message) {
        if (destroyed || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", (d, w) -> copyToClipboard(message))
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
        input.setSingleLine(true);
        input.setText(lastQuery);
        input.setSelection(lastQuery.length());
        new AlertDialog.Builder(this)
                .setTitle(searchMatches.isEmpty() ? "Search in PDF" : "Search / Next match")
                .setView(input)
                .setPositiveButton("Search", (dialog, which) -> {
                    String query = input.getText().toString().trim().replaceAll("\\s+", " ");
                    if (query.isEmpty()) {
                        clearSearchHighlights();
                    } else {
                        runTextSearch(query);
                    }
                })
                .setNegativeButton("Clear", (dialog, which) -> clearSearchHighlights())
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
                    mainHandler.post(() -> { if (destroyed) return; progress.dismiss(); Toast.makeText(this, "Failed to read PDF file", Toast.LENGTH_SHORT).show(); });
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
                            if (destroyed) return;
                            progress.dismiss();
                            Toast.makeText(this, "Saved & Updated successfully!", Toast.LENGTH_LONG).show();
                            currentUri = Uri.fromFile(cacheFile);
                            displayPdf(currentUri, pdfView.getCurrentPage());
                        });
                    } else {
                        mainHandler.post(() -> { if (destroyed) return; progress.dismiss(); Toast.makeText(this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show(); });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> { if (destroyed) return; progress.dismiss(); Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
            }
        });
    }

        private void runTextSearch(String query) {
        // Same word searched again -> go to next match
        if (!searchMatches.isEmpty() && query.equalsIgnoreCase(lastQuery)) {
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size();
            goToCurrentMatch();
            return;
        }

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Searching...");
        progress.setCancelable(false);
        progress.show();

        final Uri uri = currentUri;
        backgroundExecutor.execute(() -> {
            final java.util.List<SearchMatch> found = new java.util.ArrayList<>();
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Cannot open PDF");
                PDDocument document = PDDocument.load(is);
                try {
                    int total = document.getNumberOfPages();
                    for (int i = 0; i < total && found.size() < 300; i++) {
                        MatchFinder finder = new MatchFinder();
                        finder.setStartPage(i + 1);
                        finder.setEndPage(i + 1);
                        finder.getText(document);
                        found.addAll(finder.findMatches(query, i));
                    }
                } finally { document.close(); }
            } catch (Exception ignored) {}

            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (found.isEmpty()) {
                    clearSearchHighlights();
                    Toast.makeText(PdfViewerActivity.this, "Not found in this PDF", Toast.LENGTH_SHORT).show();
                } else {
                    lastQuery = query;
                    searchMatches = found;
                    currentMatchIndex = 0;
                    goToCurrentMatch();
                }
            });
        });
    }

    private void clearSearchHighlights() {
        searchMatches = new java.util.ArrayList<>();
        currentMatchIndex = -1;
        lastQuery = "";
        pdfView.invalidate();
    }

    // Jump to the page of the current match, scroll so the line is in the upper third, highlight it
    private void goToCurrentMatch() {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatches.size()) return;
        final SearchMatch m = searchMatches.get(currentMatchIndex);
        final float topNorm = m.rects.isEmpty() ? 0f : m.rects.get(0).top;

        pdfView.jumpTo(m.page, false);
        pdfView.post(() -> {
            if (destroyed) return;
            try {
                float pageH = pdfView.getPageSize(m.page).getHeight() * pdfView.getZoom();
                float targetY = topNorm * pageH;
                float wantedY = pdfView.getHeight() / 3f;
                if (targetY > wantedY) {
                    pdfView.moveRelativeTo(0, -(targetY - wantedY));
                }
            } catch (Exception ignored) {}
            pdfView.invalidate();
        });

        Toast.makeText(this, "Match " + (currentMatchIndex + 1) + " of " + searchMatches.size()
                + " (page " + (m.page + 1) + ")", Toast.LENGTH_SHORT).show();
    }

        private void drawHighlights(Canvas canvas, float pw, float ph, int page) {
        if (selWords != null && selPage == page) {
            selPaint.setColor(0x6642A5F5);
            for (int i = selStart; i <= selEnd && i < selWords.size(); i++) {
                RectF r = selWords.get(i).rect;
                canvas.drawRect(r.left * pw, r.top * ph, r.right * pw, r.bottom * ph, selPaint);
            }
        }
        if (searchMatches.isEmpty()) return;
        for (int i = 0; i < searchMatches.size(); i++) {
            SearchMatch m = searchMatches.get(i);
            if (m.page != page) continue;
            hlPaint.setColor(i == currentMatchIndex ? 0x99FF9800 : 0x66FFEB3B);
            for (RectF r : m.rects) {
                canvas.drawRect(r.left * pw, r.top * ph, r.right * pw, r.bottom * ph, hlPaint);
            }
        }
    }

    // Collects every character of a page with its position, then finds the query in it
    private static class MatchFinder extends PDFTextStripper {
        private final java.util.List<TextPosition> chars = new java.util.ArrayList<>(); // null = word break

        MatchFinder() throws IOException { super(); }

        @Override
        protected void writeString(String text, java.util.List<TextPosition> positions) throws IOException {
            chars.addAll(positions);
            chars.add(null);
        }

        java.util.List<WordBox> words() {
            java.util.List<WordBox> out = new java.util.ArrayList<>();
            StringBuilder sb = new StringBuilder();
            RectF cur = null;
            for (int i = 0; i <= chars.size(); i++) {
                TextPosition tp = i < chars.size() ? chars.get(i) : null;
                String u = tp == null ? null : tp.getUnicode();
                boolean breakHere = tp == null || u == null || u.trim().isEmpty();
                if (breakHere) {
                    if (sb.length() > 0 && cur != null) {
                        WordBox w = new WordBox();
                        w.text = sb.toString();
                        w.rect = cur;
                        out.add(w);
                    }
                    sb.setLength(0);
                    cur = null;
                    continue;
                }
                float pw = tp.getPageWidth(), ph = tp.getPageHeight();
                if (pw <= 0 || ph <= 0) continue;
                float h = tp.getHeightDir();
                float left = tp.getXDirAdj();
                float top = tp.getYDirAdj() - h;
                RectF r = new RectF(left / pw, top / ph, (left + tp.getWidthDirAdj()) / pw, (top + h) / ph);
                sb.append(u);
                if (cur == null) cur = r; else cur.union(r);
            }
            return out;
        }

        private static void appendSpace(StringBuilder sb, java.util.List<Integer> map, int idx) {
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) == ' ') return;
            sb.append(' ');
            map.add(idx);
        }

        java.util.List<SearchMatch> findMatches(String query, int pageIndex) {
            java.util.List<SearchMatch> out = new java.util.ArrayList<>();
            StringBuilder sb = new StringBuilder();
            java.util.List<Integer> map = new java.util.ArrayList<>(); // text index -> chars index
            for (int i = 0; i < chars.size(); i++) {
                TextPosition tp = chars.get(i);
                if (tp == null) {
                    appendSpace(sb, map, i);
                } else {
                    String u = tp.getUnicode();
                    if (u == null) continue;
                    for (int k = 0; k < u.length(); k++) {
                        char ch = u.charAt(k);
                        if (Character.isWhitespace(ch)) {
                            appendSpace(sb, map, i);
                        } else {
                            sb.append(ch);
                            map.add(i);
                        }
                    }
                }
            }
            String text = sb.toString();
            int qLen = query.length();
            int from = 0;
            while (qLen > 0 && from + qLen <= text.length()) {
                int idx = -1;
                for (int p = from; p + qLen <= text.length(); p++) {
                    if (text.regionMatches(true, p, query, 0, qLen)) { idx = p; break; }
                }
                if (idx < 0) break;
                from = idx + qLen;

                SearchMatch m = new SearchMatch();
                m.page = pageIndex;
                android.graphics.RectF cur = null;
                for (int ci = map.get(idx); ci <= map.get(idx + qLen - 1); ci++) {
                    TextPosition tp = chars.get(ci);
                    if (tp == null) continue;
                    float pw = tp.getPageWidth(), ph = tp.getPageHeight();
                    if (pw <= 0 || ph <= 0) continue;
                    float h = tp.getHeightDir();
                    float left = tp.getXDirAdj();
                    float top = tp.getYDirAdj() - h;
                    android.graphics.RectF r = new android.graphics.RectF(
                            left / pw, top / ph, (left + tp.getWidthDirAdj()) / pw, (top + h) / ph);
                    if (cur == null) {
                        cur = r;
                    } else if (Math.abs(r.top - cur.top) < cur.height() * 0.5f && r.left >= cur.left - 0.02f) {
                        cur.union(r);
                    } else {
                        m.rects.add(cur);
                        cur = r;
                    }
                }
                if (cur != null) m.rects.add(cur);
                if (!m.rects.isEmpty()) out.add(m);
            }
            return out;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        reading = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        backgroundExecutor.shutdownNow();
        if (bannerView != null) {
            bannerView.destroy();
            bannerView = null;
        }
    }


    // =====================================================================
    //  Extra features: bookmarks, annotate, OCR, crop & share, AI tasks
    // =====================================================================
    private static final int M_BOOKMARK = 9001, M_BOOKMARKS = 9002, M_ANNOTATE = 9003, M_OCR = 9004,
            M_CROP = 9005, M_KEYPOINTS = 9006, M_QUIZ = 9007, M_SAVE_COPY = 9008;

    private String docKey = "";
    private String docName = "document";
    private String pdfPassword = null;

    private android.content.SharedPreferences vp() {
        return getSharedPreferences("viewer_prefs", MODE_PRIVATE);
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ---------- locked PDF ----------
    private void askPdfPassword(final Uri uri, final int page) {
        if (destroyed || isFinishing()) return;
        final EditText in = new EditText(this);
        in.setHint("Password");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("This PDF is locked")
                .setView(in)
                .setCancelable(false)
                .setPositiveButton("Open", (d, w) -> {
                    pdfPassword = in.getText().toString();
                    displayPdf(uri, page);
                })
                .setNegativeButton("Cancel", (d, w) -> finish())
                .show();
    }

    // ---------- bookmarks ----------
    private TreeSet<Integer> loadBookmarks() {
        TreeSet<Integer> set = new TreeSet<>();
        String raw = vp().getString("bm_" + docKey, "");
        for (String p : raw.split(",")) {
            try { if (!p.trim().isEmpty()) set.add(Integer.parseInt(p.trim())); } catch (Exception ignored) {}
        }
        return set;
    }

    private void saveBookmarks(TreeSet<Integer> set) {
        StringBuilder sb = new StringBuilder();
        for (int p : set) { if (sb.length() > 0) sb.append(','); sb.append(p); }
        vp().edit().putString("bm_" + docKey, sb.toString()).apply();
    }

    private void toggleBookmark() {
        int page = pdfView.getCurrentPage();
        TreeSet<Integer> set = loadBookmarks();
        if (set.contains(page)) {
            set.remove(page);
            Toast.makeText(this, "Bookmark removed (page " + (page + 1) + ")", Toast.LENGTH_SHORT).show();
        } else {
            set.add(page);
            Toast.makeText(this, "Page " + (page + 1) + " bookmarked", Toast.LENGTH_SHORT).show();
        }
        saveBookmarks(set);
    }

    private void showBookmarks() {
        final TreeSet<Integer> set = loadBookmarks();
        if (set.isEmpty()) {
            Toast.makeText(this, "No bookmarks yet. Use \"Bookmark this page\".", Toast.LENGTH_LONG).show();
            return;
        }
        final Integer[] pages = set.toArray(new Integer[0]);
        String[] labels = new String[pages.length];
        for (int i = 0; i < pages.length; i++) labels[i] = "Page " + (pages[i] + 1);
        new AlertDialog.Builder(this)
                .setTitle("My bookmarks")
                .setItems(labels, (d, which) -> pdfView.jumpTo(pages[which], true))
                .setNeutralButton("Clear all", (d, w) -> { saveBookmarks(new TreeSet<>()); })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------- page text (PDF text first, OCR if the page is a scan) ----------
    private String extractPageText(int pageIndex) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(currentUri)) {
            if (is == null) throw new IOException("Cannot read PDF");
            PDDocument document = PDDocument.load(is);
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                return stripper.getText(document);
            } finally {
                document.close();
            }
        }
    }

    private String getPageTextWithOcr(int pageIndex) throws Exception {
        String t = null;
        try { t = extractPageText(pageIndex); } catch (Exception ignored) {}
        if (t != null && t.trim().length() >= 10) return t;
        Bitmap bmp = PdfUtils.renderPage(this, currentUri, pageIndex, 1600);
        try {
            return PdfUtils.ocr(bmp);
        } finally {
            bmp.recycle();
        }
    }

    private void requestAiTask(final String title, final String instruction) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("AI is working...");
        progress.setCancelable(false);
        progress.show();

        final int pageIdx = pdfView.getCurrentPage();
        backgroundExecutor.execute(() -> {
            String result;
            try {
                String text = getPageTextWithOcr(pageIdx);
                if (text == null || text.trim().length() < 10) {
                    result = "No readable text found on this page.";
                } else {
                    if (text.length() > MAX_AI_CHARS) text = text.substring(0, MAX_AI_CHARS);
                    result = callGroqApi(instruction + "\n\n" + text);
                }
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
            }
            final String finalResult = result;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                showResultDialog(title + " (Page " + (pageIdx + 1) + ")", finalResult);
            });
        });
    }

    // ---------- OCR ----------
    private void runOcrPage() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Reading page (OCR)...");
        progress.setCancelable(false);
        progress.show();

        final int pageIdx = pdfView.getCurrentPage();
        final Uri uri = currentUri;
        backgroundExecutor.execute(() -> {
            String result;
            try {
                Bitmap bmp = PdfUtils.renderPage(this, uri, pageIdx, 1600);
                try {
                    result = PdfUtils.ocr(bmp);
                } finally {
                    bmp.recycle();
                }
            } catch (Throwable e) {
                result = "OCR failed: " + e.getMessage();
            }
            final String text = (result == null || result.trim().isEmpty()) ? "No text found on this page." : result;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                new AlertDialog.Builder(this)
                        .setTitle("OCR text (page " + (pageIdx + 1) + ")")
                        .setMessage(text)
                        .setPositiveButton("Copy", (d, w) -> copyToClipboard(text))
                        .setNegativeButton("Close", null)
                        .show();
            });
        });
    }

    // ---------- crop & share ----------
    private void startCropShare() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Preparing page...");
        progress.setCancelable(false);
        progress.show();

        final int pageIdx = pdfView.getCurrentPage();
        final Uri uri = currentUri;
        backgroundExecutor.execute(() -> {
            Bitmap bmp = null;
            String err = null;
            try {
                bmp = PdfUtils.renderPage(this, uri, pageIdx, 1600);
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final Bitmap fb = bmp;
            final String ferr = err;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (fb == null) {
                    Toast.makeText(this, "Cannot render page: " + ferr, Toast.LENGTH_LONG).show();
                } else {
                    showCropDialog(fb);
                }
            });
        });
    }

    private void showCropDialog(final Bitmap bmp) {
        final android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final CropImageView cropView = new CropImageView(this);
        cropView.setBitmap(bmp);
        root.addView(cropView, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(16, 16, 16, 16);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        Button share = new Button(this);
        share.setText("Share");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(cancel, blp);
        bar.addView(share, blp);
        root.addView(bar, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        cancel.setOnClickListener(v -> dialog.dismiss());
        share.setOnClickListener(v -> {
            final Bitmap cropped = cropView.getCroppedBitmap();
            dialog.dismiss();
            backgroundExecutor.execute(() -> {
                try {
                    final Uri u = PdfUtils.saveBitmapToPictures(this, cropped, "page_" + System.currentTimeMillis());
                    mainHandler.post(() -> { if (!destroyed) shareImage(u); });
                } catch (Exception e) {
                    mainHandler.post(() -> { if (!destroyed) Toast.makeText(this, "Cannot save image: " + e.getMessage(), Toast.LENGTH_LONG).show(); });
                }
            });
        });
        dialog.setContentView(root);
        dialog.show();
    }

    private void shareImage(Uri uri) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share page image"));
    }

    // ---------- save copy ----------
    private void saveCopyToDownloads() {
        final Uri uri = currentUri;
        backgroundExecutor.execute(() -> {
            File tmp = new File(getCacheDir(), "copy_" + System.currentTimeMillis() + ".pdf");
            String msg;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PdfUtils.copy(is, os);
                PdfUtils.saveToDownloads(this, tmp, docName + "_copy.pdf", "application/pdf");
                msg = "Saved in Downloads/" + PdfUtils.FOLDER + "/" + docName + "_copy.pdf";
            } catch (Exception e) {
                msg = "Save failed: " + e.getMessage();
            } finally {
                tmp.delete();
            }
            final String fm = msg;
            mainHandler.post(() -> { if (!destroyed) Toast.makeText(this, fm, Toast.LENGTH_LONG).show(); });
        });
    }

    // ---------- highlight / note saved inside the PDF ----------
    private void showAnnotateDialog() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Reading page text...");
        progress.setCancelable(false);
        progress.show();

        final int pageIdx = pdfView.getCurrentPage();
        backgroundExecutor.execute(() -> {
            String text = "";
            try { text = extractPageText(pageIdx); } catch (Exception ignored) {}
            final List<String> lines = new ArrayList<>();
            for (String l : text.split("\\r?\\n")) {
                if (!l.trim().isEmpty()) lines.add(l.trim());
            }
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (lines.isEmpty()) {
                    Toast.makeText(this, "No selectable text on this page (scanned). Use \"OCR\" instead.", Toast.LENGTH_LONG).show();
                    return;
                }
                final String[] arr = lines.toArray(new String[0]);
                new AlertDialog.Builder(this)
                        .setTitle("Tap a line (Page " + (pageIdx + 1) + ")")
                        .setItems(arr, (d, which) -> onLineChosen(pageIdx, arr[which]))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void onLineChosen(final int page, final String line) {
        final String[] items = {"Yellow highlight", "Green highlight", "Pink highlight", "Blue highlight", "Underline", "Add a note"};
        new AlertDialog.Builder(this)
                .setTitle("What to do with this line?")
                .setItems(items, (d, which) -> {
                    if (which < 5) applyAnnotation(page, line, which, null);
                    else askNote(page, line);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void askNote(final int page, final String line) {
        final EditText in = new EditText(this);
        in.setHint("Write your note");
        in.setMinLines(3);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("Note")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (!n.isEmpty()) applyAnnotation(page, line, -1, n);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyAnnotation(final int page, final String line, final int colorIdx, final String note) {
        annotateInternal(page, line, null, colorIdx, note);
    }

    private void annotateInternal(final int page, final String line, final List<RectF> preRects, final int colorIdx, final String note) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Saving to PDF...");
        progress.setCancelable(false);
        progress.show();

        final Uri src = currentUri;
        backgroundExecutor.execute(() -> {
            final File out = new File(getCacheDir(), "annot_" + System.currentTimeMillis() + ".pdf");
            String error = null;
            try (InputStream is = getContentResolver().openInputStream(src)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PDDocument doc = PDDocument.load(is);
                try {
                    PDPage pg = doc.getPage(page);
                    if (pg.getRotation() != 0) throw new IOException("Rotated pages are not supported yet");

                    List<RectF> rects;
                    if (preRects != null) {
                        rects = preRects;
                    } else {
                        MatchFinder f = new MatchFinder();
                        f.setStartPage(page + 1);
                        f.setEndPage(page + 1);
                        f.getText(doc);
                        List<SearchMatch> ms = f.findMatches(line.replaceAll("\\s+", " ").trim(), page);
                        rects = ms.isEmpty() ? new ArrayList<RectF>() : ms.get(0).rects;
                    }

                    PDRectangle box = pg.getCropBox();
                    List<PDAnnotation> anns = pg.getAnnotations();

                    if (colorIdx >= 0) {
                        if (rects.isEmpty()) throw new IOException("Could not locate this line on the page");
                        float[][] cols = {{1f, 0.92f, 0.23f}, {0.55f, 0.85f, 0.4f}, {1f, 0.6f, 0.8f}, {0.45f, 0.7f, 1f}, {0.9f, 0.1f, 0.1f}};
                        for (RectF r : rects) {
                            float x1 = box.getLowerLeftX() + r.left * box.getWidth();
                            float x2 = box.getLowerLeftX() + r.right * box.getWidth();
                            float yTop = box.getUpperRightY() - r.top * box.getHeight();
                            float yBot = box.getUpperRightY() - r.bottom * box.getHeight();
                            PDAnnotationTextMarkup hl = new PDAnnotationTextMarkup(colorIdx == 4
                                    ? PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
                                    : PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT);
                            hl.setRectangle(new PDRectangle(x1, yBot, x2 - x1, yTop - yBot));
                            hl.setQuadPoints(new float[]{x1, yTop, x2, yTop, x1, yBot, x2, yBot});
                            hl.setColor(new PDColor(cols[colorIdx], PDDeviceRGB.INSTANCE));
                            anns.add(hl);
                        }
                    } else {
                        float nx = box.getLowerLeftX() + 8f;
                        float ny = box.getUpperRightY() - 28f;
                        if (!rects.isEmpty()) {
                            RectF r = rects.get(0);
                            nx = Math.max(box.getLowerLeftX() + 4f, box.getLowerLeftX() + r.left * box.getWidth() - 26f);
                            ny = box.getUpperRightY() - r.top * box.getHeight() - 20f;
                        }
                        PDAnnotationText t = new PDAnnotationText();
                        t.setContents(note);
                        t.setRectangle(new PDRectangle(nx, ny, 20f, 20f));
                        t.setColor(new PDColor(new float[]{1f, 0.85f, 0f}, PDDeviceRGB.INSTANCE));
                        anns.add(t);
                    }
                    pg.setAnnotations(anns);
                    doc.save(out);
                } finally {
                    doc.close();
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            final String ferr = error;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (ferr != null) {
                    out.delete();
                    Toast.makeText(this, "Could not save: " + ferr, Toast.LENGTH_LONG).show();
                    return;
                }
                // remove the previous annotated copy, show the new one
                if ("file".equals(src.getScheme()) && src.getLastPathSegment() != null
                        && src.getLastPathSegment().startsWith("annot_")) {
                    new File(src.getPath()).delete();
                }
                currentUri = Uri.fromFile(out);
                displayPdf(currentUri, pdfView.getCurrentPage());
                Toast.makeText(this, "Saved in PDF. Use \"Save PDF copy\" to keep it in Downloads.", Toast.LENGTH_LONG).show();
            });
        });
    }


    // =====================================================================
    //  Text selection (long-press a word), draw/sign/text editor,
    //  read aloud, share
    // =====================================================================
    private static final int M_DRAW = 9009, M_THUMBS = 9010, M_READ = 9011, M_SHARE_PDF = 9012;

    private static class WordBox {
        String text;
        RectF rect;
    }

    private List<WordBox> selWords = null;
    private int selPage = -1, selStart = -1, selEnd = -1;
    private int cachedWordsPage = -1;
    private List<WordBox> cachedWords = null;
    private LinearLayout selectionBar;
    private final Paint selPaint = new Paint();

    private int dpPx(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button barButton(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(0xFFFFFFFF);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dpPx(6), 0, dpPx(6), 0);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout buildSelectionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF263238);
        bar.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(barButton("Copy", v -> copySelection()), lp);
        bar.addView(barButton("Mark", v -> markSelection()), lp);
        bar.addView(barButton("Explain", v -> {
            final String t = selectedText();
            if (!t.isEmpty()) triggerAiWithAd(() -> requestAiExplanation(t));
        }), lp);
        bar.addView(barButton("\u25C0", v -> extendSelection(-1)), lp);
        bar.addView(barButton("\u25B6", v -> extendSelection(1)), lp);
        bar.addView(barButton("\u2715", v -> clearSelection()), lp);
        return bar;
    }

    private String selectedText() {
        if (selWords == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = selStart; i <= selEnd && i < selWords.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(selWords.get(i).text);
        }
        return sb.toString();
    }

    private void copySelection() {
        String t = selectedText();
        if (!t.isEmpty()) copyToClipboard(t);
    }

    private void extendSelection(int dir) {
        if (selWords == null) return;
        if (dir < 0 && selStart > 0) selStart--;
        if (dir > 0 && selEnd < selWords.size() - 1) selEnd++;
        pdfView.invalidate();
    }

    private void clearSelection() {
        selWords = null;
        selPage = selStart = selEnd = -1;
        if (selectionBar != null) selectionBar.setVisibility(android.view.View.GONE);
        pdfView.invalidate();
    }

    private void markSelection() {
        if (selWords == null) return;
        final int page = selPage;
        final List<RectF> rects = new ArrayList<>();
        RectF cur = null;
        for (int i = selStart; i <= selEnd && i < selWords.size(); i++) {
            RectF r = new RectF(selWords.get(i).rect);
            if (cur == null) {
                cur = r;
            } else if (Math.abs(r.top - cur.top) < cur.height() * 0.5f) {
                cur.union(r);
            } else {
                rects.add(cur);
                cur = r;
            }
        }
        if (cur != null) rects.add(cur);
        final String[] items = {"Yellow highlight", "Green highlight", "Pink highlight", "Blue highlight", "Underline"};
        new AlertDialog.Builder(this)
                .setTitle("Mark selected text")
                .setItems(items, (d, which) -> {
                    annotateInternal(page, null, rects, which, null);
                    clearSelection();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // touch point -> {page, x(0..1), y(0..1)} using the viewer's scroll and zoom
    private float[] touchToPage(float tx, float ty) {
        try {
            float zoom = pdfView.getZoom();
            float docX = -pdfView.getCurrentXOffset() + tx;
            float docY = -pdfView.getCurrentYOffset() + ty;
            int n = pageCount;
            float maxW = 0f;
            for (int i = 0; i < n; i++) maxW = Math.max(maxW, pdfView.getPageSize(i).getWidth());
            float y = 0f;
            for (int i = 0; i < n; i++) {
                float w = pdfView.getPageSize(i).getWidth() * zoom;
                float h = pdfView.getPageSize(i).getHeight() * zoom;
                if (docY < y + h) {
                    float left = (maxW * zoom - w) / 2f;
                    return new float[]{i, (docX - left) / w, (docY - y) / h};
                }
                y += h;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<WordBox> loadWords(int page) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(currentUri)) {
            if (is == null) throw new IOException("Cannot read PDF");
            PDDocument doc = PDDocument.load(is);
            try {
                MatchFinder f = new MatchFinder();
                f.setStartPage(page + 1);
                f.setEndPage(page + 1);
                f.getText(doc);
                return f.words();
            } finally {
                doc.close();
            }
        }
    }

    private int findWord(List<WordBox> words, float nx, float ny) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) {
            RectF r = words.get(i).rect;
            float dx = nx < r.left ? r.left - nx : (nx > r.right ? nx - r.right : 0f);
            float dy = ny < r.top ? r.top - ny : (ny > r.bottom ? ny - r.bottom : 0f);
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = i; }
        }
        return bestD < 0.0016f ? best : -1;
    }

    private void onPdfLongPress(MotionEvent e) {
        final float[] hit = touchToPage(e.getX(), e.getY());
        if (hit == null) return;
        final int page = (int) hit[0];
        final float nx = hit[1], ny = hit[2];
        final List<WordBox> cached = (cachedWordsPage == page) ? cachedWords : null;
        backgroundExecutor.execute(() -> {
            List<WordBox> w = cached;
            if (w == null) {
                try { w = loadWords(page); } catch (Exception ignored) {}
            }
            final List<WordBox> fw = w;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (fw == null || fw.isEmpty()) {
                    Toast.makeText(this, "No selectable text here. For scanned pages use \"OCR\".", Toast.LENGTH_LONG).show();
                    return;
                }
                cachedWordsPage = page;
                cachedWords = fw;
                int idx = findWord(fw, nx, ny);
                if (idx < 0) {
                    Toast.makeText(this, "Long-press directly on a word", Toast.LENGTH_SHORT).show();
                    return;
                }
                selWords = fw;
                selPage = page;
                selStart = selEnd = idx;
                selectionBar.setVisibility(android.view.View.VISIBLE);
                pdfView.invalidate();
            });
        });
    }

    // ---------- draw / sign / add text ----------
    private void startPageEditor() {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Preparing page...");
        progress.setCancelable(false);
        progress.show();

        final int pageIdx = pdfView.getCurrentPage();
        final Uri uri = currentUri;
        backgroundExecutor.execute(() -> {
            Bitmap bmp = null;
            String err = null;
            try {
                bmp = PdfUtils.renderPage(this, uri, pageIdx, 1400);
            } catch (Throwable e) {
                err = e.getMessage();
            }
            final Bitmap fb = bmp;
            final String ferr = err;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (fb == null) {
                    Toast.makeText(this, "Cannot open page: " + ferr, Toast.LENGTH_LONG).show();
                } else {
                    PageEditorDialog.show(this, fb, items -> applyEdits(pageIdx, items));
                }
            });
        });
    }

    private void drawStrokeToPdf(PDPageContentStream cs, List<float[]> pts, int color, float widthPt,
                                 float ox, float oy, float bw, float bh) throws IOException {
        if (pts.isEmpty()) return;
        cs.setStrokingColor(android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color));
        cs.setLineWidth(Math.max(0.3f, widthPt));
        cs.setLineCapStyle(1);
        cs.setLineJoinStyle(1);
        float x0 = ox + pts.get(0)[0] * bw;
        float y0 = oy - pts.get(0)[1] * bh;
        cs.moveTo(x0, y0);
        if (pts.size() == 1) cs.lineTo(x0 + 0.1f, y0);
        for (int i = 1; i < pts.size(); i++) {
            cs.lineTo(ox + pts.get(i)[0] * bw, oy - pts.get(i)[1] * bh);
        }
        cs.stroke();
    }

    private void drawTextToPdf(PDDocument doc, PDPageContentStream cs, EditItem it,
                               float ox, float oy, float bw, float bh) throws IOException {
        float px = it.size * 2000f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(px);
        p.setColor(it.color);
        Paint.FontMetrics fm = p.getFontMetrics();
        int tw = (int) Math.ceil(p.measureText(it.text)) + 8;
        int th = (int) Math.ceil(fm.descent - fm.ascent) + 8;
        Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawText(it.text, 4f, 4f - fm.ascent, p);
        PDImageXObject img = LosslessFactory.createFromImage(doc, b);
        float scale = bw / 2000f;
        float wPt = tw * scale, hPt = th * scale;
        float cx = ox + it.cx * bw;
        float cy = oy - it.cy * bh;
        cs.drawImage(img, cx - wPt / 2f, cy - hPt / 2f, wPt, hPt);
        b.recycle();
    }

    private void applyEdits(final int pageIdx, final List<EditItem> items) {
        final ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("Saving to PDF...");
        progress.setCancelable(false);
        progress.show();

        final Uri src = currentUri;
        backgroundExecutor.execute(() -> {
            final File out = new File(getCacheDir(), "annot_" + System.currentTimeMillis() + ".pdf");
            String error = null;
            try (InputStream is = getContentResolver().openInputStream(src)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PDDocument doc = PDDocument.load(is);
                try {
                    PDPage pg = doc.getPage(pageIdx);
                    if (pg.getRotation() != 0) throw new IOException("Rotated pages are not supported yet");
                    PDRectangle box = pg.getCropBox();
                    float bw = box.getWidth(), bh = box.getHeight();
                    float ox = box.getLowerLeftX(), oy = box.getUpperRightY();

                    PDPageContentStream cs = new PDPageContentStream(doc, pg,
                            PDPageContentStream.AppendMode.APPEND, true, true);
                    try {
                        for (EditItem it : items) {
                            if (it.type == EditItem.STROKE) {
                                drawStrokeToPdf(cs, it.pts, it.color, it.width * bw, ox, oy, bw, bh);
                            } else if (it.type == EditItem.SIGN) {
                                for (List<float[]> st : it.sigStrokes) {
                                    List<float[]> mapped = new ArrayList<>();
                                    for (float[] p : st) {
                                        mapped.add(new float[]{it.cx + (p[0] - 0.5f) * it.w, it.cy + (p[1] - 0.5f) * it.h});
                                    }
                                    drawStrokeToPdf(cs, mapped, it.color, it.width * bw, ox, oy, bw, bh);
                                }
                            } else if (it.type == EditItem.TEXT) {
                                drawTextToPdf(doc, cs, it, ox, oy, bw, bh);
                            }
                        }
                    } finally {
                        cs.close();
                    }
                    doc.save(out);
                } finally {
                    doc.close();
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }

            final String ferr = error;
            mainHandler.post(() -> {
                if (destroyed) return;
                progress.dismiss();
                if (ferr != null) {
                    out.delete();
                    Toast.makeText(this, "Could not save: " + ferr, Toast.LENGTH_LONG).show();
                    return;
                }
                if ("file".equals(src.getScheme()) && src.getLastPathSegment() != null
                        && src.getLastPathSegment().startsWith("annot_")) {
                    new File(src.getPath()).delete();
                }
                currentUri = Uri.fromFile(out);
                displayPdf(currentUri, pdfView.getCurrentPage());
                Toast.makeText(this, "Added to the PDF. Use \"Save PDF copy\" to keep it in Downloads.", Toast.LENGTH_LONG).show();
            });
        });
    }

    // ---------- read aloud ----------
    private android.speech.tts.TextToSpeech tts;
    private boolean reading = false;
    private int readPage = 0;
    private LinearLayout readBar;
    private TextView readLabel;

    private LinearLayout buildReadBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF37474F);
        bar.setPadding(dpPx(12), dpPx(2), dpPx(4), dpPx(2));
        bar.setVisibility(android.view.View.GONE);
        readLabel = new TextView(this);
        readLabel.setTextColor(0xFFFFFFFF);
        readLabel.setTextSize(13);
        bar.addView(readLabel, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(barButton("\u23F9 Stop", v -> stopReading()));
        return bar;
    }

    private void startReadAloud() {
        if (reading) { stopReading(); return; }
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String id) {}
                        @Override public void onError(String id) { runOnUiThread(() -> stopReading()); }
                        @Override public void onDone(String id) {
                            if (id != null && id.endsWith("_last")) runOnUiThread(() -> readNextPage());
                        }
                    });
                    runOnUiThread(this::beginReading);
                } else {
                    runOnUiThread(() -> {
                        tts = null;
                        Toast.makeText(this, "Text-to-speech is not available on this phone", Toast.LENGTH_LONG).show();
                    });
                }
            });
        } else {
            beginReading();
        }
    }

    private void beginReading() {
        if (destroyed) return;
        reading = true;
        readPage = pdfView.getCurrentPage();
        readBar.setVisibility(android.view.View.VISIBLE);
        speakPage(readPage);
    }

    private void stopReading() {
        reading = false;
        if (tts != null) tts.stop();
        if (readBar != null) readBar.setVisibility(android.view.View.GONE);
    }

    private void readNextPage() {
        if (!reading || destroyed) return;
        readPage++;
        if (readPage >= pageCount) {
            stopReading();
            Toast.makeText(this, "Finished reading", Toast.LENGTH_SHORT).show();
            return;
        }
        pdfView.jumpTo(readPage, true);
        speakPage(readPage);
    }

    private void speakPage(final int page) {
        readLabel.setText("Reading page " + (page + 1) + " of " + pageCount + "...");
        backgroundExecutor.execute(() -> {
            String t = "";
            try { t = getPageTextWithOcr(page); } catch (Exception ignored) {}
            final String text = t == null ? "" : t.replaceAll("\\s+", " ").trim();
            mainHandler.post(() -> {
                if (destroyed || !reading || tts == null) return;
                if (text.length() < 3) {
                    readNextPage();
                    return;
                }
                int dev = 0, letters = 0;
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if (Character.isLetter(ch)) {
                        letters++;
                        if (ch >= 0x0900 && ch <= 0x097F) dev++;
                    }
                }
                boolean hindi = letters > 0 && dev * 5 > letters;
                int r = tts.setLanguage(hindi ? new Locale("hi", "IN") : new Locale("en", "IN"));
                if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                        || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (hindi) {
                        stopReading();
                        Toast.makeText(this, "Hindi voice is not installed. Install it in phone Settings > Text-to-speech.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    tts.setLanguage(Locale.US);
                }
                // split into chunks the engine can handle
                List<String> chunks = new ArrayList<>();
                StringBuilder cur = new StringBuilder();
                for (String sentence : text.split("(?<=[.!?\u0964])\\s*")) {
                    if (cur.length() + sentence.length() > 3000 && cur.length() > 0) {
                        chunks.add(cur.toString());
                        cur.setLength(0);
                    }
                    cur.append(sentence).append(' ');
                }
                if (cur.length() > 0) chunks.add(cur.toString());
                for (int i = 0; i < chunks.size(); i++) {
                    String id = "pg" + page + "_" + i + (i == chunks.size() - 1 ? "_last" : "");
                    tts.speak(chunks.get(i), android.speech.tts.TextToSpeech.QUEUE_ADD, null, id);
                }
            });
        });
    }

    // ---------- share ----------
    private void sharePdf() {
        final Uri uri = currentUri;
        Toast.makeText(this, "Preparing PDF...", Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            File tmp = new File(getCacheDir(), "share_" + System.currentTimeMillis() + ".pdf");
            Uri shared = null;
            String err = null;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                if (is == null) throw new IOException("Cannot read PDF");
                PdfUtils.copy(is, os);
                shared = PdfUtils.saveToDownloads(this, tmp, docName + "_share.pdf", "application/pdf");
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                tmp.delete();
            }
            final Uri fu = shared;
            final String ferr = err;
            mainHandler.post(() -> {
                if (destroyed) return;
                if (fu == null) {
                    Toast.makeText(this, "Cannot share: " + ferr, Toast.LENGTH_LONG).show();
                } else if ("file".equals(fu.getScheme())) {
                    Toast.makeText(this, "Saved in Downloads/" + PdfUtils.FOLDER + ". Share it from your Files app.", Toast.LENGTH_LONG).show();
                } else {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/pdf");
                    i.putExtra(Intent.EXTRA_STREAM, fu);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share PDF"));
                }
            });
        });
    }
}
