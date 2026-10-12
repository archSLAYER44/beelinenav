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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Finds the "BeelineNav" display, stays connected to it and writes route/pose packets. Main thread only. */
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
    private static final String NAME = "BeelineNav";

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic routeCh, poseCh;
    private boolean running, scanning, busy;
    private long busySince;
    private byte[] pendingRoute, pendingPose;   // only the newest of each is worth sending

    String state = "Starting";
    boolean ready;

    Ble(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    void start() {
        running = true;
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

    // Android allows one GATT operation at a time, so writes go out one by one, route first.
    private void pump() {
        if (!ready || gatt == null) return;
        long now = SystemClock.elapsedRealtime();
        if (busy && now - busySince < 2000) return;

        boolean isRoute = pendingRoute != null;
        byte[] data = isRoute ? pendingRoute : pendingPose;
        if (data == null) return;
        if (isRoute) pendingRoute = null; else pendingPose = null;

        BluetoothGattCharacteristic ch = isRoute ? routeCh : poseCh;
        ch.setWriteType(isRoute ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        ch.setValue(data);
        busy = gatt.writeCharacteristic(ch);
        busySince = now;
        if (!busy && isRoute && pendingRoute == null) pendingRoute = data;   // retry with the next pose
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
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();
        try {
            s.startScan(filters, settings, scanCb);
            scanning = true;
            setState("Searching for display");
        } catch (SecurityException e) {
            setState("Bluetooth permission missing");
        }
    }

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

    private void drop(BluetoothGatt g) {
        g.close();
        if (g != gatt) return;
        gatt = null;
        routeCh = poseCh = null;
        ready = busy = false;
        setState("Display disconnected");
        if (running) main.postDelayed(this::scan, 1500);
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
                pump();
            });
        }
    };
}
