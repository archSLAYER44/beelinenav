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
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
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

/**
 * BeeNav control screen, laid out as a stack of rounded cards: the display and GPS links, time to arrive,
 * the next turn, the destination, search, Home / Work and recent places. Light by day, dark by night.
 */
public final class MainActivity extends CardActivity implements NavService.Listener {
    private static final Pattern LAT_LON =
            Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    // status row and notices
    private View bleTile, gpsTile;
    private TextView bleText, gpsText, notice, callText;
    private LinearLayout callCard;
    // hero card: minutes to arrive while riding, the clock otherwise
    private TextView heroLabel, heroHint, heroBig, heroBadge, clockText, arriveText, leftText;
    private LinearLayout heroTimes, progressBar;
    private View progressFill, progressRest;
    // turn card and destination card
    private LinearLayout turnCard, destCard;
    private TextView turnArrow, turnBig, turnSub, statSpeed, statTrip, statDone;
    private TextView destName, destSub, mapBtn;
    private EditText input;
    private LinearLayout dash, favRow, results;
    private int panelGen;          // bumped whenever the panel changes, so late replies are dropped
    private String pendingShare;      // a place another app shared, until there is a position to route from
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tickUi = new Runnable() {
        @Override
        public void run() {
            refresh();                 // keeps the clock and the fix age honest when nothing else changes
            ticker.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Router.load(this);
        Theme.load(this);
        applyTheme();
        askPermissions();
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        NavService.ui = this;
        refresh();
        tryShare();
        ticker.removeCallbacks(tickUi);
        ticker.postDelayed(tickUi, 2000);
    }

    @Override
    protected void onPause() {
        ticker.removeCallbacks(tickUi);
        if (NavService.ui == this) NavService.ui = null;
        super.onPause();
    }

    @Override
    public void onNavState() {
        refresh();
        tryShare();
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
        if (ok) {
            startNavService();
        } else {
            notice.setText("Location and Bluetooth permissions are needed. Allow them in Settings, then reopen.");
            notice.setVisibility(View.VISIBLE);
        }
    }

    private void startNavService() {
        startForegroundService(new Intent(this, NavService.class));
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a != null && !a.isEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (RuntimeException ignored) {
                // the display card already says Bluetooth is off
            }
        }
        refresh();
    }

    // ---------- theme ----------

    /** Picks the colours for now and rebuilds the screen with them. */
    private void applyTheme() {
        NavService s = NavService.instance;
        palette(Theme.isLight(s == null ? null : s.last));
        String typed = input == null ? "" : input.getText().toString();
        setContentView(buildUi());
        input.setText(typed);
    }

    // ---------- state -> screen ----------

    private void refresh() {
        NavService s = NavService.instance;
        if (light != Theme.isLight(s == null ? null : s.last)) applyTheme();
        if (s == null) {
            heroLabel.setText("Starting");
            return;
        }

        // display link
        String ble = s.ble.state;
        boolean linked = ble.startsWith("Display connected");
        boolean busy = ble.startsWith("Searching") || ble.startsWith("Connecting");
        tint(bleTile, linked ? fg : busy ? grey : chip);
        ble = ble.replace("Searching for display", "Searching").replace("Display ", "")
                .replace("Bluetooth is off", "Bluetooth off").replace("Bluetooth permission missing", "No permission");
        ble = Character.toUpperCase(ble.charAt(0)) + ble.substring(1);
        if (s.ble.writeErrors > 0) ble += "  ·  " + s.ble.writeErrors + " errors";
        bleText.setText(ble);

        // position
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String gps;
        int gpsColor;
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gps = "Location is off";
            gpsColor = chip;
        } else if (s.last == null) {
            gps = "Waiting for a fix";
            gpsColor = chip;
        } else {
            boolean fine = LocationManager.GPS_PROVIDER.equals(s.last.getProvider());
            boolean stale = s.fixAgeS() >= 10;                      // GPS gone quiet
            gps = String.format(Locale.US, "%s±%.0f m", fine ? "" : "Rough ", s.last.getAccuracy());
            if (stale) gps += String.format(Locale.US, "  ·  %d s ago", s.fixAgeS());
            gpsColor = fine && !stale ? fg : grey;
        }
        tint(gpsTile, gpsColor);
        gpsText.setText(gps);

