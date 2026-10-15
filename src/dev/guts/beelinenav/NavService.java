package dev.guts.beelinenav;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Keeps GPS, routing and the display link alive while the screen is off. Everything runs on the main thread. */
public final class NavService extends Service implements LocationListener, Ble.Listener {
    interface Listener { void onNavState(); }

    static NavService instance;
    static Listener ui;

    static final double OFF_ROUTE_M = 50;                // further than this from the line counts as off route
    private static final double REBASE_M = 20000;
    private static final double ARRIVE_M = 30;           // this close to the end of the line (or the pin) is "arrived"
    private static final double ROUGH_FIX_M = 100;       // a fix this uncertain is kept off the display while navigating
    private static final long REROUTE_GAP_MS = 15000;
    private static final long GPS_HOLD_MS = 60000;       // network fixes are ignored this long after the last GPS fix
    private static final long ROUTE_REFRESH_MS = 10000;  // the route is re-sent this often even when nothing changed
    private static final long ARRIVED_MSG_MS = 15000;    // "Arrived at ..." stays in the app this long
    private static final long TRIP_KEEP_MS = 6 * 3600 * 1000L;   // an unfinished trip older than this is not resumed
    private static final String IDLE_TEXT = "Sending speed and directions to the display";

    Ble ble;
    NavRoute route;
    Location last;
    boolean hasDest, routing;
    String destName = "", message = "";
    float speed;                       // m/s
    boolean day;                       // light look right now; goes to the display with every pose
    int callState;                     // 0 none, 1 ringing, 2 in a call
    String callName = "";

