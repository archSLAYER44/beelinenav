package dev.guts.beelinenav;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The roads around a route, from Ola Maps vector tiles, so the display can draw the junctions
 * the rider is not taking (thin grey lines under the white route). Tiles are cached for the session.
 */
final class Roads {
    /** One road piece in the route's metre grid (east, north of the route origin). */
    static final class Line {
        final int level;
        final double[] lat, lon;
        double[] x, y;

        Line(int level, double[] lat, double[] lon) {
            this.level = level;
            this.lat = lat;
            this.lon = lon;
        }
    }

    interface Cb { void done(List<Line> lines, String error); }

    private static final int Z = 14;          // the deepest zoom Ola's road source offers
    private static final int MAX_TILES = 16;
    private static final Map<String, List<Mvt.Road>> CACHE = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Loads the roads under the route (plus a margin), in the background; callback on the main thread. */
    static void load(NavRoute route, Cb cb) {
        Set<String> keys = new LinkedHashSet<>();
        for (double[] p : route.outline(0, 400)) {              // tiles the route passes through, in order
            int[] t = tile(p[0], p[1]);
            for (int dx = -1; dx <= 1; dx++) {                  // and a neighbour when the route runs near its edge
                for (int dy = -1; dy <= 1; dy++) {
                    if ((dx == 0 && dy == 0) || near(p[0], p[1], t[0] + dx, t[1] + dy)) {
                        keys.add(Z + "/" + (t[0] + dx) + "/" + (t[1] + dy));
                    }
                }
            }
            if (keys.size() >= MAX_TILES) break;
        }
        List<String> wanted = new ArrayList<>(keys);
        if (wanted.size() > MAX_TILES) wanted = wanted.subList(0, MAX_TILES);
        final List<String> finalWanted = wanted;

        new Thread(() -> {
            List<Line> out = new ArrayList<>();
            String error = null;
            int fetched = 0;
            for (String key : finalWanted) {
                List<Mvt.Road> roads;
                synchronized (CACHE) {
                    roads = CACHE.get(key);
                }
                if (roads == null) {
                    try {
                        String[] zxy = key.split("/");
                        byte[] tile = Router.fetch(String.format(Locale.US,
                                "https://api.olamaps.io/tiles/v1/data/planet/%s/%s/%s.pbf?api_key=%s",
                                zxy[0], zxy[1], zxy[2], Router.olaKey()));
                        roads = Mvt.roads(tile, Z, Integer.parseInt(zxy[1]), Integer.parseInt(zxy[2]));
                        fetched++;
                        synchronized (CACHE) {
                            CACHE.put(key, roads);
                        }
                    } catch (Exception e) {
                        error = e.getMessage();
                        continue;
                    }
                }
                for (Mvt.Road r : roads) out.add(new Line(r.level, r.lat, r.lon));
            }
            Log.i("BeelineNav", "roads: " + out.size() + " lines from " + finalWanted.size()
                    + " tiles (" + fetched + " fetched)" + (error == null ? "" : ", last error: " + error));
            final String err = error;
            MAIN.post(() -> {
                rebase(out, route);   // on the main thread, where the route's origin is moved too
                cb.done(out, err);
            });
        }).start();
    }

    /** Recomputes the metre coordinates against the route's current origin. */
    static void rebase(List<Line> lines, NavRoute route) {
        for (Line l : lines) {
            l.x = new double[l.lat.length];
            l.y = new double[l.lat.length];
            for (int i = 0; i < l.lat.length; i++) {
                l.x[i] = route.toX(l.lon[i]);
                l.y[i] = route.toY(l.lat[i]);
            }
        }
    }

    /**
     * The pieces of the roads that fall inside the box, clipped to it, simplified, main roads
     * first, and capped so the whole set fits the display.
     */
    static List<Line> window(List<Line> lines, double minX, double minY, double maxX, double maxY, int maxPoints) {
        List<Line> pieces = new ArrayList<>();
        for (Line l : lines) {
            if (l.x == null) continue;
            List<Double> px = new ArrayList<>(), py = new ArrayList<>();
            for (int i = 0; i + 1 < l.x.length; i++) {
                double[] s = {l.x[i], l.y[i], l.x[i + 1], l.y[i + 1]};
                if (!clip(s, minX, minY, maxX, maxY)) {
                    flush(pieces, l.level, px, py);
                    continue;
                }
                boolean joins = !px.isEmpty() && Math.abs(px.get(px.size() - 1) - s[0]) < 0.01
                        && Math.abs(py.get(py.size() - 1) - s[1]) < 0.01;
                if (!joins) {
                    flush(pieces, l.level, px, py);
                    px.add(s[0]);
                    py.add(s[1]);
                }
                px.add(s[2]);
                py.add(s[3]);
            }
            flush(pieces, l.level, px, py);
        }
        Collections.sort(pieces, (a, b) -> a.level != b.level ? a.level - b.level
                : Double.compare(length(b), length(a)));
        List<Line> out = new ArrayList<>();
        int points = 0;
        for (Line p : pieces) {
            if (points + p.x.length > maxPoints) continue;
            out.add(p);
            points += p.x.length;
        }
        return out;
    }

