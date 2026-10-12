package dev.guts.beelinenav;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Routes and place search. Uses Ola Maps when the build has a key (ola_key.txt), otherwise the
 * free OpenStreetMap services (OSRM, Nominatim). Callbacks run on the main thread.
 */
final class Router {
    interface RouteCb { void done(NavRoute route, String error); }
    interface SearchCb { void done(List<Place> places, String error); }

    static final class Place {
        final String name;
        final double lat, lon;
        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String AGENT = "BeelineNav/1.0 (personal ESP32 bike display)";
    private static final String OLA = "https://api.olamaps.io";
    private static final boolean USE_OLA = !Secrets.OLA_KEY.isEmpty();

    static void route(double fromLat, double fromLon, double toLat, double toLon,
                      String name, RouteCb cb) {
        new Thread(() -> {
            try {
                NavRoute r;
                if (USE_OLA) {
                    String url = String.format(Locale.US,
                            OLA + "/routing/v1/directions?origin=%.6f,%.6f&destination=%.6f,%.6f"
                                    + "&steps=true&overview=full&api_key=%s",
                            fromLat, fromLon, toLat, toLon, Secrets.OLA_KEY);
                    r = NavRoute.fromOla(new JSONObject(request("POST", url)), name, toLat, toLon);
                } else {
                    String url = String.format(Locale.US,
                            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
                                    + "?steps=true&geometries=geojson&overview=false",
                            fromLon, fromLat, toLon, toLat);
                    JSONObject o = new JSONObject(request("GET", url));
                    if (!"Ok".equals(o.optString("code"))) throw new IOException("No route found");
                    r = NavRoute.fromOsrm(o, name, toLat, toLon);
                }
                MAIN.post(() -> cb.done(r, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Routing failed" : "Routing failed: " + m));
            }
        }).start();
    }

    /** nearLat/nearLon bias results towards the rider; pass NaN when there is no fix yet. */
    static void search(String query, double nearLat, double nearLon, SearchCb cb) {
        new Thread(() -> {
            try {
                List<Place> out = USE_OLA ? searchOla(query, nearLat, nearLon)
                        : searchOsm(query, nearLat, nearLon);
                MAIN.post(() -> cb.done(out, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Search failed" : "Search failed: " + m));
            }
        }).start();
    }

    private static List<Place> searchOla(String query, double nearLat, double nearLon) throws Exception {
        String url = OLA + "/places/v1/autocomplete?input=" + URLEncoder.encode(query, "UTF-8");
        if (!Double.isNaN(nearLat)) url += String.format(Locale.US, "&location=%.6f,%.6f", nearLat, nearLon);
        url += "&api_key=" + Secrets.OLA_KEY;
        JSONArray a = new JSONObject(request("GET", url)).optJSONArray("predictions");
        List<Place> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            JSONObject geo = p.optJSONObject("geometry");
            JSONObject loc = geo == null ? null : geo.optJSONObject("location");
            if (loc == null) continue;
            out.add(new Place(p.optString("description"), loc.getDouble("lat"), loc.getDouble("lng")));
        }
        return out;
    }

    private static List<Place> searchOsm(String query, double nearLat, double nearLon) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q="
                + URLEncoder.encode(query, "UTF-8");
        if (!Double.isNaN(nearLat)) {
            url += String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                    nearLon - 0.5, nearLat + 0.5, nearLon + 0.5, nearLat - 0.5);
        }
        JSONArray a = new JSONArray(request("GET", url));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            out.add(new Place(p.getString("display_name"), p.getDouble("lat"), p.getDouble("lon")));
        }
        return out;
    }

    // Errors thrown here never include the URL, because it carries the API key.
    private static String request(String method, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", AGENT);
            c.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
            if (method.equals("POST")) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("map service key rejected");
            if (code == 429) throw new IOException("map service limit reached");
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
