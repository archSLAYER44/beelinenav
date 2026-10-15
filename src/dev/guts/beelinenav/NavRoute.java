package dev.guts.beelinenav;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A route as a simplified polyline plus its turns, in metres east/north of a movable origin. */
final class NavRoute {
    static final int ARRIVE = 999;        // turnAngle value the display draws as "destination"
    private static final int WINDOW = 48; // most points the display accepts in one route packet
    private static final double M_LAT = 110540, M_LON = 111320;
    private static final double TURN_SPAN_M = 20;   // how far either side of a turn its angle is measured
    private static final double WRONG_WAY_M = 40;   // a stretch running against the heading counts this much further away

    final String name;
    final double destLat, destLon;
    final int n;
    private final double[] lat, lon;
    private final double[] x, y, cum;
    private final int[] manIdx, manAngle;
    private final String[] manName;   // the road each turn leads onto; the destination's name for the arrival
    private double lat0, lon0, cos0;

    int durationS;       // the service's time estimate for the whole route, seconds
    int seg;             // segment the rider is snapped to
    double progress;     // metres travelled along the route
    double offRoute;     // metres between the rider and the line
    int turnAngle;       // next turn, degrees, + = right, or ARRIVE
    double distToTurn;   // metres to that turn
    String turnName = ""; // the road after that turn ("" when unknown), or the destination when arriving

