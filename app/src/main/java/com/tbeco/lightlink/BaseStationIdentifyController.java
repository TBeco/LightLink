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
import android.os.SystemClock;

import java.util.List;
import java.util.UUID;

/**
 * Base Station 2.0의 Identify(식별) 명령을 처리하는 일회성 BLE GATT Controller.
 *
 * - 기존 Wake/Sleep Controller 및 상태 조회 코드는 변경하지 않는다.
 * - MainActivity에서 동일 장치의 다른 작업과 충돌하지 않도록 확인한다.
 * - Identify Characteristic에 0x01을 기록한다.
 * - 연결 또는 서비스 검색 단계의 일시적 실패는 1초 간격으로 재시도한다.
 *   최초 1회 + 재시도 2회 = 최대 3회이며, 같은 횟수 제한을 공유한다.
 * - WRITE를 시도한 뒤에는 장치가 이미 명령을 받았을 수 있어 절대 자동 재전송하지 않는다.
 * - WRITE_NO_RESPONSE는 Android가 전송 요청을 수락했다는 의미까지만 확인할 수 있다.
 * - GATT 진단 로그는 AppLog의 공통 태그 LightLink로 출력한다(Debug 전용).
 *
 * 모든 내부 상태 변경과 콜백 처리는 Main/UI 스레드에서 직렬화한다.
 */
public final class BaseStationIdentifyController {

    private static final UUID CONTROL_SERVICE_UUID =
            UUID.fromString("00001523-1212-EFDE-1523-785FEABCD124");
    private static final UUID IDENTIFY_UUID =
            UUID.fromString("00008421-1212-EFDE-1523-785FEABCD124");

    private static final int MAX_GATT_ATTEMPTS  = 3;
    private static final long RETRY_DELAY_MS = 1000L;
    private static final long ATTEMPT_TIMEOUT_MS = 10000L;
    private static final long NO_RESPONSE_SETTLE_MS = 850L;

    private enum Stage {
        IDLE,
        CONNECTING,
        DISCOVERING,
        WRITING,
        WAITING_NO_RESPONSE,
        RETRY_WAIT
    }

    public interface Callback {
        void onFinished(boolean success, String reason);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothGatt activeGatt;
    private Callback callback;
    private String deviceAddress;
    private int connectAttempt;
    private long attemptStartedAtMs;
    private boolean busy;
    private boolean writingWithoutResponse;
    private Stage stage = Stage.IDLE;

    private final Runnable connectRetry = this::startConnectionAttempt;

    /** 연결~WRITE 전체를 시도당 10초로 제한한다. */
    private final Runnable timeout = () -> {
        if (!busy) return;
        logWarn("Attempt timed out; stage=" + stage);
        if (isBeforeWrite()) {
            retryBeforeWrite("GATT timeout during " + stage);
        } else if (stage == Stage.WRITING || stage == Stage.WAITING_NO_RESPONSE) {
            complete(false, "Identify write timeout");
        }
    };

    /** NO_RESPONSE에서만: 원격 수신 확인 없이 짧은 대기 후 GATT를 정리한다. */
    private final Runnable noResponseSettle = () -> {
        if (!busy || stage != Stage.WAITING_NO_RESPONSE) return;
        logDebug("NO_RESPONSE settle complete (remote receipt not confirmed)");
        complete(true, "Identify write queued (no response)");
    };

    public BaseStationIdentifyController(Context context, BluetoothAdapter adapter) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
    }

    /** Main/UI 스레드에서 호출한다. 재시도 대기 중에도 true다. */
    public boolean isBusy() {
        return busy;
    }

    /** Main/UI 스레드에서 호출한다. */
    public void identify(String address, Callback resultCallback) {
        if (busy) {
            if (resultCallback != null) {
                resultCallback.onFinished(false, "Identify already running");
            }
            return;
        }
        if (adapter == null) {
            if (resultCallback != null) {
                resultCallback.onFinished(false, "Bluetooth unavailable");
            }
            return;
        }

        busy = true;
        writingWithoutResponse = false;
        callback = resultCallback;
        deviceAddress = address;
        connectAttempt = 0;
        attemptStartedAtMs = 0L;
        stage = Stage.IDLE;

        logDebug("Identify requested");
        startConnectionAttempt();
    }

