package vn.bangnc.tuyenduong;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Định tuyến theo đường bộ: OSRM (miễn phí) hoặc Google Routes API (xe máy). */
final class Router {

    static final class Result {
        int[] order;              // thứ tự đi (chỉ số trong P)
        List<double[]> line;      // hình học tuyến [lat, lon]
        List<double[]> legs;      // [km, phút] mỗi chặng
        String used;
    }

    /** Gửi kèm để khóa API giới hạn "Android apps" chấp nhận yêu cầu từ app này. */
    static String androidPkg, androidCert;

    private Router() {
    }

    static String http(String method, String url, String body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "TuyenDuong-Android");
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String s = in == null ? "" : Data.utf8(Data.readAll(in));
        c.disconnect();
        if (code >= 400) {
            // OSRM trả JSON có "message" kể cả khi lỗi
            try {
                JSONObject j = new JSONObject(s);
                if (j.has("message")) throw new IOException(j.getString("message"));
                if (j.has("error")) {
                    JSONObject e = j.getJSONObject("error");
                    StringBuilder m = new StringBuilder(e.optString("message", s));
                    m.append(" (HTTP ").append(code).append(' ').append(e.optString("status", "")).append(')');
                    JSONArray det = e.optJSONArray("details");
                    boolean got = false;
                    if (det != null) {
                        for (int i = 0; i < det.length(); i++) {
                            JSONObject d = det.getJSONObject(i);
                            String reason = d.optString("reason", "");
                            if (!reason.isEmpty()) {
                                m.append(" [").append(reason);
                                JSONObject md = d.optJSONObject("metadata");
                                if (md != null && md.has("consumer")) m.append(" · ").append(md.optString("consumer"));
                                m.append(']');
                                got = true;
                            }
                        }
                    }
                    if (!got) m.append("\n").append(s.substring(0, Math.min(600, s.length())));
                    throw new IOException(m.toString());
                }
            } catch (org.json.JSONException ignore) {
            }
            throw new IOException("HTTP " + code + ": " + s.substring(0, Math.min(200, s.length())));
        }
        return s;
    }

    static double hav(double[] a, double[] b) {
        double r = Math.PI / 180;
        double x = Math.pow(Math.sin((b[0] - a[0]) * r / 2), 2)
                + Math.cos(a[0] * r) * Math.cos(b[0] * r) * Math.pow(Math.sin((b[1] - a[1]) * r / 2), 2);
        return 12742 * Math.asin(Math.sqrt(x));
    }

    /** OSRM Trip: tối ưu thứ tự, cố định điểm đầu, không quay về. */
    static Result osrm(List<double[]> P) throws Exception {
        StringBuilder c = new StringBuilder();
        for (double[] p : P) {
            if (c.length() > 0) c.append(';');
            c.append(p[1]).append(',').append(p[0]);
        }
        String url = "https://router.project-osrm.org/trip/v1/driving/" + c
                + "?source=first&roundtrip=false&geometries=geojson&overview=full";
        JSONObject r = new JSONObject(http("GET", url, null, null));
        if (!"Ok".equals(r.optString("code"))) throw new IOException(r.optString("message", r.optString("code")));
        JSONArray wp = r.getJSONArray("waypoints");
        JSONObject t = r.getJSONArray("trips").getJSONObject(0);
        int n = P.size();
        int[] wi = new int[n];
        for (int i = 0; i < n; i++) wi[i] = wp.getJSONObject(i).getInt("waypoint_index");
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(wi[a], wi[b]));
        Result res = new Result();
        res.order = new int[n];
        for (int i = 0; i < n; i++) res.order[i] = idx[i];
        JSONArray co = t.getJSONObject("geometry").getJSONArray("coordinates");
        res.line = new ArrayList<>();
        for (int i = 0; i < co.length(); i++) {
            JSONArray xy = co.getJSONArray(i);
            res.line.add(new double[]{xy.getDouble(1), xy.getDouble(0)});
        }
        res.legs = new ArrayList<>();
        JSONArray legs = t.getJSONArray("legs");
        for (int i = 0; i < legs.length(); i++) {
            JSONObject l = legs.getJSONObject(i);
            res.legs.add(new double[]{l.optDouble("distance", 0) / 1000, l.optDouble("duration", 0) / 60});
        }
        res.used = "OSRM · đường ô tô";
        return res;
    }

    /**
     * VIETMAP TSP v4: xe máy, tối ưu thứ tự, xuất phát cố định tại điểm đầu, không quay về.
     * https://maps.vietmap.vn/docs/map-api/tsp-version/tsp-v4/
     */
    static Result vietmap(List<double[]> P, String apiKey) throws Exception {
        StringBuilder u = new StringBuilder("https://maps.vietmap.vn/api/tsp/v4?apikey=")
                .append(java.net.URLEncoder.encode(apiKey, "UTF-8"));
        for (double[] p : P) u.append("&point=").append(p[0]).append(',').append(p[1]);
        u.append("&vehicle=motorcycle&roundtrip=false&sources=first&destinations=any&points_encoded=true");
        JSONObject r = new JSONObject(http("GET", u.toString(), null, null));
        if (!"OK".equalsIgnoreCase(r.optString("code"))) {
            String msg = r.optString("messages", "");
            if (msg.isEmpty() || "null".equals(msg)) msg = r.optString("message", "");
            throw new IOException(r.optString("code", "Lỗi") + (msg.isEmpty() || "null".equals(msg) ? "" : ": " + msg));
        }
        JSONArray paths = r.optJSONArray("paths");
        if (paths == null || paths.length() == 0) throw new IOException("Vietmap không trả về tuyến");
        JSONObject path = paths.getJSONObject(0);
        Result res = new Result();
        res.line = coords(path.opt("points"));
        List<double[]> snapped = coords(path.opt("snapped_waypoints"));
        res.order = orderOf(P, snapped.size() == P.size() ? snapped : null, res.line);
        res.legs = new ArrayList<>();
        res.legs.add(new double[]{path.optDouble("distance", 0) / 1000, path.optDouble("time", 0) / 60000});
        res.used = "VIETMAP · xe máy";
        return res;
    }

    /** "points" có thể là polyline (chuỗi) hoặc mảng [lon,lat] / GeoJSON. */
    private static List<double[]> coords(Object o) throws Exception {
        List<double[]> out = new ArrayList<>();
        if (o instanceof String) return decode((String) o);
        JSONArray a = null;
        if (o instanceof JSONArray) a = (JSONArray) o;
        else if (o instanceof JSONObject) a = ((JSONObject) o).optJSONArray("coordinates");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONArray xy = a.getJSONArray(i);
                out.add(new double[]{xy.getDouble(1), xy.getDouble(0)});
            }
        }
        return out;
    }

    /** Suy ra thứ tự đi: theo các điểm dừng đã bám đường, nếu không có thì theo vị trí trên tuyến. */
    private static int[] orderOf(List<double[]> P, List<double[]> snapped, List<double[]> line) {
        int n = P.size();
        int[] order = new int[n];
        boolean[] used = new boolean[n];
        order[0] = 0;
        used[0] = true;
        if (snapped != null) {
            for (int k = 1; k < n; k++) {
                int best = -1;
                double bd = Double.MAX_VALUE;
                for (int i = 1; i < n; i++) {
                    if (used[i]) continue;
                    double d = hav(snapped.get(k), P.get(i));
                    if (d < bd) {
                        bd = d;
                        best = i;
                    }
                }
                order[k] = best;
                used[best] = true;
            }
            return order;
        }
        // vị trí gần nhất trên tuyến (chỉ số đỉnh), sắp xếp tăng dần
        final double[] pos = new double[n];
        for (int i = 1; i < n; i++) {
            double bd = Double.MAX_VALUE;
            for (int j = 0; j < line.size(); j++) {
                double d = hav(line.get(j), P.get(i));
                if (d < bd) {
                    bd = d;
                    pos[i] = j;
                }
            }
        }
        Integer[] idx = new Integer[n - 1];
        for (int i = 1; i < n; i++) idx[i - 1] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(pos[a], pos[b]));
        for (int k = 1; k < n; k++) order[k] = idx[k - 1];
        return order;
    }

    private static JSONObject wp(double[] p) throws Exception {
        return new JSONObject().put("location", new JSONObject().put("latLng",
                new JSONObject().put("latitude", p[0]).put("longitude", p[1])));
    }

    /** Google Routes API, chế độ xe máy, tối ưu thứ tự điểm dừng (tối đa 25 điểm giữa). */
    static Result google(List<double[]> P, String apiKey) throws Exception {
        List<double[]> rest = P.subList(1, P.size());
        int di = 0;
        double best = -1;
        for (int i = 0; i < rest.size(); i++) {
            double d = hav(P.get(0), rest.get(i));
            if (d > best) {
                best = d;
                di = i;
            }
        }
        List<double[]> mid = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) if (i != di) mid.add(rest.get(i));
        JSONObject body = new JSONObject()
                .put("origin", wp(P.get(0)))
                .put("destination", wp(rest.get(di)))
                .put("travelMode", "TWO_WHEELER");
        if (!mid.isEmpty()) {
            JSONArray im = new JSONArray();
            for (double[] p : mid) im.put(wp(p));
            body.put("intermediates", im).put("optimizeWaypointOrder", true);
        }
        Map<String, String> h = new java.util.HashMap<>();
        h.put("X-Goog-Api-Key", apiKey);

        h.put("X-Goog-FieldMask", "routes.optimizedIntermediateWaypointIndex,routes.polyline.encodedPolyline,"
                + "routes.legs.distanceMeters,routes.legs.duration");
        String url = "https://routes.googleapis.com/directions/v2:computeRoutes";
        String raw;
        try {
            // Khóa không giới hạn ứng dụng: gửi không kèm thông tin Android
            raw = http("POST", url, body.toString(), h);
        } catch (IOException first) {
            // Khóa giới hạn "Android apps": thử lại kèm tên gói + SHA-1
            if (androidPkg == null || androidCert == null) throw first;
            h.put("X-Android-Package", androidPkg);
            h.put("X-Android-Cert", androidCert);
            try {
                raw = http("POST", url, body.toString(), h);
            } catch (IOException second) {
                throw first;
            }
        }
        JSONObject r = new JSONObject(raw);
        JSONArray routes = r.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new IOException("Google không tìm được tuyến");
        JSONObject rt = routes.getJSONObject(0);
        JSONArray oi = rt.optJSONArray("optimizedIntermediateWaypointIndex");
        Result res = new Result();
        res.order = new int[P.size()];
        int k = 0;
        res.order[k++] = 0;
        for (int i = 0; i < mid.size(); i++) {
            int j = (oi != null && i < oi.length()) ? oi.getInt(i) : i;
            res.order[k++] = 1 + (j < di ? j : j + 1);
        }
        res.order[k] = 1 + di;
        res.legs = new ArrayList<>();
        JSONArray legs = rt.optJSONArray("legs");
        if (legs != null) {
            for (int i = 0; i < legs.length(); i++) {
                JSONObject l = legs.getJSONObject(i);
                String d = l.optString("duration", "0s").replace("s", "");
                double sec;
                try {
                    sec = Double.parseDouble(d);
                } catch (NumberFormatException e) {
                    sec = 0;
                }
                res.legs.add(new double[]{l.optDouble("distanceMeters", 0) / 1000, sec / 60});
            }
        }
        res.line = decode(rt.getJSONObject("polyline").getString("encodedPolyline"));
        res.used = "Google Routes API · xe máy";
        return res;
    }

    /** Giải mã Google encoded polyline. */
    static List<double[]> decode(String s) {
        List<double[]> out = new ArrayList<>();
        int i = 0, lat = 0, lng = 0;
        while (i < s.length()) {
            int b, shift = 0, result = 0;
            do {
                b = s.charAt(i++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && i < s.length());
            lat += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            shift = 0;
            result = 0;
            do {
                b = s.charAt(i++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20 && i < s.length());
            lng += (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
            out.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return out;
    }
}
