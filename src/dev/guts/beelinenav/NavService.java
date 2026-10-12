package dev.guts.beelinenav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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

    private double destLat, destLon;
    private float heading;
    private boolean haveHeading;
    private int offCount, sentSeg = -1;
    private long lastRouteRequest, lastGpsMs = -100000;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Router.loadKey(this);
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
        ble.stop();
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
        NavRoute r = route;
        if (r == null) {
            ble.sendPose(pose(0, 0, heading, speed, 0, 0));   // display shows a plain speedometer
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
        ble.sendPose(pose(px, py, heading, speed, r.distToTurn, r.turnAngle));

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

    /** Pose packet, 16 bytes LE: int32 x, y (dm), uint16 heading (0.01 deg), speed (cm/s), dist (m), int16 angle. */
    private static byte[] pose(double x, double y, float heading, float speed, double dist, int angle) {
        float h = ((heading % 360) + 360) % 360;
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) Math.round(x * 10));
        b.putInt((int) Math.round(y * 10));
        b.putShort((short) Math.min(35999, Math.round(h * 100)));
        b.putShort((short) Math.min(65535, Math.round(speed * 100)));
        b.putShort((short) Math.min(65535, Math.round(dist)));
        b.putShort((short) angle);
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
        if (route == null) ble.sendRoute(new byte[]{0});
        if (last != null) tick();
        notifyUi();
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
