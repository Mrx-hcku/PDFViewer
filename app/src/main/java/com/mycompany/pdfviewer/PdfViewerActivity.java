package com.mycompany.pdfviewer;

import android.app.AlertDialog;
import android.graphics.Bitmap;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PdfViewerActivity extends AppCompatActivity {

    private RecyclerView pageList;
    private TextView pageIndicator;
    private LinearLayoutManager layoutManager;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private List<Bitmap> pageBitmaps = new ArrayList<>();

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

        pageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                int pos = layoutManager.findFirstVisibleItemPosition();
                if (pos >= 0) pageIndicator.setText((pos + 1) + "/" + pageBitmaps.size());
            }
        });

        String uriString = getIntent().getStringExtra("pdf_uri");
        if (uriString != null) loadPdf(Uri.parse(uriString));
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
                .setTitle("Jump to page (1-" + pageBitmaps.size() + ")")
                .setView(input)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString()) - 1;
                        if (page >= 0 && page < pageBitmaps.size())
                            pageList.smoothScrollToPosition(page);
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
            pageList.setAdapter(new PdfPageAdapter());
            pageIndicator.setText("1/" + pageBitmaps.size());
        } catch (IOException e) {
            Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (fileDescriptor != null) fileDescriptor.close();
        } catch (IOException ignored) {}
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
            holder.image.setImageBitmap(pageBitmaps.get(position));
        }

        @Override
        public int getItemCount() {
            return pageBitmaps.size();
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
