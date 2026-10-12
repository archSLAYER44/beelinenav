package dev.guts.beelinenav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * Routes and place search. Uses Ola Maps when a key has been set (pasted in the app, or compiled
 * in from ola_key.txt), otherwise the free OpenStreetMap services (OSRM, Nominatim).
 * Callbacks run on the main thread.
 */
final class Router {
    interface RouteCb { void done(NavRoute route, String error); }
    interface SearchCb { void done(List<Place> places, String error); }
    interface MapCb { void done(Bitmap map, String error); }

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
    private static volatile String key = Secrets.OLA_KEY;

    /** Loads the key the user pasted in the app; a key compiled in from ola_key.txt is the fallback. */
    static void loadKey(Context ctx) {
        String saved = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ola_key", "");
        key = saved.isEmpty() ? Secrets.OLA_KEY : saved;
    }

    static void saveKey(Context ctx, String k) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("ola_key", k.trim()).apply();
        loadKey(ctx);
    }

    static boolean hasKey() {
        return !key.isEmpty();
    }

    static String key() {
        return key;
    }

    static void route(double fromLat, double fromLon, double toLat, double toLon,
                      String name, RouteCb cb) {
        new Thread(() -> {
            try {
                NavRoute r;
                if (hasKey()) {
                    String url = String.format(Locale.US,
                            OLA + "/routing/v1/directions?origin=%.6f,%.6f&destination=%.6f,%.6f"
                                    + "&steps=true&overview=full&api_key=%s",
                            fromLat, fromLon, toLat, toLon, key);
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
                List<Place> out = hasKey() ? searchOla(query, nearLat, nearLon)
                        : searchOsm(query, nearLat, nearLon);
                MAIN.post(() -> cb.done(out, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Search failed" : "Search failed: " + m));
            }
        }).start();
    }

    /**
     * One dark map picture fitted to the path ({lat, lon} points), with a pin on its last point
     * and, if given, a marker at the rider. Needs an Ola Maps key.
     */
    static void mapImage(double[][] path, double[] me, MapCb cb) {
        new Thread(() -> {
            try {
                StringBuilder url = new StringBuilder(OLA
                        + "/tiles/v1/styles/default-dark-standard/static/auto/400x400@2x.png?path=");
                for (int i = 0; i < path.length; i++) {
                    if (i > 0) url.append("%7C");
                    url.append(String.format(Locale.US, "%.5f,%.5f", path[i][1], path[i][0]));
                }
                double[] end = path[path.length - 1];
                url.append("&stroke=white&width=4&padding=0.12");
                url.append(String.format(Locale.US, "&marker=%.5f,%.5f%%7Cred", end[1], end[0]));
                if (me != null) url.append(String.format(Locale.US, "&marker=%.5f,%.5f%%7Cblue", me[1], me[0]));
                url.append("&api_key=").append(key);
                byte[] png = requestBytes("GET", url.toString());
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp == null) throw new IOException("not an image");
                MAIN.post(() -> cb.done(bmp, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Map failed" : "Map failed: " + m));
            }
        }).start();
    }

    private static List<Place> searchOla(String query, double nearLat, double nearLon) throws Exception {
        String url = OLA + "/places/v1/autocomplete?input=" + URLEncoder.encode(query, "UTF-8");
        if (!Double.isNaN(nearLat)) url += String.format(Locale.US, "&location=%.6f,%.6f", nearLat, nearLon);
        url += "&api_key=" + key;
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
        return new String(requestBytes(method, url), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] requestBytes(String method, String url) throws IOException {
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
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
