package com.mycompany.pdfviewer;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Shared helpers: page rendering, OCR (Hindi + English), saving files to Downloads/Pictures. */
public final class PdfUtils {
    private PdfUtils() {}

    public static final String FOLDER = "PDFViewer";

    // ---------- file info ----------
    public static String displayName(Context c, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) name = cur.getString(0);
            } catch (Exception ignored) {}
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) name = "document";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        return name;
    }

    public static long sizeOf(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) {}
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) return pfd.getStatSize();
        } catch (Exception ignored) {}
        return -1;
    }

    public static String fmtSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static int pageCount(Context c, Uri uri) {
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return -1;
            PdfRenderer r = new PdfRenderer(pfd);
            try { return r.getPageCount(); } finally { r.close(); }
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- rendering ----------
    public static Bitmap renderPage(Context c, Uri uri, int pageIndex, int targetWidth) throws IOException {
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IOException("Cannot open PDF");
            PdfRenderer r = new PdfRenderer(pfd);
            try {
                if (pageIndex < 0 || pageIndex >= r.getPageCount()) throw new IOException("Invalid page");
                PdfRenderer.Page p = r.openPage(pageIndex);
                try {
                    float scale = targetWidth / (float) p.getWidth();
                    int h = Math.max(1, Math.round(p.getHeight() * scale));
                    Bitmap bmp = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    return bmp;
                } finally {
                    p.close();
                }
            } finally {
                r.close();
            }
        }
    }

    // ---------- OCR (Devanagari model reads Hindi + English) ----------
    public static String ocr(Bitmap bmp) throws Exception {
        TextRecognizer rec = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
        try {
            // First use: Google Play services downloads the Hindi model, so retry for a short while.
            Exception last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    Text t = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)));
                    return t.getText();
                } catch (Exception e) {
                    last = e;
                    String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
                    boolean waiting = m.contains("download") || m.contains("unavailable") || m.contains("module");
                    if (!waiting) throw e;
                    Thread.sleep(4000);
                }
            }
            throw new IOException("Hindi OCR model is still downloading. Connect to the internet and try again in a minute.", last);
        } finally {
            rec.close();
        }
    }

    // ---------- saving ----------
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** Saves a file into Downloads/PDFViewer and returns its Uri. */
    public static Uri saveToDownloads(Context c, File src, String fileName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Cannot create file in Downloads");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot write file");
                copy(in, out);
            }
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create Downloads/" + FOLDER);
            File dest = new File(dir, fileName);
            if (dest.exists()) {
                int dot = fileName.lastIndexOf('.');
                String base = dot > 0 ? fileName.substring(0, dot) : fileName;
                String ext = dot > 0 ? fileName.substring(dot) : "";
                dest = new File(dir, base + "_" + System.currentTimeMillis() + ext);
            }
            try (InputStream in = new FileInputStream(src); OutputStream out = new java.io.FileOutputStream(dest)) {
                copy(in, out);
            }
            MediaScannerConnection.scanFile(c, new String[]{dest.getAbsolutePath()}, new String[]{mime}, null);
            return Uri.fromFile(dest);
        }
    }

    /** Saves a bitmap into Pictures/PDFViewer and returns a content Uri that other apps can open. */
    public static Uri saveBitmapToPictures(Context c, Bitmap bmp, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name + ".png");
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Cannot create image");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot write image");
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            return uri;
        } else {
            @SuppressWarnings("deprecation")
            String s = MediaStore.Images.Media.insertImage(c.getContentResolver(), bmp, name, null);
            if (s == null) throw new IOException("Cannot save image");
            return Uri.parse(s);
        }
    }
}
