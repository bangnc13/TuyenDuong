package vn.bangnc.tuyenduong;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Đọc dữ liệu: Google Sheet "Tọa độ", file CSV và XLSX (giống app.py). */
final class Data {

    static final String SHEET_ID = "1P7juPjYYReWhr9zQIyCg2PW_aTASA5dq1UG0DvfJUTk";
    static final String GID = "455030063"; // sheet "Tọa độ"
    static final String CSV_URL = "https://docs.google.com/spreadsheets/d/" + SHEET_ID
            + "/gviz/tq?tqx=out:csv&gid=" + GID;

    static final class Item {
        final String name, key, plain;
        final double lat, lon;

        Item(String name, double lat, double lon) {
            this.name = name;
            this.key = key(name);
            this.plain = plain(name);
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Data() {
    }

    // ---------- Chuẩn hoá chuỗi ----------
    static String plain(String s) {
        s = String.valueOf(s).replace("đ", "d").replace("Đ", "D");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[^\\x00-\\x7F]", "");
        return s.toLowerCase(Locale.ROOT).trim();
    }

    static String key(String s) {
        s = Normalizer.normalize(String.valueOf(s), Normalizer.Form.NFC);
        return s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    // ---------- Tọa độ ----------
    private static final Pattern PAIR = Pattern.compile(
            "(-?\\d{1,3}[.,]\\d{3,})\\s*[,;/\\s]\\s*(-?\\d{1,3}[.,]\\d{3,})");
    private static final Pattern NUM = Pattern.compile("\\s*-?\\d{1,3}[.,]\\d{3,}\\s*");

    private static double[] fix(double a, double b) {
        if (Math.abs(a) > 90) {
            double t = a;
            a = b;
            b = t;
        }
        return (Math.abs(a) <= 90 && Math.abs(b) <= 180) ? new double[]{a, b} : null;
    }

    private static double num(String s) {
        return Double.parseDouble(s.trim().replace(",", "."));
    }

    /** Tìm cặp (lat, lon) trong một dòng: 1 ô "lat, lon" hoặc 2 ô số riêng. */
    static double[] coord(List<String> cells) {
        for (String c : cells) {
            Matcher m = PAIR.matcher(c);
            if (m.find()) return fix(num(m.group(1)), num(m.group(2)));
        }
        List<Double> n = new ArrayList<>();
        for (String c : cells) if (NUM.matcher(c).matches()) n.add(num(c));
        return n.size() > 1 ? fix(n.get(0), n.get(1)) : null;
    }

    /** Bảng (dòng đầu là tiêu đề) -> danh sách đối tượng có tọa độ, theo key tên. */
    static LinkedHashMap<String, Item> buildRows(List<List<String>> table) {
        LinkedHashMap<String, Item> rows = new LinkedHashMap<>();
        if (table.isEmpty()) return rows;
        List<String> head = table.get(0);
        int nc = -1;
        for (int i = 0; i < head.size() && nc < 0; i++) if (plain(head.get(i)).contains("ten doi tuong")) nc = i;
        for (int i = 0; i < head.size() && nc < 0; i++) if (plain(head.get(i)).contains("ten")) nc = i;
        if (nc < 0) nc = 0;
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            String name = nc < row.size() ? row.get(nc).trim() : "";
            if (name.isEmpty()) continue;
            List<String> others = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) if (i != nc) others.add(row.get(i));
            double[] p;
            try {
                p = coord(others);
            } catch (NumberFormatException e) {
                p = null;
            }
            if (p == null) continue;
            String k = key(name);
            if (!rows.containsKey(k)) rows.put(k, new Item(name, p[0], p[1]));
        }
        return rows;
    }

