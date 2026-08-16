package com.mycompany.pdfviewer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_PDF = 101;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private List<String[]> recentFiles = new ArrayList<>(); // {uri, name, date}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recyclerView = findViewById(R.id.recentList);
        emptyText = findViewById(R.id.emptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        FloatingActionButton fab = findViewById(R.id.fabOpen);
        fab.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/pdf");
            startActivityForResult(intent, PICK_PDF);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecents();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                saveRecent(uri);
                openViewer(uri);
            }
        }
    }

    private void openViewer(Uri uri) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra("pdf_uri", uri.toString());
        startActivity(intent);
    }

    private String getFileName(Uri uri) {
        String name = "document.pdf";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    private void saveRecent(Uri uri) {
        SharedPreferences prefs = getSharedPreferences("recent_pdfs", MODE_PRIVATE);
        List<String> entries = new ArrayList<>();
        String existing = prefs.getString("list", "");
        if (!existing.isEmpty()) {
            for (String e : existing.split("\n")) {
                if (!e.startsWith(uri.toString() + "::")) entries.add(e);
            }
        }
        String date = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(new Date());
        entries.add(0, uri.toString() + "::" + getFileName(uri) + "::" + date);
        if (entries.size() > 10) entries = entries.subList(0, 10);
        prefs.edit().putString("list", String.join("\n", entries)).apply();
    }

    private void loadRecents() {
        recentFiles.clear();
        SharedPreferences prefs = getSharedPreferences("recent_pdfs", MODE_PRIVATE);
        String existing = prefs.getString("list", "");
        if (!existing.isEmpty()) {
            for (String e : existing.split("\n")) {
                String[] parts = e.split("::");
                if (parts.length == 3) recentFiles.add(parts);
            }
        }
        emptyText.setVisibility(recentFiles.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setAdapter(new RecentAdapter());
    }

    private class RecentAdapter extends RecyclerView.Adapter<RecentAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_file, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String[] item = recentFiles.get(position);
            holder.name.setText(item[1]);
            holder.date.setText(item[2]);
            holder.itemView.setOnClickListener(v -> openViewer(Uri.parse(item[0])));
        }

        @Override
        public int getItemCount() {
            return recentFiles.size();
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
