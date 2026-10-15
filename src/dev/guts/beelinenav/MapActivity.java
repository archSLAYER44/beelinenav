package dev.guts.beelinenav;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Full-screen zoomable map with the route drawn on it: MapLibre with Ola Maps tiles, or the Google Maps
 * JavaScript API when Google is the service in use.
 * Opened from the map picture; loads many tiles, so only on request.
 */
public final class MapActivity extends Activity {
    private static final String TAG = "BeelineMap";
    private WebView web;

    /** route is {lat, lon} points; me is the rider's {lat, lon} or null. */
    static Intent intent(Context ctx, double[][] route, double[] me, boolean light) {
        double[] flat = new double[route.length * 2];
        for (int i = 0; i < route.length; i++) {
            flat[2 * i] = route[i][0];
            flat[2 * i + 1] = route[i][1];
        }
        return new Intent(ctx, MapActivity.class).putExtra("route", flat).putExtra("me", me).putExtra("light", light);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        boolean light = getIntent().getBooleanExtra("light", false);
        int bg = light ? Color.WHITE : Color.BLACK;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, m.message() + " (" + m.lineNumber() + ")");
                return true;
            }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void close() {
                runOnUiThread(MapActivity.this::finish);
            }
        }, "Android");
        setContentView(web);

        String html;
        try {
            if (Router.GOOGLE.equals(Router.provider())) {
                html = asset("map_google.html")
                        .replace("__DATA__", pageData())
                        .replace("__KEY__", Router.key().replace("\"", "").replace("&", ""));
            } else {
                html = asset("map.html")
                        .replace("__DATA__", pageData())
                        .replace("/*CSS*/", asset("maplibre-gl.css"))
                        .replace("/*JS*/", asset("maplibre-gl.js"));
            }
        } catch (IOException e) {
            html = "<body style='background:#000;color:#888;font-family:sans-serif'>Map files missing</body>";
        }
        // An https base gives the page a normal origin, so the tile requests are plain CORS fetches.
        web.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null);
    }

    /** The key, route and positions as a JSON object literal for the page. */
    private String pageData() {
        double[] route = getIntent().getDoubleArrayExtra("route");
        double[] me = getIntent().getDoubleArrayExtra("me");
        StringBuilder b = new StringBuilder("{\"key\":\"").append(Router.key().replace("\"", ""))
                .append("\",\"light\":").append(getIntent().getBooleanExtra("light", false)).append(",\"route\":[");
        for (int i = 0; route != null && i + 1 < route.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(String.format(Locale.US, "[%.6f,%.6f]", route[i + 1], route[i]));   // GeoJSON order: lon, lat
        }
        b.append("],\"me\":");
        if (me == null) b.append("null");
        else b.append(String.format(Locale.US, "[%.6f,%.6f]", me[1], me[0]));
        return b.append('}').toString();
    }

    private String asset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
