package dev.guts.beelinenav;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Routes, place search and map pictures from one of three services: Ola Maps or Google Maps when
 * there is a key for it (pasted under Settings, or compiled in from ola_key.txt / google_key.txt),
 * otherwise the free OpenStreetMap services (OSRM for routes, Nominatim for search, no pictures).
 * Callbacks run on the main thread.
 */
final class Router {
    interface RouteCb { void done(NavRoute route, String error); }
    interface SearchCb { void done(List<Place> places, String error); }
    interface MapCb { void done(Bitmap map, String error); }
    interface UrlCb { void done(String url, String error); }

    static final class Place {
        final String name;
        final double lat, lon;
        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Service ids, as stored in the settings. */
    static final String OSM = "osm", OLA_MAPS = "ola", GOOGLE = "google";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String AGENT = "BeelineNav/1.0 (personal ESP32 bike display)";
    private static final String OLA = "https://api.olamaps.io";
    private static final String GOOGLE_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes";
    private static final String GOOGLE_PLACES = "https://places.googleapis.com/v1/places:searchText";
    private static final String GOOGLE_STATIC = "https://maps.googleapis.com/maps/api/staticmap";
    /** Google's map picture at night, in the app's dark look ("%7C" is the "|" separator). */
    private static final String[] GOOGLE_DARK = {
            "feature:all%7Celement:geometry%7Ccolor:0x1c1c1c",
            "feature:all%7Celement:labels.text.fill%7Ccolor:0x9a9a9a",
            "feature:all%7Celement:labels.text.stroke%7Ccolor:0x1c1c1c",
            "feature:road%7Celement:geometry%7Ccolor:0x383838",
            "feature:road%7Celement:geometry.stroke%7Ccolor:0x2a2a2a",
            "feature:water%7Celement:geometry%7Ccolor:0x0f1a26",
            "feature:poi%7Celement:labels%7Cvisibility:off",
            "feature:transit%7Cvisibility:off"};

    private static volatile String olaKey = Secrets.OLA_KEY, googleKey = Secrets.GOOGLE_KEY;
    private static volatile boolean olaPasted, googlePasted;
    private static volatile String chosen = "";     // the service picked under Settings; "" = automatic

    // ---------- settings ----------

    /** Loads the keys the user pasted (a compiled-in key is the fallback) and the chosen service. */
    static void load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String o = p.getString("ola_key", ""), g = p.getString("google_key", "");
        olaPasted = !o.isEmpty();
        googlePasted = !g.isEmpty();
        olaKey = olaPasted ? o : Secrets.OLA_KEY;
        googleKey = googlePasted ? g : Secrets.GOOGLE_KEY;
        chosen = p.getString("provider", "");
    }

    /** Stores a pasted key for Ola or Google on the phone only; an empty key removes the pasted one. */
    static void saveKey(Context ctx, String service, String k) {
        prefs(ctx).edit().putString(service.equals(GOOGLE) ? "google_key" : "ola_key", k.trim()).apply();
        load(ctx);
    }

