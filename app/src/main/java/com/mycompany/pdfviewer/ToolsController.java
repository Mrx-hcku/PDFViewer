package com.mycompany.pdfviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds the Tools screen and runs merge / split / compress / image<->PDF / lock / unlock. */
public class ToolsController {

    public interface Opener { void open(Uri pdf); }

    private interface Job { Result call() throws Exception; }
    private interface TextCallback { void onText(String text); }

    private static class Result {
        final String message;
        final Uri openUri;
        Result(String message, Uri openUri) { this.message = message; this.openUri = openUri; }
    }

    private static final int REQ_MERGE = 501, REQ_SPLIT = 502, REQ_COMPRESS = 503, REQ_IMG2PDF = 504,
            REQ_PDF2IMG = 505, REQ_LOCK = 506, REQ_UNLOCK = 507, REQ_SCAN = 508;

    private final Activity act;
    private final Opener opener;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean dead = false;

    public ToolsController(Activity act, Opener opener) {
        this.act = act;
        this.opener = opener;
        PDFBoxResourceLoader.init(act.getApplicationContext());
    }

    public void destroy() {
        dead = true;
        ex.shutdownNow();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    // ================= UI =================
        public void attach(LinearLayout parent) {
        ScrollView sv = new ScrollView(act);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(6), dp(10), dp(14));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addRow(col,
                card(ToolIconView.SCAN, 0xFF4F8DF7, "Scan Document", "Camera to PDF, auto crop", () -> startScan()),
                card(ToolIconView.MERGE, 0xFF7C6CF0, "Merge PDFs", "Join many PDFs into one", () -> pickPdf(REQ_MERGE, true)));
        addRow(col,
                card(ToolIconView.SPLIT, 0xFFF29D38, "Split PDF", "Extract chosen pages", () -> pickPdf(REQ_SPLIT, false)),
                card(ToolIconView.COMPRESS, 0xFF2FB47C, "Compress PDF", "Make file size smaller", () -> pickPdf(REQ_COMPRESS, false)));
        addRow(col,
                card(ToolIconView.IMAGE_TO_PDF, 0xFFE9628C, "Image to PDF", "Photos into one PDF", () -> pickImages(REQ_IMG2PDF)),
                card(ToolIconView.PDF_TO_IMAGE, 0xFF1FB5C9, "PDF to Image", "Save pages as pictures", () -> pickPdf(REQ_PDF2IMG, false)));
        addRow(col,
                card(ToolIconView.LOCK, 0xFFE5A100, "Lock PDF", "Add a password", () -> pickPdf(REQ_LOCK, false)),
                card(ToolIconView.UNLOCK, 0xFF8E9AAF, "Unlock PDF", "Remove the password", () -> pickPdf(REQ_UNLOCK, false)));

        parent.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addRow(LinearLayout col, View a, View b) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(a, lp);
        if (b != null) {
            row.addView(b, lp);
        } else {
            View filler = new View(act);
            row.addView(filler, lp);
        }
        col.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

        private View card(int iconType, int color, String title, String sub, Runnable click) {
        MaterialCardView card = new MaterialCardView(act);
        card.setRadius(dp(16));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> click.run());

        LinearLayout in = new LinearLayout(act);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setGravity(Gravity.CENTER_HORIZONTAL);
        in.setPadding(dp(10), dp(16), dp(10), dp(16));

        // round tinted badge with the line icon inside
        android.widget.FrameLayout badge = new android.widget.FrameLayout(act);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);
        badge.setBackground(bg);
        ToolIconView icon = new ToolIconView(act, iconType, color);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        badge.addView(icon, ilp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(56), dp(56));
        blp.bottomMargin = dp(8);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        TextView s = new TextView(act);
        s.setText(sub);
        s.setTextSize(11);
        s.setAlpha(0.7f);
        s.setGravity(Gravity.CENTER);

