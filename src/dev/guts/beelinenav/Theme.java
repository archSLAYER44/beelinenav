package dev.guts.beelinenav;

import android.content.Context;
import android.location.Location;

import java.util.Calendar;

/** Day or night look: automatic from sunrise and sunset at the rider's position, or forced by the user. */
final class Theme {
    static final int AUTO = 0, LIGHT = 1, DARK = 2;
    private static int mode = AUTO;

    static void load(Context ctx) {
        mode = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", AUTO);
    }

    static void setMode(Context ctx, int m) {
        mode = m;
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("theme", m).apply();
    }

    static int mode() {
        return mode;
    }

    static String modeName() {
        return mode == LIGHT ? "LIGHT" : mode == DARK ? "DARK" : "AUTO";
    }

    /** True when the screens should be dark-on-light. Without a fix, day is 06:00 to 18:00. */
    static boolean isLight(Location fix) {
        if (mode != AUTO) return mode == LIGHT;
        Calendar c = Calendar.getInstance();
        double now = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        double[] sun = fix == null ? null : sunTimes(fix.getLatitude(), fix.getLongitude(), c);
        double rise = sun == null ? 6.0 : wrap(sun[0] - 0.33);   // twilight is still bright enough
        double set = sun == null ? 18.0 : wrap(sun[1] + 0.33);
        return rise < set ? now >= rise && now < set : now >= rise || now < set;
    }

    private static double wrap(double h) {
        return ((h % 24) + 24) % 24;
    }

    /** Sunrise and sunset as local hours (NOAA approximation, a few minutes off at most), or null in polar day/night. */
    static double[] sunTimes(double lat, double lon, Calendar c) {
        double g = 2 * Math.PI / 365 * (c.get(Calendar.DAY_OF_YEAR) - 1 + 0.5);
        double eq = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosHa = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl)) - Math.tan(latR) * Math.tan(decl);
        if (cosHa < -1 || cosHa > 1) return null;
        double ha = Math.toDegrees(Math.acos(cosHa));
        double tz = (c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / 3600000.0;
        return new double[]{(720 - 4 * (lon + ha) - eq) / 60 + tz, (720 - 4 * (lon - ha) - eq) / 60 + tz};
    }
}
