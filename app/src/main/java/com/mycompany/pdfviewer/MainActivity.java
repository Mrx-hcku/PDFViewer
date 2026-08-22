package com.mycompany.pdfviewer;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recentList);
        emptyText = findViewById(R.id.emptyText);
        searchBox = findViewById(R.id.searchBox);
        bannerContainer = findViewById(R.id.bannerContainer);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new PdfAdapter());

        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);
        final LinearLayout pdfSectionLayout = findViewById(R.id.pdfSectionLayout);
        final LinearLayout toolsSectionLayout = findViewById(R.id.toolsSectionLayout);
        MaterialCardView cardEditPdfTool = findViewById(R.id.cardEditPdfTool);

        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_pdfs) {
                pdfSectionLayout.setVisibility(View.VISIBLE);
                toolsSectionLayout.setVisibility(View.GONE);
                if (getSupportActionBar() != null) getSupportActionBar().setTitle("PDF Viewer");
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

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterList(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        initAds();
        checkStoragePermission();

        cachePrefs = getSharedPreferences("pdf_cache", MODE_PRIVATE);
        List<String[]> cached = loadCachedList();
        if (!cached.isEmpty()) {
            allPdfs = cached;
            filterList(searchBox.getText().toString());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermission() && !scanning) scanDevice();
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
        if (requestCode == REQ_MANAGE_STORAGE && hasStoragePermission()) {
            scanDevice();
        }
    }

    private void scanDevice() {
        if (scanning) return;
        scanning = true;
        emptyText.setOnClickListener(null);

        scanExecutor.execute(() -> {
            List<String[]> results = new ArrayList<>();
            File root = Environment.getExternalStorageDirectory();
            scanRecursive(root, 0, results);

            if (destroyed) return;
            mainHandler.post(() -> {
                if (destroyed) return;
                allPdfs = results;
                scanning = false;
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

    private void scanRecursive(File dir, int depth, List<String[]> results) {
        if (dir == null || !dir.isDirectory() || depth > 12) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.isHidden()) continue;
                scanRecursive(f, depth + 1, results);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                String date = sdf.format(new Date(f.lastModified()));
                results.add(new String[]{Uri.fromFile(f).toString(), f.getName(), date});
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
        bannerView = new BannerView(this, BANNER_PLACEMENT, new UnityBannerSize(320, 50));
        bannerView.setListener(new BannerView.IListener() {
            @Override
            public void onBannerLoaded(BannerView bannerAdView) {
                runOnUiThread(() -> {
                    bannerContainer.setVisibility(View.VISIBLE);
                    Toast.makeText(MainActivity.this, "Welcome to the app!", Toast.LENGTH_SHORT).show();
                });
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
        bannerContainer.addView(bannerView);
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
        for (String[] item : allPdfs) {
            if (item[1] != null && item[1].toLowerCase(Locale.getDefault())
                    .contains(query.toLowerCase(Locale.getDefault()))) {
                filteredPdfs.add(item);
            }
        }
        if (hasStoragePermission()) {
            emptyText.setVisibility(filteredPdfs.isEmpty() ? View.VISIBLE : View.GONE);
            emptyText.setText(allPdfs.isEmpty() ? "No PDFs found on device." : "No matching PDFs found.");
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
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override
            public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                navigateToViewer(uri);
                interstitialReady = false;
                interstitialRetryCount = 0;
                loadInterstitial();
            }
        });
    }

    private void navigateToViewer(Uri uri) {
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
            holder.name.setText(item[1]);
            holder.date.setText(item[2]);
            holder.itemView.setOnClickListener(v -> openViewer(Uri.parse(item[0])));
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
    
