package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfViewerActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TextView pageIndicator;
    private SeekBar seekBar;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private List<Bitmap> pageBitmaps = new ArrayList<>();
    private boolean nightMode = false;
    private boolean updatingFromViewPager = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        viewPager = findViewById(R.id.viewPager);
        pageIndicator = findViewById(R.id.pageIndicator);
        seekBar = findViewById(R.id.pageSeekBar);

        String uriString = getIntent().getStringExtra("pdf_uri");
        if (uriString != null) loadPdf(Uri.parse(uriString));

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updatingFromViewPager = true;
                seekBar.setProgress(position);
                pageIndicator.setText((position + 1) + "/" + pageBitmaps.size());
                updatingFromViewPager = false;
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !updatingFromViewPager) viewPager.setCurrentItem(progress, true);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
        if (id == R.id.action_night) { toggleNightMode(); return true; }
        if (id == R.id.action_jump) { showJumpDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showJumpDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Jump to page (1-" + pageBitmaps.size() + ")")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString()) - 1;
                        if (page >= 0 && page < pageBitmaps.size()) viewPager.setCurrentItem(page, true);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadPdf(Uri uri) {
        try {
            fileDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (fileDescriptor == null) return;
            pdfRenderer = new PdfRenderer(fileDescriptor);
            int count = pdfRenderer.getPageCount();
            for (int i = 0; i < count; i++) {
                PdfRenderer.Page page = pdfRenderer.openPage(i);
                Bitmap bitmap = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pageBitmaps.add(bitmap);
                page.close();
            }
            viewPager.setAdapter(new PdfPagerAdapter());
            seekBar.setMax(Math.max(0, pageBitmaps.size() - 1));
            pageIndicator.setText("1/" + pageBitmaps.size());
        } catch (IOException e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleNightMode() {
        nightMode = !nightMode;
        if (viewPager.getAdapter() != null) viewPager.getAdapter().notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (IOException ignored) {}
    }

    private class PdfPagerAdapter extends RecyclerView.Adapter<PdfPagerAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf_page, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.image.setImageBitmap(pageBitmaps.get(position));
            ((View) holder.image.getParent()).setBackgroundColor(nightMode ? Color.DKGRAY : Color.WHITE);
        }

        @Override
        public int getItemCount() {
            return pageBitmaps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ZoomableImageView image;
            VH(View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.pageImage);
            }
        }
    }
                }