    static void saveProvider(Context ctx, String service) {
        prefs(ctx).edit().putString("provider", service).apply();
        load(ctx);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** The service picked under Settings, or "" for automatic. */
    static String chosen() {
        return chosen;
    }

    static boolean hasKey(String service) {
        return service.equals(GOOGLE) ? !googleKey.isEmpty() : service.equals(OLA_MAPS) && !olaKey.isEmpty();
    }

    /** Where a service's key came from: "pasted", "built in", or "" when there is none. */
    static String keySource(String service) {
        if (!hasKey(service)) return "";
        return (service.equals(GOOGLE) ? googlePasted : olaPasted) ? "pasted" : "built in";
    }

    /** The service in use: the chosen one when its key is there, else the first with a key, else OpenStreetMap. */
    static String provider() {
        if (chosen.equals(OSM)) return OSM;
        if (chosen.equals(OLA_MAPS) && hasKey(OLA_MAPS)) return OLA_MAPS;
        if (chosen.equals(GOOGLE) && hasKey(GOOGLE)) return GOOGLE;
        return hasKey(OLA_MAPS) ? OLA_MAPS : hasKey(GOOGLE) ? GOOGLE : OSM;
    }

    static String name(String service) {
        return service.equals(OLA_MAPS) ? "Ola Maps" : service.equals(GOOGLE) ? "Google Maps" : "OpenStreetMap";
    }

    /** Map pictures and the zoomable map need Ola or Google. */
    static boolean hasMap() {
        return !provider().equals(OSM);
    }

    /** The side roads for the display's junction view come from Ola's vector tiles only. */
    static boolean olaTiles() {
        return provider().equals(OLA_MAPS);
    }

    /** Key of the service in use, for the zoomable map page. */
    static String key() {
        String p = provider();
        return p.equals(GOOGLE) ? googleKey : p.equals(OLA_MAPS) ? olaKey : "";
    }

    static String olaKey() {
        return olaKey;
    }

    // ---------- routes ----------

    static void route(double fromLat, double fromLon, double toLat, double toLon,
                      String name, RouteCb cb) {
        new Thread(() -> {
            try {
                NavRoute r;
                String p = provider();
                if (p.equals(OLA_MAPS)) {
                    String url = String.format(Locale.US,
                            OLA + "/routing/v1/directions?origin=%.6f,%.6f&destination=%.6f,%.6f"
                                    + "&steps=true&overview=full&api_key=%s",
                            fromLat, fromLon, toLat, toLon, olaKey);
                    r = NavRoute.fromOla(new JSONObject(request("POST", url, null, null)), name, toLat, toLon);
                } else if (p.equals(GOOGLE)) {
                    r = routeGoogle(fromLat, fromLon, toLat, toLon, name);
                } else {
                    String url = String.format(Locale.US,
                            "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
                                    + "?steps=true&geometries=geojson&overview=false",
                            fromLon, fromLat, toLon, toLat);
                    JSONObject o = new JSONObject(request("GET", url, null, null));
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

    /**
     * Google Routes API. Plain DRIVE with no traffic keeps the call in the "Compute Routes Essentials"
     * tier, which has the largest free allowance.
     */
    private static NavRoute routeGoogle(double fromLat, double fromLon, double toLat, double toLon, String name)
            throws Exception {
        String body = String.format(Locale.US,
                "{\"origin\":{\"location\":{\"latLng\":{\"latitude\":%.6f,\"longitude\":%.6f}}},"
                        + "\"destination\":{\"location\":{\"latLng\":{\"latitude\":%.6f,\"longitude\":%.6f}}},"
                        + "\"travelMode\":\"DRIVE\",\"polylineQuality\":\"HIGH_QUALITY\","
                        + "\"languageCode\":\"en\",\"units\":\"METRIC\"}",
                fromLat, fromLon, toLat, toLon);
        String[][] headers = {
                {"X-Goog-Api-Key", googleKey},
                {"X-Goog-FieldMask", "routes.duration,routes.polyline.encodedPolyline,"
                        + "routes.legs.steps.startLocation,routes.legs.steps.navigationInstruction"}};
        return NavRoute.fromGoogle(new JSONObject(request("POST", GOOGLE_ROUTES, body, headers)), name, toLat, toLon);
    }

    /** Follows a shared short link to the long URL that carries the coordinates; callback on the main thread. */
    static void resolve(String url, UrlCb cb) {
        new Thread(() -> {
            try {
                String out = Links.follow(url);
                MAIN.post(() -> cb.done(out, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Could not open the link" : "Could not open the link: " + m));
            }
        }).start();
    }

    // ---------- search ----------

    /** nearLat/nearLon bias results towards the rider; pass NaN when there is no fix yet. */
    static void search(String query, double nearLat, double nearLon, SearchCb cb) {
        new Thread(() -> {
            try {
                String p = provider();
                List<Place> out = p.equals(OLA_MAPS) ? searchOla(query, nearLat, nearLon)
                        : p.equals(GOOGLE) ? searchGoogle(query, nearLat, nearLon)
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
        url += "&api_key=" + olaKey;
        JSONArray a = new JSONObject(request("GET", url, null, null)).optJSONArray("predictions");
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

    /** Google Places (New) text search: name, address and position in one call. */
    private static List<Place> searchGoogle(String query, double nearLat, double nearLon) throws Exception {
        JSONObject body = new JSONObject().put("textQuery", query).put("pageSize", 6).put("languageCode", "en");
        if (!Double.isNaN(nearLat)) {
            body.put("locationBias", new JSONObject().put("circle", new JSONObject()
                    .put("center", new JSONObject().put("latitude", nearLat).put("longitude", nearLon))
                    .put("radius", 50000.0)));
        }
        String[][] headers = {
                {"X-Goog-Api-Key", googleKey},
                {"X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location"}};
        JSONArray a = new JSONObject(request("POST", GOOGLE_PLACES, body.toString(), headers)).optJSONArray("places");
        List<Place> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            JSONObject loc = p.optJSONObject("location");
            if (loc == null) continue;
            JSONObject dn = p.optJSONObject("displayName");
            String title = dn == null ? "" : dn.optString("text");
            String addr = p.optString("formattedAddress");
            String name = title.isEmpty() ? addr : addr.isEmpty() ? title : title + ", " + addr;
            out.add(new Place(name, loc.getDouble("latitude"), loc.getDouble("longitude")));
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
        JSONArray a = new JSONArray(request("GET", url, null, null));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.getJSONObject(i);
            out.add(new Place(p.getString("display_name"), p.getDouble("lat"), p.getDouble("lon")));
        }
        return out;
    }

    // ---------- map pictures ----------

    /**
     * One map picture fitted to the path ({lat, lon} points), with a pin on its last point and,
     * if given, a marker at the rider. Light or dark to match the app. Needs Ola or Google.
     */
    static void mapImage(double[][] path, double[] me, boolean light, MapCb cb) {
        new Thread(() -> {
            try {
                String p = provider();
                byte[] png = p.equals(OLA_MAPS) ? olaMap(path, me, light)
                        : p.equals(GOOGLE) ? googleMap(path, me, light) : null;
                if (png == null) throw new IOException("needs an Ola Maps or Google Maps key");
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp == null) throw new IOException("not an image");
                MAIN.post(() -> cb.done(bmp, null));
            } catch (Exception e) {
                String m = e.getMessage();
                MAIN.post(() -> cb.done(null, m == null ? "Map failed" : "Map failed: " + m));
            }
        }).start();
    }

    private static byte[] olaMap(double[][] path, double[] me, boolean light) throws IOException {
        StringBuilder url = new StringBuilder(OLA
                + "/tiles/v1/styles/" + (light ? "default-light-standard" : "default-dark-standard")
                + "/static/auto/400x400@2x.png?path=");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) url.append("%7C");
            url.append(String.format(Locale.US, "%.5f,%.5f", path[i][1], path[i][0]));   // lon,lat
        }
        double[] end = path[path.length - 1];
        url.append(light ? "&stroke=black" : "&stroke=white").append("&width=4&padding=0.12");
        url.append(String.format(Locale.US, "&marker=%.5f,%.5f%%7Cred", end[1], end[0]));
        if (me != null) url.append(String.format(Locale.US, "&marker=%.5f,%.5f%%7Cblue", me[1], me[0]));
        url.append("&api_key=").append(olaKey);
        return request("GET", url.toString(), null, null, true);
    }

    private static byte[] googleMap(double[][] path, double[] me, boolean light) throws IOException {
        StringBuilder url = new StringBuilder(GOOGLE_STATIC + "?size=400x400&scale=2&maptype=roadmap");
        url.append("&path=color:").append(light ? "0x111111ff" : "0xffffffff").append("%7Cweight:5");
        for (double[] p : path) url.append(String.format(Locale.US, "%%7C%.5f,%.5f", p[0], p[1]));   // lat,lng
        double[] end = path[path.length - 1];
        url.append(String.format(Locale.US, "&markers=color:red%%7C%.5f,%.5f", end[0], end[1]));
        if (me != null) url.append(String.format(Locale.US, "&markers=color:blue%%7Csize:small%%7C%.5f,%.5f", me[0], me[1]));
        if (!light) for (String s : GOOGLE_DARK) url.append("&style=").append(s);
        url.append("&key=").append(googleKey);
        return request("GET", url.toString(), null, null, true);
    }

    // ---------- HTTP ----------
    // Errors thrown here never include the URL, because it may carry an API key.

    private static String request(String method, String url, String body, String[][] headers) throws IOException {
        return new String(request(method, url, body, headers, true), StandardCharsets.UTF_8);
    }

    /** GET of a binary resource such as a map tile; gunzipped when the server sent it compressed. */
    static byte[] fetch(String url) throws IOException {
        return request("GET", url, null, null, true);
    }

    private static byte[] request(String method, String url, String body, String[][] headers, boolean unused)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", AGENT);
            c.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
            if (headers != null) for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
            if (method.equals("POST")) {
                byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
                if (body != null) c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("map service key rejected");
            if (code == 429) throw new IOException("map service limit reached");
            if (code != 200) throw new IOException("HTTP " + code + errorDetail(c));
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** The service's own explanation of a failed call, when it sent one as JSON ("error": {"message": ...}). */
    private static String errorDetail(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            if (in == null) return "";
            JSONObject err = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8)).optJSONObject("error");
            String m = err == null ? "" : err.optString("message", "");
            return m.isEmpty() ? "" : ": " + (m.length() > 120 ? m.substring(0, 120) : m);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
        byte[] body = out.toByteArray();
        if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {   // gzip magic
            try (InputStream gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                ByteArrayOutputStream plain = new ByteArrayOutputStream();
                for (int k; (k = gz.read(buf)) > 0; ) plain.write(buf, 0, k);
                body = plain.toByteArray();
            }
        }
        return body;
    }
}