        // incoming call
        boolean ringing = s.callState == 1;
        callCard.setVisibility(ringing ? View.VISIBLE : View.GONE);
        if (ringing) callText.setText(s.callName.isEmpty() ? "Unknown number" : s.callName);

        long nowMs = System.currentTimeMillis();
        String kmh = s.last == null ? "" : Math.round(s.speed * 3.6f) + " km/h";
        NavRoute r = s.route;

        // hero card: the clock stays small in the corner; the body is the arrival while riding
        clockText.setText(clock(nowMs));
        heroBadge.setText(kmh);
        heroBadge.setVisibility(kmh.isEmpty() ? View.GONE : View.VISIBLE);
        if (r != null) {
            int secs = r.remainingTimeS();
            heroLabel.setText("Arrival in");
            heroLabel.setTextSize(12);
            heroLabel.setTextColor(inkGrey);
            heroLabel.setTypeface(Typeface.DEFAULT);
            heroBig.setText(secs >= 3600
                    ? units(String.valueOf(secs / 3600), "h ", String.format(Locale.US, "%02d", secs % 3600 / 60), "min")
                    : units(String.valueOf(Math.max(1, Math.round(secs / 60f))), "min"));
            heroBig.setVisibility(View.VISIBLE);
            heroHint.setVisibility(View.GONE);
            heroTimes.setVisibility(View.VISIBLE);
            arriveText.setText(clock(nowMs + secs * 1000L));
            leftText.setText(NavRoute.distText(r.remaining()));
            progressBar.setVisibility(View.VISIBLE);
            setProgress(r.totalM() > 0 ? (float) (r.progress / r.totalM()) : 0);
        } else {
            heroLabel.setText(!s.message.isEmpty() ? s.message : s.hasDest ? "Finding route" : "No destination");
            heroLabel.setTextSize(17);
            heroLabel.setTextColor(Color.WHITE);
            heroLabel.setTypeface(MEDIUM);
            heroBig.setVisibility(View.GONE);
            heroHint.setVisibility(View.VISIBLE);
            heroTimes.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
        }

        // turn card
        if (r != null) {
            turnCard.setVisibility(View.VISIBLE);
            turnArrow.setText(arrow(r.turnAngle));
            String[] dist = splitUnit(NavRoute.distText(r.distToTurn));
            turnBig.setText(units(dist[0], dist[1]));
            boolean off = r.offRoute > NavService.OFF_ROUTE_M;
            String onto = NavRoute.ontoText(r.turnAngle, r.turnName);
            turnSub.setText(off ? "Off route" : NavRoute.turnText(r.turnAngle) + (onto.isEmpty() ? "" : " " + onto));
            turnSub.setTextColor(off ? fg : grey);
            turnSub.setTypeface(off ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            statSpeed.setText(kmh.isEmpty() ? "--" : kmh);
            statTrip.setText(NavRoute.distText(r.totalM()));
            statDone.setText(r.totalM() > 0 ? Math.round(100 * Math.max(0, Math.min(1, r.progress / r.totalM()))) + "%" : "--");
        } else {
            turnCard.setVisibility(View.GONE);
        }

        // destination card
        if (s.hasDest) {
            destCard.setVisibility(View.VISIBLE);
            destName.setText(s.destName);
            destSub.setText(r == null ? s.message
                    : s.message.isEmpty() ? NavRoute.distText(r.remaining()) + " left" : s.message);
            mapBtn.setVisibility(r != null && Router.hasMap() ? View.VISIBLE : View.GONE);
        } else {
            destCard.setVisibility(View.GONE);
        }
    }

    private String clock(long ms) {
        return DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", new Date(ms)).toString();
    }

