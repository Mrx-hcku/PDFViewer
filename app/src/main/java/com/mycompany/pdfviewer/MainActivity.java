package com.mycompany.pdfviewer;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.services.banners.BannerErrorInfo;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_MANAGE_STORAGE = 400;

    private static final String GAME_ID = "800356656";
    private static final boolean TEST_MODE = false;
    private static final String BANNER_PLACEMENT = "Banner_Android";
    private static final String INTERSTITIAL_PLACEMENT = "Interstitial_Android";
    private static final String REWARDED_PLACEMENT = "Rewarded_Android";

    private RecyclerView recyclerView;
    private TextView emptyText;
    private EditText searchBox;
    private BannerView bannerView;
    private boolean rewardedReady = false;

    private List<String[]> allPdfs = new ArrayList<>();
    private List<String[]> filteredPdfs = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recentList);
        emptyText = findViewById(R.id.emptyText);
        searchBox = findViewById(R.id.searchBox);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterList(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        initAds();
        checkStoragePermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasStoragePermission()) scanDevice();
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
            Toast.makeText(this, "Grant 'All files access' to auto-load PDFs", Toast.LENGTH_LONG).show();
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
        emptyText.setOnClickListener(null);
        allPdfs.clear();
        File root = Environment.getExternalStorageDirectory();
        scanRecursive(root, 0);
        filterList(searchBox.getText().toString());
    }

    private void scanRecursive(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 12) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.isHidden()) continue;
                scanRecursive(f, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                String date = sdf.format(new Date(f.lastModified()));
                allPdfs.add(new String[]{Uri.fromFile(f).toString(), f.getName(), date});
            }
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
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ad init failed: " + error + " - " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadBanner() {
        FrameLayout container = findViewById(R.id.bannerContainer);
        bannerView = new BannerView(this, BANNER_PLACEMENT, new UnityBannerSize(320, 50));
        bannerView.setListener(new BannerView.IListener() {
            @Override
            public void onBannerLoaded(BannerView bannerAdView) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Welcome to the app!", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onBannerShown(BannerView bannerAdView) {}

            @Override
            public void onBannerClick(BannerView bannerAdView) {}

            @Override
            public void onBannerFailedToLoad(BannerView bannerAdView, BannerErrorInfo errorInfo) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ad failed to load: [" + errorInfo.errorCode + "] " + errorInfo.errorMessage,
                        Toast.LENGTH_LONG).show());
            }

            @Override
            public void onBannerLeftApplication(BannerView bannerAdView) {}
        });
        container.addView(bannerView);
        bannerView.load();
    }

    private void loadInterstitial() {
        UnityAds.load(INTERSTITIAL_PLACEMENT, new IUnityAdsLoadListener() {
            @Override
            public void onUnityAdsAdLoaded(String placementId) {}

            @Override
            public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Interstitial load failed: [" + error + "] " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void loadRewarded() {
        rewardedReady = false;
        UnityAds.load(REWARDED_PLACEMENT, new IUnityAdsLoadListener() {
            @Override
            public void onUnityAdsAdLoaded(String placementId) {
                rewardedReady = true;
            }

            @Override
            public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                rewardedReady = false;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Rewarded ad load failed: [" + error + "] " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showRewardedAd() {
        if (!rewardedReady) {
            Toast.makeText(this, "Ad not ready yet, try again in a moment", Toast.LENGTH_SHORT).show();
            loadRewarded();
            return;
        }
        UnityAds.show(this, REWARDED_PLACEMENT, new IUnityAdsShowListener() {
            @Override
            public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Ad show failed: [" + error + "] " + message, Toast.LENGTH_LONG).show());
            }
            @Override public void onUnityAdsShowStart(String placementId) {}
            @Override public void onUnityAdsShowClick(String placementId) {}
            @Override
            public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                runOnUiThread(() -> {
                    if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                        Toast.makeText(MainActivity.this, "Thanks for your support!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Ad skipped, no reward given", Toast.LENGTH_SHORT).show();
                    }
                });
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
        if (item.getItemId() == R.id.action_watch_ad) {
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
        recyclerView.setAdapter(new PdfAdapter());
    }

    private void openViewer(Uri uri) {
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
