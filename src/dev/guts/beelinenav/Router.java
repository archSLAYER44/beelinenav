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

/** Free OpenStreetMap services: OSRM for routes, Nominatim for place search. Callbacks run on the main thread. */
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

    static void route(double fromLat, double fromLon, double toLat, double toLon,
                      String name, RouteCb cb) {
        new Thread(() -> {
            try {
                String url = String.format(Locale.US,
                        "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
                                + "?steps=true&geometries=geojson&overview=false",
                        fromLon, fromLat, toLon, toLat);
                JSONObject o = new JSONObject(get(url));
                if (!"Ok".equals(o.optString("code"))) throw new IOException("No route found");
                NavRoute r = NavRoute.fromOsrm(o, name, toLat, toLon);
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
                String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q="
                        + URLEncoder.encode(query, "UTF-8");
                if (!Double.isNaN(nearLat)) {
                    url += String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                            nearLon - 0.5, nearLat + 0.5, nearLon + 0.5, nearLat - 0.5);
                }
                JSONArray a = new JSONArray(get(url));
                List<Place> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject p = a.getJSONObject(i);
                    out.add(new Place(p.getString("display_name"),
                            p.getDouble("lat"), p.getDouble("lon")));
                }
                MAIN.post(() -> cb.done(out, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Search failed" : "Search failed: " + m));
            }
        }).start();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", AGENT);
            int code = c.getResponseCode();
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
