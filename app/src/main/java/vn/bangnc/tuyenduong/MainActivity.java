package vn.bangnc.tuyenduong;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lộ trình tối ưu xe máy — bản app Android gốc (không dùng giao diện web). */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_IMPORT = 11, REQ_PERM = 12;
    private static final int TEAL = 0xFF0F766E, TEAL2 = 0xFF14B8A6, INK = 0xFF0F2A26, MUT = 0xFF5B6F6A, LINE = 0xFFCFE3DE;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // dữ liệu
    private LinkedHashMap<String, Data.Item> rows = new LinkedHashMap<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private List<String> missing = new ArrayList<>();
    private Route route; // lộ trình đã tính

    // vị trí + hướng
    private LocationManager lm;
    private Location myLoc;
    private SensorManager sm;
    private Float rot;
    private long lastInvalidate;
    private boolean hasCompass, fitted;

    // giao diện
    private FrameLayout root;
    private MapView map;
    private MapGfx.MeOverlay me;
    private RotationGestureOverlay rotOverlay;
    private final List<Overlay> routeOverlays = new ArrayList<>();
    private final List<Overlay> pinOverlays = new ArrayList<>();
    private OnlineTileSourceBase street, sat;
    private boolean isSat;
    private View panel, menuBtn;
    private TextView hud, northBtn, layerBtn, tvCount, tvMiss, tvUsed, tvSheet, statKm, statMin, statStops;
    private LinearLayout selBox, mapsBox, statsRow;
    private AlertDialog progress;
    private boolean panelOpen = true;
    private int panelW;

    private static final class Route {
        List<double[]> P;
        List<String> N;
        boolean fb;
        Router.Result res;
    }

    // ================= Vòng đời =================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("app", MODE_PRIVATE);

        Configuration.getInstance().load(this, getSharedPreferences("osm", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());
        File base = new File(getCacheDir(), "osm");
        Configuration.getInstance().setOsmdroidBasePath(base);
        Configuration.getInstance().setOsmdroidTileCache(new File(base, "tiles"));

        Router.androidPkg = getPackageName();
        Router.androidCert = signSha1();

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        buildUi();
        askPermissions();
        loadSheet(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        startLocation();
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
        else hud(null);
    }

    @Override
    protected void onPause() {
        map.onPause();
        try {
            lm.removeUpdates(locListener);
        } catch (Exception ignore) {
        }
        if (sm != null) sm.unregisterListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen && isPhone()) setPanel(false);
        else super.onBackPressed();
    }

    /** SHA-1 chữ ký của app (dạng HEX in hoa, không dấu :) để gửi kèm Google API. */
    @SuppressWarnings("deprecation")
    private String signSha1() {
        try {
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                sigs = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            }
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ================= Giao diện =================
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private boolean isPhone() {
        return getResources().getConfiguration().screenWidthDp < 700;
    }

    private static GradientDrawable round(int color, float r, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    private GradientDrawable grad(float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{TEAL, TEAL2});
        g.setCornerRadius(r);
        return g;
    }

    private Drawable ripple(Drawable bg) {
        return new RippleDrawable(ColorStateList.valueOf(0x33000000), bg, null);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** kind: 0 = nút chính (gradient), 1 = viền xanh, 2 = nhạt */
    private TextView button(String s, int kind, View.OnClickListener l) {
        TextView b = text(s, 14.5f, kind == 0 ? Color.WHITE : TEAL, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(11), dp(12), dp(11));
        Drawable bg = kind == 0 ? grad(dp(12)) : kind == 1 ? round(Color.WHITE, dp(12), TEAL, dp(1.5f))
                : round(0xFFEAF5F2, dp(12), LINE, dp(1));
        b.setBackground(ripple(bg));
        if (kind == 0) b.setElevation(dp(4));
        b.setOnClickListener(l);
        b.setClickable(true);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private View step(int n, String title) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView i = text(String.valueOf(n), 12, Color.WHITE, true);
        i.setGravity(Gravity.CENTER);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(TEAL);
        i.setBackground(c);
        l.addView(i, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(title, 15, INK, true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(10);
        l.addView(t, p);
        l.setLayoutParams(lp(-1, -2, 20));
        return l;
    }

    private TextView pill(String s, boolean ok) {
        TextView t = text(s, 12, ok ? 0xFF0B5C55 : MUT, false);
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        t.setBackground(round(ok ? 0xFFD9F4EE : Color.WHITE, dp(99), ok ? 0xFFA8E0D4 : LINE, dp(1)));
        return t;
    }

    private TextView stat(LinearLayout row, String unit) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        b.setBackground(round(Color.WHITE, dp(14), LINE, dp(1)));
        TextView v = text("0", 22, TEAL, true);
        b.addView(v);
        b.addView(text(unit, 11.5f, MUT, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) p.leftMargin = dp(8);
        row.addView(b, p);
        return v;
    }

    private TextView mapButton(String s, View.OnClickListener l) {
        TextView b = text(s, 18, TEAL, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(round(Color.WHITE, dp(10), 0, 0)));
        b.setElevation(dp(4));
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        setContentView(root);

        // ---- Bản đồ toàn màn hình ----
        street = MapGfx.google("GoogleStreet", "m");
        sat = MapGfx.google("GoogleHybrid", "y");
        map = new MapView(this);
        map.setTileSource(street);
        map.setTilesScaledToDpi(true);
        map.setMultiTouchControls(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(4.0);
        map.setMaxZoomLevel(21.0);
        map.getController().setZoom(6.0);
        map.getController().setCenter(new GeoPoint(16.0, 107.5));
        rotOverlay = new RotationGestureOverlay(map);
        rotOverlay.setEnabled(true);
        map.getOverlays().add(rotOverlay);
        me = new MapGfx.MeOverlay();
        me.onFrame = this::syncNorth;
        map.getOverlays().add(me);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // ---- Cột nút bên phải ----
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        layerBtn = mapButton("🛰️", v -> toggleLayer());
        col.addView(layerBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView ctr = mapButton("◎", v -> centerMe());
        ctr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        col.addView(ctr, lp(dp(42), dp(42), 10));
        northBtn = mapButton("▲", v -> {
            map.setMapOrientation(0f);
            map.invalidate();
        });
        northBtn.setTextColor(0xFFEF4444);
        northBtn.setVisibility(View.GONE);
        col.addView(northBtn, lp(dp(42), dp(42), 10));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.topMargin = dp(14);
        cp.rightMargin = dp(12);
        root.addView(col, cp);

        // ---- Bảng tọa độ / hướng ----
        hud = text("Đang chờ định vị…", 12.5f, Color.WHITE, true);
        hud.setPadding(dp(12), dp(8), dp(12), dp(8));
        hud.setBackground(round(0xE60F2A26, dp(12), 0, 0));
        hud.setElevation(dp(4));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        hp.rightMargin = dp(12);
        hp.bottomMargin = dp(16);
        root.addView(hud, hp);

        // ---- Nút mở menu ----
        TextView mb = text("☰", 22, Color.WHITE, true);
        mb.setGravity(Gravity.CENTER);
        mb.setBackground(ripple(grad(dp(14))));
        mb.setElevation(dp(6));
        mb.setOnClickListener(v -> setPanel(true));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.START);
        mp.topMargin = dp(14);
        mp.leftMargin = dp(12);
        root.addView(mb, mp);
        menuBtn = mb;

        // ---- Menu bên trái ----
        panelW = Math.min(dp(350), (int) (getResources().getDisplayMetrics().widthPixels * 0.88f));
        ScrollView sv = new ScrollView(this);
        GradientDrawable pbg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2F3FAF8, 0xF2E2F0EC});
        sv.setBackground(pbg);
        sv.setElevation(dp(10));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(24));
        sv.addView(box, new ScrollView.LayoutParams(-1, -2));

        // thương hiệu
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(14), dp(14), dp(10), dp(14));
        brand.setBackground(grad(dp(18)));
        brand.setElevation(dp(4));
        TextView logo = text("🛵", 24, Color.WHITE, false);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(0x2BFFFFFF, dp(14), 0, 0));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(text("Tuyến đường", 19, Color.WHITE, true));
        TextView sub = text("Make by BangNC13", 12.5f, 0xEBFFFFFF, false);
        tt.addView(sub);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(0, -2, 1);
        ttp.leftMargin = dp(12);
        brand.addView(tt, ttp);
        TextView close = text("✕", 18, Color.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> setPanel(false));
        brand.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        box.addView(brand);

        tvSheet = text("Đang tải Google Sheet…", 12, MUT, false);
        box.addView(tvSheet, lp(-1, -2, 8));

        // bước 1
        box.addView(step(1, "Chọn điểm đến"));
        box.addView(button("📂  Import Excel/CSV (cột “Tên đối tượng”)", 1, v -> pickImportFile()), lp(-1, -2, 4));
        box.addView(button("⬇  Tải file mẫu", 2, v -> saveTemplate()), lp(-1, -2, 8));
        box.addView(button("🔍  Chọn từ danh sách", 1, v -> showPicker()), lp(-1, -2, 8));
        tvMiss = text("", 12.5f, 0xFF92400E, false);
        tvMiss.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvMiss.setBackground(round(0xFFFEF3C7, dp(10), 0xFFFCD34D, dp(1)));
        tvMiss.setVisibility(View.GONE);
        box.addView(tvMiss, lp(-1, -2, 8));
        LinearLayout cnt = new LinearLayout(this);
        cnt.setGravity(Gravity.CENTER_VERTICAL);
        tvCount = pill("Đã chọn 0 điểm", false);
        cnt.addView(tvCount);
        View sp = new View(this);
        cnt.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        TextView clear = text("Xóa hết", 13, 0xFFDC2626, true);
        clear.setPadding(dp(8), dp(4), dp(4), dp(4));
        clear.setOnClickListener(v -> {
            selected.clear();
            missing = new ArrayList<>();
            onSelectionChanged();
        });
        cnt.addView(clear);
        box.addView(cnt, lp(-1, -2, 10));
        selBox = new LinearLayout(this);
        selBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(selBox, lp(-1, -2, 6));

        // bước 2
        box.addView(step(2, "Lộ trình"));
        box.addView(button("Tính lộ trình tối ưu", 0, v -> computeRoute()), lp(-1, -2, 4));
        statsRow = new LinearLayout(this);
        statKm = stat(statsRow, "km");
        statMin = stat(statsRow, "phút");
        statStops = stat(statsRow, "điểm dừng");
        statsRow.setVisibility(View.GONE);
        box.addView(statsRow, lp(-1, -2, 12));
        tvUsed = pill("", true);
        tvUsed.setVisibility(View.GONE);
        LinearLayout.LayoutParams up = lp(-2, -2, 8);
        box.addView(tvUsed, up);
        mapsBox = new LinearLayout(this);
        mapsBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(mapsBox, lp(-1, -2, 4));

        // cài đặt
        LinearLayout foot = new LinearLayout(this);
        foot.addView(button("⟳  Tải lại dữ liệu", 2, v -> loadSheet(true)), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1);
        fp.leftMargin = dp(8);
        foot.addView(button("⚙  Cài đặt", 2, v -> showSettings()), fp);
        box.addView(foot, lp(-1, -2, 26));

        panel = sv;
        root.addView(sv, new FrameLayout.LayoutParams(panelW, -1, Gravity.START));
        menuBtn.setVisibility(View.GONE);
    }

    private void setPanel(boolean open) {
        panelOpen = open;
        panel.animate().cancel();
        if (open) {
            panel.setVisibility(View.VISIBLE);
            panel.animate().translationX(0).setDuration(200).start();
            menuBtn.setVisibility(View.GONE);
        } else {
            panel.animate().translationX(-panelW - dp(20)).setDuration(200)
                    .withEndAction(() -> panel.setVisibility(View.GONE)).start();
            menuBtn.setVisibility(View.VISIBLE);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void showProgress(String msg) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(22), dp(20), dp(22), dp(20));
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(TEAL));
        l.addView(pb, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView t = text(msg, 15, INK, false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = dp(16);
        l.addView(t, p);
        progress = new AlertDialog.Builder(this).setView(l).setCancelable(false).create();
        progress.show();
    }

    private void hideProgress() {
        if (progress != null) progress.dismiss();
        progress = null;
    }

    // ================= Dữ liệu Google Sheet =================
    private File cacheFile() {
        return new File(getFilesDir(), "toado.csv");
    }

    private void loadSheet(boolean manual) {
        if (manual) showProgress("Đang tải Google Sheet…");
        tvSheet.setText("Đang tải Google Sheet…");
        exec.execute(() -> {
            String csv = null, err = null;
            boolean fromCache = false;
            try {
                csv = Router.http("GET", Data.CSV_URL, null, null);
                if (csv.trim().startsWith("<")) throw new Exception("Sheet chưa được chia sẻ công khai");
                try (OutputStream o = new FileOutputStream(cacheFile())) {
                    o.write(csv.getBytes("UTF-8"));
                }
            } catch (Exception e) {
                err = e.getMessage();
                csv = null;
                try (InputStream in = new java.io.FileInputStream(cacheFile())) {
                    csv = Data.utf8(Data.readAll(in));
                    fromCache = true;
                } catch (Exception ignore) {
                }
            }
            LinkedHashMap<String, Data.Item> r = csv == null ? new LinkedHashMap<>() : Data.buildRows(Data.parseCsv(csv));
            final String e2 = err;
            final boolean fc = fromCache;
            ui.post(() -> {
                hideProgress();
                rows = r;
                if (r.isEmpty()) {
                    tvSheet.setText("Không đọc được Google Sheet");
                    new AlertDialog.Builder(this).setTitle("Không đọc được Google Sheet")
                            .setMessage("Hãy kiểm tra mạng và chia sẻ file ở chế độ “Bất kỳ ai có đường liên kết đều xem được”."
                                    + (e2 != null ? "\n\nChi tiết: " + e2 : ""))
                            .setPositiveButton("Thử lại", (d, w) -> loadSheet(true))
                            .setNegativeButton("Đóng", null).show();
                } else {
                    tvSheet.setText("Đã tải " + r.size() + " đối tượng" + (fc ? " (dữ liệu lưu sẵn, chưa có mạng)" : ""));
                    if (selected.isEmpty()) {
                        String s = prefs.getString("sel", "");
                        if (!s.isEmpty()) for (String k : s.split("\n")) if (rows.containsKey(k)) selected.add(k);
                    } else {
                        selected.retainAll(rows.keySet());
                    }
                    onSelectionChanged();
                    if (manual) toast("Đã cập nhật " + r.size() + " đối tượng");
                }
            });
        });
    }

    private List<Data.Item> picked() {
        List<Data.Item> out = new ArrayList<>();
        for (String k : selected) {
            Data.Item it = rows.get(k);
            if (it != null) out.add(it);
        }
        return out;
    }

    private void onSelectionChanged() {
        prefs.edit().putString("sel", TextUtils.join("\n", selected)).apply();
        route = null;
        List<Data.Item> p = picked();
        tvCount.setText("Đã chọn " + p.size() + " điểm");
        selBox.removeAllViews();
        int i = 0;
        for (Data.Item it : p) {
            i++;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(10), dp(6), dp(4), dp(6));
            r.setBackground(round(Color.WHITE, dp(10), LINE, dp(1)));
            TextView n = text(i + ". " + it.name, 13.5f, INK, false);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = text("✕", 15, MUT, true);
            x.setGravity(Gravity.CENTER);
            final String k = it.key;
            x.setOnClickListener(v -> {
                selected.remove(k);
                onSelectionChanged();
            });
            r.addView(x, new LinearLayout.LayoutParams(dp(34), dp(30)));
            selBox.addView(r, lp(-1, -2, i == 1 ? 0 : 6));
        }
        if (missing.isEmpty()) tvMiss.setVisibility(View.GONE);
        else {
            List<String> m = missing.size() > 30 ? missing.subList(0, 30) : missing;
            tvMiss.setText("Không có trong sheet Tọa độ: " + TextUtils.join(", ", m));
            tvMiss.setVisibility(View.VISIBLE);
        }
        showResult();
        render();
    }

    // ================= Chọn từ danh sách =================
    private void showPicker() {
        if (rows.isEmpty()) {
            toast("Chưa có dữ liệu Google Sheet");
            return;
        }
        final List<Data.Item> all = new ArrayList<>(rows.values());
        final List<Data.Item> shown = new ArrayList<>();
        final LinkedHashSet<String> tmp = new LinkedHashSet<>(selected);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText search = new EditText(this);
        search.setHint("Gõ để tìm tên đối tượng");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(search, new LinearLayout.LayoutParams(-1, -2));
        final TextView info = text("", 12, MUT, false);
        box.addView(info, lp(-1, -2, 4));
        final ListView lv = new ListView(this);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice,
                new ArrayList<>());
        lv.setAdapter(ad);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        box.addView(lv, new LinearLayout.LayoutParams(-1, h));

        final Runnable refresh = () -> {
            String q = Data.plain(search.getText().toString());
            shown.clear();
            for (Data.Item it : all) if (q.isEmpty() || it.plain.contains(q)) shown.add(it);
            ad.clear();
            for (Data.Item it : shown) ad.add(it.name);
            ad.notifyDataSetChanged();
            lv.clearChoices();
            for (int i = 0; i < shown.size(); i++) lv.setItemChecked(i, tmp.contains(shown.get(i).key));
            info.setText("Đã chọn " + tmp.size() + " · hiển thị " + shown.size() + "/" + all.size());
        };
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            String k = shown.get(pos).key;
            if (lv.isItemChecked(pos)) tmp.add(k);
            else tmp.remove(k);
            info.setText("Đã chọn " + tmp.size() + " · hiển thị " + shown.size() + "/" + all.size());
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refresh.run();
            }
        });
        refresh.run();

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Chọn đối tượng")
                .setView(box)
                .setPositiveButton("Xong", (x, y) -> {
                    selected.clear();
                    selected.addAll(tmp);
                    missing = new ArrayList<>();
                    onSelectionChanged();
                })
                .setNegativeButton("Hủy", null)
                .setNeutralButton("Bỏ chọn hết", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            tmp.clear();
            refresh.run();
        }));
        d.show();
    }

    // ================= Import Excel/CSV =================
    private void pickImportFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("Không mở được trình chọn file");
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignore) {
        }
        String s = u.getLastPathSegment();
        return s == null ? "" : s;
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri u = data.getData();
        final String name = displayName(u).toLowerCase(Locale.ROOT);
        if (rows.isEmpty()) {
            toast("Chưa có dữ liệu Google Sheet để đối chiếu");
            return;
        }
        showProgress("Đang đọc file…");
        exec.execute(() -> {
            String err = null;
            List<String> names = null;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                byte[] b = Data.readAll(in);
                boolean zip = b.length > 2 && b[0] == 'P' && b[1] == 'K';
                List<List<String>> t;
                if (zip) t = Data.parseXlsx(b);
                else if (name.endsWith(".xls"))
                    throw new Exception("File .xls (Excel 97-2003) chưa hỗ trợ. Hãy lưu lại dạng .xlsx hoặc .csv");
                else t = Data.parseCsv(Data.utf8(b));
                names = Data.importNames(t);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String e2 = err;
            final List<String> n2 = names;
            ui.post(() -> {
                hideProgress();
                if (e2 != null) {
                    toast("Lỗi đọc file: " + e2);
                    return;
                }
                selected.clear();
                List<String> miss = new ArrayList<>();
                for (String n : n2) {
                    String k = Data.key(n);
                    if (rows.containsKey(k)) selected.add(k);
                    else miss.add(n);
                }
                missing = miss;
                onSelectionChanged();
                toast("Đã nhập " + selected.size() + " điểm" + (miss.isEmpty() ? "" : ", " + miss.size() + " tên không tìm thấy"));
            });
        });
    }

    // ================= File mẫu =================
    private void saveTemplate() {
        try {
            byte[] b;
            try (InputStream in = getAssets().open("Template.xlsx")) {
                b = Data.readAll(in);
            }
            Uri uri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "Template.xlsx");
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("không tạo được file");
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(b);
                }
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                    toast("Cấp quyền lưu file rồi bấm lại");
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                File f = new File(dir, "Template.xlsx");
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(b);
                }
                uri = null;
            }
            toast("Đã lưu Template.xlsx vào thư mục Download");
            if (uri != null) {
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(v);
                } catch (Exception ignore) {
                }
            }
        } catch (Exception e) {
            toast("Không lưu được file mẫu: " + e.getMessage());
        }
    }

    // ================= Cài đặt =================
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text("Khóa API VIETMAP (maps.vietmap.vn) — tính đường xe máy, tối ưu thứ tự. Để trống sẽ dùng OSRM miễn phí (đường ô tô).", 13, MUT, false));
        final EditText k = new EditText(this);
        k.setSingleLine(true);
        k.setHint("Dán khóa Vietmap");
        k.setText(prefs.getString("vkey", ""));
        box.addView(k, lp(-1, -2, 6));
        new AlertDialog.Builder(this).setTitle("Cài đặt").setView(box)
                .setPositiveButton("Lưu", (d, w) -> {
                    prefs.edit().putString("vkey", cleanKey(k.getText().toString())).apply();
                    toast("Đã lưu");
                })
                .setNeutralButton("Lưu + Kiểm tra", (d, w) -> {
                    prefs.edit().putString("vkey", cleanKey(k.getText().toString())).apply();
                    testKey();
                })
                .setNegativeButton("Hủy", null).show();
    }

    /** Bỏ mọi ký tự lạ (dấu cách, xuống dòng, ký tự ẩn) khi dán khóa. */
    private static String cleanKey(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    /** Gọi thử Google Routes với 2 điểm cố định và hiện nguyên văn kết quả. */
    private void testKey() {
        final String key = cleanKey(prefs.getString("vkey", ""));
        if (key.isEmpty()) {
            toast("Chưa nhập khóa");
            return;
        }
        showProgress("Đang kiểm tra khóa…");
        exec.execute(() -> {
            String out;
            try {
                List<double[]> P = new ArrayList<>();
                P.add(new double[]{21.8153, 105.2103});
                P.add(new double[]{21.8050, 105.2150});
                P.add(new double[]{21.7900, 105.2200});
                Router.Result r = Router.vietmap(P, key);
                out = String.format(Locale.US, "✅ Khóa dùng được\nThử 3 điểm ở TP Tuyên Quang: %.1f km, %.0f phút (xe máy)",
                        r.legs.get(0)[0], r.legs.get(0)[1]);
            } catch (Exception e) {
                out = "❌ Lỗi:\n" + e.getMessage();
            }
            final String o2 = out;
            ui.post(() -> {
                hideProgress();
                new AlertDialog.Builder(this).setTitle("Kiểm tra VIETMAP").setMessage(o2)
                        .setPositiveButton("OK", null).show();
            });
        });
    }

    // ================= Tính lộ trình =================
    private void computeRoute() {
        List<Data.Item> p = picked();
        final boolean fb = myLoc == null;
        final List<double[]> P = new ArrayList<>();
        final List<String> N = new ArrayList<>();
        if (!fb) {
            P.add(new double[]{myLoc.getLatitude(), myLoc.getLongitude()});
            N.add("Vị trí xuất phát");
        }
        for (Data.Item it : p) {
            P.add(new double[]{it.lat, it.lon});
            N.add(it.name);
        }
        if (P.size() < 2) {
            toast("Hãy chọn thêm đối tượng (hoặc bật định vị).");
            return;
        }
        final String key = cleanKey(prefs.getString("vkey", ""));
        showProgress("Đang tính lộ trình theo đường bộ…");
        exec.execute(() -> {
            Router.Result res = null;
            String warn = null, err = null;
            if (!key.isEmpty()) {
                try {
                    res = Router.vietmap(P, key);
                } catch (Exception e) {
                    warn = e.getMessage();
                }
            }
            if (res == null) {
                try {
                    res = Router.osrm(P);
                } catch (Exception e) {
                    err = "Không tính được lộ trình: " + e.getMessage();
                }
            }
            final Router.Result r2 = res;
            final String w2 = warn, e2 = err;
            ui.post(() -> {
                hideProgress();
                if (w2 != null) {
                    final String k4 = key.length() > 4 ? key.substring(key.length() - 4) : key;
                    new AlertDialog.Builder(this).setTitle("VIETMAP lỗi – đã dùng OSRM")
                            .setMessage(w2 + "\n\nKhóa đang dùng: …" + k4)
                            .setPositiveButton("OK", null).show();
                }
                if (r2 == null) {
                    new AlertDialog.Builder(this).setTitle("Lỗi").setMessage(e2).setPositiveButton("OK", null).show();
                    return;
                }
                Route rt = new Route();
                rt.P = P;
                rt.N = N;
                rt.fb = fb;
                rt.res = r2;
                route = rt;
                showResult();
                render();
                if (isPhone()) setPanel(false);
            });
        });
    }

    private void showResult() {
        mapsBox.removeAllViews();
        if (route == null) {
            statsRow.setVisibility(View.GONE);
            tvUsed.setVisibility(View.GONE);
            return;
        }
        double km = 0, min = 0;
        for (double[] l : route.res.legs) {
            km += l[0];
            min += l[1];
        }
        int[] order = route.res.order;
        statKm.setText(String.format(Locale.US, "%.1f", km));
        statMin.setText(String.format(Locale.US, "%.0f", min));
        statStops.setText(String.valueOf(order.length - (route.fb ? 0 : 1)));
        statsRow.setVisibility(View.VISIBLE);
        tvUsed.setText(route.res.used);
        tvUsed.setVisibility(View.VISIBLE);

        // Mở Google Maps chế độ xe máy (mỗi liên kết tối đa 10 điểm dừng)
        List<double[]> stops = new ArrayList<>();
        for (int i = route.fb ? 0 : 1; i < order.length; i++) stops.add(route.P.get(order[i]));
        double[] o = route.fb ? null : route.P.get(0);
        for (int i = 0; i < stops.size(); i += 10) {
            List<double[]> ch = stops.subList(i, Math.min(i + 10, stops.size()));
            double[] d = ch.get(ch.size() - 1);
            StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=two-wheeler");
            if (o != null) url.append("&origin=").append(o[0]).append(',').append(o[1]);
            url.append("&destination=").append(d[0]).append(',').append(d[1]);
            if (ch.size() > 1) {
                StringBuilder w = new StringBuilder();
                for (int j = 0; j < ch.size() - 1; j++) {
                    if (w.length() > 0) w.append('|');
                    w.append(ch.get(j)[0]).append(',').append(ch.get(j)[1]);
                }
                try {
                    url.append("&waypoints=").append(URLEncoder.encode(w.toString(), "UTF-8"));
                } catch (Exception ignore) {
                }
            }
            String label = "🗺  Mở Google Maps" + (stops.size() > 10 ? " · điểm " + (i + 1) + "–" + (i + ch.size()) : "");
            final String u = url.toString();
            mapsBox.addView(button(label, 1, v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    toast("Không mở được Google Maps");
                }
            }), lp(-1, -2, 8));
            o = d;
        }
    }

    // ================= Vẽ bản đồ =================
    private void render() {
        map.getOverlays().removeAll(routeOverlays);
        routeOverlays.clear();
        List<GeoPoint> all = new ArrayList<>();
        pinOverlays.clear();

        if (route != null) {
            List<GeoPoint> line = new ArrayList<>();
            for (double[] p : route.res.line) line.add(new GeoPoint(p[0], p[1]));
            if (!line.isEmpty()) {
                Polyline halo = new Polyline(map);
                halo.setPoints(line);
                halo.getOutlinePaint().setColor(0x8CFFFFFF);
                halo.getOutlinePaint().setStrokeWidth(dp(9));
                halo.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
                halo.setInfoWindow(null);
                Polyline pl = new Polyline(map);
                pl.setPoints(line);
                pl.getOutlinePaint().setColor(0xF2F97316);
                pl.getOutlinePaint().setStrokeWidth(dp(5));
                pl.getOutlinePaint().setStrokeCap(android.graphics.Paint.Cap.ROUND);
                pl.setInfoWindow(null);
                routeOverlays.add(halo);
                routeOverlays.add(pl);
                all.addAll(line);
            }
            int off = route.fb ? 1 : 0;
            int[] order = route.res.order;
            for (int k = 0; k < order.length; k++) {
                if (!route.fb && k == 0) continue;
                int i = order[k];
                double[] p = route.P.get(i);
                addMarker(p, String.valueOf(k + off), route.N.get(i), all);
            }
        } else {
            for (Data.Item it : picked()) addMarker(new double[]{it.lat, it.lon}, "", it.name, all);
        }
        routeOverlays.addAll(pinOverlays); // ghim số luôn nằm trên các nhãn tên
        pinOverlays.clear();
        // vẽ dưới chấm vị trí của tôi
        int meIdx = map.getOverlays().indexOf(me);
        map.getOverlays().addAll(Math.max(0, meIdx), routeOverlays);
        map.invalidate();

        if (all.size() > 1) {
            fitted = true;
            final BoundingBox bb = BoundingBox.fromGeoPoints(all);
            map.post(() -> {
                try {
                    map.zoomToBoundingBox(bb.increaseByScale(1.15f), true, dp(40));
                } catch (Exception ignore) {
                }
            });
        } else if (all.size() == 1) {
            fitted = true;
            map.getController().setZoom(16.0);
            map.getController().animateTo(all.get(0));
        }
    }

    private void addMarker(double[] p, String num, final String name, List<GeoPoint> all) {
        GeoPoint g = new GeoPoint(p[0], p[1]);
        all.add(g);
        Marker.OnMarkerClickListener tap = (mk, mv) -> {
            toast(name);
            return true;
        };
        MapGfx.Icon lab = MapGfx.pin(getResources(), num, name, true, false);
        Marker ml = new Marker(map);
        ml.setPosition(g);
        ml.setIcon(lab.drawable);
        ml.setAnchor(Marker.ANCHOR_CENTER, lab.anchorY);
        ml.setInfoWindow(null);
        ml.setOnMarkerClickListener(tap);
        routeOverlays.add(ml);
        MapGfx.Icon pin = MapGfx.pin(getResources(), num, name, false, true);
        Marker mp = new Marker(map);
        mp.setPosition(g);
        mp.setIcon(pin.drawable);
        mp.setAnchor(Marker.ANCHOR_CENTER, pin.anchorY);
        mp.setInfoWindow(null);
        mp.setOnMarkerClickListener(tap);
        pinOverlays.add(mp);
    }

    private void toggleLayer() {
        isSat = !isSat;
        map.setTileSource(isSat ? sat : street);
        layerBtn.setText(isSat ? "🗺️" : "🛰️");
    }

    private void centerMe() {
        if (myLoc == null) {
            toast("Chưa có vị trí. Hãy bật định vị (GPS).");
            return;
        }
        if (map.getZoomLevelDouble() < 17) map.getController().setZoom(17.0);
        map.getController().animateTo(new GeoPoint(myLoc.getLatitude(), myLoc.getLongitude()));
    }

    private float lastNorth = 9999;

    private void syncNorth() {
        float o = map.getMapOrientation();
        float n = ((o % 360) + 360) % 360;
        if (Math.abs(n - lastNorth) < 0.5f) return;
        lastNorth = n;
        final boolean show = n > 1 && n < 359;
        final float rr = o;
        ui.post(() -> {
            northBtn.setVisibility(show ? View.VISIBLE : View.GONE);
            northBtn.setRotation(rr);
        });
    }

    // ================= Quyền =================
    private boolean hasLocationPerm() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        if (!hasLocationPerm()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM) {
            if (hasLocationPerm()) startLocation();
            else hud.setText("Chưa có quyền định vị. Hãy cho phép vị trí cho app.");
        }
    }

    // ================= Vị trí realtime =================
    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            onLocation(l);
        }

        @Override
        public void onStatusChanged(String p, int s, Bundle e) {
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
        }
    };

    private void startLocation() {
        if (!hasLocationPerm() || lm == null) return;
        try {
            lm.removeUpdates(locListener);
            boolean any = false;
            for (String pr : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.getAllProviders().contains(pr)) {
                    lm.requestLocationUpdates(pr, 1500, 0, locListener, Looper.getMainLooper());
                    Location last = lm.getLastKnownLocation(pr);
                    if (last != null && System.currentTimeMillis() - last.getTime() < 5 * 60_000) onLocation(last);
                    any = true;
                }
            }
            if (!any) hud.setText("Thiết bị không hỗ trợ định vị.");
        } catch (SecurityException e) {
            hud.setText("Chưa có quyền định vị.");
        }
    }

    private void onLocation(Location l) {
        if (myLoc != null) {
            boolean gpsFresh = LocationManager.GPS_PROVIDER.equals(myLoc.getProvider())
                    && l.getTime() - myLoc.getTime() < 10_000;
            if (!LocationManager.GPS_PROVIDER.equals(l.getProvider()) && gpsFresh
                    && l.getAccuracy() > myLoc.getAccuracy()) return; // bỏ vị trí mạng kém hơn GPS
        }
        myLoc = l;
        me.pos = new GeoPoint(l.getLatitude(), l.getLongitude());
        me.acc = l.getAccuracy();
        if (!hasCompass && l.hasBearing() && l.getSpeed() > 1) setHeading(l.getBearing());
        if (!fitted) {
            fitted = true;
            map.getController().setZoom(16.0);
            map.getController().setCenter(me.pos);
        }
        hud(null);
        map.invalidate();
    }

    private void hud(String extra) {
        StringBuilder s = new StringBuilder();
        if (myLoc == null) s.append(hasLocationPerm() ? "Đang chờ định vị…" : "Chưa có quyền định vị");
        else s.append(String.format(Locale.US, "📍 %.5f, %.5f · ±%d m",
                myLoc.getLatitude(), myLoc.getLongitude(), Math.round(myLoc.getAccuracy())));
        if (me.heading != null) s.append("\n🧭 Hướng ").append(Math.round(me.heading)).append("°");
        else if (sm == null || sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) s.append("\nKhông có cảm biến hướng");
        if (extra != null) s.append("\n").append(extra);
        hud.setText(s);
    }

    private void setHeading(float h) {
        rot = rot == null ? h : rot + (((((h - (rot % 360)) + 540) % 360) - 180) * 0.3f);
        me.heading = ((rot % 360) + 360) % 360;
        long now = System.currentTimeMillis();
        if (now - lastInvalidate > 60) {
            lastInvalidate = now;
            map.invalidate();
            hud(null);
        }
    }

    private final float[] rm = new float[9], rm2 = new float[9], ori = new float[3];

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) return;
        SensorManager.getRotationMatrixFromVector(rm, e.values);
        int ax = SensorManager.AXIS_X, ay = SensorManager.AXIS_Y;
        int r = getWindowManager().getDefaultDisplay().getRotation();
        if (r == Surface.ROTATION_90) {
            ax = SensorManager.AXIS_Y;
            ay = SensorManager.AXIS_MINUS_X;
        } else if (r == Surface.ROTATION_180) {
            ax = SensorManager.AXIS_MINUS_X;
            ay = SensorManager.AXIS_MINUS_Y;
        } else if (r == Surface.ROTATION_270) {
            ax = SensorManager.AXIS_MINUS_Y;
            ay = SensorManager.AXIS_X;
        }
        SensorManager.remapCoordinateSystem(rm, ax, ay, rm2);
        SensorManager.getOrientation(rm2, ori);
        float az = (float) Math.toDegrees(ori[0]);
        hasCompass = true;
        setHeading((az + 360) % 360);
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {
    }
}
