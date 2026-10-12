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
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Black-and-white control screen: speed, next turn, destination search. */
public final class MainActivity extends Activity implements NavService.Listener {
    private static final int GREY = 0xFF8A8A8A, LINE = 0xFF3A3A3A;
    private static final Pattern LAT_LON =
            Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    private TextView status, speedView, turnView, destView;
    private EditText input;
    private LinearLayout results;
    private TextView stopBtn, keyBtn;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Router.loadKey(this);
        setContentView(buildUi());
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

    // ---------- state -> screen ----------

    private void refresh() {
        NavService s = NavService.instance;
        if (s == null) {
            speedView.setText("--");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String gps = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? "Location is off"
                : s.last == null ? "Waiting for GPS"
                : String.format(Locale.US, "%s ±%.0f m",
                        LocationManager.GPS_PROVIDER.equals(s.last.getProvider()) ? "GPS" : "Rough location",
                        s.last.getAccuracy());
        status.setText(s.ble.state + "  ·  " + gps);
        speedView.setText(s.last == null ? "--" : String.valueOf(Math.round(s.speed * 3.6f)));

        NavRoute r = s.route;
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
        stopBtn.setVisibility(s.hasDest ? View.VISIBLE : View.GONE);
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
            s.navigateTo(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), q);
            return;
        }
        showMessage("Searching");
        double lat = s.last == null ? Double.NaN : s.last.getLatitude();
        double lon = s.last == null ? Double.NaN : s.last.getLongitude();
        Router.search(q, lat, lon, (places, err) -> {
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
        TextView t = text(p.name, 15, Color.WHITE);
        t.setPadding(0, dp(14), 0, dp(14));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(underline());
        t.setOnClickListener(v -> {
            NavService s = NavService.instance;
            if (s == null) return;
            results.removeAllViews();
            input.setText("");
            int comma = p.name.indexOf(',');
            s.navigateTo(p.lat, p.lon, comma > 0 ? p.name.substring(0, comma) : p.name);
        });
        return t;
    }

    private void showMessage(String m) {
        results.removeAllViews();
        TextView t = text(m, 14, GREY);
        t.setPadding(0, dp(14), 0, 0);
        results.addView(t);
    }

    // ---------- map key ----------

    private void showKeyState() {
        keyBtn.setText(Router.hasKey() ? "OLA MAPS" : "ADD MAP KEY");
        keyBtn.setTextColor(Router.hasKey() ? Color.WHITE : GREY);
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
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("BEELINE NAV", 12, Color.WHITE);
        title.setLetterSpacing(0.3f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        keyBtn = text("", 12, GREY);
        keyBtn.setLetterSpacing(0.3f);
        keyBtn.setPadding(dp(12), dp(8), dp(8), dp(8));
        keyBtn.setOnClickListener(v -> askKey());
        top.addView(keyBtn);
        showKeyState();
        TextView quit = text("QUIT", 12, GREY);
        quit.setLetterSpacing(0.3f);
        quit.setPadding(dp(12), dp(8), 0, dp(8));
        quit.setOnClickListener(v -> {
            stopService(new Intent(this, NavService.class));
            finish();
        });
        top.addView(quit);
        root.addView(top);

        status = text("Starting", 13, GREY);
        status.setPadding(0, dp(4), 0, 0);
        root.addView(status);

        speedView = text("--", 112, Color.WHITE);
        speedView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        speedView.setGravity(Gravity.CENTER);
        speedView.setIncludeFontPadding(false);
        speedView.setPadding(0, dp(28), 0, 0);
        root.addView(speedView, new LinearLayout.LayoutParams(-1, -2));

        TextView unit = text("KM/H", 13, GREY);
        unit.setLetterSpacing(0.3f);
        unit.setGravity(Gravity.CENTER);
        root.addView(unit, new LinearLayout.LayoutParams(-1, -2));

        turnView = text("No destination", 24, Color.WHITE);
        turnView.setTypeface(Typeface.DEFAULT_BOLD);
        turnView.setPadding(0, dp(32), 0, 0);
        root.addView(turnView);

        destView = text("", 14, GREY);
        destView.setPadding(0, dp(4), 0, dp(20));
        root.addView(destView);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setHint("Destination, or lat,lon");
        input.setHintTextColor(GREY);
        input.setTextColor(Color.WHITE);
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

        stopBtn = button("STOP NAVIGATION");
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(v -> {
            NavService s = NavService.instance;
            if (s != null) s.stopNav();
        });
        root.addView(stopBtn, new LinearLayout.LayoutParams(-1, -2));
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
        TextView b = text(label, 13, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.2f);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.BLACK);
        g.setStroke(dp(1), Color.WHITE);
        g.setCornerRadius(dp(6));
        b.setBackground(g);
        return b;
    }

    /** A 1 dp line along the bottom edge, for the input field and result rows. */
    private android.graphics.drawable.Drawable underline() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1), LINE);
        android.graphics.drawable.LayerDrawable l =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{g});
        l.setLayerInset(0, -dp(2), -dp(2), -dp(2), 0);
        return l;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