    /** Tên trong file import (cột "Tên đối tượng", không có thì cột đầu). */
    static List<String> importNames(List<List<String>> table) {
        List<String> out = new ArrayList<>();
        if (table.isEmpty()) return out;
        List<String> head = table.get(0);
        int col = 0;
        for (int i = 0; i < head.size(); i++) {
            if (plain(head.get(i)).contains("ten doi tuong")) {
                col = i;
                break;
            }
        }
        for (int r = 1; r < table.size(); r++) {
            List<String> row = table.get(r);
            if (col < row.size()) {
                String v = row.get(col).trim();
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    // ---------- CSV ----------
    static List<List<String>> parseCsv(String text) {
        if (text.startsWith("﻿")) text = text.substring(1);
        char sep = ',';
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        if (count(first, ';') > count(first, ',')) sep = ';';

        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else q = false;
                } else cell.append(c);
            } else if (c == '"') {
                q = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // ---------- XLSX (đọc trực tiếp file zip/xml, sheet đầu tiên) ----------
    static List<List<String>> parseXlsx(byte[] bytes) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml")
                        || n.equals("xl/_rels/workbook.xml.rels") || n.startsWith("xl/worksheets/sheet")) {
                    files.put(n, readAll(z));
                }
            }
        }
        if (files.isEmpty()) throw new IOException("Không phải file .xlsx hợp lệ");
        try {
            List<String> shared = files.containsKey("xl/sharedStrings.xml")
                    ? sharedStrings(files.get("xl/sharedStrings.xml")) : new ArrayList<>();
            String path = firstSheet(files);
            byte[] sheet = files.get(path);
            if (sheet == null) {
                String best = null;
                for (String n : files.keySet()) {
                    if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")
                            && (best == null || n.length() < best.length()
                            || (n.length() == best.length() && n.compareTo(best) < 0))) best = n;
                }
                if (best == null) throw new IOException("File Excel không có sheet");
                sheet = files.get(best);
            }
            return sheetRows(sheet, shared);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Lỗi đọc Excel: " + e.getMessage());
        }
    }

    private static String local(String n) {
        int i = n == null ? -1 : n.indexOf(':');
        return i < 0 ? n : n.substring(i + 1);
    }

    private static XmlPullParser parser(byte[] b) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(b), "UTF-8");
        return p;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (local(p.getAttributeName(i)).equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String firstSheet(Map<String, byte[]> files) throws Exception {
        byte[] wb = files.get("xl/workbook.xml"), rels = files.get("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null) return "xl/worksheets/sheet1.xml";
        String rid = null;
        XmlPullParser p = parser(wb);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && rid == null; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG && "sheet".equals(local(p.getName()))) rid = attr(p, "id");
        }
        if (rid == null) return "xl/worksheets/sheet1.xml";
        p = parser(rels);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG && "Relationship".equals(local(p.getName()))
                    && rid.equals(p.getAttributeValue(null, "Id"))) {
                String t = p.getAttributeValue(null, "Target");
                if (t == null) break;
                return t.startsWith("/") ? t.substring(1) : "xl/" + t;
            }
        }
        return "xl/worksheets/sheet1.xml";
    }

    private static List<String> sharedStrings(byte[] b) throws Exception {
        List<String> out = new ArrayList<>();
        XmlPullParser p = parser(b);
        StringBuilder sb = null;
        boolean inT = false, inRph = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("si".equals(n)) sb = new StringBuilder();
                else if ("rPh".equals(n)) inRph = true;
                else if ("t".equals(n)) inT = true;
            } else if (ev == XmlPullParser.TEXT) {
                if (inT && !inRph && sb != null) sb.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if ("t".equals(n)) inT = false;
                else if ("rPh".equals(n)) inRph = false;
                else if ("si".equals(n)) {
                    out.add(sb == null ? "" : sb.toString());
                    sb = null;
                }
            }
        }
        return out;
    }

    private static int colIndex(String ref) {
        int c = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
        }
        return c - 1;
    }

    private static List<List<String>> sheetRows(byte[] b, List<String> shared) throws Exception {
        List<List<String>> out = new ArrayList<>();
        XmlPullParser p = parser(b);
        int rowIdx = -1, col = -1, nextCol = 0;
        String type = null;
        StringBuilder val = null;
        boolean inV = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if ("row".equals(n)) {
                    String r = attr(p, "r");
                    int ri;
                    try {
                        ri = r == null ? rowIdx + 1 : Integer.parseInt(r) - 1;
                    } catch (NumberFormatException e) {
                        ri = rowIdx + 1;
                    }
                    rowIdx = ri;
                    nextCol = 0;
                } else if ("c".equals(n)) {
                    String r = attr(p, "r");
                    col = r == null ? nextCol : colIndex(r);
                    if (col < 0) col = nextCol;
                    type = attr(p, "t");
                    val = new StringBuilder();
                } else if ("v".equals(n) || "t".equals(n)) {
                    inV = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inV && val != null) val.append(p.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if ("v".equals(n) || "t".equals(n)) inV = false;
                else if ("c".equals(n) && val != null && rowIdx >= 0) {
                    String v = val.toString();
                    if ("s".equals(type)) {
                        try {
                            int i = Integer.parseInt(v.trim());
                            v = i >= 0 && i < shared.size() ? shared.get(i) : "";
                        } catch (NumberFormatException e) {
                            v = "";
                        }
                    }
                    while (out.size() <= rowIdx) out.add(new ArrayList<>());
                    List<String> row = out.get(rowIdx);
                    while (row.size() <= col) row.add("");
                    row.set(col, v);
                    nextCol = col + 1;
                    val = null;
                }
            }
        }
        // bỏ các dòng trống ở đầu để dòng tiêu đề nằm ở vị trí 0
        while (!out.isEmpty() && isBlank(out.get(0))) out.remove(0);
        return out;
    }

    private static boolean isBlank(List<String> row) {
        for (String s : row) if (!s.trim().isEmpty()) return false;
        return true;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
