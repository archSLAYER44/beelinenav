package dev.guts.beelinenav;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The last few destinations, newest first, kept on the phone so a usual ride is one tap away. */
final class Recents {
    private static final int MAX = 8;
    private static final double SAME_M = 50;   // two pins this close are the same place

    static List<Router.Place> list(Context ctx) {
        List<Router.Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString("recents", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Router.Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon")));
            }
        } catch (JSONException ignored) {
            // unreadable list: start afresh
        }
        return out;
    }

    /** Puts a place at the top, replacing any older entry for the same spot. */
    static void add(Context ctx, String name, double lat, double lon) {
        List<Router.Place> l = list(ctx);
        for (int i = l.size() - 1; i >= 0; i--) {
            Router.Place p = l.get(i);
            if (p.name.equals(name) || metres(p.lat, p.lon, lat, lon) < SAME_M) l.remove(i);
        }
        l.add(0, new Router.Place(name, lat, lon));
        while (l.size() > MAX) l.remove(l.size() - 1);
        save(ctx, l);
    }

    /** A saved place under a fixed key ("home" or "work"), or null when not set. */
    static Router.Place favourite(Context ctx, String key) {
        String s = prefs(ctx).getString("fav_" + key, null);
        if (s == null) return null;
        String[] f = s.split(",", 3);          // the name comes last, so its own commas are safe
        try {
            return new Router.Place(f[2], Double.parseDouble(f[0]), Double.parseDouble(f[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void setFavourite(Context ctx, String key, Router.Place p) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (p == null) e.remove("fav_" + key);
        else e.putString("fav_" + key, p.lat + "," + p.lon + "," + p.name);
        e.apply();
    }

    static void remove(Context ctx, int index) {
        List<Router.Place> l = list(ctx);
        if (index >= 0 && index < l.size()) {
            l.remove(index);
            save(ctx, l);
        }
    }

    private static void save(Context ctx, List<Router.Place> l) {
        JSONArray a = new JSONArray();
        try {
            for (Router.Place p : l) a.put(new JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon));
        } catch (JSONException ignored) {
            // only plain numbers and strings go in; this cannot happen
        }
        prefs(ctx).edit().putString("recents", a.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dx = (lon2 - lon1) * 111320 * Math.cos(Math.toRadians(lat1)), dy = (lat2 - lat1) * 110540;
        return Math.hypot(dx, dy);
    }
}
