package com.mycompany.pdfviewer;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.services.banners.BannerErrorInfo;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PdfViewerAds";
    private static final int REQ_MANAGE_STORAGE = 400;

    private static final String GAME_ID = "800356656";
    private static final boolean TEST_MODE = false;
    private static final String BANNER_PLACEMENT = "Banner_Android";
    private static final String INTERSTITIAL_PLACEMENT = "Interstitial_Android";
    private static final String REWARDED_PLACEMENT = "Rewarded_Android";

    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {5000, 15000, 30000};

    private RecyclerView recyclerView;
    private TextView emptyText;
    private EditText searchBox;
    private FrameLayout bannerContainer;
    private BannerView bannerView;
    private boolean searchVisible = false;

    private final Handler adHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean scanning = false;
    private long lastScanTime = 0;
    private long lastScrollAt = 0;
    private boolean bannerRequested = false;
    private ToolsController toolsController;
    private static final int ID_RECENT = 7001, ID_FAV = 7002;
    private int listMode = 0; // 0 = all PDFs, 1 = recent, 2 = favorites
    private android.content.SharedPreferences listPrefs;
    private final java.util.Set<String> favSet = new java.util.HashSet<>();
    private boolean destroyed = false;

    private boolean rewardedReady = false;
    private boolean interstitialReady = false;
    private int rewardedRetryCount = 0;
    private int interstitialRetryCount = 0;

    private List<String[]> allPdfs = new ArrayList<>();
    private List<String[]> filteredPdfs = new ArrayList<>();
    private android.content.SharedPreferences cachePrefs;
    private static final String CACHE_KEY = "cached_pdf_list";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recentList);
        emptyText = findViewById(R.id.emptyText);
        searchBox = findViewById(R.id.searchBox);
        bannerContainer = findViewById(R.id.bannerContainer);
        // keep a fixed 50dp strip so the list is never resized when the ad arrives
        android.view.ViewGroup.LayoutParams bcl = bannerContainer.getLayoutParams();
        if (bcl != null) {
            bcl.height = Math.round(50 * getResources().getDisplayMetrics().density);
            bannerContainer.setLayoutParams(bcl);
        }
        bannerContainer.setVisibility(View.INVISIBLE);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(20);
        recyclerView.setAdapter(new PdfAdapter());
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                lastScrollAt = android.os.SystemClock.uptimeMillis();
            }
        });

        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);
        final LinearLayout pdfSectionLayout = findViewById(R.id.pdfSectionLayout);
        final LinearLayout toolsSectionLayout = findViewById(R.id.toolsSectionLayout);
        MaterialCardView cardEditPdfTool = findViewById(R.id.cardEditPdfTool);

        // one consistent bar: same icon style, labels always visible, sensible order
        Menu navMenu = bottomNavigation.getMenu();
        navMenu.clear();
        navMenu.add(Menu.NONE, R.id.nav_pdfs, 0, "PDFs").setIcon(NavIcons.make(this, NavIcons.PDFS));
        navMenu.add(Menu.NONE, ID_RECENT, 1, "Recent").setIcon(NavIcons.make(this, NavIcons.RECENT));
        navMenu.add(Menu.NONE, ID_FAV, 2, "Favorites").setIcon(NavIcons.make(this, NavIcons.FAVORITES));
        navMenu.add(Menu.NONE, R.id.nav_tools, 3, "Tools").setIcon(NavIcons.make(this, NavIcons.TOOLS));
        bottomNavigation.setLabelVisibilityMode(
                com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        navMenu.findItem(R.id.nav_pdfs).setChecked(true);

        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_pdfs || id == ID_RECENT || id == ID_FAV) {
                listMode = id == ID_RECENT ? 1 : (id == ID_FAV ? 2 : 0);
                pdfSectionLayout.setVisibility(View.VISIBLE);
                toolsSectionLayout.setVisibility(View.GONE);
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(listMode == 1 ? "Recent" : (listMode == 2 ? "Favorites" : "PDF Viewer"));
                }
                filterList(searchBox.getText().toString());
                return true;
            } else if (id == R.id.nav_tools) {
                pdfSectionLayout.setVisibility(View.GONE);
                toolsSectionLayout.setVisibility(View.VISIBLE);
                if (getSupportActionBar() != null) getSupportActionBar().setTitle("PDF Tools");
                return true;
            }
            return false;
        });

        cardEditPdfTool.setOnClickListener(v -> 
            Toast.makeText(MainActivity.this, "Please open a PDF from the PDFs tab to edit it inside the viewer.", Toast.LENGTH_LONG).show()
        );

        toolsController = new ToolsController(this, uri -> navigateToViewer(uri));
        toolsController.attach(toolsSectionLayout);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterList(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        initAds();
        checkStoragePermission();
        showLastCrashIfAny();

        cachePrefs = getSharedPreferences("pdf_cache", MODE_PRIVATE);
        listPrefs = getSharedPreferences("pdf_lists", MODE_PRIVATE);
        favSet.addAll(listPrefs.getStringSet("fav", new java.util.HashSet<String>()));
        List<String[]> cached = loadCachedList();
        if (!cached.isEmpty()) {
            allPdfs = cached;
            filterList(searchBox.getText().toString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermission() && !scanning && System.currentTimeMillis() - lastScanTime > 120000) scanDevice();
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Environment.isExternalStorageManager()
                : true;
    }

    private void checkStoragePermission() {
        if (!hasStoragePermission()) {
            emptyText.setText("Storage permission needed. Tap here to allow access.");
            emptyText.setOnClickListener(v -> requestStoragePermission());
            Toast.makeText(this, "Grant All files access to auto-load PDFs", Toast.LENGTH_LONG).show();
            requestStoragePermission();
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQ_MANAGE_STORAGE);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQ_MANAGE_STORAGE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (toolsController != null && toolsController.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == REQ_MANAGE_STORAGE && hasStoragePermission()) {
            scanDevice();
        }
    }

    // Saves the last crash stack trace so it can be shown on next launch (no logcat needed)
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                getSharedPreferences("crash_log", MODE_PRIVATE).edit()
                        .putString("last", sw.toString()).commit();
            } catch (Exception ignored) {}
            if (old != null) old.uncaughtException(t, e);
        });
    }

    private void showLastCrashIfAny() {
        final android.content.SharedPreferences sp = getSharedPreferences("crash_log", MODE_PRIVATE);
        final String last = sp.getString("last", null);
        if (last == null) return;
        sp.edit().remove("last").apply();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("App crashed last time")
                .setMessage(last.length() > 1500 ? last.substring(0, 1500) : last)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", last));
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void scanDevice() {
        if (scanning) return;
        scanning = true;
        emptyText.setOnClickListener(null);

        scanExecutor.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
            // Fast path: ask Android's media index. Slow folder walk only if that finds nothing.
            List<String[]> results = queryPdfsFromMediaStore(sdf);
            if (results.isEmpty()) {
                File root = Environment.getExternalStorageDirectory();
                scanRecursive(root, 0, results, sdf);
            }
            Collections.sort(results, (a, b) -> Long.compare(Long.parseLong(b[3]), Long.parseLong(a[3])));

            if (destroyed) return;
            mainHandler.post(() -> {
                if (destroyed) return;
                allPdfs = results;
                scanning = false;
                lastScanTime = System.currentTimeMillis();
                filterList(searchBox.getText().toString());
                saveCachedList(results);
            });
        });
    }

    private List<String[]> loadCachedList() {
        List<String[]> list = new ArrayList<>();
        try {
            String json = cachePrefs.getString(CACHE_KEY, null);
            if (json == null) return list;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new String[]{o.getString("uri"), o.getString("name"), o.getString("date")});
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void saveCachedList(List<String[]> list) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] item : list) {
                JSONObject o = new JSONObject();
                o.put("uri", item[0]);
                o.put("name", item[1]);
                o.put("date", item[2]);
                arr.put(o);
            }
            cachePrefs.edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private List<String[]> queryPdfsFromMediaStore(SimpleDateFormat sdf) {
        List<String[]> list = new ArrayList<>();
        String[] projection = {
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED
        };
        try (Cursor c = getContentResolver().query(
                MediaStore.Files.getContentUri("external"),
                projection,
                MediaStore.Files.FileColumns.MIME_TYPE + "=?",
                new String[]{"application/pdf"},
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return list;
            int iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
            int iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
            while (c.moveToNext()) {
                String path = c.getString(iData);
                if (path == null) continue;
                File f = new File(path);
                if (!f.exists()) continue;
                String name = c.getString(iName);
                if (name == null) name = f.getName();
                long modified = c.getLong(iMod) * 1000L;
                list.add(new String[]{Uri.fromFile(f).toString(), name,
                        sdf.format(new Date(modified)), String.valueOf(modified)});
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query failed: " + e.getMessage());
        }
        return list;
    }

    private void scanRecursive(File dir, int depth, List<String[]> results, SimpleDateFormat sdf) {
        if (dir == null || !dir.isDirectory() || depth > 12) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.isHidden()) continue;
                if (depth == 0 && f.getName().equals("Android")) continue;
                scanRecursive(f, depth + 1, results, sdf);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                String date = sdf.format(new Date(f.lastModified()));
                results.add(new String[]{Uri.fromFile(f).toString(), f.getName(), date, String.valueOf(f.lastModified())});
            }
        }
    }

    private void toggleSearch() {
        searchVisible = !searchVisible;
        if (searchVisible) {
            searchBox.setVisibility(View.VISIBLE);
            searchBox.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT);
        } else {
            searchBox.setText("");
            searchBox.setVisibility(View.GONE);
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        }
    }

    private void initAds() {
        UnityAds.initialize(this, GAME_ID, TEST_MODE, new IUnityAdsInitializationListener() {
            @Override
            public void onInitializationComplete() {
                loadBanner();
                loadInterstitial();
                loadRewarded();
            }

            @Override
            public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) {
                Log.e(TAG, "Ad init failed: " + error + " - " + message);
            }
        });
    }

    private void loadBanner() {
        runOnUiThread(() -> {
            if (bannerRequested || destroyed) return;
            bannerRequested = true;
            mainHandler.postDelayed(this::bannerIdleCheck, 1500);
        });
    }

    // create the ad view only when the user is not scrolling
    private void bannerIdleCheck() {
        if (destroyed) return;
        if (android.os.SystemClock.uptimeMillis() - lastScrollAt < 1500) {
            mainHandler.postDelayed(this::bannerIdleCheck, 700);
        } else {
            loadBannerNow();
        }
    }

    private void loadBannerNow() {
        if (bannerView != null) return;
        bannerView = new BannerView(this, BANNER_PLACEMENT, new UnityBannerSize(320, 50));
        bannerView.setListener(new BannerView.IListener() {
            @Override
            public void onBannerLoaded(BannerView bannerAdView) {
                runOnUiThread(() -> bannerContainer.setVisibility(View.VISIBLE));
            }

            @Override public void onBannerShown(BannerView bannerAdView) {}
            @Override public void onBannerClick(BannerView bannerAdView) {}

            @Override
            public void onBannerFailedToLoad(BannerView bannerAdView, BannerErrorInfo errorInfo) {
                Log.e(TAG, "Banner failed: [" + errorInfo.errorCode + "] " + errorInfo.errorMessage);
                runOnUiThread(() -> bannerContainer.setVisibility(View.GONE));
            }

            @Override public void onBannerLeftApplication(BannerView bannerAdView) {}
        });
        float d = getResources().getDisplayMetrics().density;
        bannerContainer.addView(bannerView, new FrameLayout.LayoutParams(
                Math.round(320 * d), Math.round(50 * d), android.view.Gravity.CENTER_HORIZONTAL));
        bannerView.load();
    }

    private void loadInterstitial() {
        UnityAds.load(INTERSTITIAL_PLACEMENT, new IUnityAdsLoadListener() {
            @Override
            public void onUnityAdsAdLoaded(String placementId) {
                interstitialReady = true;
                interstitialRetryCount = 0;
            }

            @Override
            public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                interstitialReady = false;
                retryInterstitial();
            }
        });
    }

    private void retryInterstitial() {
        if (interstitialRetryCount >= MAX_RETRIES) {
            interstitialRetryCount = 0;
            return;
        }
        long delay = RETRY_DELAYS_MS[interstitialRetryCount];
        interstitialRetryCount++;
        adHandler.postDelayed(this::loadInterstitial, delay);
    }

    private void loadRewarded() {
        rewardedReady = false;
        UnityAds.load(REWARDED_PLACEMENT, new IUnityAdsLoadListener() {
            @Override
            public void onUnityAdsAdLoaded(String placementId) {
                rewardedReady = true;
                rewardedRetryCount = 0;
            }

            @Override
            public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                rewardedReady = false;
                retryRewarded();
            }
        });
    }

    private void retryRewarded() {
        if (rewardedRetryCount >= MAX_RETRIES) {
            rewardedRetryCount = 0;
            return;
        }
        long delay = RETRY_DELAYS_MS[rewardedRetryCount];
        rewardedRetryCount++;
        adHandler.postDelayed(this::loadRewarded, delay);
    }

    private void showRewardedAd() {
        if (!rewardedReady) {
            Toast.makeText(this, "Please try again in a moment", Toast.LENGTH_SHORT).show();
            rewardedRetryCount = 0;
            loadRewarded();
            return;
        }
        UnityAds.show(this, REWARDED_PLACEMENT, new IUnityAdsShowListener() {
            @Override
            public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                rewardedReady = false;
                loadRewarded();
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override
            public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                runOnUiThread(() -> {
                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                        Toast.makeText(MainActivity.this, "Thanks for your support!", Toast.LENGTH_SHORT).show();
                    }
                });
                rewardedReady = false;
                rewardedRetryCount = 0;
                loadRewarded();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_search) {
            toggleSearch();
            return true;
        }
        if (id == R.id.action_watch_ad) {
            showRewardedAd();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void filterList(String query) {
        filteredPdfs.clear();
        List<String[]> source = new ArrayList<>();
        if (listMode == 1) {
            source = loadRecents();
        } else if (listMode == 2) {
            for (String[] it : allPdfs) if (favSet.contains(it[0])) source.add(it);
        } else {
            source = allPdfs;
        }
        for (String[] item : source) {
            if (item[1] != null && item[1].toLowerCase(Locale.getDefault())
                    .contains(query.toLowerCase(Locale.getDefault()))) {
                filteredPdfs.add(item);
            }
        }
        if (hasStoragePermission()) {
            emptyText.setVisibility(filteredPdfs.isEmpty() ? View.VISIBLE : View.GONE);
            if (listMode == 1) {
                emptyText.setText("No recent files yet.");
            } else if (listMode == 2) {
                emptyText.setText("No favorites yet. Long-press a PDF to add it.");
            } else {
                emptyText.setText(allPdfs.isEmpty()
                        ? (scanning ? "Scanning for PDFs..." : "No PDFs found on device.")
                        : "No matching PDFs found.");
            }
        }
        if (recyclerView.getAdapter() != null) {
            recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    private void openViewer(Uri uri) {
        if (!interstitialReady) {
            navigateToViewer(uri);
            return;
        }
        UnityAds.show(this, INTERSTITIAL_PLACEMENT, new IUnityAdsShowListener() {
            @Override
            public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                navigateToViewer(uri);
                interstitialReady = false;
                loadInterstitial();
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override
            public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                getSharedPreferences("ad_prefs", MODE_PRIVATE).edit()
                        .putLong("last_interstitial", System.currentTimeMillis()).apply();
                navigateToViewer(uri);
                interstitialReady = false;
                interstitialRetryCount = 0;
                loadInterstitial();
            }
        });
    }

    // ---------- favorites and recent files ----------
    private void showItemMenu(final String[] item) {
        final boolean fav = favSet.contains(item[0]);
        final String[] options = {fav ? "Remove from favorites" : "Add to favorites", "Remove from recent"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(item[1])
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        if (fav) favSet.remove(item[0]); else favSet.add(item[0]);
                        listPrefs.edit().putStringSet("fav", new java.util.HashSet<>(favSet)).apply();
                        Toast.makeText(this, fav ? "Removed from favorites" : "Added to favorites", Toast.LENGTH_SHORT).show();
                    } else {
                        removeRecent(item[0]);
                    }
                    filterList(searchBox.getText().toString());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<String[]> loadRecents() {
        List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(listPrefs.getString("recent", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray o = arr.getJSONArray(i);
                String uri = o.getString(0);
                if (uri.startsWith("file:") && !new File(Uri.parse(uri).getPath()).exists()) continue;
                list.add(new String[]{uri, o.getString(1), o.getString(2)});
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void saveRecents(List<String[]> list) {
        JSONArray arr = new JSONArray();
        for (String[] it : list) {
            JSONArray o = new JSONArray();
            o.put(it[0]);
            o.put(it[1]);
            o.put(it[2]);
            arr.put(o);
        }
        listPrefs.edit().putString("recent", arr.toString()).apply();
    }

    private void addRecent(Uri uri) {
        String name = null;
        for (String[] it : allPdfs) {
            if (it[0].equals(uri.toString())) { name = it[1]; break; }
        }
        if (name == null) name = PdfUtils.displayName(this, uri) + ".pdf";
        String date = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date());
        List<String[]> list = loadRecents();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i)[0].equals(uri.toString())) list.remove(i);
        }
        list.add(0, new String[]{uri.toString(), name, date});
        while (list.size() > 30) list.remove(list.size() - 1);
        saveRecents(list);
    }

    private void removeRecent(String uri) {
        List<String[]> list = loadRecents();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i)[0].equals(uri)) list.remove(i);
        }
        saveRecents(list);
    }

    private void navigateToViewer(Uri uri) {
        addRecent(uri);
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        adHandler.removeCallbacksAndMessages(null);
        mainHandler.removeCallbacksAndMessages(null);
        scanExecutor.shutdownNow();
        if (toolsController != null) toolsController.destroy();
        if (bannerView != null) bannerView.destroy();
    }

    private class PdfAdapter extends RecyclerView.Adapter<PdfAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String[] item = filteredPdfs.get(position);
            holder.name.setText((favSet.contains(item[0]) ? "\u2605 " : "") + item[1]);
            holder.date.setText(item[2]);
            holder.itemView.setOnClickListener(v -> openViewer(Uri.parse(item[0])));
            holder.itemView.setOnLongClickListener(v -> {
                showItemMenu(item);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return filteredPdfs.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, date;
            VH(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.fileName);
                date = itemView.findViewById(R.id.fileDate);
            }
        }
    }
    }
    
