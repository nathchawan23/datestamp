package app.datestamp.photo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;

    private static final String[] FORMATS = {
            "วัน/เดือน/พ.ศ.  08/11/2569",
            "วัน/เดือน/ค.ศ.  08/11/2026",
            "เดือนไทยแบบย่อ  8 พ.ย. 2569",
            "เดือนไทยแบบเต็ม  8 พฤศจิกายน 2569",
            "ปี-เดือน-วัน  2026-11-08",
            "แบบกล้องฟิล์ม  '26 11 08"
    };
    private static final String[] POSITIONS = {"ล่างขวา", "ล่างซ้าย", "บนขวา", "บนซ้าย"};
    private static final String[] COLOR_NAMES = {"เหลือง", "ส้ม", "ขาว", "แดง", "ดำ"};
    private static final int[] COLORS = {0xFFFFE14A, 0xFFFF9A2E, 0xFFFFFFFF, 0xFFFF4B3A, 0xFF111111};
    private static final String[] TH_SHORT = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
            "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."};
    private static final String[] TH_LONG = {"มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
            "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม"};

    /** What we read from one photo before stamping it. */
    private static class Meta {
        Date date;
        boolean fromExif;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        String make, model;
    }

    /** A snapshot of the settings, so background work is not affected by later taps. */
    private static class Style {
        int format, position, color;
        boolean time;
        float sizePercent;
    }

    private final ArrayList<Uri> uris = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int previewToken;
    private boolean busy;
    private boolean ready;

    private SharedPreferences prefs;
    private ImageView preview;
    private TextView status;
    private Button pickBtn, saveBtn;
    private Spinner fmtSp, posSp, colSp;
    private CheckBox timeCb;
    private SeekBar sizeSb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        preview = findViewById(R.id.preview);
        status = findViewById(R.id.status);
        pickBtn = findViewById(R.id.pick);
        saveBtn = findViewById(R.id.save);
        fmtSp = findViewById(R.id.fmt);
        posSp = findViewById(R.id.pos);
        colSp = findViewById(R.id.col);
        timeCb = findViewById(R.id.time);
        sizeSb = findViewById(R.id.size);

        fill(fmtSp, FORMATS, prefs.getInt("format", 0));
        fill(posSp, POSITIONS, prefs.getInt("position", 0));
        fill(colSp, COLOR_NAMES, prefs.getInt("color", 0));
        timeCb.setChecked(prefs.getBoolean("time", false));
        sizeSb.setProgress(prefs.getInt("size", 8));

        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                settingsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        fmtSp.setOnItemSelectedListener(changed);
        posSp.setOnItemSelectedListener(changed);
        colSp.setOnItemSelectedListener(changed);
        timeCb.setOnCheckedChangeListener((button, checked) -> settingsChanged());
        sizeSb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) settingsChanged();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        pickBtn.setOnClickListener(v -> pick());
        saveBtn.setOnClickListener(v -> saveAll());
        ready = true;
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void fill(Spinner spinner, String[] items, int selected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(items.length - 1, selected)), false);
    }

    private void settingsChanged() {
        if (!ready) return;
        prefs.edit()
                .putInt("format", fmtSp.getSelectedItemPosition())
                .putInt("position", posSp.getSelectedItemPosition())
                .putInt("color", colSp.getSelectedItemPosition())
                .putBoolean("time", timeCb.isChecked())
                .putInt("size", sizeSb.getProgress())
                .apply();
        refreshPreview();
    }

    private Style currentStyle() {
        Style s = new Style();
        s.format = Math.max(0, fmtSp.getSelectedItemPosition());
        s.position = Math.max(0, posSp.getSelectedItemPosition());
        s.color = COLORS[Math.max(0, Math.min(COLORS.length - 1, colSp.getSelectedItemPosition()))];
        s.time = timeCb.isChecked();
        s.sizePercent = 1.5f + sizeSb.getProgress() * 0.25f;
        return s;
    }

    /* ---------- choosing photos ---------- */

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "เลือกรูป"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) picked.add(u);
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        setPhotos(picked);
    }

    /** Photos sent from the gallery with Share > Datestamp. */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        ArrayList<Uri> shared = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) shared.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Uri u : list) if (u != null) shared.add(u);
            }
        }
        if (!shared.isEmpty()) setPhotos(shared);
    }

    private void setPhotos(ArrayList<Uri> list) {
        if (busy || list.isEmpty()) return;
        uris.clear();
        uris.addAll(list);
        saveBtn.setEnabled(true);
        saveBtn.setText(uris.size() > 1 ? "บันทึกทั้งหมด " + uris.size() + " รูป" : "บันทึกรูป");
        status.setText("เลือกแล้ว " + uris.size() + " รูป กดบันทึกได้เลย");
        refreshPreview();
    }

    /* ---------- preview ---------- */

    private void refreshPreview() {
        if (uris.isEmpty() || busy) return;
        final int token = ++previewToken;
        final Uri first = uris.get(0);
        final Style style = currentStyle();
        worker.execute(() -> {
            try {
                Meta meta = readMeta(first);
                Bitmap bitmap = load(first, meta.orientation, 1600);
                if (bitmap == null) return;
                Date date = meta.date != null ? meta.date : new Date();
                stamp(bitmap, format(date, style), style);
                final boolean noDate = meta.date == null;
                final boolean fileDate = meta.date != null && !meta.fromExif;
                ui.post(() -> {
                    if (token != previewToken) return;
                    preview.setImageBitmap(bitmap);
                    if (noDate) {
                        status.setText("รูปแรกไม่มีวันที่ถ่าย รูปที่ไม่มีวันที่จะถูกข้ามตอนบันทึก");
                    } else if (fileDate) {
                        status.setText("รูปแรกไม่มีวันที่ถ่ายใน EXIF จึงใช้วันที่ของไฟล์แทน");
                    }
                });
            } catch (Throwable t) {
                ui.post(() -> status.setText("แสดงตัวอย่างไม่ได้: " + t.getClass().getSimpleName()));
            }
        });
    }

    /* ---------- saving ---------- */

    private void saveAll() {
        if (busy || uris.isEmpty()) return;
        busy = true;
        pickBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        final ArrayList<Uri> list = new ArrayList<>(uris);
        final Style style = currentStyle();
        worker.execute(() -> {
            int saved = 0, skipped = 0, failed = 0;
            for (int i = 0; i < list.size(); i++) {
                final String progress = "กำลังประทับวันที่ " + (i + 1) + "/" + list.size();
                ui.post(() -> status.setText(progress));
                try {
                    Meta meta = readMeta(list.get(i));
                    if (meta.date == null) {
                        skipped++;
                        continue;
                    }
                    Bitmap bitmap;
                    try {
                        bitmap = load(list.get(i), meta.orientation, 0);
                    } catch (OutOfMemoryError e) {
                        System.gc();
                        bitmap = load(list.get(i), meta.orientation, 4096);
                    }
                    if (bitmap == null) {
                        failed++;
                        continue;
                    }
                    stamp(bitmap, format(meta.date, style), style);
                    write(bitmap, outputName(list.get(i), i), meta);
                    bitmap.recycle();
                    saved++;
                } catch (Throwable t) {
                    failed++;
                }
            }
            final StringBuilder msg = new StringBuilder();
            msg.append("บันทึก ").append(saved).append(" รูปแล้ว ดูได้ในแกลเลอรี อัลบั้ม Datestamp");
            if (skipped > 0) msg.append("\nข้าม ").append(skipped).append(" รูปที่ไม่มีวันที่ถ่าย");
            if (failed > 0) msg.append("\nบันทึกไม่สำเร็จ ").append(failed).append(" รูป");
            ui.post(() -> {
                busy = false;
                pickBtn.setEnabled(true);
                saveBtn.setEnabled(true);
                status.setText(msg);
            });
        });
    }

    private void write(Bitmap bitmap, String name, Meta meta) throws Exception {
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Datestamp");
        values.put(MediaStore.Images.Media.DATE_TAKEN, meta.date.getTime());
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri out = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (out == null) throw new IllegalStateException("insert failed");
        try {
            try (OutputStream os = cr.openOutputStream(out)) {
                if (os == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 93, os)) {
                    throw new IllegalStateException("write failed");
                }
            }
            // Keep the original capture date inside the new file. Not fatal if the device refuses.
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(out, "rw")) {
                if (pfd != null) {
                    ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
                    String when = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(meta.date);
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, when);
                    exif.setAttribute(ExifInterface.TAG_DATETIME, when);
                    if (meta.make != null) exif.setAttribute(ExifInterface.TAG_MAKE, meta.make);
                    if (meta.model != null) exif.setAttribute(ExifInterface.TAG_MODEL, meta.model);
                    exif.saveAttributes();
                }
            } catch (Throwable ignored) {
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(out, values, null, null);
        } catch (Exception e) {
            cr.delete(out, null, null);
            throw e;
        }
    }

    private String outputName(Uri uri, int index) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) {
            name = "IMG_" + System.currentTimeMillis() + "_" + (index + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + "_stamped.jpg";
    }

    /* ---------- reading the photo ---------- */

    private Meta readMeta(Uri uri) {
        Meta meta = new Meta();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                meta.orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                meta.make = exif.getAttribute(ExifInterface.TAG_MAKE);
                meta.model = exif.getAttribute(ExifInterface.TAG_MODEL);
                String[] tags = {ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                        ExifInterface.TAG_DATETIME};
                for (String tag : tags) {
                    Date d = parseExifDate(exif.getAttribute(tag));
                    if (d != null) {
                        meta.date = d;
                        meta.fromExif = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (meta.date == null) {
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String[] columns = {"datetaken", "last_modified", "date_modified"};
                    for (String column : columns) {
                        int idx = c.getColumnIndex(column);
                        if (idx < 0 || c.isNull(idx)) continue;
                        long value = c.getLong(idx);
                        if (value <= 0) continue;
                        if ("date_modified".equals(column)) value *= 1000L;
                        meta.date = new Date(value);
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return meta;
    }

    private static Date parseExifDate(String value) {
        if (value == null || value.length() < 19) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            f.setLenient(false);
            Date d = f.parse(value.substring(0, 19));
            if (d == null) return null;
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return c.get(Calendar.YEAR) < 1900 ? null : d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes the photo upright and editable. maxSide 0 means full size. */
    private Bitmap load(Uri uri, int orientation, int maxSide) throws Exception {
        ContentResolver cr = getContentResolver();
        BitmapFactory.Options opts = new BitmapFactory.Options();
        if (maxSide > 0) {
            opts.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, opts);
            }
            int longest = Math.max(opts.outWidth, opts.outHeight);
            int sample = 1;
            while (longest / (sample * 2) >= maxSide) sample *= 2;
            opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
        }
        opts.inMutable = true;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = cr.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bitmap == null) return null;

        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(270); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(270); break;
            default: break;
        }
        if (!m.isIdentity()) {
            Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (turned != bitmap) bitmap.recycle();
            bitmap = turned;
        }
        if (!bitmap.isMutable()) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            bitmap.recycle();
            bitmap = copy;
        }
        return bitmap;
    }

    /* ---------- drawing ---------- */

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static String format(Date date, Style s) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        int y = c.get(Calendar.YEAR), mo = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
        String text;
        switch (s.format) {
            case 1: text = two(d) + "/" + two(mo + 1) + "/" + y; break;
            case 2: text = d + " " + TH_SHORT[mo] + " " + (y + 543); break;
            case 3: text = d + " " + TH_LONG[mo] + " " + (y + 543); break;
            case 4: text = y + "-" + two(mo + 1) + "-" + two(d); break;
            case 5: text = "'" + two(y % 100) + " " + two(mo + 1) + " " + two(d); break;
            default: text = two(d) + "/" + two(mo + 1) + "/" + (y + 543); break;
        }
        if (s.time) text += " " + two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
        return text;
    }

    private static void stamp(Bitmap bitmap, String text, Style s) {
        Canvas canvas = new Canvas(bitmap);
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        float size = Math.max(9f, Math.min(w, h) * s.sizePercent / 100f);
        float margin = size * 1.15f;
        boolean dark = s.color == 0xFF111111;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(s.color);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(size);
        paint.setShadowLayer(size * 0.16f, 0f, size * 0.04f, dark ? 0xB3FFFFFF : 0xA6000000);

        boolean right = s.position == 0 || s.position == 2;
        boolean top = s.position >= 2;
        paint.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float x = right ? w - margin : margin;
        float y = top ? margin - paint.ascent() * 0.85f : h - margin;
        canvas.drawText(text, x, y, paint);
    }
}