    private double destLat, destLon;
    private float heading;
    private boolean haveHeading;
    private int offCount, arriveCount, sentSeg = -1;
    private String sentTurn;                 // the road sign the display has
    private long lastRouteRequest, lastGpsMs = -100000, routeSentMs;
    private BroadcastReceiver callRx;
    private List<Roads.Line> roads;          // side roads around the route, for the junction view
    private int roadsSeg = -1;
    private double roadsProgress = -1e9;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private String shownNote = "";           // the notification's current text

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Router.load(this);
        Theme.load(this);
        goForeground();
        resumeTrip();
        ble = new Ble(this, this);
        ble.start();
        // GPS alone can take minutes for a first fix, so start from the last known position
        // and accept rough network fixes until GPS takes over.
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!lm.getAllProviders().contains(p)) continue;
                lm.requestLocationUpdates(p, 1000, 0, this, Looper.getMainLooper());
                Location l = lm.getLastKnownLocation(p);
                boolean fresh = l != null && System.currentTimeMillis() - l.getTime() < 120000;
                if (fresh && (last == null || l.getTime() > last.getTime())) last = l;
            } catch (SecurityException | IllegalArgumentException e) {
                message = "Location is not available";
            }
        }
        timer.post(themeCheck);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setupCalls();   // the activity may have just been granted the phone permissions
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
        ble.stop();
        timer.removeCallbacksAndMessages(null);
        if (callRx != null) unregisterReceiver(callRx);
        clearTrip();                         // a deliberate stop; only a kill leaves the trip behind to resume
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- called by the activity ----------

    /** Fetches a route from the current position without starting navigation. */
    void preview(double lat, double lon, String name, Router.RouteCb cb) {
        if (last == null) {
            cb.done(null, "Waiting for GPS");
            return;
        }
        Router.route(last.getLatitude(), last.getLongitude(), lat, lon, name, cb);
    }

    /** Starts navigating a route that preview() already fetched, so no second request is made. */
    void start(NavRoute r) {
        hasDest = true;
        destLat = r.destLat;
        destLon = r.destLon;
        destName = r.name;
        saveTrip();
        route = r;
        loadRoads(r);
        sentSeg = -1;
        sentTurn = null;
        offCount = 0;
        arriveCount = 0;
        message = "";
        timer.removeCallbacks(clearMessage);
        lastRouteRequest = SystemClock.elapsedRealtime();
        if (last != null) tick();
        notifyUi();
    }

    void stopNav() {
        hasDest = false;
        route = null;
        clearTrip();
        clearRoads();
        message = "";
        timer.removeCallbacks(clearMessage);
        ble.sendRoute(new byte[]{0});
        if (last != null) tick();
        notifyUi();
    }

    // ---------- GPS ----------

    @Override
    public void onLocationChanged(Location loc) {
        long now = SystemClock.elapsedRealtime();
        if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) lastGpsMs = now;
        else if (now - lastGpsMs < GPS_HOLD_MS) return;   // GPS was live a moment ago; a network fix would only jump around
        last = loc;
        speed = loc.hasSpeed() ? loc.getSpeed() : 0;
        if (loc.hasBearing() && speed > 1.0f) {     // GPS bearing is noise when standing still
            heading = loc.getBearing();
            haveHeading = true;
        }
        if (hasDest && route == null && !routing && now - lastRouteRequest > REROUTE_GAP_MS) requestRoute();
        tick();
        notifyUi();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    /** Sends the display its next update from the latest fix. */
    private void tick() {
        day = Theme.isLight(last);
        NavRoute r = route;
        if (r == null) {
            ble.sendPose(pose(0, 0, heading, speed, 0, 0, day, false, 0xFFFF));   // display shows clock and speed
            return;
        }
        // A rough fix (cell tower, first GPS fix) can be hundreds of metres out and would put the rider on
        // the wrong road. The display keeps the last good position and greys its arrow until GPS is back.
        if (last.hasAccuracy() && last.getAccuracy() > ROUGH_FIX_M) return;

        double px = r.toX(last.getLongitude()), py = r.toY(last.getLatitude());
        if (Math.hypot(px, py) > REBASE_M) {
            r.setOrigin(last.getLatitude(), last.getLongitude());
            px = py = 0;
            sentSeg = -1;
            if (roads != null) Roads.rebase(roads, r);
            roadsSeg = -1;
        }
        r.snap(px, py, heading, haveHeading && speed > 1.0f);
        if (!haveHeading) heading = (float) r.bearing();

        // Arrived: at the end of the line, or at the pin itself once the line is nearly done (the pin may
        // sit off the road). Two fixes in a row, so a single GPS wobble does not end the trip.
        double toDest = Math.hypot(px - r.toX(destLon), py - r.toY(destLat));
        boolean there = r.remaining() < ARRIVE_M || (toDest < ARRIVE_M && r.remaining() < 300);
        arriveCount = there ? arriveCount + 1 : 0;
        if (arriveCount >= 2) {
            arrive();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (r.seg != sentSeg || now - routeSentMs > ROUTE_REFRESH_MS) {   // the refresh heals a lost packet
            ble.sendRoute(r.routePacket());
            sentSeg = r.seg;
            routeSentMs = now;
        }
        if (!r.turnName.equals(sentTurn)) {                 // the sign at the top of the display
            ble.sendTurn(ascii24(NavRoute.shortRoad(r.turnName)));
            sentTurn = r.turnName;
        }
        int eta = Math.min(0xFFFE, Math.round(r.remainingTimeS() / 60f));
        ble.sendPose(pose(px, py, heading, speed, r.distToTurn, r.turnAngle, day, r.offRoute > OFF_ROUTE_M, eta));
        if (roads != null && (r.seg != roadsSeg || Math.abs(r.progress - roadsProgress) > 250)) sendRoadsWindow(r, px, py);

        offCount = r.offRoute > OFF_ROUTE_M ? offCount + 1 : 0;
        if (offCount >= 5 && !routing && now - lastRouteRequest > REROUTE_GAP_MS) requestRoute();
    }

    /** The rider is there: the display shows "destination reached" for a few seconds, then its clock. */
    private void arrive() {
        String name = destName;
        Log.i("BeelineNav", "arrived at " + name);
        hasDest = false;
        route = null;
        arriveCount = 0;
        clearTrip();
        clearRoads();
        ble.sendRoute(arrivedPacket(name));
        message = "Arrived at " + name;
        timer.removeCallbacks(clearMessage);
        timer.postDelayed(clearMessage, ARRIVED_MSG_MS);
        if (last != null) tick();
        notifyUi();
    }

    private final Runnable clearMessage = () -> {
        if (!hasDest) {
            message = "";
            notifyUi();
        }
    };

    /** Seconds since the fix the app is working from; grows when GPS has gone quiet. */
    long fixAgeS() {
        return last == null ? 0 : Math.max(0, (System.currentTimeMillis() - last.getTime()) / 1000);
    }

    private void requestRoute() {
        routing = true;
        lastRouteRequest = SystemClock.elapsedRealtime();
        message = route == null ? "Finding route" : "Rerouting";
        final double lat = destLat, lon = destLon;
        Router.route(last.getLatitude(), last.getLongitude(), lat, lon, destName, (r, err) -> {
            routing = false;
            if (!hasDest || lat != destLat || lon != destLon) return;   // cancelled or changed meanwhile
            if (r != null) {
                route = r;
                loadRoads(r);
                sentSeg = -1;
                sentTurn = null;
                offCount = 0;
                message = "";
                tick();
            } else {
                message = err;
            }
            notifyUi();
        });
    }

    /**
     * Route packet with no points, which clears the display's route, then uint8 flags (bit0 = arrived)
     * and the destination's name in ASCII: the display shows "destination reached" before its clock.
     */
    private static byte[] arrivedPacket(String name) {
        byte[] n = ascii24(name);
        byte[] out = new byte[2 + n.length];
        out[0] = 0;
        out[1] = 1;
        System.arraycopy(n, 0, out, 2, n.length);
        return out;
    }

    /** Up to 24 bytes of printable ASCII, which is all the display's font can show. */
    private static byte[] ascii24(String s) {
        String ascii = s.replaceAll("[^\\x20-\\x7E]", "").trim();
        if (ascii.length() > 24) ascii = ascii.substring(0, 24);
        return ascii.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Pose packet, 19 bytes LE: int32 x, y (dm), uint16 heading (0.01 deg), speed (cm/s), dist (m),
     * int16 angle, uint8 flags (bit0 = day, bit1 = off route), uint16 minutes to arrive (0xFFFF = none).
     */
    private static byte[] pose(double x, double y, float heading, float speed, double dist, int angle,
                               boolean day, boolean offRoute, int etaMin) {
        float h = ((heading % 360) + 360) % 360;
        ByteBuffer b = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) Math.round(x * 10));
        b.putInt((int) Math.round(y * 10));
        b.putShort((short) Math.min(35999, Math.round(h * 100)));
        b.putShort((short) Math.min(65535, Math.round(speed * 100)));
        b.putShort((short) Math.min(65535, Math.round(dist)));
        b.putShort((short) angle);
        b.put((byte) ((day ? 1 : 0) | (offRoute ? 2 : 0)));
        b.putShort((short) etaMin);
        return b.array();
    }

    // ---------- display link ----------

    @Override
    public void onBleState() {
        notifyUi();
    }

    @Override
    public void onBleReady() {
        sentSeg = -1;                                  // the display forgets everything on reconnect
        sentTurn = null;
        roadsSeg = -1;
        ble.sendTime(timePacket());
        if (callState != 0) ble.sendCall(callPacket(callState, callName));
        if (route == null) ble.sendRoute(new byte[]{0});
        if (last != null) tick();
        notifyUi();
    }

    // ---------- side roads for the junction view ----------

    private void loadRoads(NavRoute r) {
        roads = null;
        roadsSeg = -1;
        ble.sendRoads(Roads.packets(new ArrayList<>()));   // the old route's roads would sit in the wrong place
        if (!Router.olaTiles()) return;                // the road shapes come from Ola's tiles only
        Roads.load(r, (lines, err) -> {
            if (route != r) return;                    // the route changed while the tiles loaded
            roads = lines;
            roadsSeg = -1;
            if (last != null) tick();
        });
    }

    private void clearRoads() {
        roads = null;
        roadsSeg = -1;
        ble.sendRoads(Roads.packets(new ArrayList<>()));   // an empty set wipes the display's copy
    }

    /** Sends the roads within 150 m of the next ~650 m of route, which is as far as the display looks. */
    private void sendRoadsWindow(NavRoute r, double px, double py) {
        int end = r.indexAhead(r.seg, 650);
        double minX = px, maxX = px, minY = py, maxY = py;
        for (int i = r.seg; i <= end; i++) {
            minX = Math.min(minX, r.x(i));
            maxX = Math.max(maxX, r.x(i));
            minY = Math.min(minY, r.y(i));
            maxY = Math.max(maxY, r.y(i));
        }
        List<Roads.Line> pieces = Roads.window(roads, minX - 150, minY - 150, maxX + 150, maxY + 150, 160);
        ble.sendRoads(Roads.packets(pieces));
        roadsSeg = r.seg;
        roadsProgress = r.progress;
        Log.i("BeelineNav", "roads window: " + pieces.size() + " pieces, " + Roads.pointCount(pieces) + " points");
    }

    // ---------- day / night ----------

    private final Runnable themeCheck = new Runnable() {
        @Override
        public void run() {
            boolean d = Theme.isLight(last);
            if (d != day) {
                day = d;
                if (last != null) tick();      // tell the display straight away
                notifyUi();
            }
            ble.sendTime(timePacket());        // keeps the display's clock in step
            timer.postDelayed(this, 60000);
        }
    };

    /** Called when the user changes the theme mode in the app. */
    void refreshTheme() {
        timer.removeCallbacks(themeCheck);
        timer.post(themeCheck);
    }

    /** Time packet: uint16 minutes since local midnight, uint8 flags (bit0 day, bit1 12-hour clock). */
    private byte[] timePacket() {
        Calendar c = Calendar.getInstance();
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int flags = (day ? 1 : 0) | (DateFormat.is24HourFormat(this) ? 0 : 2);
        return new byte[]{(byte) mins, (byte) (mins >> 8), (byte) flags};
    }

    // ---------- phone calls ----------

    /** Starts listening for calls once the phone permission exists; without it the feature is simply off. */
    private void setupCalls() {
        if (callRx != null
                || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        callRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                onCall(i.getStringExtra(TelephonyManager.EXTRA_STATE),
                        i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER));
            }
        };
        IntentFilter f = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(callRx, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(callRx, f);
    }

    private void onCall(String state, String number) {
        int s = TelephonyManager.EXTRA_STATE_RINGING.equals(state) ? 1
                : TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state) ? 2 : 0;
        String name = callName;
        if (s == 0) name = "";
        else if (number != null && !number.isEmpty()) name = lookupName(number);   // only sent with READ_CALL_LOG
        if (s == callState && name.equals(callName)) return;   // Android repeats the ringing broadcast
        callState = s;
        callName = name;
        Log.i("BeelineNav", "call state " + s + (name.isEmpty() ? "" : " from " + name));
        ble.sendCall(callPacket(s, name));
        notifyUi();
    }

    /** The contact's name for a number, or the number itself. */
    private String lookupName(String number) {
        try (Cursor c = getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
            // no contacts permission, or the lookup failed: show the number
        }
        return number;
    }

    /** Call packet: uint8 state (0 none, 1 ringing, 2 in call), then up to 24 ASCII bytes of the caller's name. */
    private static byte[] callPacket(int state, String name) {
        byte[] n = ascii24(name);
        byte[] out = new byte[1 + n.length];
        out[0] = (byte) state;
        System.arraycopy(n, 0, out, 1, n.length);
        return out;
    }

    private void notifyUi() {
        updateNote();
        if (ui != null) ui.onNavState();
    }

    // ---------- notification ----------

    private void goForeground() {
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW));
        shownNote = IDLE_TEXT;
        Notification n = note(IDLE_TEXT);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    private Notification note(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "nav")
                .setContentTitle("BeeNav")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** The notification follows the ride: next turn and time left, or what the app is waiting for. */
    private void updateNote() {
        if (nm == null) return;
        NavRoute r = route;
        String text;
        if (r != null) {
            int min = Math.max(1, Math.round(r.remainingTimeS() / 60f));
            String onto = r.offRoute > OFF_ROUTE_M ? "off route" : NavRoute.ontoText(r.turnAngle, r.turnName);
            text = NavRoute.turnText(r.turnAngle) + " in " + NavRoute.distText(r.distToTurn)
                    + (onto.isEmpty() ? "" : "  ·  " + onto) + "  ·  " + min + " min to " + destName;
        } else if (hasDest) {
            text = message.isEmpty() ? "Finding route to " + destName : message;
        } else {
            text = message.isEmpty() ? IDLE_TEXT : message;
        }
        if (text.equals(shownNote)) return;
        shownNote = text;
        nm.notify(1, note(text));
    }

    // ---------- the trip survives Android killing the app ----------

    private void saveTrip() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("trip", destLat + "," + destLon + "," + System.currentTimeMillis() + "," + destName).apply();
    }

    private void clearTrip() {
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("trip").apply();
    }

    /** Started again after Android killed the service mid-ride: carry on to the same destination. */
    private void resumeTrip() {
        String t = getSharedPreferences("settings", MODE_PRIVATE).getString("trip", null);
        if (t == null) return;
        String[] f = t.split(",", 4);        // the name comes last, so its own commas are safe
        try {
            if (f.length == 4 && System.currentTimeMillis() - Long.parseLong(f[2]) < TRIP_KEEP_MS) {
                destLat = Double.parseDouble(f[0]);
                destLon = Double.parseDouble(f[1]);
                destName = f[3];
                hasDest = true;
                message = "Finding route";   // the first fix asks for the route
                Log.i("BeelineNav", "resuming the trip to " + destName);
                return;
            }
        } catch (NumberFormatException ignored) {
            // a damaged entry: forget it
        }
        clearTrip();
    }
}
