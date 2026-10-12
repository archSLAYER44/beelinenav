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
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Keeps GPS, routing and the display link alive while the screen is off. Everything runs on the main thread. */
public final class NavService extends Service implements LocationListener, Ble.Listener {
    interface Listener { void onNavState(); }

    static NavService instance;
    static Listener ui;

    private static final double OFF_ROUTE_M = 50, REBASE_M = 20000;
    private static final long REROUTE_GAP_MS = 15000;

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
    private int offCount, sentSeg = -1;
    private long lastRouteRequest, lastGpsMs = -100000;
    private BroadcastReceiver callRx;
    private final Handler timer = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Router.loadKey(this);
        Theme.load(this);
        goForeground();
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
        route = r;
        sentSeg = -1;
        offCount = 0;
        message = "";
        lastRouteRequest = SystemClock.elapsedRealtime();
        if (last != null) tick();
        notifyUi();
    }

    void stopNav() {
        hasDest = false;
        route = null;
        message = "";
        ble.sendRoute(new byte[]{0});
        if (last != null) tick();
        notifyUi();
    }

    // ---------- GPS ----------

    @Override
    public void onLocationChanged(Location loc) {
        long now = SystemClock.elapsedRealtime();
        if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) lastGpsMs = now;
        else if (now - lastGpsMs < 10000) return;   // GPS is live; ignore the rougher network fix
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
            ble.sendPose(pose(0, 0, heading, speed, 0, 0, day));   // display shows a plain speedometer
            return;
        }
        double px = r.toX(last.getLongitude()), py = r.toY(last.getLatitude());
        if (Math.hypot(px, py) > REBASE_M) {
            r.setOrigin(last.getLatitude(), last.getLongitude());
            px = py = 0;
            sentSeg = -1;
        }
        r.snap(px, py);
        if (!haveHeading) heading = (float) r.bearing();
        if (r.seg != sentSeg) {
            ble.sendRoute(r.routePacket());
            sentSeg = r.seg;
        }
        ble.sendPose(pose(px, py, heading, speed, r.distToTurn, r.turnAngle, day));

        offCount = r.offRoute > OFF_ROUTE_M ? offCount + 1 : 0;
        long now = SystemClock.elapsedRealtime();
        if (offCount >= 5 && !routing && now - lastRouteRequest > REROUTE_GAP_MS) requestRoute();
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
                sentSeg = -1;
                offCount = 0;
                message = "";
                tick();
            } else {
                message = err;
            }
            notifyUi();
        });
    }

    /** Pose packet, 17 bytes LE: int32 x, y (dm), uint16 heading (0.01 deg), speed (cm/s), dist (m), int16 angle, uint8 flags (bit0 = day). */
    private static byte[] pose(double x, double y, float heading, float speed, double dist, int angle, boolean day) {
        float h = ((heading % 360) + 360) % 360;
        ByteBuffer b = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) Math.round(x * 10));
        b.putInt((int) Math.round(y * 10));
        b.putShort((short) Math.min(35999, Math.round(h * 100)));
        b.putShort((short) Math.min(65535, Math.round(speed * 100)));
        b.putShort((short) Math.min(65535, Math.round(dist)));
        b.putShort((short) angle);
        b.put((byte) (day ? 1 : 0));
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
        if (callState != 0) ble.sendCall(callPacket(callState, callName));
        if (route == null) ble.sendRoute(new byte[]{0});
        if (last != null) tick();
        notifyUi();
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
            timer.postDelayed(this, 60000);
        }
    };

    /** Called when the user changes the theme mode in the app. */
    void refreshTheme() {
        timer.removeCallbacks(themeCheck);
        timer.post(themeCheck);
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
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "").trim();   // the display font is ASCII only
        if (ascii.length() > 24) ascii = ascii.substring(0, 24);
        byte[] n = ascii.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[1 + n.length];
        out[0] = (byte) state;
        System.arraycopy(n, 0, out, 1, n.length);
        return out;
    }

    private void notifyUi() {
        if (ui != null) ui.onNavState();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "nav")
                .setContentTitle("Beeline Nav")
                .setContentText("Sending speed and directions to the display")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }
}
