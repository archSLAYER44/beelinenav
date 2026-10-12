package dev.guts.beelinenav;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** BeeNav control screen: time to arrive, next turn, destination search. Light by day, dark by night. */
public final class MainActivity extends Activity implements NavService.Listener {
    private static final Pattern LAT_LON =
            Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    private TextView status, bigView, bigLabel, subLabel, turnView, destView;
    private EditText input;
    private LinearLayout results;
    private TextView stopBtn, keyBtn, mapBtn;
    private LinearLayout dash, navRow;
    private int panelGen;          // bumped whenever the panel changes, so late replies are dropped
    private boolean light;
    private int bg, fg, grey, line;   // colours of the current theme

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Router.loadKey(this);
        Theme.load(this);
        applyTheme();
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavService.ui = this;
        refresh();
    }

    @Override
    protected void onPause() {
        if (NavService.ui == this) NavService.ui = null;
        super.onPause();
    }

    @Override
    public void onNavState() {
        refresh();
    }

    // ---------- permissions and service start ----------

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        need.add(Manifest.permission.ACCESS_COARSE_LOCATION);   // Android 12+ ignores a fine-only request
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN);
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        need.add(Manifest.permission.READ_PHONE_STATE);   // incoming calls on the display; optional
        need.add(Manifest.permission.READ_CALL_LOG);      // Android 9+ hides the caller's number behind this one
        need.add(Manifest.permission.READ_CONTACTS);      // the caller's name

        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) startNavService();
        else requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        boolean ok = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 31) {
            ok &= checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (ok) startNavService();
        else status.setText("Location and Bluetooth permissions are needed. Allow them in Settings, then reopen.");
    }

    private void startNavService() {
        startForegroundService(new Intent(this, NavService.class));
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a != null && !a.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (RuntimeException ignored) {
                // the status line already says Bluetooth is off
            }
        }
        refresh();
    }

    // ---------- theme ----------

    /** Picks the colours for now and rebuilds the screen with them. */
    private void applyTheme() {
        NavService s = NavService.instance;
        light = Theme.isLight(s == null ? null : s.last);
        bg = light ? Color.WHITE : Color.BLACK;
        fg = light ? Color.BLACK : Color.WHITE;
        grey = light ? 0xFF6E6E6E : 0xFF8A8A8A;
        line = light ? 0xFFCCCCCC : 0xFF3A3A3A;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        String typed = input == null ? "" : input.getText().toString();
        setContentView(buildUi());
        input.setText(typed);
    }

    // ---------- state -> screen ----------

    private void refresh() {
        NavService s = NavService.instance;
        if (light != Theme.isLight(s == null ? null : s.last)) applyTheme();
        if (s == null) {
            bigView.setText("--");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String gps = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? "Location is off"
                : s.last == null ? "Waiting for GPS"
                : String.format(Locale.US, "%s ±%.0f m",
                        LocationManager.GPS_PROVIDER.equals(s.last.getProvider()) ? "GPS" : "Rough location",
                        s.last.getAccuracy());
        String call = s.callState != 1 ? ""
                : "Incoming call" + (s.callName.isEmpty() ? "" : ": " + s.callName) + "  ·  ";
        status.setText(call + s.ble.state + "  ·  " + gps + "  ·  b" + Secrets.BUILD);

        long nowMs = System.currentTimeMillis();
        String kmh = s.last == null ? "" : Math.round(s.speed * 3.6f) + " km/h";
        NavRoute r = s.route;
        if (r != null) {
            int secs = r.remainingTimeS();
            boolean hours = secs >= 3600;
            bigView.setText(hours ? String.format(Locale.US, "%d:%02d", secs / 3600, secs % 3600 / 60)
                    : String.valueOf(Math.max(1, Math.round(secs / 60f))));
            bigLabel.setText(hours ? "HOURS LEFT" : "MIN LEFT");
            subLabel.setText("Arrive " + clock(nowMs + secs * 1000L) + (kmh.isEmpty() ? "" : "  ·  " + kmh));
        } else {
            boolean h24 = DateFormat.is24HourFormat(this);
            Date d = new Date(nowMs);
            bigView.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", d));
            bigLabel.setText(h24 ? "TIME" : DateFormat.format("a", d).toString().toUpperCase(Locale.US));
            subLabel.setText(kmh);
        }

        if (r != null) {
            turnView.setText(turnText(r.turnAngle) + " in " + dist(r.distToTurn));
            String extra = s.message.isEmpty() ? dist(r.remaining()) + " left" : s.message;
            destView.setText(s.destName + "\n" + extra);
        } else if (s.hasDest) {
            turnView.setText(s.message);
            destView.setText(s.destName);
        } else {
            turnView.setText("No destination");
            destView.setText("");
        }
        navRow.setVisibility(s.hasDest ? View.VISIBLE : View.GONE);
        mapBtn.setVisibility(r != null && Router.hasKey() ? View.VISIBLE : View.GONE);
    }

    private String clock(long ms) {
        return DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", new Date(ms)).toString();
    }

    private static String turnText(int a) {
        if (a == NavRoute.ARRIVE) return "Arrive";
        String side = a > 0 ? "right" : "left";
        int m = Math.abs(a);
        if (m > 150) return "U-turn";
        if (m > 110) return "Sharp " + side;
        if (m < 45) return "Slight " + side;
        return "Turn " + side;
    }

    private static String dist(double m) {
        if (m < 1000) return Math.round(m / 10) * 10 + " m";
        return String.format(Locale.US, "%.1f km", m / 1000);
    }

    // ---------- destination search ----------

    private void onSearch() {
        NavService s = NavService.instance;
        String q = input.getText().toString().trim();
        if (s == null || q.isEmpty()) return;
        hideKeyboard();

        Matcher m = LAT_LON.matcher(q);
        if (m.matches()) {
            openPreview(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), q);
            return;
        }
        showMessage("Searching");
        double lat = s.last == null ? Double.NaN : s.last.getLatitude();
        double lon = s.last == null ? Double.NaN : s.last.getLongitude();
        int gen = panelGen;
        Router.search(q, lat, lon, (places, err) -> {
            if (gen != panelGen) return;
            results.removeAllViews();
            if (places == null) {
                showMessage(err);
            } else if (places.isEmpty()) {
                showMessage("Nothing found");
            } else {
                for (Router.Place p : places) results.addView(resultRow(p));
            }
        });
    }

    private View resultRow(Router.Place p) {
        TextView t = text(p.name, 15, fg);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(underline());
        t.setOnClickListener(v -> {
            int comma = p.name.indexOf(',');
            openPreview(p.lat, p.lon, comma > 0 ? p.name.substring(0, comma) : p.name);
        });
        return t;
    }

    private void clearPanel() {
        panelGen++;
        results.removeAllViews();
        dash.setVisibility(View.VISIBLE);
    }

    private void showMessage(String m) {
        clearPanel();
        TextView t = text(m, 14, grey);
        t.setPadding(0, dp(14), 0, 0);
        results.addView(t);
    }

    // ---------- route preview and map ----------

    private void openPreview(double lat, double lon, String name) {
        NavService s = NavService.instance;
        if (s == null) return;
        showMessage("Finding route");
        int gen = panelGen;
        s.preview(lat, lon, name, (r, err) -> {
            if (gen != panelGen) return;
            if (r == null) showMessage(err); else showPanel(r, true);
        });
    }

    /** Map picture of the route, with START / CANCEL before riding or CLOSE while riding. */
    private void showPanel(NavRoute r, boolean preview) {
        NavService s = NavService.instance;
        if (s == null) return;
        clearPanel();
        dash.setVisibility(View.GONE);
        int gen = panelGen;
        int from = preview ? 0 : r.seg;
        double[] me = s.last == null ? null : new double[]{s.last.getLatitude(), s.last.getLongitude()};

        ImageView map = new ImageView(this);
        map.setAdjustViewBounds(true);
        map.setVisibility(View.GONE);
        results.addView(map, new LinearLayout.LayoutParams(-1, -2));
        TextView mapMsg = text(Router.hasKey() ? "Loading map" : "Map needs an Ola Maps key", 12, grey);
        mapMsg.setLetterSpacing(0.2f);
        mapMsg.setPadding(0, dp(10), 0, dp(10));
        results.addView(mapMsg);
        if (Router.hasKey()) {
            Router.mapImage(r.outline(from, 80), me, (bmp, err) -> {
                if (gen != panelGen) return;
                if (bmp == null) {
                    mapMsg.setText(err);
                    return;
                }
                map.setImageBitmap(bmp);
                map.setVisibility(View.VISIBLE);
                mapMsg.setText("TAP MAP TO ZOOM");
            });
            map.setOnClickListener(v -> startActivity(MapActivity.intent(this, r.outline(from, 500), me)));
        }

        TextView name = text(r.name, 20, fg);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        results.addView(name);
        String info = preview ? dist(r.totalM()) + "  ·  " + time(r.durationS) : dist(r.remaining()) + " left";
        TextView infoView = text(info, 14, grey);
        infoView.setPadding(0, dp(4), 0, dp(16));
        results.addView(infoView);

        LinearLayout row = new LinearLayout(this);
        if (preview) {
            TextView start = button("START");
            start.setOnClickListener(v -> {
                NavService svc = NavService.instance;
                if (svc == null) return;
                input.setText("");
                clearPanel();
                svc.start(r);
            });
            row.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView close = button(preview ? "CANCEL" : "CLOSE");
        close.setOnClickListener(v -> clearPanel());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(preview ? -2 : -1, -2);
        if (preview) lp.leftMargin = dp(12);
        row.addView(close, lp);
        results.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private static String time(int seconds) {
        int m = Math.max(1, Math.round(seconds / 60f));
        return m < 60 ? m + " min" : m / 60 + " h " + m % 60 + " min";
    }

    // ---------- map key ----------

    private void showKeyState() {
        keyBtn.setText(Router.hasKey() ? "OLA MAPS" : "ADD MAP KEY");
        keyBtn.setTextColor(Router.hasKey() ? fg : grey);
    }

    /** Lets the user paste their own Ola Maps key; it is stored on the phone only. */
    private void askKey() {
        EditText field = new EditText(this);
        field.setHint("Paste Ola Maps API key");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Ola Maps key")
                .setMessage(Router.hasKey()
                        ? "A key is saved. Paste a new one to replace it, or remove it to use OpenStreetMap."
                        : "Without a key the app uses OpenStreetMap for routes and search.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String k = field.getText().toString().trim();
                    if (k.isEmpty()) return;
                    Router.saveKey(this, k);
                    showKeyState();
                })
                .setNeutralButton("Remove", (d, w) -> {
                    Router.saveKey(this, "");
                    showKeyState();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    // ---------- layout, built in code so the project needs no XML layouts ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("BEENAV", 12, fg);
        title.setLetterSpacing(0.3f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView themeBtn = text(Theme.modeName(), 12, grey);   // AUTO -> LIGHT -> DARK
        themeBtn.setLetterSpacing(0.3f);
        themeBtn.setPadding(dp(12), dp(8), dp(8), dp(8));
        themeBtn.setOnClickListener(v -> {
            Theme.setMode(this, (Theme.mode() + 1) % 3);
            NavService s = NavService.instance;
            if (s != null) s.refreshTheme();
            applyTheme();
            refresh();
        });
        top.addView(themeBtn);
        keyBtn = text("", 12, grey);
        keyBtn.setLetterSpacing(0.3f);
        keyBtn.setPadding(dp(12), dp(8), dp(8), dp(8));
        keyBtn.setOnClickListener(v -> askKey());
        top.addView(keyBtn);
        showKeyState();
        TextView quit = text("QUIT", 12, grey);
        quit.setLetterSpacing(0.3f);
        quit.setPadding(dp(12), dp(8), 0, dp(8));
        quit.setOnClickListener(v -> {
            stopService(new Intent(this, NavService.class));
            finish();
        });
        top.addView(quit);
        root.addView(top);

        status = text("Starting", 13, grey);
        status.setPadding(0, dp(4), 0, 0);
        root.addView(status);

        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        root.addView(dash, new LinearLayout.LayoutParams(-1, -2));

        bigView = text("--", 96, fg);            // minutes to arrive, or the clock when idle
        bigView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        bigView.setGravity(Gravity.CENTER);
        bigView.setIncludeFontPadding(false);
        bigView.setPadding(0, dp(28), 0, 0);
        dash.addView(bigView, new LinearLayout.LayoutParams(-1, -2));

        bigLabel = text("", 13, grey);
        bigLabel.setLetterSpacing(0.3f);
        bigLabel.setGravity(Gravity.CENTER);
        dash.addView(bigLabel, new LinearLayout.LayoutParams(-1, -2));

        subLabel = text("", 14, grey);
        subLabel.setGravity(Gravity.CENTER);
        subLabel.setPadding(0, dp(6), 0, 0);
        dash.addView(subLabel, new LinearLayout.LayoutParams(-1, -2));

        turnView = text("No destination", 24, fg);
        turnView.setTypeface(Typeface.DEFAULT_BOLD);
        turnView.setPadding(0, dp(28), 0, 0);
        dash.addView(turnView);

        destView = text("", 14, grey);
        destView.setPadding(0, dp(4), 0, dp(20));
        dash.addView(destView);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Destination, or lat,lon");
        input.setHintTextColor(grey);
        input.setTextColor(fg);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(underline());
        input.setPadding(0, dp(12), 0, dp(12));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            onSearch();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView go = button("GO");
        go.setOnClickListener(v -> onSearch());
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(-2, -2);
        goLp.leftMargin = dp(12);
        row.addView(go, goLp);
        root.addView(row);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        navRow = new LinearLayout(this);
        navRow.setVisibility(View.GONE);
        navRow.setPadding(0, dp(12), 0, 0);
        mapBtn = button("MAP");
        mapBtn.setOnClickListener(v -> {
            NavService s = NavService.instance;
            if (s != null && s.route != null) showPanel(s.route, false);
        });
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-2, -2);
        mapLp.rightMargin = dp(12);
        navRow.addView(mapBtn, mapLp);
        stopBtn = button("STOP NAVIGATION");
        stopBtn.setOnClickListener(v -> {
            NavService s = NavService.instance;
            clearPanel();
            if (s != null) s.stopNav();
        });
        navRow.addView(stopBtn, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(navRow, new LinearLayout.LayoutParams(-1, -2));
        return root;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String label) {
        TextView b = text(label, 13, fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.2f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setStroke(dp(1), fg);
        g.setCornerRadius(dp(6));
        b.setBackground(g);
        return b;
    }

    /** A 1 dp line along the bottom edge, for the input field and result rows. */
    private Drawable underline() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1), line);
        LayerDrawable l = new LayerDrawable(new Drawable[]{g});
        l.setLayerInset(0, -dp(2), -dp(2), -dp(2), 0);
        return l;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