    /** 최초 연결 또는 실패 후 연결 재시도를 시작한다. */
    @SuppressLint("MissingPermission")
    private void startConnectionAttempt() {
        if (!busy) return;

        mainHandler.removeCallbacks(connectRetry);
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);

        connectAttempt++;
        attemptStartedAtMs = SystemClock.elapsedRealtime();
        stage = Stage.CONNECTING;
        writingWithoutResponse = false;
        logDebug("connectGatt() start");

        // 한 번의 시도에는 연결·서비스 검색·WRITE를 포함한 총 10초 제한을 둔다.
        mainHandler.postDelayed(timeout, ATTEMPT_TIMEOUT_MS);

        try {
            BluetoothDevice device = adapter.getRemoteDevice(deviceAddress);
            BluetoothGatt gatt = device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                retryBeforeWrite("connectGatt returned null");
            } else {
                activeGatt = gatt;
                logDebug("connectGatt() returned; waiting for connection callback");
            }
        } catch (SecurityException e) {
            logWarn("Bluetooth permission denied: " + e.getClass().getSimpleName());
            complete(false, "Bluetooth permission denied");
        } catch (IllegalArgumentException e) {
            logWarn("Invalid Bluetooth address");
            complete(false, "Invalid Bluetooth address");
        } catch (Exception e) {
            logWarn("connectGatt exception: " + e.getClass().getSimpleName());
            retryBeforeWrite("GATT connect: " + e.getClass().getSimpleName());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt)) return;
                logDebug("onConnectionStateChange: status=" + status
                        + ", newState=" + newState + ", stage=" + stage);

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    String reason = "GATT connection status " + status;
                    if (isBeforeWrite()) {
                        retryBeforeWrite(reason);
                    } else {
                        complete(false, reason);
                    }
                    return;
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (stage != Stage.CONNECTING) return;
                    stage = Stage.DISCOVERING;
                    logDebug("GATT connected; discoverServices() start");
                    try {
                        if (!gatt.discoverServices()) {
                            retryBeforeWrite("Service discovery could not start");
                        }
                    } catch (SecurityException e) {
                        logWarn("Permission denied during service discovery");
                        complete(false, "Bluetooth permission denied");
                    } catch (Exception e) {
                        logWarn("discoverServices exception: " + e.getClass().getSimpleName());
                        retryBeforeWrite("Service discovery failed");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (isBeforeWrite()) {
                        retryBeforeWrite("Disconnected before Identify write");
                    } else {
                        complete(false, "Disconnected during Identify operation");
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt) || stage != Stage.DISCOVERING) return;
                logDebug("onServicesDiscovered: status=" + status);

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    retryBeforeWrite("GATT service discovery status " + status);
                    return;
                }

                try {
                    // status=0이라고 해도 서비스 목록이 비어 있을 수 있으므로
                    // 사용 가능한 서비스 목록과 목표 서비스 검색 결과를 나눠 기록한다.
                    List<BluetoothGattService> services = gatt.getServices();
                    int serviceCount = services == null ? 0 : services.size();
                    logDebug("Discovered service count=" + serviceCount);

                    BluetoothGattService service = gatt.getService(CONTROL_SERVICE_UUID);
                    if (service == null) {
                        logWarn("Control service MISSING: " + CONTROL_SERVICE_UUID);
                        logAvailableServices(services);
                        retryBeforeWrite("Control service unavailable");
                        return;
                    }

                    logDebug("Control service FOUND; characteristic count="
                            + service.getCharacteristics().size());
                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(IDENTIFY_UUID);
                    if (characteristic == null) {
                        logWarn("Identify characteristic MISSING: " + IDENTIFY_UUID);
                        logAvailableCharacteristics(service);
                        retryBeforeWrite("Identify characteristic unavailable");
                        return;
                    }

                    int properties = characteristic.getProperties();
                    boolean hasResponse = (properties
                            & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
                    boolean noResponse = (properties
                            & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
                    logDebug("Identify characteristic FOUND; properties=0x"
                            + Integer.toHexString(properties)
                            + ", WRITE=" + hasResponse
                            + ", WRITE_NO_RESPONSE=" + noResponse);

                    // 실제로 WRITE 속성이 없으면 GATT 캐시 문제라고 단정할 수 없다.
                    // 무의미한 재연결은 피하고 진단 로그만 남긴다.
                    if (!hasResponse && !noResponse) {
                        complete(false, "Identify characteristic is not writable");
                        return;
                    }

                    writingWithoutResponse = noResponse;
                    characteristic.setWriteType(noResponse
                            ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    characteristic.setValue(new byte[]{0x01});

                    // 중요: 여기부터는 장치가 이미 명령을 받았을 가능성이 있으므로
                    // writeCharacteristic()이 false를 반환해도 재시도하지 않는다.
                    stage = Stage.WRITING;
                    logDebug("Identify 0x01 write start; mode="
                            + (noResponse ? "NO_RESPONSE" : "WITH_RESPONSE"));

                    try {
                        if (!gatt.writeCharacteristic(characteristic)) {
                            complete(false, "Identify write could not start");
                            return;
                        }
                    } catch (SecurityException e) {
                        logWarn("Permission denied during Identify write");
                        complete(false, "Bluetooth permission denied");
                        return;
                    } catch (Exception e) {
                        logWarn("writeCharacteristic exception: " + e.getClass().getSimpleName());
                        complete(false, "Identify write failed: "
                                + e.getClass().getSimpleName());
                        return;
                    }

                    logDebug("writeCharacteristic() accepted by Android BLE stack");
                    if (noResponse) {
                        stage = Stage.WAITING_NO_RESPONSE;
                        mainHandler.postDelayed(noResponseSettle, NO_RESPONSE_SETTLE_MS);
                    }
                } catch (SecurityException e) {
                    logWarn("Permission denied during GATT service inspection");
                    complete(false, "Bluetooth permission denied");
                } catch (Exception e) {
                    // 아직 WRITE 이전이라면 검색 단계의 일시적인 예외에만 재시도.
                    logWarn("GATT service inspection exception: "
                            + e.getClass().getSimpleName());
                    if (isBeforeWrite()) {
                        retryBeforeWrite("Service inspection failed");
                    } else {
                        complete(false, "Identify operation failed: "
                                + e.getClass().getSimpleName());
                    }
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status) {
            mainHandler.post(() -> {
                if (!isCurrent(gatt)
                        || characteristic == null
                        || !IDENTIFY_UUID.equals(characteristic.getUuid())) {
                    return;
                }

                logDebug("onCharacteristicWrite: status=" + status
                        + ", noResponse=" + writingWithoutResponse);

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    complete(false, "Identify write status " + status);
                } else if (!writingWithoutResponse) {
                    complete(true, "Identify write acknowledged");
                }
                // NO_RESPONSE의 성공 콜백은 원격 수신 확인이 아니므로
                // 짧은 settle 시간이 끝날 때까지 연결을 유지한다.
            });
        }
    };

    /** WRITE가 아직 시작되지 않은 단계인지 확인한다. */
    private boolean isBeforeWrite() {
        return stage == Stage.CONNECTING || stage == Stage.DISCOVERING;
    }

    /**
     * GATT 작업이 시작되기 전에 발생한 연결/검색 실패에 한해 재시도한다.
     * 같은 작업에서 연결 실패와 서비스 검색 실패가 함께 발생해도 최대 3회만 시도한다.
     */
    private void retryBeforeWrite(String reason) {
        if (!busy || !isBeforeWrite()) return;

        logWarn("Pre-write attempt failed: " + reason);
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);
        closeGatt();

        if (connectAttempt >= MAX_GATT_ATTEMPTS ) {
            logWarn("All " + MAX_GATT_ATTEMPTS  + " attempts exhausted");
            complete(false, reason);
            return;
        }

        stage = Stage.RETRY_WAIT;
        logDebug("Retry scheduled in " + RETRY_DELAY_MS + "ms"
                + " (next attempt " + (connectAttempt + 1)
                + "/" + MAX_GATT_ATTEMPTS  + ")");
        mainHandler.postDelayed(connectRetry, RETRY_DELAY_MS);
    }

    /** 이전 시도에서 도착한 늦은 GATT 콜백은 무시한다. */
    private boolean isCurrent(BluetoothGatt gatt) {
        return busy && activeGatt == gatt;
    }

    private void logAvailableServices(List<BluetoothGattService> services) {
        if (services == null || services.isEmpty()) {
            logDebug("Available services: NONE");
            return;
        }
        for (BluetoothGattService service : services) {
            logDebug("Available service UUID: " + service.getUuid());
        }
    }

    private void logAvailableCharacteristics(BluetoothGattService service) {
        for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
            logDebug("Control characteristic UUID: " + characteristic.getUuid()
                    + ", properties=0x"
                    + Integer.toHexString(characteristic.getProperties()));
        }
    }

    private void complete(boolean success, String reason) {
        if (!busy) return;
        if (success) {
            logDebug("Identify finished successfully: " + reason);
        } else {
            logWarn("Identify finished with failure: " + reason);
        }

        busy = false;
        writingWithoutResponse = false;
        stage = Stage.IDLE;
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);
        mainHandler.removeCallbacks(connectRetry);
        closeGatt();

        Callback finished = callback;
        callback = null;
        deviceAddress = null;
        connectAttempt = 0;
        attemptStartedAtMs = 0L;

        if (finished != null) finished.onFinished(success, reason);
    }

    /** 재검색, Bluetooth OFF 또는 Activity 종료 시 취소한다. Main/UI 스레드에서 호출한다. */
    public void close() {
        if (busy) logDebug("Identify canceled by owner");
        busy = false;
        writingWithoutResponse = false;
        stage = Stage.IDLE;
        callback = null;
        mainHandler.removeCallbacks(timeout);
        mainHandler.removeCallbacks(noResponseSettle);
        mainHandler.removeCallbacks(connectRetry);
        closeGatt();
        deviceAddress = null;
        connectAttempt = 0;
        attemptStartedAtMs = 0L;
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        BluetoothGatt gatt = activeGatt;
        activeGatt = null;
        if (gatt == null) return;

        logDebug("Releasing GATT (disconnect + close)");
        try {
            gatt.disconnect();
        } catch (SecurityException e) {
            logWarn("GATT disconnect permission denied");
        } catch (Exception e) {
            logWarn("GATT disconnect exception: " + e.getClass().getSimpleName());
        }
        try {
            gatt.close();
        } catch (Exception e) {
            logWarn("GATT close exception: " + e.getClass().getSimpleName());
        }
    }

    /** Logcat TAG는 AppLog의 LightLink를 사용하고 메시지에 [Identify]를 붙인다. */
    private String logPrefix() {
        long elapsedMs = attemptStartedAtMs == 0L ? 0L
                : SystemClock.elapsedRealtime() - attemptStartedAtMs;
        return "[Identify] [" + (deviceAddress == null ? "unknown" : deviceAddress)
                + "] [attempt " + connectAttempt + "/" + MAX_GATT_ATTEMPTS  + "]"
                + " [+" + elapsedMs + "ms] ";
    }

    private void logDebug(String message) {
        AppLog.d(logPrefix() + message);
    }

    private void logWarn(String message) {
        AppLog.w(logPrefix() + message);
    }
}
