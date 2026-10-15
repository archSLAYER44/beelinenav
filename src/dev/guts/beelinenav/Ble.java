package dev.guts.beelinenav;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Finds the "BeeNav" display, stays connected to it and writes route/pose packets. Main thread only. */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
final class Ble {
    interface Listener {
        void onBleState();
        void onBleReady();
    }

    private static final UUID SVC = UUID.fromString("7b1e0001-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID ROUTE = UUID.fromString("7b1e0002-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID POSE = UUID.fromString("7b1e0003-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID CALL = UUID.fromString("7b1e0004-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID TIME = UUID.fromString("7b1e0005-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID ROADS = UUID.fromString("7b1e0006-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final UUID TURN = UUID.fromString("7b1e0007-5c3a-4f6e-9d2b-8a4c6e0f1a2b");
    private static final String NAME = "BeeNav";
    private static final long FAST_SCAN_MS = 30000;   // scan flat out at first, then ease off to save battery

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic routeCh, poseCh, callCh, timeCh, roadsCh, turnCh;
    private boolean running, scanning, busy;
    private long busySince, fastUntil;
    private byte[] pendingRoute, pendingPose, pendingCall, pendingTime, pendingTurn;   // only the newest of each counts
    private final ArrayDeque<byte[]> pendingRoads = new ArrayDeque<>();  // one side-road set, in chunks
    private BluetoothGattCharacteristic inFlight;                         // the write we are waiting on, for a retry
    private byte[] inFlightData;
    private int retries;                                                  // failed attempts at the current packet

    String state = "Starting";
    boolean ready;
    int writeErrors;                                                      // since connecting; shown in the app

    Ble(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void start() {
        running = true;
        fastUntil = SystemClock.elapsedRealtime() + FAST_SCAN_MS;
        scan();
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
        stopScan();
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        ready = false;
    }

    void sendRoute(byte[] packet) {
        pendingRoute = packet;
        pump();
    }

    void sendPose(byte[] packet) {
        pendingPose = packet;
        pump();
    }

    void sendCall(byte[] packet) {
        pendingCall = packet;
        pump();
    }

    void sendTime(byte[] packet) {
        pendingTime = packet;
        pump();
    }

    /** The road after the next turn, for the sign at the top of the display. */
    void sendTurn(byte[] packet) {
        pendingTurn = packet;
        pump();
    }

    /** Replaces whatever side-road set was still going out with this one. */
    void sendRoads(List<byte[]> packets) {
        pendingRoads.clear();
        pendingRoads.addAll(packets);
        pump();
    }

    // Android allows one GATT operation at a time, so writes go out one by one: route, turn, call, time,
    // pose, and only then the side roads. Those are decoration and must never hold up the arrow.
    private void pump() {
        if (!ready || gatt == null) return;
        long now = SystemClock.elapsedRealtime();
        if (busy && now - busySince < 2000) return;
        if (pendingCall != null && callCh == null) pendingCall = null;   // old firmware without the call screen
        if (pendingTime != null && timeCh == null) pendingTime = null;
        if (pendingTurn != null && turnCh == null) pendingTurn = null;
        if (roadsCh == null) pendingRoads.clear();

        BluetoothGattCharacteristic ch;
        byte[] data;
        if (pendingRoute != null) {
            ch = routeCh;
            data = pendingRoute;
            pendingRoute = null;
        } else if (pendingTurn != null) {
            ch = turnCh;
            data = pendingTurn;
            pendingTurn = null;
        } else if (pendingCall != null) {
            ch = callCh;
            data = pendingCall;
            pendingCall = null;
        } else if (pendingTime != null) {
            ch = timeCh;
            data = pendingTime;
            pendingTime = null;
        } else if (pendingPose != null) {
            ch = poseCh;
            data = pendingPose;
            pendingPose = null;
        } else if (!pendingRoads.isEmpty()) {
            ch = roadsCh;
            data = pendingRoads.poll();
        } else {
            return;
        }
        ch.setWriteType(ch == poseCh ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        ch.setValue(data);
        busy = gatt.writeCharacteristic(ch);
        busySince = now;
        inFlight = busy ? ch : null;
        inFlightData = busy ? data : null;
        if (!busy) requeue(ch, data);   // write refused: keep the important ones for the next attempt
    }

    /** Puts a packet back at the head of its queue. A pose is not kept: a newer one is on its way. */
    private void requeue(BluetoothGattCharacteristic ch, byte[] data) {
        if (ch == routeCh && pendingRoute == null) pendingRoute = data;
        else if (ch == callCh && pendingCall == null) pendingCall = data;
        else if (ch == timeCh && pendingTime == null) pendingTime = data;
        else if (ch == turnCh && pendingTurn == null) pendingTurn = data;
        else if (ch == roadsCh) pendingRoads.addFirst(data);
    }

    private void scan() {
        if (!running || scanning || gatt != null) return;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = m == null ? null : m.getAdapter();
        BluetoothLeScanner s = a != null && a.isEnabled() ? a.getBluetoothLeScanner() : null;
        if (s == null) {
            setState("Bluetooth is off");
            main.postDelayed(this::scan, 3000);
            return;
        }
        List<ScanFilter> filters = Arrays.asList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SVC)).build(),
                new ScanFilter.Builder().setDeviceName(NAME).build());
        boolean fast = SystemClock.elapsedRealtime() < fastUntil;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(fast ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_BALANCED)
                .build();
        try {
            s.startScan(filters, settings, scanCb);
            scanning = true;
            setState("Searching for display");
            main.removeCallbacks(slowDown);
            if (fast) main.postDelayed(slowDown, FAST_SCAN_MS);
        } catch (SecurityException e) {
            setState("Bluetooth permission missing");
        }
    }

    private final Runnable slowDown = () -> {
        if (!scanning) return;
        stopScan();
        scan();
    };

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try {
            BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothLeScanner s = m.getAdapter().getBluetoothLeScanner();
            if (s != null) s.stopScan(scanCb);
        } catch (RuntimeException ignored) {
            // Bluetooth was switched off mid-scan; nothing left to stop
        }
    }

    private void connect(BluetoothDevice dev) {
        if (!running || gatt != null) return;
        stopScan();
        setState("Connecting");
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
    }

    /** Android caches a device's GATT table forever; this hidden call makes it discover afresh. */
    private static void refreshCache(BluetoothGatt g) {
        try {
            g.getClass().getMethod("refresh").invoke(g);
        } catch (Exception ignored) {
            // not available on this Android build; the cache then only clears when Bluetooth is toggled
        }
    }

    private void drop(BluetoothGatt g) {
        g.close();
        if (g != gatt) return;
        gatt = null;
        routeCh = poseCh = callCh = timeCh = roadsCh = turnCh = inFlight = null;
        inFlightData = null;
        pendingRoads.clear();
        ready = busy = false;
        writeErrors = retries = 0;
        setState("Display disconnected");
        fastUntil = SystemClock.elapsedRealtime() + FAST_SCAN_MS;
        if (running) main.postDelayed(this::scan, 300);
    }

    private void setState(String s) {
        state = s;
        listener.onBleState();
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            main.post(() -> connect(result.getDevice()));
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> {
                scanning = false;
                setState("Scan failed (" + errorCode + ")");
                main.postDelayed(Ble.this::scan, 5000);
            });
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    refreshCache(g);   // the display's service list has grown over firmware updates
                    if (!g.requestMtu(247)) g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    drop(g);
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(g::discoverServices);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g != gatt) return;
                BluetoothGattService svc = g.getService(SVC);
                routeCh = svc == null ? null : svc.getCharacteristic(ROUTE);
                poseCh = svc == null ? null : svc.getCharacteristic(POSE);
                callCh = svc == null ? null : svc.getCharacteristic(CALL);   // absent on older firmware
                timeCh = svc == null ? null : svc.getCharacteristic(TIME);
                roadsCh = svc == null ? null : svc.getCharacteristic(ROADS);
                turnCh = svc == null ? null : svc.getCharacteristic(TURN);
                if (routeCh == null || poseCh == null) {
                    g.disconnect();
                    return;
                }
                ready = true;
                busy = false;
                setState("Display connected");
                listener.onBleReady();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                busy = false;
                BluetoothGattCharacteristic ch = inFlight;
                byte[] data = inFlightData;
                inFlight = null;
                inFlightData = null;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    retries = 0;
                } else {
                    writeErrors++;
                    Log.w("BeelineNav", "write failed, status " + status + " on characteristic "
                            + c.getUuid().toString().substring(4, 8));
                    if (ch == roadsCh) {
                        pendingRoads.clear();                       // decoration: not worth a retry storm
                    } else if (ch != null && ch != poseCh && data != null && retries < 3) {
                        retries++;
                        requeue(ch, data);                          // route, call, time: try again
                    } else {
                        retries = 0;
                    }
                    listener.onBleState();
                }
                pump();
            });
        }
    };
}
