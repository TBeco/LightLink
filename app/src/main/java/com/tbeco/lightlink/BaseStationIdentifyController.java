package com.tbeco.lightlink;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Base Station 2.0 Identify 명령만 처리하는 일회성 BLE GATT Controller.
 * Wake/Sleep Controller와 분리하며, 동일 장치에서 작업이 겹치지 않도록
 * MainActivity에서 busy 여부를 확인한다.
 *
 * Identify Characteristic에 0x01 한 바이트만 기록한다.
 * WRITE_NO_RESPONSE 전용 장치는 Android 스택이 Write를 수락했다는 것까지만
 * 확인 가능하며, 실제 LED 점멸 여부는 사용자/실기기로 검증해야 한다.
 */
public final class BaseStationIdentifyController {

    private static final UUID CONTROL_SERVICE_UUID =
            UUID.fromString("00001523-1212-EFDE-1523-785FEABCD124");
    private static final UUID IDENTIFY_UUID =
            UUID.fromString("00008421-1212-EFDE-1523-785FEABCD124");

    private static final long COMMAND_TIMEOUT_MS = 10000L;
    private static final long NO_RESPONSE_SETTLE_MS = 850L;

    public interface Callback {
        void onFinished(boolean success, String reason);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt activeGatt;
    private Callback callback;
    private boolean busy;
    private boolean writingWithoutResponse;

    private final Runnable timeout = () -> complete(false, "Identify timeout");
    private final Runnable noResponseSettle = () ->
            complete(true, "Identify write queued (no response)");

    public BaseStationIdentifyController(Context context, BluetoothAdapter adapter) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
    }

    /** Main/UI 스레드에서 호출한다. */
    public boolean isBusy() {
        return busy;
    }

    @SuppressLint("MissingPermission")
    public void identify(String address, Callback resultCallback) {
        if (busy) {
            resultCallback.onFinished(false, "Identify already running");
            return;
        }
        if (adapter == null) {
            resultCallback.onFinished(false, "Bluetooth unavailable");
            return;
        }

        busy = true;
        writingWithoutResponse = false;
        callback = resultCallback;
        mainHandler.postDelayed(timeout, COMMAND_TIMEOUT_MS);

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            BluetoothGatt gatt = device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                complete(false, "connectGatt returned null");
            } else {
                activeGatt = gatt;
            }
        } catch (Exception e) {
            complete(false, "GATT connect: " + e.getClass().getSimpleName());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(false, "GATT connection status " + status);
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!gatt.discoverServices()) {
                            complete(false, "Service discovery could not start");
                        }
                    } catch (Exception e) {
                        complete(false, "Service discovery failed");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    complete(false, "Disconnected before Identify write");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(false, "GATT service discovery status " + status);
                    return;
                }
                BluetoothGattService service = gatt.getService(CONTROL_SERVICE_UUID);
                BluetoothGattCharacteristic characteristic = service == null
                        ? null : service.getCharacteristic(IDENTIFY_UUID);
                if (characteristic == null) {
                    complete(false, "Identify characteristic unavailable");
                    return;
                }

                final int properties = characteristic.getProperties();
                final boolean hasResponse =
                        (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                final boolean noResponse =
                        (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                if (!hasResponse && !noResponse) {
                    complete(false, "Identify characteristic is not writable");
                    return;
                }

                // Base Station 역공학 구현에서 NO_RESPONSE 전송을 사용하므로
                // 지원할 경우 해당 방식 우선, 아니면 응답을 받는 WRITE 사용.
                writingWithoutResponse = noResponse;
                characteristic.setWriteType(noResponse
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(new byte[]{0x01});
                try {
                    if (!gatt.writeCharacteristic(characteristic)) {
                        complete(false, "Identify write could not start");
                        return;
                    }
                } catch (Exception e) {
                    complete(false, "Identify write failed: "
                            + e.getClass().getSimpleName());
                    return;
                }

                // NO_RESPONSE는 수신 확인이 없다. 일시적으로 연결을 유지하여
                // Write가 전송될 기회를 준 후 정리한다. 콜백이 먼저 오면 즉시 종료.
                if (noResponse) {
                    mainHandler.postDelayed(noResponseSettle, NO_RESPONSE_SETTLE_MS);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt) || !IDENTIFY_UUID.equals(characteristic.getUuid())) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(false, "Identify write status " + status);
                } else if (!writingWithoutResponse) {
                    complete(true, "Identify write acknowledged");
                }
                // NO_RESPONSE 콜백 성공은 원격 장치의 수신 확인이 아니므로
                // 연결을 너무 빨리 닫지 않고 짧은 settle 시간까지 유지한다.
            });
        }
    };

    private boolean isCurrent(BluetoothGatt gatt) {
        return busy && activeGatt == gatt;
    }

    private void complete(boolean success, String reason) {
        if (!busy) return;
        busy = false;
        writingWithoutResponse = false;
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);
        closeGatt();
        Callback finished = callback;
        callback = null;
        if (finished != null) finished.onFinished(success, reason);
    }

    /** 재검색, Bluetooth OFF 또는 Activity 종료 시 취소한다. */
    public void close() {
        busy = false;
        writingWithoutResponse = false;
        callback = null;
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);
        closeGatt();
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt gatt = activeGatt;
        activeGatt = null;
        if (gatt == null) return;
        try { gatt.disconnect(); } catch (Exception ignored) { }
        try { gatt.close(); } catch (Exception ignored) { }
    }
}