    private NavRoute(String name, double destLat, double destLon,
                     ArrayList<double[]> pts, ArrayList<int[]> mans, ArrayList<String> names) {
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
        manName = names.toArray(new String[0]);
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
        ArrayList<String> names = new ArrayList<>();   // the road each turn leads onto

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
                names.add(name);
                continue;
            }
            double a = norm180(m.optDouble("bearing_after", 0) - m.optDouble("bearing_before", 0));
            if (Math.abs(a) >= 20) {
                mans.add(new int[]{first, (int) Math.round(a)});
                names.add(roadName(step.optString("name"), step.optString("ref")));
            }
        }
        if (pts.size() < 2) throw new JSONException("Route is too short");
        NavRoute r = new NavRoute(name, destLat, destLon, pts, mans, names);
        r.durationS = (int) root.getJSONArray("routes").getJSONObject(0).optDouble("duration", 0);
        return r;
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
        ArrayList<String> roads = new ArrayList<>();   // "Turn left onto Kasturba Road" -> Kasturba Road
        JSONArray legs = route.optJSONArray("legs");
        int duration = 0;
        for (int l = 0; legs != null && l < legs.length(); l++) {
            duration += legs.getJSONObject(l).optInt("duration", 0);
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            for (int s = 0; steps != null && s < steps.length(); s++) {
                JSONObject step = steps.getJSONObject(s);
                JSONObject at = step.optJSONObject("start_location");
                String man = step.optString("maneuver");
                // "continue" is only a road-name change; the line's last point is the arrival
                if (at == null || man.equals("depart") || man.equals("continue") || man.equals("arrive")) continue;
                double side = man.contains("left") ? -1 : man.contains("right") ? 1 : 0;
                turns.add(new double[]{at.optDouble("lat"), at.optDouble("lng"), side});
                roads.add(roadFromInstruction(step.optString("instructions")));
            }
        }
        NavRoute r = fromLine(line, turns.toArray(new double[0][]), roads.toArray(new String[0]), name, destLat, destLon);
        r.durationS = duration;
        return r;
    }

    /**
     * Google Routes API (computeRoutes) response: one route with an encoded polyline and steps that
     * carry a maneuver ("TURN_LEFT") and a spoken-style instruction naming the road.
     */
    static NavRoute fromGoogle(JSONObject root, String name, double destLat, double destLon)
            throws JSONException {
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            JSONObject err = root.optJSONObject("error");
            throw new JSONException(err == null ? "No route found" : err.optString("message", "No route found"));
        }
        JSONObject route = routes.getJSONObject(0);
        JSONObject pl = route.optJSONObject("polyline");
        String enc = pl == null ? "" : pl.optString("encodedPolyline");
        if (enc.isEmpty()) throw new JSONException("No route line in the response");
        double[][] line = decodePolyline(enc, 1e5);

        ArrayList<double[]> turns = new ArrayList<>();
        ArrayList<String> roads = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            for (int s = 0; steps != null && s < steps.length(); s++) {
                JSONObject step = steps.getJSONObject(s);
                JSONObject nav = step.optJSONObject("navigationInstruction");
                JSONObject at = step.optJSONObject("startLocation");
                at = at == null ? null : at.optJSONObject("latLng");
                String man = nav == null ? "" : nav.optString("maneuver");
                // the start, a road-name change and "straight" are not turns; the line's last point is the arrival
                if (at == null || man.isEmpty() || man.equals("DEPART") || man.equals("NAME_CHANGE")
                        || man.equals("STRAIGHT") || man.equals("MANEUVER_UNSPECIFIED")) continue;
                double side = man.contains("LEFT") ? -1 : man.contains("RIGHT") ? 1 : 0;
                turns.add(new double[]{at.optDouble("latitude"), at.optDouble("longitude"), side});
                roads.add(roadFromInstruction(nav.optString("instructions")));
            }
        }
        NavRoute r = fromLine(line, turns.toArray(new double[0][]), roads.toArray(new String[0]), name, destLat, destLon);
        String dur = route.optString("duration", "0s").replace("s", "");   // "1234s"
        try {
            r.durationS = (int) Math.round(Double.parseDouble(dur));
        } catch (NumberFormatException ignored) {
            r.durationS = 0;
        }
        return r;
    }

    /**
     * Builds a route from a full-resolution line of {lat, lon} points and the turn points
     * {lat, lon, side}. Turn angles are measured from the line itself; side (-1 left, +1 right,
     * 0 unknown) only decides the icon for turns too gentle to measure, like "keep right".
     * turnRoads names the road each turn point leads onto (same order; may be null).
     */
    static NavRoute fromLine(double[][] line, double[][] turnPoints, String[] turnRoads, String name,
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
        double[] side = new double[n];
        String[] road = new String[n];
        cut[0] = cut[n - 1] = true;
        int from = 0;
        for (int k = 0; k < turnPoints.length; k++) {
            double[] t = turnPoints[k];
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
            side[at] = t.length > 2 ? t[2] : 0;
            road[at] = turnRoads != null && k < turnRoads.length && turnRoads[k] != null ? turnRoads[k] : "";
            from = at;
        }

        ArrayList<double[]> pts = new ArrayList<>();
        ArrayList<int[]> mans = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
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
                if (Math.abs(a) < 20) a = side[end] * 25;
                if (a != 0) {
                    mans.add(new int[]{pts.size() - 1, (int) Math.round(a)});
                    names.add(road[end] == null ? "" : road[end]);
                }
            }
            start = end;
        }
        if (pts.size() < 2) throw new JSONException("Route is too short");
        mans.add(new int[]{pts.size() - 1, ARRIVE});
        names.add(name);
        return new NavRoute(name, destLat, destLon, pts, mans, names);
    }

    /** The road an OSRM step runs along: its name, else its number ("NH 44"), else nothing. */
    private static String roadName(String name, String ref) {
        if (name != null && !name.isEmpty()) return name;
        return ref == null ? "" : ref;
    }

    private static final Pattern ONTO = Pattern.compile("\\b(?:onto|on|towards?)\\s+(.+)$");

    /** The road named in a spoken-style instruction: "Turn left onto Kasturba Road" gives "Kasturba Road". */
    static String roadFromInstruction(String instruction) {
        if (instruction == null) return "";
        Matcher m = ONTO.matcher(instruction.replaceAll("<[^>]+>", ""));
        if (!m.find()) return "";
        String road = m.group(1).replaceAll("\\s+(?:toward|towards|for|and)\\b.*$", "").replaceAll("[.,;:]+$", "").trim();
        return road.matches("(?i)the (left|right)\\b.*") ? "" : road;   // "arrived ..., on the left" names no road
    }

    /** A road name that fits the display's 24 characters: the usual abbreviations when it is too long. */
    static String shortRoad(String road) {
        if (road.length() <= 24) return road;
        return road.replaceAll("\\bMahatma Gandhi\\b", "MG").replaceAll("\\bNational Highway\\b", "NH")
                .replaceAll("\\bRoad\\b", "Rd").replaceAll("\\bStreet\\b", "St").replaceAll("\\bAvenue\\b", "Ave")
                .replaceAll("\\bBoulevard\\b", "Blvd").replaceAll("\\bHighway\\b", "Hwy").replaceAll("\\bCross\\b", "Cr")
                .replaceAll("\\bExtension\\b", "Ext").replaceAll("\\bJunction\\b", "Jn").replaceAll("\\bDoctor\\b", "Dr");
    }

    /** "onto Kasturba Road" for a turn with a known road; nothing for the arrival or an unnamed road. */
    static String ontoText(int angle, String road) {
        return angle == ARRIVE || road == null || road.isEmpty() ? "" : "onto " + road;
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

    /**
     * Snaps the rider to the line, looking near the previous segment first. When the heading is known,
     * a stretch running the other way (the return leg of an out-and-back route, the far side of a loop)
     * only wins if it is clearly closer, so the rider is not shown on the wrong leg of the route.
     */
    void snap(double px, double py, double headingDeg, boolean useHeading) {
        double hx = useHeading ? Math.sin(Math.toRadians(headingDeg)) : 0;
        double hy = useHeading ? Math.cos(Math.toRadians(headingDeg)) : 0;
        double best = search(px, py, Math.max(0, seg - 3), Math.min(n - 2, seg + 40), hx, hy);
        if (best > 60) best = search(px, py, 0, n - 2, hx, hy);
        offRoute = best;

        turnAngle = ARRIVE;
        turnName = name;
        distToTurn = Math.max(0, cum[n - 1] - progress);
        for (int k = 0; k < manIdx.length; k++) {
            double at = cum[manIdx[k]];
            if (at > progress + 3) {
                turnAngle = manAngle[k];
                turnName = manName[k];
                distToTurn = at - progress;
                break;
            }
        }
    }

    /** Picks the segment in lo..hi nearest the rider (penalising ones that run against the unit heading hx,hy) and returns its distance. */
    private double search(double px, double py, int lo, int hi, double hx, double hy) {
        double best = Double.MAX_VALUE, bestDist = Double.MAX_VALUE;
        for (int i = lo; i <= hi; i++) {
            double vx = x[i + 1] - x[i], vy = y[i + 1] - y[i], len2 = vx * vx + vy * vy;
            double t = len2 > 0 ? ((px - x[i]) * vx + (py - y[i]) * vy) / len2 : 0;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(x[i] + vx * t - px, y[i] + vy * t - py);
            double score = vx * hx + vy * hy < 0 ? d + WRONG_WAY_M : d;   // more than 90 degrees off our heading
            if (score < best) {
                best = score;
                bestDist = d;
                seg = i;
                progress = cum[i] + t * Math.sqrt(len2);
            }
        }
        return bestDist;
    }

    double remaining() { return Math.max(0, cum[n - 1] - progress); }

    double x(int i) { return x[i]; }
    double y(int i) { return y[i]; }

    /** Index of the route point about `metres` further along than the start of segment `from`. */
    int indexAhead(int from, double metres) {
        double target = cum[from] + metres;
        int i = from;
        while (i < n - 1 && cum[i] < target) i++;
        return i;
    }

    double totalM() { return cum[n - 1]; }

    /** Time left at the pace the routing service predicted, seconds. */
    int remainingTimeS() {
        double total = totalM();
        return total <= 0 ? 0 : (int) Math.round(durationS * remaining() / total);
    }

    /** "Turn right", "Arrive", ... for a turn angle, as the app and the notification say it. */
    static String turnText(int a) {
        if (a == ARRIVE) return "Arrive";
        String side = a > 0 ? "right" : "left";
        int m = Math.abs(a);
        if (m > 150) return "U-turn";
        if (m > 110) return "Sharp " + side;
        if (m < 45) return "Slight " + side;
        return "Turn " + side;
    }

    /** Metres rounded to 10, or kilometres with one decimal. */
    static String distText(double m) {
        if (m < 1000) return Math.round(m / 10) * 10 + " m";
        return String.format(java.util.Locale.US, "%.1f km", m / 1000);
    }

    /** The line from a segment onward as {lat, lon}, thinned evenly to at most maxPoints, for drawing on a map. */
    double[][] outline(int fromSeg, int maxPoints) {
        int count = n - fromSeg, keep = Math.min(count, Math.max(2, maxPoints));
        double[][] out = new double[keep][];
        for (int k = 0; k < keep; k++) {
            int i = fromSeg + (int) Math.round(k * (count - 1) / (double) (keep - 1));
            out[k] = new double[]{lat[i], lon[i]};
        }
        return out;
    }

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