        in.addView(badge, blp);
        in.addView(t);
        in.addView(s);
        card.addView(in);
        return card;
    }

    // ================= pickers =================
    private void pickPdf(int req, boolean multi) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        if (multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        act.startActivityForResult(i, req);
    }

    private void pickImages(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        act.startActivityForResult(i, req);
    }

    /** Call from Activity.onActivityResult. Returns true if the result belonged to the tools. */
    public boolean onActivityResult(int req, int res, Intent data) {
        if (req < REQ_MERGE || req > REQ_SCAN) return false;
        if (res != Activity.RESULT_OK || data == null) return true;

        if (req == REQ_SCAN) {
            handleScanResult(data);
            return true;
        }

        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return true;

        switch (req) {
            case REQ_MERGE: doMerge(uris); break;
            case REQ_SPLIT: doSplit(uris.get(0)); break;
            case REQ_COMPRESS: doCompress(uris.get(0)); break;
            case REQ_IMG2PDF: doImagesToPdf(uris); break;
            case REQ_PDF2IMG: doPdfToImages(uris.get(0)); break;
            case REQ_LOCK: doLock(uris.get(0)); break;
            case REQ_UNLOCK: doUnlock(uris.get(0)); break;
        }
        return true;
    }

    // ================= camera scanner =================
    private void startScan() {
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(25)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build();
        GmsDocumentScanning.getClient(options)
                .getStartScanIntent(act)
                .addOnSuccessListener(sender -> {
                    try {
                        act.startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0);
                    } catch (Exception e) {
                        Toast.makeText(act, "Cannot start scanner: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(act, "Scanner needs Google Play services: " + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    private void handleScanResult(Intent data) {
        GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
        if (r == null || r.getPdf() == null) {
            Toast.makeText(act, "No scan received", Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri pdf = r.getPdf().getUri();
        final int pages = r.getPdf().getPageCount();
        run("Saving scan...", () -> {
            File out = tmp("scan");
            try (java.io.InputStream in = act.getContentResolver().openInputStream(pdf);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                if (in == null) throw new java.io.IOException("Cannot read scan");
                PdfUtils.copy(in, os);
            }
            return savePdf(out, "scan_" + stamp() + ".pdf", "Scanned " + pages + " page(s).");
        });
    }

    // ================= tools =================
    private void doMerge(List<Uri> uris) {
        if (uris.size() < 2) {
            Toast.makeText(act, "Choose at least 2 PDFs", Toast.LENGTH_LONG).show();
            return;
        }
        run("Merging PDFs...", () -> {
            File out = tmp("merged");
            PdfTools.merge(act, uris, out);
            return savePdf(out, "merged_" + stamp() + ".pdf", "Merged " + uris.size() + " PDFs.");
        });
    }

    private void doSplit(Uri uri) {
        final int total = PdfUtils.pageCount(act, uri);
        final String base = PdfUtils.displayName(act, uri);
        askText("Pages to extract" + (total > 0 ? " (total " + total + ")" : ""), "e.g. 1-3,5", "",
                InputType.TYPE_CLASS_TEXT, text -> {
                    final List<Integer> pages;
                    try {
                        pages = PdfTools.parseRanges(text, total > 0 ? total : 9999);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(act, e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    run("Extracting pages...", () -> {
                        File out = tmp("split");
                        PdfTools.extractPages(act, uri, pages, out);
                        return savePdf(out, base + "_pages_" + stamp() + ".pdf", pages.size() + " pages extracted.");
                    });
                });
    }

    private void doCompress(Uri uri) {
        final String base = PdfUtils.displayName(act, uri);
        final String[] labels = {"Light (best quality)", "Medium (recommended)", "Maximum (smallest size)"};
        final int[] quality = {75, 55, 35};
        final int[] maxDim = {1800, 1400, 1000};
        new AlertDialog.Builder(act)
                .setTitle("Compression level")
                .setItems(labels, (d, which) -> run("Compressing PDF...", () -> {
                    long before = PdfUtils.sizeOf(act, uri);
                    File out = tmp("compressed");
                    PdfTools.compress(act, uri, out, quality[which], maxDim[which]);
                    long after = out.length();
                    if (before > 0 && after >= before * 0.95) {
                        out.delete();
                        return new Result("This PDF could not be made smaller (" + PdfUtils.fmtSize(before)
                                + "). It has no large images.", null);
                    }
                    return savePdf(out, base + "_small_" + stamp() + ".pdf",
                            PdfUtils.fmtSize(before) + "  \u2192  " + PdfUtils.fmtSize(after));
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doImagesToPdf(List<Uri> uris) {
        run("Creating PDF...", () -> {
            File out = tmp("images");
            PdfTools.imagesToPdf(act, uris, out);
            return savePdf(out, "images_" + stamp() + ".pdf", uris.size() + " images added.");
        });
    }

    private void doPdfToImages(Uri uri) {
        final int total = PdfUtils.pageCount(act, uri);
        if (total <= 0) {
            Toast.makeText(act, "Cannot read this PDF (locked?). Unlock it first.", Toast.LENGTH_LONG).show();
            return;
        }
        final String base = PdfUtils.displayName(act, uri);
        askText("Pages (total " + total + ")", "all  or  1-3,5", "all", InputType.TYPE_CLASS_TEXT, text -> {
            final List<Integer> pages;
            try {
                pages = PdfTools.parseRanges(text, total);
            } catch (IllegalArgumentException e) {
                Toast.makeText(act, e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            if (pages.size() > 40) {
                Toast.makeText(act, "Max 40 pages at a time", Toast.LENGTH_LONG).show();
                return;
            }
            run("Saving pages as images...", () -> {
                int n = PdfTools.pdfToImages(act, uri, pages, base);
                return new Result(n + " images saved in Pictures/" + PdfUtils.FOLDER, null);
            });
        });
    }

    private void doLock(Uri uri) {
        final String base = PdfUtils.displayName(act, uri);
        askText("Set a password", "Password", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, pass -> {
                    if (pass.isEmpty()) {
                        Toast.makeText(act, "Password cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    run("Locking PDF...", () -> {
                        File out = tmp("locked");
                        PdfTools.protect(act, uri, pass, out);
                        return savePdf(out, base + "_locked_" + stamp() + ".pdf", "Password added. Remember it, it cannot be recovered.");
                    });
                });
    }

    private void doUnlock(Uri uri) {
        final String base = PdfUtils.displayName(act, uri);
        askText("Enter the PDF password", "Password", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, pass ->
                        run("Unlocking PDF...", () -> {
                            File out = tmp("unlocked");
                            PdfTools.unprotect(act, uri, pass, out);
                            return savePdf(out, base + "_unlocked_" + stamp() + ".pdf", "Password removed.");
                        }));
    }

    // ================= helpers =================
    private File tmp(String prefix) throws java.io.IOException {
        return File.createTempFile(prefix, ".pdf", act.getCacheDir());
    }

    private String stamp() {
        return String.valueOf(System.currentTimeMillis() % 100000000L);
    }

    private Result savePdf(File out, String name, String info) throws java.io.IOException {
        try {
            Uri saved = PdfUtils.saveToDownloads(act, out, name, "application/pdf");
            return new Result(info + "\n\nSaved in Downloads/" + PdfUtils.FOLDER + "/" + name, saved);
        } finally {
            out.delete();
        }
    }

    private void askText(String title, String hint, String def, int inputType, TextCallback cb) {
        final EditText in = new EditText(act);
        in.setHint(hint);
        in.setInputType(inputType);
        in.setSingleLine(true);
        in.setText(def);
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(in)
                .setPositiveButton("OK", (d, w) -> cb.onText(in.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void run(String msg, Job job) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setMessage(msg);
        pd.setCancelable(false);
        pd.show();
        ex.execute(() -> {
            Result r;
            try {
                r = job.call();
            } catch (Throwable e) {
                r = new Result("Failed: " + friendly(e), null);
            }
            final Result fr = r;
            ui.post(() -> {
                if (dead || act.isFinishing()) return;
                pd.dismiss();
                showResult(fr);
            });
        });
    }

    private String friendly(Throwable e) {
        if (e instanceof OutOfMemoryError) return "This file is too big for the phone's memory.";
        if (e.getClass().getSimpleName().contains("InvalidPassword")) return "Wrong password, or this PDF is locked.";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private void showResult(Result r) {
        AlertDialog.Builder b = new AlertDialog.Builder(act)
                .setTitle(r.message.startsWith("Failed") ? "Problem" : "Done")
                .setMessage(r.message)
                .setNegativeButton("Close", null);
        if (r.openUri != null) {
            b.setPositiveButton("Open", (d, w) -> opener.open(r.openUri));
        }
        b.show();
    }
}
