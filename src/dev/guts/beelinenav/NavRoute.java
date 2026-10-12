package dev.guts.beelinenav;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** A route as a simplified polyline plus its turns, in metres east/north of a movable origin. */
final class NavRoute {
    static final int ARRIVE = 999;        // turnAngle value the display draws as "destination"
    private static final int WINDOW = 48; // most points the display accepts in one route packet
    private static final double M_LAT = 110540, M_LON = 111320;
    private static final double TURN_SPAN_M = 20;   // how far either side of a turn its angle is measured

    final String name;
    final double destLat, destLon;
    final int n;
    private final double[] lat, lon;
    private final double[] x, y, cum;
    private final int[] manIdx, manAngle;
    private double lat0, lon0, cos0;

    int seg;             // segment the rider is snapped to
    double progress;     // metres travelled along the route
    double offRoute;     // metres between the rider and the line
    int turnAngle;       // next turn, degrees, + = right, or ARRIVE
    double distToTurn;   // metres to that turn

    private NavRoute(String name, double destLat, double destLon,
                     ArrayList<double[]> pts, ArrayList<int[]> mans) {
        this.name = name;
        this.destLat = destLat;
        this.destLon = destLon;
        n = pts.size();
        lat = new double[n];
        lon = new double[n];
        x = new double[n];
        y = new double[n];
        cum = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = pts.get(i)[0];
            lon[i] = pts.get(i)[1];
        }
        manIdx = new int[mans.size()];
        manAngle = new int[mans.size()];
        for (int i = 0; i < mans.size(); i++) {
            manIdx[i] = mans.get(i)[0];
            manAngle[i] = mans.get(i)[1];
        }
        setOrigin(lat[0], lon[0]);
    }

    /** Builds a route from an OSRM response requested with steps=true&geometries=geojson. */
    static NavRoute fromOsrm(JSONObject root, String name, double destLat, double destLon)
            throws JSONException {
        JSONArray steps = root.getJSONArray("routes").getJSONObject(0)
                .getJSONArray("legs").getJSONObject(0).getJSONArray("steps");
        ArrayList<double[]> pts = new ArrayList<>();   // {lat, lon}
        ArrayList<int[]> mans = new ArrayList<>();     // {point index, angle}

        for (int s = 0; s < steps.length(); s++) {
            JSONObject step = steps.getJSONObject(s);
            JSONArray c = step.getJSONObject("geometry").getJSONArray("coordinates");
            double[][] raw = new double[c.length()][];
            for (int i = 0; i < raw.length; i++) {
                JSONArray p = c.getJSONArray(i);
                raw[i] = new double[]{p.getDouble(1), p.getDouble(0)};
            }
            // Simplify each step on its own so the turn point at its start is always kept
            boolean[] keep = simplify(raw, 4.0);
            int first = -1;
            for (int i = 0; i < raw.length; i++) {
                if (!keep[i]) continue;
                boolean dup = !pts.isEmpty() && same(pts.get(pts.size() - 1), raw[i]);
                if (!dup) pts.add(raw[i]);
                if (first < 0) first = pts.size() - 1;
            }
            if (first < 0) first = Math.max(pts.size() - 1, 0);

            JSONObject m = step.getJSONObject("maneuver");
            String type = m.optString("type");
            if (type.equals("depart")) continue;
            if (type.equals("arrive")) {
                mans.add(new int[]{first, ARRIVE});
                continue;
            }
            double a = norm180(m.optDouble("bearing_after", 0) - m.optDouble("bearing_before", 0));
            if (Math.abs(a) >= 20) mans.add(new int[]{first, (int) Math.round(a)});
        }
        if (pts.size() < 2) throw new JSONException("Route is too short");
        return new NavRoute(name, destLat, destLon, pts, mans);
    }

    /**
     * Builds a route from an Ola Maps directions response (Google-style JSON): the encoded
     * overview line gives the shape, each step's start point marks where a turn may be.
     */
    static NavRoute fromOla(JSONObject root, String name, double destLat, double destLon)
            throws JSONException {
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new JSONException("No route found");
        JSONObject route = routes.getJSONObject(0);

        Object ov = route.opt("overview_polyline");
        String enc = ov instanceof JSONObject ? ((JSONObject) ov).optString("points")
                : ov instanceof String ? (String) ov : "";
        if (enc.isEmpty()) throw new JSONException("No route line in the response");
        double[][] line = decodePolyline(enc, 1e5);
        if (line.length > 0 && Math.abs(line[0][0]) > 90) line = decodePolyline(enc, 1e6);

        ArrayList<double[]> turns = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            for (int s = 0; steps != null && s < steps.length(); s++) {
                JSONObject at = steps.getJSONObject(s).optJSONObject("start_location");
                if (at != null) turns.add(new double[]{at.optDouble("lat"), at.optDouble("lng")});
            }
        }
        return fromLine(line, turns.toArray(new double[0][]), name, destLat, destLon);
    }

    /**
     * Builds a route from a full-resolution line of {lat, lon} points and the places where the
     * instructions change. Turn angles are measured from the line itself.
     */
    static NavRoute fromLine(double[][] line, double[][] turnPoints, String name,
                             double destLat, double destLon) throws JSONException {
        int n = line.length;
        if (n < 2) throw new JSONException("Route is too short");
        double c = Math.cos(Math.toRadians(line[0][0]));
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = (line[i][1] - line[0][1]) * M_LON * c;
            py[i] = (line[i][0] - line[0][0]) * M_LAT;
        }

        // Nearest vertex for each turn point, never going backwards along the line
        boolean[] cut = new boolean[n];
        cut[0] = cut[n - 1] = true;
        int from = 0;
        for (double[] t : turnPoints) {
            double tx = (t[1] - line[0][1]) * M_LON * c, ty = (t[0] - line[0][0]) * M_LAT;
            double best = Double.MAX_VALUE;
            int at = from;
            for (int i = from; i < n; i++) {
                double d = Math.hypot(px[i] - tx, py[i] - ty);
                if (d < best) {
                    best = d;
                    at = i;
                }
            }
            if (best > 50) continue;        // step point is nowhere near the line; ignore it
            cut[at] = true;
            from = at;
        }

        ArrayList<double[]> pts = new ArrayList<>();
        ArrayList<int[]> mans = new ArrayList<>();
        int start = 0;
        for (int end = 1; end < n; end++) {
            if (!cut[end]) continue;
            // Simplify each stretch on its own so the turn vertices are always kept
            double[][] part = java.util.Arrays.copyOfRange(line, start, end + 1);
            boolean[] keep = simplify(part, 4.0);
            for (int i = 0; i < part.length; i++) {
                boolean dup = !pts.isEmpty() && same(pts.get(pts.size() - 1), part[i]);
                if (keep[i] && !dup) pts.add(part[i]);
            }
            if (end < n - 1) {
                double a = norm180(bearingAlong(px, py, end, TURN_SPAN_M) - bearingAlong(px, py, end, -TURN_SPAN_M));
                if (Math.abs(a) >= 20) mans.add(new int[]{pts.size() - 1, (int) Math.round(a)});
            }
            start = end;
        }
        if (pts.size() < 2) throw new JSONException("Route is too short");
        mans.add(new int[]{pts.size() - 1, ARRIVE});
        return new NavRoute(name, destLat, destLon, pts, mans);
    }

    /** Bearing of travel just after vertex i (span > 0) or just before it (span < 0), in degrees. */
    private static double bearingAlong(double[] px, double[] py, int i, double span) {
        int step = span > 0 ? 1 : -1, j = i;
        double left = Math.abs(span);
        while (j + step >= 0 && j + step < px.length && left > 0) {
            left -= Math.hypot(px[j + step] - px[j], py[j + step] - py[j]);
            j += step;
        }
        double dx = px[j] - px[i], dy = py[j] - py[i];
        if (step < 0) {
            dx = -dx;
            dy = -dy;
        }
        return Math.toDegrees(Math.atan2(dx, dy));
    }

    /** Google encoded polyline to {lat, lon} points. */
    static double[][] decodePolyline(String enc, double precision) {
        ArrayList<double[]> out = new ArrayList<>();
        int i = 0;
        long lat = 0, lon = 0;
        while (i < enc.length()) {
            long[] v = new long[2];
            for (int k = 0; k < 2 && i < enc.length(); k++) {
                long result = 0;
                int shift = 0, b;
                do {
                    b = enc.charAt(i++) - 63;
                    result |= (long) (b & 0x1f) << shift;
                    shift += 5;
                } while (b >= 0x20 && i < enc.length());
                v[k] = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
            }
            lat += v[0];
            lon += v[1];
            out.add(new double[]{lat / precision, lon / precision});
        }
        return out.toArray(new double[0][]);
    }

    /** Re-centres the metre grid. Needed because route packets carry int16 metres (about 32 km). */
    void setOrigin(double oLat, double oLon) {
        lat0 = oLat;
        lon0 = oLon;
        cos0 = Math.cos(Math.toRadians(oLat));
        for (int i = 0; i < n; i++) {
            x[i] = toX(lon[i]);
            y[i] = toY(lat[i]);
            cum[i] = i == 0 ? 0 : cum[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
    }

    double toX(double lonDeg) { return (lonDeg - lon0) * M_LON * cos0; }
    double toY(double latDeg) { return (latDeg - lat0) * M_LAT; }

    /** Snaps the rider to the line, looking near the previous segment first. */
    void snap(double px, double py) {
        double best = search(px, py, Math.max(0, seg - 3), Math.min(n - 2, seg + 40));
        if (best > 60) best = search(px, py, 0, n - 2);
        offRoute = best;

        turnAngle = ARRIVE;
        distToTurn = Math.max(0, cum[n - 1] - progress);
        for (int k = 0; k < manIdx.length; k++) {
            double at = cum[manIdx[k]];
            if (at > progress + 3) {
                turnAngle = manAngle[k];
                distToTurn = at - progress;
                break;
            }
        }
    }

    private double search(double px, double py, int lo, int hi) {
        double best = Double.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            double vx = x[i + 1] - x[i], vy = y[i + 1] - y[i], len2 = vx * vx + vy * vy;
            double t = len2 > 0 ? ((px - x[i]) * vx + (py - y[i]) * vy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(x[i] + vx * t - px, y[i] + vy * t - py);
            if (d < best) {
                best = d;
                seg = i;
                progress = cum[i] + t * Math.sqrt(len2);
            }
        }
        return best;
    }

    double remaining() { return Math.max(0, cum[n - 1] - progress); }

    /** Compass bearing of the current segment; used until GPS reports a heading. */
    double bearing() {
        double b = Math.toDegrees(Math.atan2(x[seg + 1] - x[seg], y[seg + 1] - y[seg]));
        return (b + 360) % 360;
    }

    /** Route packet: uint8 n, then n x (int16 x, int16 y) metres, starting at the current segment. */
    byte[] routePacket() {
        int cnt = Math.min(WINDOW, n - seg);
        ByteBuffer b = ByteBuffer.allocate(1 + cnt * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) cnt);
        for (int i = seg; i < seg + cnt; i++) {
            b.putShort(clamp16(x[i]));
            b.putShort(clamp16(y[i]));
        }
        return b.array();
    }

    private static short clamp16(double v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    private static double norm180(double a) {
        a %= 360;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-7 && Math.abs(a[1] - b[1]) < 1e-7;
    }

    /** Douglas-Peucker: marks the points to keep so the line stays within tol metres. */
    private static boolean[] simplify(double[][] p, double tol) {
        int n = p.length;
        boolean[] keep = new boolean[n];
        if (n == 0) return keep;
        keep[0] = keep[n - 1] = true;
        if (n < 3) return keep;

        double c = Math.cos(Math.toRadians(p[0][0]));
        double[] px = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = (p[i][1] - p[0][1]) * M_LON * c;
            py[i] = (p[i][0] - p[0][0]) * M_LAT;
        }
        int[] stack = new int[2 * n + 4];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        while (sp > 0) {
            int b = stack[--sp], a = stack[--sp];
            double max = 0;
            int idx = -1;
            double vx = px[b] - px[a], vy = py[b] - py[a], len2 = vx * vx + vy * vy;
            for (int i = a + 1; i < b; i++) {
                double t = len2 > 0 ? ((px[i] - px[a]) * vx + (py[i] - py[a]) * vy) / len2 : 0;
                t = Math.max(0, Math.min(1, t));
                double d = Math.hypot(px[a] + vx * t - px[i], py[a] + vy * t - py[i]);
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
}