    /** Chunked packets for the display: seq, flags (bit0 first, bit1 last), then n, level, n x (int16 x, y). */
    static List<byte[]> packets(List<Line> pieces) {
        final int maxRun = 55;                       // points per polyline per packet, so a packet stays under 240 bytes
        List<byte[]> out = new ArrayList<>();
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        List<int[]> runs = new ArrayList<>();        // {piece index, start, count}
        for (int p = 0; p < pieces.size(); p++) {
            int n = pieces.get(p).x.length;
            for (int s = 0; s < n - 1; s += maxRun - 1) runs.add(new int[]{p, s, Math.min(maxRun, n - s)});
        }
        for (int[] r : runs) {
            Line l = pieces.get(r[0]);
            int bytes = 2 + 4 * r[2];
            if (cur.size() > 0 && cur.size() + bytes > 238) {
                out.add(cur.toByteArray());
                cur = new ByteArrayOutputStream();
            }
            cur.write(r[2]);
            cur.write(l.level);
            for (int i = r[1]; i < r[1] + r[2]; i++) {
                int x = clamp16(l.x[i]), y = clamp16(l.y[i]);
                cur.write(x & 0xFF);
                cur.write(x >> 8 & 0xFF);
                cur.write(y & 0xFF);
                cur.write(y >> 8 & 0xFF);
            }
        }
        out.add(cur.toByteArray());                  // possibly empty: an empty set clears the display
        for (int i = 0; i < out.size(); i++) {
            byte[] body = out.get(i), pkt = new byte[body.length + 2];
            pkt[0] = (byte) i;
            pkt[1] = (byte) ((i == 0 ? 1 : 0) | (i == out.size() - 1 ? 2 : 0));
            System.arraycopy(body, 0, pkt, 2, body.length);
            out.set(i, pkt);
        }
        return out;
    }

    static int pointCount(List<Line> pieces) {
        int n = 0;
        for (Line p : pieces) n += p.x.length;
        return n;
    }

    // ---------- helpers ----------

    private static int[] tile(double lat, double lon) {
        double n = 1 << Z, latR = Math.toRadians(lat);
        int x = (int) Math.floor((lon + 180) / 360 * n);
        int y = (int) Math.floor((1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n);
        return new int[]{x, y};
    }

    /** True when the point is within ~300 m of the given tile (so roads just over its edge are included). */
    private static boolean near(double lat, double lon, int tx, int ty) {
        double n = 1 << Z;
        double lon0 = tx / n * 360 - 180, lon1 = (tx + 1) / n * 360 - 180;
        double lat1 = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * ty / n))));
        double lat0 = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty + 1) / n))));
        double dLat = 300 / 110540.0, dLon = 300 / (111320.0 * Math.cos(Math.toRadians(lat)));
        return lon >= lon0 - dLon && lon <= lon1 + dLon && lat >= lat0 - dLat && lat <= lat1 + dLat;
    }

    private static void flush(List<Line> pieces, int level, List<Double> px, List<Double> py) {
        if (px.size() >= 2) {
            double[] x = new double[px.size()], y = new double[px.size()];
            for (int i = 0; i < x.length; i++) {
                x[i] = px.get(i);
                y[i] = py.get(i);
            }
            Line l = new Line(level, null, null);
            boolean[] keep = simplify(x, y, 3.0);
            int n = 0;
            for (boolean k : keep) if (k) n++;
            l.x = new double[n];
            l.y = new double[n];
            for (int i = 0, j = 0; i < x.length; i++) {
                if (keep[i]) {
                    l.x[j] = x[i];
                    l.y[j++] = y[i];
                }
            }
            if (length(l) >= 15) pieces.add(l);
        }
        px.clear();
        py.clear();
    }

    private static double length(Line l) {
        double d = 0;
        for (int i = 1; i < l.x.length; i++) d += Math.hypot(l.x[i] - l.x[i - 1], l.y[i] - l.y[i - 1]);
        return d;
    }

    /** Liang-Barsky: clips the segment {x0,y0,x1,y1} in place; false if it misses the box. */
    private static boolean clip(double[] s, double xmin, double ymin, double xmax, double ymax) {
        double dx = s[2] - s[0], dy = s[3] - s[1], t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy}, q = {s[0] - xmin, xmax - s[0], s[1] - ymin, ymax - s[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return false;
                continue;
            }
            double t = q[i] / p[i];
            if (p[i] < 0) {
                if (t > t1) return false;
                if (t > t0) t0 = t;
            } else {
                if (t < t0) return false;
                if (t < t1) t1 = t;
            }
        }
        double ox = s[0], oy = s[1];
        s[0] = ox + t0 * dx;
        s[1] = oy + t0 * dy;
        s[2] = ox + t1 * dx;
        s[3] = oy + t1 * dy;
        return true;
    }

    /** Douglas-Peucker on metre coordinates. */
    private static boolean[] simplify(double[] x, double[] y, double tol) {
        int n = x.length;
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        int[] stack = new int[2 * n + 4];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            double vx = x[b] - x[a], vy = y[b] - y[a], len2 = vx * vx + vy * vy, max = 0;
            int idx = -1;
            for (int i = a + 1; i < b; i++) {
                double t = len2 > 0 ? ((x[i] - x[a]) * vx + (y[i] - y[a]) * vy) / len2 : 0;
                t = Math.max(0, Math.min(1, t));
                double d = Math.hypot(x[a] + vx * t - x[i], y[a] + vy * t - y[i]);
                if (d > max) {
                    max = d;
                    idx = i;
                }
            }
            if (idx >= 0 && max > tol) {
                keep[idx] = true;
                stack[sp++] = a;
                stack[sp++] = idx;
                stack[sp++] = idx;
                stack[sp++] = b;
            }
        }
        return keep;
    }

    private static int clamp16(double v) {
        return (int) Math.max(-32768, Math.min(32767, Math.round(v)));
    }
}