    /** Arrow glyph for a turn angle (+ = right), or a dot for the destination. */
    private static String arrow(int a) {
        if (a == NavRoute.ARRIVE) return "●";
        boolean right = a > 0;
        int m = Math.abs(a);
        if (m > 150) return "↩";
        if (m > 110) return right ? "↘" : "↙";
        if (m < 45) return right ? "↗" : "↖";
        return right ? "→" : "←";
    }

    /** "3.4 km" -> {"3.4", "km"}. */
    private static String[] splitUnit(String t) {
        int sp = t.lastIndexOf(' ');
        return sp < 0 ? new String[]{t, ""} : new String[]{t.substring(0, sp), t.substring(sp + 1)};
    }

    /** Big number, small unit, big number, small unit... like "53min" on the sample widgets. */
    private static CharSequence units(String... parts) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < parts.length; i++) {
            int start = b.length();
            b.append(parts[i]);
            if (i % 2 == 1) b.setSpan(new RelativeSizeSpan(0.42f), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private void setProgress(float f) {
        f = Math.max(0, Math.min(1, f));
        ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = f;
        ((LinearLayout.LayoutParams) progressRest.getLayoutParams()).weight = 1 - f;
        progressBar.requestLayout();
    }

    private static void tint(View v, int color) {
        ((GradientDrawable) v.getBackground().mutate()).setColor(color);
    }

    // ---------- destination search ----------

    private void onSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        hideKeyboard();
        search(q);
    }

    /** Routes straight to "lat,lon", otherwise looks the words up as a place. */
    private void search(String q) {
        NavService s = NavService.instance;
        if (s == null) return;
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
                LinearLayout list = card(card);
                list.addView(label("Results"));
                for (int i = 0; i < places.size(); i++) {
                    if (i > 0) list.addView(hline(), new LinearLayout.LayoutParams(-1, dp(1)));
                    list.addView(resultRow(places.get(i)));
                }
                results.addView(list, stack(12));
            }
        });
    }

    /** "Cubbon Park, Ambedkar Veedhi, Bengaluru" -> the name on one line, the rest in grey under it. */
    private View resultRow(Router.Place p) {
        int comma = p.name.indexOf(',');
        String name = comma > 0 ? p.name.substring(0, comma) : p.name;
        String detail = comma > 0 ? p.name.substring(comma + 1).trim() : "";
        View row = row(name, detail);
        row.setOnClickListener(v -> openPreview(p.lat, p.lon, name));
        return row;
    }

    /** Empties the panel below the search box and brings the dashboard back. */
    private void resetPanel() {
        panelGen++;
        results.removeAllViews();
        dash.setVisibility(View.VISIBLE);
    }

    /** Back to the idle screen: dashboard on top, recent destinations below. */
    private void clearPanel() {
        resetPanel();
        showRecents();
    }

    /** A one-line message ("Searching", an error) above the recent destinations, which stay reachable. */
    private void showMessage(String m) {
        resetPanel();
        TextView t = text(m, 13, grey);
        t.setPadding(dp(18), dp(2), dp(18), dp(14));
        results.addView(t);
        showRecents();
    }

    /** The last destinations: one tap routes there again; hold one to make it Home or Work, or forget it. */
    private void showRecents() {
        List<Router.Place> recent = Recents.list(this);
        if (recent.isEmpty()) return;
        LinearLayout list = card(card);
        list.addView(label("Recent"));
        for (int i = 0; i < recent.size(); i++) {
            Router.Place p = recent.get(i);
            int index = i;
            if (i > 0) list.addView(hline(), new LinearLayout.LayoutParams(-1, dp(1)));
            View row = row(p.name, null);
            row.setOnClickListener(v -> openPreview(p.lat, p.lon, p.name));
            row.setOnLongClickListener(v -> {
                recentMenu(p, index);
                return true;
            });
            list.addView(row);
        }
        results.addView(list, stack(12));
    }

    /** Hold on a recent destination: make it Home or Work, or forget it. */
    private void recentMenu(Router.Place p, int index) {
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setItems(new String[]{"Set as Home", "Set as Work", "Remove from recent"}, (d, which) -> {
                    if (which == 2) Recents.remove(this, index);
                    else Recents.setFavourite(this, which == 0 ? "home" : "work", p);
                    fillFavourites();
                    clearPanel();
                })
                .show();
    }

    // ---------- Home and Work ----------

    /** Home and Work cards: one tap routes there. An empty one says how to set it; a hold changes it. */
    private void fillFavourites() {
        favRow.removeAllViews();
        String[] keys = {"home", "work"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            Router.Place p = Recents.favourite(this, key);
            LinearLayout c = card(card);
            c.setPadding(dp(16), dp(14), dp(14), dp(14));
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(cap(key), 15, p == null ? grey : fg);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(round("↗", chip, p == null ? grey : fg, 28, 14));
            c.addView(head, new LinearLayout.LayoutParams(-1, -2));
            TextView where = text(p == null ? "Hold to set" : p.name, 12, grey);
            where.setMaxLines(1);
            where.setEllipsize(TextUtils.TruncateAt.END);
            where.setPadding(0, dp(4), 0, 0);
            c.addView(where);
            c.setOnClickListener(v -> {
                if (p != null) openPreview(p.lat, p.lon, p.name);
                else showMessage("To set " + key + ": hold a recent destination, or hold this card to use where you are now");
            });
            c.setOnLongClickListener(v -> {
                favouriteMenu(key);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(12);
            favRow.addView(c, lp);
        }
    }

    /** Hold on Home or Work: save where the phone is now, or clear it. */
    private void favouriteMenu(String key) {
        String title = cap(key);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(new String[]{"Use where I am now", "Clear"}, (d, which) -> {
                    NavService s = NavService.instance;
                    if (which == 0) {
                        if (s == null || s.last == null) {
                            showMessage("Waiting for GPS");
                            return;
                        }
                        Recents.setFavourite(this, key, new Router.Place(title, s.last.getLatitude(), s.last.getLongitude()));
                    } else {
                        Recents.setFavourite(this, key, null);
                    }
                    fillFavourites();
                })
                .show();
    }

    // ---------- places shared from other apps ----------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** A place shared from Google Maps (or any map app), or a geo: link that was tapped. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        }
        if (text == null || text.trim().isEmpty()) return;
        intent.setAction(null);            // so a relaunch does not route to it again
        pendingShare = text;
        tryShare();
    }

    /** Routes to the shared place once the service has a position to start from. */
    private void tryShare() {
        NavService s = NavService.instance;
        if (pendingShare == null || s == null) return;
        if (s.last == null) {
            showMessage("Waiting for GPS to route to the shared place");
            return;
        }
        String text = pendingShare;
        pendingShare = null;
        Links.Target t = Links.parse(text);
        if (t.hasPoint()) {
            openPreview(t.lat, t.lon, placeName(t));
        } else if (t.url != null) {        // a short link: follow it to the long one with the coordinates
            showMessage("Opening link");
            int gen = panelGen;
            Router.resolve(t.url, (url, err) -> {
                if (gen != panelGen) return;
                Links.Target full = url == null ? null : Links.parse(url);
                if (full != null && full.hasPoint()) {
                    openPreview(full.lat, full.lon, full.name != null ? full.name : t.name != null ? t.name : placeName(full));
                } else if (t.name != null) {
                    input.setText(t.name);
                    search(t.name);
                } else {
                    showMessage(err != null ? err : "No place found in that link");
                }
            });
        } else if (t.query != null) {
            input.setText(t.query);
            search(t.query);
        }
    }

    private static String placeName(Links.Target t) {
        return t.name != null ? t.name : String.format(Locale.US, "%.5f, %.5f", t.lat, t.lon);
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

    /** Route card: map picture, name, distance and time, with START / CANCEL before riding or CLOSE while riding. */
    private void showPanel(NavRoute r, boolean preview) {
        NavService s = NavService.instance;
        if (s == null) return;
        resetPanel();
        dash.setVisibility(View.GONE);
        int gen = panelGen;
        int from = preview ? 0 : r.seg;
        double[] me = s.last == null ? null : new double[]{s.last.getLatitude(), s.last.getLongitude()};

        LinearLayout c = card(card);
        ImageView map = new ImageView(this);
        map.setAdjustViewBounds(true);
        map.setBackground(rounded(chip, 16));
        map.setClipToOutline(true);
        map.setVisibility(View.GONE);
        c.addView(map, new LinearLayout.LayoutParams(-1, -2));
        TextView mapMsg = label(Router.hasMap() ? "Loading map" : "Map needs an Ola Maps or Google Maps key (Settings)");
        mapMsg.setPadding(0, dp(10), 0, dp(10));
        c.addView(mapMsg);
        if (Router.hasMap()) {
            Router.mapImage(r.outline(from, 80), me, light, (bmp, err) -> {
                if (gen != panelGen) return;
                if (bmp == null) {
                    mapMsg.setText(err);
                    return;
                }
                map.setImageBitmap(bmp);
                map.setVisibility(View.VISIBLE);
                mapMsg.setText("Tap the map to zoom");
            });
            map.setOnClickListener(v -> startActivity(MapActivity.intent(this, r.outline(from, 500), me, light)));
        }

        TextView name = text(r.name, 18, fg);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(name);
        String info = preview ? NavRoute.distText(r.totalM()) + "  ·  " + time(r.durationS)
                : NavRoute.distText(r.remaining()) + " left";
        TextView infoView = text(info, 13, grey);
        infoView.setPadding(0, dp(4), 0, dp(16));
        c.addView(infoView);

        LinearLayout row = new LinearLayout(this);
        if (preview) {
            TextView start = pill("Start", fg, bg);
            start.setOnClickListener(v -> {
                NavService svc = NavService.instance;
                if (svc == null) return;
                input.setText("");
                Recents.add(this, r.name, r.destLat, r.destLon);
                clearPanel();
                svc.start(r);
            });
            row.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView close = pill(preview ? "Cancel" : "Close", chip, fg);
        close.setOnClickListener(v -> clearPanel());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(preview ? -2 : -1, -2);
        if (preview) lp.leftMargin = dp(10);
        row.addView(close, lp);
        c.addView(row, new LinearLayout.LayoutParams(-1, -2));
        results.addView(c, stack(12));
    }

    private static String time(int seconds) {
        int m = Math.max(1, Math.round(seconds / 60f));
        return m < 60 ? m + " min" : m / 60 + " h " + m % 60 + " min";
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
        root.setPadding(dp(16), dp(10), dp(16), dp(24));

        // title row: name on the left, theme / map key / quit on the right
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(8), 0, dp(14));
        TextView title = text("BeeNav", 22, fg);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = tag("Settings", fg);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, beside(8));
        TextView quit = round("×", chip, fg, 32, 18);
        quit.setOnClickListener(v -> {
            stopService(new Intent(this, NavService.class));
            finish();
        });
        top.addView(quit, beside(8));
        root.addView(top);

        // status row: the display link and the GPS fix
        LinearLayout statusRow = new LinearLayout(this);
        bleTile = square(chip, 34, 11);
        bleText = text("Starting", 12, grey);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1);
        half.rightMargin = dp(12);
        statusRow.addView(statusCard("Display", bleTile, bleText), half);
        gpsTile = square(chip, 34, 11);
        gpsText = text("Waiting for a fix", 12, grey);
        statusRow.addView(statusCard("GPS", gpsTile, gpsText), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(statusRow, stack(12));

        notice = text("", 13, fg);
        notice.setPadding(dp(8), 0, dp(8), dp(12));
        notice.setVisibility(View.GONE);
        root.addView(notice);

        callCard = card(fg);                                      // inverted card, so it stands out
        TextView callLabel = text("Incoming call", 12, light ? 0xCCFFFFFF : 0xCC000000);
        callCard.addView(callLabel);
        callText = text("", 18, bg);
        callText.setTypeface(Typeface.DEFAULT_BOLD);
        callText.setPadding(0, dp(4), 0, 0);
        callCard.addView(callText);
        callCard.setVisibility(View.GONE);
        root.addView(callCard, stack(12));

        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        dash.addView(heroCard(), stack(12));
        dash.addView(turnCard(), stack(12));
        dash.addView(destCard(), stack(12));
        root.addView(dash, new LinearLayout.LayoutParams(-1, -2));

        // search card
        LinearLayout searchCard = card(card);
        searchCard.setOrientation(LinearLayout.HORIZONTAL);
        searchCard.setGravity(Gravity.CENTER_VERTICAL);
        searchCard.setPadding(dp(20), dp(8), dp(8), dp(8));
        input = new EditText(this);
        input.setHint("Where to?");
        input.setHintTextColor(grey);
        input.setTextColor(fg);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(null);
        input.setPadding(0, dp(12), 0, dp(12));
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            onSearch();
            return true;
        });
        searchCard.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView go = round("→", fg, bg, 44, 20);
        go.setOnClickListener(v -> onSearch());
        searchCard.addView(go, beside(10));
        root.addView(searchCard, stack(12));

        favRow = new LinearLayout(this);
        fillFavourites();
        root.addView(favRow, stack(12));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        showRecents();
        root.addView(results, new LinearLayout.LayoutParams(-1, -2));

        TextView build = text("Build " + Secrets.BUILD, 11, grey);
        build.setGravity(Gravity.CENTER);
        build.setPadding(0, dp(8), 0, 0);
        root.addView(build, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(page);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root);
        return scroll;
    }

    /** Dark card with a small clock in the corner: minutes to arrive, arrive time and a progress bar while riding. */
    private LinearLayout heroCard() {
        LinearLayout hero = card(ink);
        hero.setClipToPadding(false);     // the progress bar sits in the padding strip at the bottom

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        heroLabel = text("No destination", 17, Color.WHITE);
        heroLabel.setTypeface(MEDIUM);
        head.addView(heroLabel, new LinearLayout.LayoutParams(0, -2, 1));
        clockText = text("", 13, inkGrey);
        clockText.setTypeface(MEDIUM);
        head.addView(clockText, beside(12));
        hero.addView(head, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        heroHint = text("Search below, or tap Home, Work or a recent place", 12, inkGrey);
        heroHint.setPadding(0, dp(4), 0, 0);
        row.addView(heroHint, new LinearLayout.LayoutParams(0, -2, 1));
        heroBig = text("", 54, Color.WHITE);
        heroBig.setTypeface(Typeface.DEFAULT_BOLD);
        heroBig.setLetterSpacing(-0.03f);
        heroBig.setIncludeFontPadding(false);
        heroBig.setPadding(0, dp(8), 0, 0);
        heroBig.setVisibility(View.GONE);
        row.addView(heroBig, new LinearLayout.LayoutParams(0, -2, 1));
        heroBadge = tag("", Color.BLACK);
        heroBadge.setBackground(rounded(Color.WHITE, 999));
        heroBadge.setVisibility(View.GONE);
        row.addView(heroBadge, beside(8));
        hero.addView(row, new LinearLayout.LayoutParams(-1, -2));

        heroTimes = new LinearLayout(this);
        heroTimes.setGravity(Gravity.CENTER_VERTICAL);
        heroTimes.setPadding(0, dp(18), 0, 0);
        arriveText = text("", 16, Color.WHITE);
        arriveText.setTypeface(Typeface.DEFAULT_BOLD);
        heroTimes.addView(stat("Arrive", arriveText, inkGrey, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1));
        leftText = text("", 16, Color.WHITE);
        leftText.setTypeface(Typeface.DEFAULT_BOLD);
        heroTimes.addView(stat("Distance left", leftText, inkGrey, Gravity.END), new LinearLayout.LayoutParams(0, -2, 1));
        heroTimes.setVisibility(View.GONE);
        hero.addView(heroTimes, new LinearLayout.LayoutParams(-1, -2));

        // progress bar along the bottom edge of the card (negative margins reach past the padding)
        progressBar = new LinearLayout(this);
        progressFill = new View(this);
        progressFill.setBackgroundColor(Color.WHITE);
        progressBar.addView(progressFill, new LinearLayout.LayoutParams(0, -1, 0f));
        progressRest = new View(this);
        progressRest.setBackgroundColor(inkChip);
        progressBar.addView(progressRest, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
        lp.topMargin = dp(18);
        lp.leftMargin = lp.rightMargin = lp.bottomMargin = -dp(18);
        progressBar.setVisibility(View.GONE);
        hero.addView(progressBar, lp);
        return hero;
    }

    /** Next turn: arrow and distance, the road it leads onto, then speed / trip length / share done. */
    private LinearLayout turnCard() {
        turnCard = card(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        turnArrow = text("←", 40, fg);
        turnArrow.setTypeface(Typeface.DEFAULT_BOLD);
        turnArrow.setIncludeFontPadding(false);
        turnArrow.setPadding(0, 0, dp(10), 0);
        row.addView(turnArrow);
        turnBig = text("", 44, fg);
        turnBig.setTypeface(Typeface.DEFAULT_BOLD);
        turnBig.setLetterSpacing(-0.03f);
        turnBig.setIncludeFontPadding(false);
        row.addView(turnBig);
        turnCard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        turnSub = text("", 14, grey);
        turnSub.setPadding(0, dp(6), 0, 0);
        turnSub.setMaxLines(2);
        turnSub.setEllipsize(TextUtils.TruncateAt.END);
        turnCard.addView(turnSub);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, dp(1));
        lineLp.topMargin = dp(16);
        lineLp.bottomMargin = dp(14);
        turnCard.addView(hline(), lineLp);
        LinearLayout stats = new LinearLayout(this);
        statSpeed = value();
        stats.addView(stat("Speed", statSpeed, grey, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1));
        statTrip = value();
        stats.addView(stat("Trip", statTrip, grey, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1));
        statDone = value();
        stats.addView(stat("Done", statDone, grey, Gravity.START), new LinearLayout.LayoutParams(0, -2, 1));
        turnCard.addView(stats, new LinearLayout.LayoutParams(-1, -2));
        turnCard.setVisibility(View.GONE);
        return turnCard;
    }

    /** Where we are going, with MAP and STOP. */
    private LinearLayout destCard() {
        destCard = card(card);
        destCard.addView(label("Destination"));
        destName = text("", 18, fg);
        destName.setTypeface(Typeface.DEFAULT_BOLD);
        destName.setPadding(0, dp(4), 0, 0);
        destName.setMaxLines(2);
        destName.setEllipsize(TextUtils.TruncateAt.END);
        destCard.addView(destName);
        destSub = text("", 13, grey);
        destSub.setPadding(0, dp(2), 0, 0);
        destCard.addView(destSub);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(16), 0, 0);
        mapBtn = pill("Map", chip, fg);
        mapBtn.setOnClickListener(v -> {
            NavService s = NavService.instance;
            if (s != null && s.route != null) showPanel(s.route, false);
        });
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-2, -2);
        mapLp.rightMargin = dp(10);
        row.addView(mapBtn, mapLp);
        TextView stop = pill("Stop navigation", fg, bg);
        stop.setOnClickListener(v -> {
            NavService s = NavService.instance;
            clearPanel();
            if (s != null) s.stopNav();
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        destCard.addView(row, new LinearLayout.LayoutParams(-1, -2));
        destCard.setVisibility(View.GONE);
        return destCard;
    }

    /** Small card with a coloured square, a title and a one-line state under it. */
    private LinearLayout statusCard(String title, View tile, TextView state) {
        LinearLayout c = card(card);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = text(title, 13, fg);
        t.setTypeface(MEDIUM);
        col.addView(t);
        state.setMaxLines(1);
        state.setEllipsize(TextUtils.TruncateAt.END);
        state.setPadding(0, dp(2), 0, 0);
        col.addView(state);
        c.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return c;
    }
}
