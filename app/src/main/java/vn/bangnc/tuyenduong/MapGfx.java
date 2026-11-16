package vn.bangnc.tuyenduong;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/** Nền bản đồ, ghim đánh số kèm nhãn tên, và chấm vị trí của tôi + tia hướng. */
final class MapGfx {

    private MapGfx() {
    }

    static float dp(float v) {
        return v * Resources.getSystem().getDisplayMetrics().density;
    }

    // ---------- Nền bản đồ ----------
    static OnlineTileSourceBase google(String name, final String lyrs) {
        return new OnlineTileSourceBase(name, 0, 20, 256, ".png", new String[]{
                "https://mt0.google.com", "https://mt1.google.com", "https://mt2.google.com", "https://mt3.google.com"}) {
            @Override
            public String getTileURLString(long idx) {
                return getBaseUrl() + "/vt/lyrs=" + lyrs + "&hl=vi&gl=VN&x=" + MapTileIndex.getX(idx)
                        + "&y=" + MapTileIndex.getY(idx) + "&z=" + MapTileIndex.getZoom(idx);
            }
        };
    }

    // ---------- Ghim + nhãn ----------
    static final class Icon {
        final BitmapDrawable drawable;
        final float anchorY;

        Icon(BitmapDrawable d, float ay) {
            drawable = d;
            anchorY = ay;
        }
    }

    static Icon pin(Resources res, String num, String name) {
        boolean numbered = num != null && !num.isEmpty();
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextSize(dp(11.5f));
        String label = name == null ? "" : (name.length() > 34 ? name.substring(0, 33) + "…" : name);
        float padX = dp(6), padY = dp(3);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float lw = label.isEmpty() ? 0 : tp.measureText(label) + padX * 2;
        float lh = label.isEmpty() ? 0 : textH + padY * 2;
        float gap = label.isEmpty() ? 0 : dp(4);
        float pd = numbered ? dp(26) : dp(15);
        float sh = dp(2); // lề cho bóng
        int w = (int) Math.ceil(Math.max(lw, pd) + sh * 2);
        int h = (int) Math.ceil(lh + gap + pd + sh * 2);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = w / 2f;

        if (!label.isEmpty()) {
            RectF r = new RectF(cx - lw / 2, sh, cx + lw / 2, sh + lh);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xE00F2A26);
            bg.setShadowLayer(dp(2), 0, dp(1), 0x73000000);
            c.drawRoundRect(r, dp(6), dp(6), bg);
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(dp(1));
            st.setColor(0xFF14B8A6);
            c.drawRoundRect(r, dp(6), dp(6), st);
            c.drawText(label, r.left + padX, r.top + padY - fm.ascent, tp);
        }

        float cy = sh + lh + gap + pd / 2;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(numbered ? 0xFFEA580C : 0xFFF97316);
        fill.setShadowLayer(dp(2.5f), 0, dp(1), 0x88000000);
        c.drawCircle(cx, cy, pd / 2, fill);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        ring.setColor(Color.WHITE);
        c.drawCircle(cx, cy, pd / 2 - dp(1), ring);
        if (numbered) {
            Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
            np.setColor(Color.WHITE);
            np.setTypeface(Typeface.DEFAULT_BOLD);
            np.setTextSize(dp(num.length() > 2 ? 10 : 12));
            np.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics f2 = np.getFontMetrics();
            c.drawText(num, cx, cy - (f2.ascent + f2.descent) / 2, np);
        }
        return new Icon(new BitmapDrawable(res, b), cy / h);
    }

    // ---------- Vị trí của tôi ----------
    static final class MeOverlay extends Overlay {
        GeoPoint pos;
        float acc;
        Float heading;
        Runnable onFrame;

        private final Paint accFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blue = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float beamR = -1;

        MeOverlay() {
            accFill.setColor(0x1A3B82F6);
            accLine.setColor(0x993B82F6);
            accLine.setStyle(Paint.Style.STROKE);
            accLine.setStrokeWidth(dp(1));
            white.setColor(Color.WHITE);
            white.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
            blue.setColor(0xFF4285F4);
            halo.setColor(0x334285F4);
        }

        @Override
        public void draw(Canvas c, MapView mv, boolean shadow) {
            if (onFrame != null) onFrame.run();
            if (shadow || pos == null) return;
            Projection pj = mv.getProjection();
            Point p = pj.toPixels(pos, null);
            float x = p.x, y = p.y;

            if (acc > 0) {
                Point q = pj.toPixels(pos.destinationPoint(acc, 90), null);
                float r = (float) Math.hypot(q.x - x, q.y - y);
                if (r > dp(12)) {
                    c.drawCircle(x, y, r, accFill);
                    c.drawCircle(x, y, r, accLine);
                }
            }
            if (heading != null) {
                float R = dp(58);
                if (beamR != R) {
                    beamR = R;
                    beam.setShader(new RadialGradient(0, 0, R,
                            new int[]{0xE64285F4, 0x994285F4, 0x004285F4}, new float[]{0.15f, 0.45f, 1f},
                            Shader.TileMode.CLAMP));
                }
                c.save();
                c.translate(x, y);
                c.rotate(heading);
                Path path = new Path();
                path.moveTo(0, 0);
                path.arcTo(new RectF(-R, -R, R, R), -90 - 32, 64);
                path.close();
                c.drawPath(path, beam);
                c.restore();
            }
            c.drawCircle(x, y, dp(16), halo);
            c.drawCircle(x, y, dp(10.5f), white);
            c.drawCircle(x, y, dp(7.5f), blue);
        }
    }
}
