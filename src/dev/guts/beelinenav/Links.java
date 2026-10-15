package dev.guts.beelinenav;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what another app shared into a destination: a geo: link, a Google / Apple / OpenStreetMap
 * link, a bare "lat, lon", or just a place name. Plain Java, so it can be tested on a computer.
 */
final class Links {
    /** Where to go: either lat/lon are set, or url still needs its redirects followed, or query needs a search. */
    static final class Target {
        String name;
        double lat = Double.NaN, lon = Double.NaN;
        String url, query;

        boolean hasPoint() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+");
    private static final Pattern GEO = Pattern.compile(
            "^geo:(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)(?:,[^?]*)?(?:\\?(.*))?$", Pattern.DOTALL);
    private static final Pattern PAIR = Pattern.compile("(-?\\d{1,2}\\.\\d+)\\s*,\\s*(-?\\d{1,3}\\.\\d+)");
    private static final Pattern LABEL = Pattern.compile("\\(([^)]+)\\)");
    private static final Pattern PLACE_IN_PATH = Pattern.compile("/(?:place|search)/([^/@?]+)");
    private static final Pattern CONSENT = Pattern.compile("[?&]continue=([^&]+)");
    private static final Pattern[] IN_URL = {   // most exact first
            Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),                        // the pin of a Google Maps place
            Pattern.compile("[?&](?:q|query|ll|sll|daddr|destination|center)=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)"),
            Pattern.compile("[?&]mlat=(-?\\d+\\.\\d+)&mlon=(-?\\d+\\.\\d+)"),                 // OpenStreetMap marker
            Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),                              // the map's centre: close enough
    };

    static Target parse(String shared) {
        Target t = new Target();
        String text = shared == null ? "" : shared.trim();
        if (text.isEmpty()) return t;

        Matcher g = GEO.matcher(text);
        if (g.matches()) {
            String q = g.group(3) == null ? "" : param(g.group(3), "q");
            Matcher p = PAIR.matcher(q);
            if (p.find()) {                                   // geo:0,0?q=lat,lon(Label)
                t.lat = Double.parseDouble(p.group(1));
                t.lon = Double.parseDouble(p.group(2));
                Matcher l = LABEL.matcher(q);
                if (l.find()) t.name = clean(l.group(1));
            } else {
                double lat = Double.parseDouble(g.group(1)), lon = Double.parseDouble(g.group(2));
                if (lat != 0 || lon != 0) {
                    t.lat = lat;
                    t.lon = lon;
                }
                if (!q.isEmpty()) {
                    if (t.hasPoint()) t.name = clean(q); else t.query = clean(q);
                }
            }
            return check(t);
        }

        Matcher u = URL_IN_TEXT.matcher(text);
        String url = u.find() ? u.group() : null;
        String words = clean(text.replaceAll("https?://\\S+", " "));   // what is left is the name, usually
        if (url != null) {
            for (Pattern pat : IN_URL) {
                Matcher m = pat.matcher(url);
                if (m.find()) {
                    t.lat = Double.parseDouble(m.group(1));
                    t.lon = Double.parseDouble(m.group(2));
                    break;
                }
            }
            Matcher pl = PLACE_IN_PATH.matcher(url);
            if (pl.find()) t.name = clean(decode(pl.group(1)));
            int qm = url.indexOf('?');
            if (t.name == null && qm > 0) {                   // maps.apple.com/?ll=..&q=Name
                String q = param(url.substring(qm + 1).replaceAll("#.*", ""), "q");
                if (!q.isEmpty() && !PAIR.matcher(q).find()) t.name = clean(q);
            }
            if (t.name == null && !words.isEmpty()) t.name = words;
            if (!t.hasPoint()) t.url = url;                   // a short link, or one without coordinates: follow it
            return check(t);
        }

        Matcher p = PAIR.matcher(text);
        if (p.find()) {                                       // "12.9716, 77.5946" with maybe a name around it
            t.lat = Double.parseDouble(p.group(1));
            t.lon = Double.parseDouble(p.group(2));
            String rest = clean(text.replace(p.group(), " ").replaceAll("[()\\[\\]:]", " "));
            if (!rest.isEmpty()) t.name = rest;
            return check(t);
        }
        t.query = words;
        return check(t);
    }

    /**
     * Follows a short link's redirects (maps.app.goo.gl and the like) to the long URL that carries the
     * coordinates. Blocking: call it off the main thread.
     */
    static String follow(String url) throws IOException {
        String at = url;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(at).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) BeeNav");
                int code = c.getResponseCode();
                String next = c.getHeaderField("Location");
                if (code / 100 != 3 || next == null) return at;
                at = new URL(new URL(at), next).toString();
                Matcher m = CONSENT.matcher(at);              // Google's cookie page wraps the real link in some regions
                if (at.contains("consent.google") && m.find()) at = decode(m.group(1));
            } finally {
                c.disconnect();
            }
        }
        return at;
    }

    private static Target check(Target t) {
        if (t.hasPoint() && (Math.abs(t.lat) > 90 || Math.abs(t.lon) > 180)) t.lat = t.lon = Double.NaN;
        if (t.name != null && t.name.isEmpty()) t.name = null;
        if (t.query != null && t.query.isEmpty()) t.query = null;
        return t;
    }

    /** First line only, tidied, at most 60 characters. */
    private static String clean(String s) {
        String line = s.trim().split("\\R", 2)[0].replaceAll("\\s+", " ").trim();
        return line.length() > 60 ? line.substring(0, 60).trim() : line;
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return decode(kv.substring(eq + 1));
        }
        return "";
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;   // a stray % sign: show it as it is
        }
    }
}
