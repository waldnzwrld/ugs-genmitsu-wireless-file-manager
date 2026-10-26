package com.willwinder.ugs.nbp.genmitsusd;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the file record the module expects with an {@code ft} upload, in the same shape it
 * echoes back from {@code rr}, e.g.
 * {"bytes":48397,"fname":"x.nc","size":"48.397 kB","lines":1741,"timeCutting":"13mins41s",
 * "prt":821,"unit":"mm","max_fr":"1000.0","min_fr":"1000.0","dx":"390.250",...}.
 * Mirrors the vendor app's FileManager.parserFile: comments stripped, X/Y/Z/F words tracked.
 * Bounds and time are used by the module for display only.
 */
final class FileEntity {
    private static final double RAPID_MM_MIN = 1000;
    private static final Pattern COMMENT = Pattern.compile("\\(.*?\\)|;.*");
    private static final Pattern FEED = Pattern.compile("F(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern[] AXES = {
            Pattern.compile("X(-?\\d+(?:\\.\\d+)?)"),
            Pattern.compile("Y(-?\\d+(?:\\.\\d+)?)"),
            Pattern.compile("Z(-?\\d+(?:\\.\\d+)?)"),
    };
    private static final Pattern RAPID = Pattern.compile("\\bG0?0(?!\\d)");

    private FileEntity() {
    }

    static JsonObject of(String fname, byte[] data, String text) {
        int lines = 0;
        boolean inch = false, relative = false;
        double[] pos = new double[3], min = new double[3], max = new double[3];
        double feed = 0, minFr = Double.POSITIVE_INFINITY, maxFr = 0, seconds = 0;

        for (String raw : text.split("\\r?\\n", -1)) {
            if (!raw.isBlank()) {
                lines++;
            }
            String line = COMMENT.matcher(raw).replaceAll("").trim().toUpperCase(Locale.ROOT);
            if (line.isEmpty()) {
                continue;
            }
            if (line.matches(".*G20\\b.*")) inch = true;
            if (line.matches(".*G21\\b.*")) inch = false;
            if (line.matches(".*G91\\b.*")) relative = true;
            if (line.matches(".*G90\\b.*")) relative = false;

            Matcher f = FEED.matcher(line);
            if (f.find()) {
                feed = Double.parseDouble(f.group(1));
                minFr = Math.min(minFr, feed);
                maxFr = Math.max(maxFr, feed);
            }

            double[] from = pos.clone();
            boolean moved = false;
            for (int a = 0; a < 3; a++) {
                Matcher m = AXES[a].matcher(line);
                if (!m.find()) {
                    continue;
                }
                double v = Double.parseDouble(m.group(1));
                pos[a] = relative ? pos[a] + v : v;
                min[a] = Math.min(min[a], pos[a]);
                max[a] = Math.max(max[a], pos[a]);
                moved = true;
            }
            if (moved) {
                double dist = Math.sqrt(sq(pos[0] - from[0]) + sq(pos[1] - from[1]) + sq(pos[2] - from[2]));
                double rate = RAPID.matcher(line).find() || feed == 0 ? RAPID_MM_MIN : feed;
                seconds += dist / rate * 60;
            }
        }

        long prt = Math.round(seconds);
        JsonObject o = new JsonObject();
        o.addProperty("bytes", data.length);
        o.addProperty("fname", fname);
        o.addProperty("size", String.format(Locale.ROOT, "%.3f kB", data.length / 1000.0));
        o.addProperty("lines", lines);
        o.addProperty("timeCutting", formatTime(prt));
        o.addProperty("prt", prt);
        o.addProperty("unit", inch ? "inch" : "mm");
        o.addProperty("max_fr", n1(maxFr));
        o.addProperty("min_fr", n1(minFr == Double.POSITIVE_INFINITY ? 0 : minFr));
        o.addProperty("dx", n3(max[0] - min[0]));
        o.addProperty("dy", n3(max[1] - min[1]));
        o.addProperty("dz", n3(max[2] - min[2]));
        o.addProperty("minx", n3(min[0]));
        o.addProperty("miny", n3(min[1]));
        o.addProperty("minz", n3(min[2]));
        o.addProperty("maxx", n3(max[0]));
        o.addProperty("maxy", n3(max[1]));
        o.addProperty("maxz", n3(max[2]));
        return o;
    }

    // Matches the app's "13mins41s" style.
    private static String formatTime(long total) {
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return (h > 0 ? h + "h" : "") + (h > 0 || m > 0 ? m + "mins" : "") + s + "s";
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String n1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String n3(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
