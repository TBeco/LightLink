package com.tbeco.lightlink;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Base Station 2.0 RF 채널 변경 전용 BLE GATT Controller.
 *
 * 역할:
 * - 독립적인 GATT 연결 생성
 * - Mode Characteristic 검색
 * - WRITE 지원 여부 확인
 * - RF 채널 변경 명령 전송
 * - WRITE 결과 전달
 * - GATT 연결 및 자원 정리
 *
 * 기존 Wake/Sleep 및 Identify Controller와 독립적으로 동작한다.
 *
 * 주의:
 * - 인자로 받는 값은 화면에 표시하는 채널 번호가 아닌 BLE 원시 값이다.
 * - WRITE 성공만으로 실제 채널 변경이 완료되었다고 판단하지 않는다.
 * - 실제 변경 결과는 이후 별도의 READ로 검증해야 한다.
 * - WRITE를 시도한 이후에는 명령 중복을 방지하기 위해 자동 재전송하지 않는다.
 *
 * 모든 내부 상태 변경은 Main 스레드에서 처리한다.
 */
public final class BaseStationChannelController {

    /*
     * Base Station 2.0 Control Service.
     */
    private static final UUID CONTROL_SERVICE_UUID =
            UUID.fromString(
                    "00001523-1212-EFDE-1523-785FEABCD124"
            );

    /*
     * Base Station 2.0 Mode / RF Channel Characteristic.
     */
    private static final UUID MODE_UUID =
            UUID.fromString(
                    "00001524-1212-EFDE-1523-785FEABCD124"
            );

    /*
     * GATT 연결부터 WRITE 처리까지의 최대 대기 시간.
     */
    private static final long COMMAND_TIMEOUT_MS = 12000L;

    /*
     * WRITE_NO_RESPONSE 방식일 때
     * 전송 요청 이후 연결을 잠시 유지하는 시간.
     *
     * 실제 장치의 수신 성공을 의미하지 않는다.
     */
    private static final long NO_RESPONSE_SETTLE_MS = 850L;

    public interface Callback {

        /*
         * accepted:
         * Android BLE Stack에서 WRITE 요청을 처리했는지 여부.
         *
         * acknowledged:
         * 응답을 요구하는 WRITE에서 GATT 성공 콜백을 받았는지 여부.
         *
         * 두 값 모두 실제 RF 채널 변경 완료를 보장하지 않는다.
         */
        void onFinished(
                boolean accepted,
                boolean acknowledged,
                String reason
        );
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private BluetoothGatt currentGatt;
    private Callback currentCallback;

    private boolean busy = false;
    private boolean writeStarted = false;
    private boolean writingWithoutResponse = false;

    private int requestedRawChannel = -1;

    /*
     * 명령 처리 제한 시간.
     */
    private final Runnable commandTimeout = () -> {

        if (!busy) {
            return;
        }

        if (writeStarted) {

            finish(
                    false,
                    false,
                    "Channel WRITE result unknown (timeout)"
            );

        } else {

            finish(
                    false,
                    false,
                    "Channel GATT operation timed out"
            );
        }
    };

    /*
     * WRITE_NO_RESPONSE에서는 장치 수신 확인 없이
     * Android 측 전송 요청까지만 성공으로 처리한다.
     */
    private final Runnable noResponseSettle = () -> {

        if (!busy
                || !writeStarted
                || !writingWithoutResponse) {
            return;
        }

        finish(
                true,
                false,
                "Channel WRITE queued without response"
        );
    };

    public BaseStationChannelController(
            Context context,
            BluetoothAdapter bluetoothAdapter) {

        this.context =
                context.getApplicationContext();

        this.bluetoothAdapter =
                bluetoothAdapter;
    }

    /*
     * 현재 채널 변경 작업이 진행 중인지 확인한다.
     *
     * Main/UI 스레드에서 호출한다.
     */
    public boolean isBusy() {
        return busy;
    }

    /**
     * RF 채널 WRITE 요청.
     *
     * rawChannel:
     * Mode Characteristic에 기록할 원시 바이트 값.
     *
     * 이 메서드는 화면 채널 번호를 변환하지 않는다.
     * UI 채널과 BLE 원시 값의 대응 관계는 호출 전에 확정해야 한다.
     *
     * Base Station 2.0 실기기에서 검증한
     * RF 채널 1~16 범위의 원시 값만 허용한다.
     *
     * Main/UI 스레드에서 호출한다.
     */
    public void writeRawChannel(
            String address,
            int rawChannel,
            Callback callback) {

        if (busy) {

            if (callback != null) {
                callback.onFinished(
                        false,
                        false,
                        "Channel operation already running"
                );
            }

            return;
        }

        if (bluetoothAdapter == null) {

            if (callback != null) {
                callback.onFinished(
                        false,
                        false,
                        "Bluetooth unavailable"
                );
            }

            return;
        }

        /*
         * 원시 채널 값 범위 검증.
         */
        if (rawChannel < 1 || rawChannel > 16) {

            if (callback != null) {
                callback.onFinished(
                        false,
                        false,
                        "Invalid raw channel value"
                );
            }

            return;
        }

        busy = true;
        writeStarted = false;
        writingWithoutResponse = false;

        requestedRawChannel = rawChannel;
        currentCallback = callback;

        AppLog.d(
                "[Channel] WRITE requested"
                        + " / raw = "
                        + rawChannel
        );

        handler.postDelayed(
                commandTimeout,
                COMMAND_TIMEOUT_MS
        );

        /*
         * Base Station과 독립적인 GATT 연결 생성.
         */
        try {

            BluetoothDevice device =
                    bluetoothAdapter.getRemoteDevice(
                            address
                    );

            currentGatt = device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
            );

            if (currentGatt == null) {

                finish(
                        false,
                        false,
                        "Channel connectGatt returned null"
                );
            }

        } catch (SecurityException e) {

            finish(
                    false,
                    false,
                    "Bluetooth permission denied"
            );

        } catch (Exception e) {

            AppLog.w(
                    "[Channel] Connection exception",
                    e
            );

            finish(
                    false,
                    false,
                    "Channel connection failed"
            );
        }
    }

    /*
     * GATT 연결 및 명령 처리 콜백.
     */
    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {

                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int status,
                        int newState) {

                    handler.post(() -> {

                        if (!isCurrentGatt(gatt)) {
                            return;
                        }

                        if (status != BluetoothGatt.GATT_SUCCESS) {

                            finish(
                                    false,
                                    false,
                                    writeStarted
                                            ? "Channel connection lost after WRITE attempt"
                                            : "Channel GATT connection failed: " + status
                            );

                            return;
                        }

                        if (newState == BluetoothProfile.STATE_CONNECTED) {

                            AppLog.d(
                                    "[Channel] GATT connected"
                            );

                            try {

                                if (!gatt.discoverServices()) {

                                    finish(
                                            false,
                                            false,
                                            "Channel service discovery could not start"
                                    );
                                }

                            } catch (SecurityException e) {

                                finish(
                                        false,
                                        false,
                                        "Bluetooth permission denied"
                                );

                            } catch (Exception e) {

                                finish(
                                        false,
                                        false,
                                        "Channel service discovery failed"
                                );
                            }

                        } else if (newState ==
                                BluetoothProfile.STATE_DISCONNECTED) {

                            finish(
                                    false,
                                    false,
                                    writeStarted
                                            ? "Channel disconnected after WRITE attempt"
                                            : "Channel disconnected before WRITE"
                            );
                        }
                    });
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int status) {

                    handler.post(() -> {

                        if (!isCurrentGatt(gatt)) {
                            return;
                        }

                        if (status != BluetoothGatt.GATT_SUCCESS) {

                            finish(
                                    false,
                                    false,
                                    "Channel service discovery failed: " + status
                            );

                            return;
                        }

                        BluetoothGattService service =
                                gatt.getService(
                                        CONTROL_SERVICE_UUID
                                );

                        if (service == null) {

                            finish(
                                    false,
                                    false,
                                    "Channel control service unavailable"
                            );

                            return;
                        }

                        BluetoothGattCharacteristic characteristic =
                                service.getCharacteristic(
                                        MODE_UUID
                                );

                        if (characteristic == null) {

                            finish(
                                    false,
                                    false,
                                    "Channel Mode Characteristic unavailable"
                            );

                            return;
                        }

                        /*
                         * WRITE 종류는 실제 Characteristic 속성을 확인한다.
                         *
                         * 가능하면 응답이 있는 WRITE를 우선 사용한다.
                         */
                        int properties =
                                characteristic.getProperties();

                        boolean supportsWrite =
                                (properties
                                        & BluetoothGattCharacteristic.PROPERTY_WRITE)
                                        != 0;

                        boolean supportsNoResponse =
                                (properties
                                        & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                                        != 0;

                        if (!supportsWrite && !supportsNoResponse) {

                            finish(
                                    false,
                                    false,
                                    "Channel WRITE unsupported"
                            );

                            return;
                        }

                        writingWithoutResponse =
                                !supportsWrite;

                        int writeType =
                                writingWithoutResponse
                                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

                        byte[] payload = {
                                (byte) requestedRawChannel
                        };

                        AppLog.d(
                                "[Channel] WRITE start"
                                        + " / raw = "
                                        + requestedRawChannel
                                        + " / type = "
                                        + (writingWithoutResponse
                                        ? "NO_RESPONSE"
                                        : "DEFAULT")
                        );

                        /*
                         * 이 시점 이후에는 장치가 명령을 받았을 가능성이 있으므로
                         * 자동으로 WRITE를 재전송하지 않는다.
                         */
                        writeStarted = true;

                        try {

                            boolean accepted;

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                                int result = gatt.writeCharacteristic(
                                        characteristic,
                                        payload,
                                        writeType
                                );

                                accepted =
                                        result == BluetoothStatusCodes.SUCCESS;

                            } else {

                                characteristic.setWriteType(
                                        writeType
                                );

                                characteristic.setValue(
                                        payload
                                );

                                accepted =
                                        gatt.writeCharacteristic(
                                                characteristic
                                        );
                            }

                            if (!accepted) {

                                finish(
                                        false,
                                        false,
                                        "Channel WRITE could not start"
                                );

                                return;
                            }

                            if (writingWithoutResponse) {

                                handler.postDelayed(
                                        noResponseSettle,
                                        NO_RESPONSE_SETTLE_MS
                                );
                            }

                        } catch (SecurityException e) {

                            finish(
                                    false,
                                    false,
                                    "Bluetooth permission denied"
                            );

                        } catch (Exception e) {

                            AppLog.w(
                                    "[Channel] WRITE exception",
                                    e
                            );

                            finish(
                                    false,
                                    false,
                                    "Channel WRITE failed"
                            );
                        }
                    });
                }

                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {

                    handler.post(() -> {

                        if (!isCurrentGatt(gatt)
                                || !writeStarted
                                || characteristic == null
                                || !MODE_UUID.equals(
                                characteristic.getUuid()
                        )) {
                            return;
                        }

                        AppLog.d(
                                "[Channel] onCharacteristicWrite"
                                        + " / status = "
                                        + status
                        );

                        if (status != BluetoothGatt.GATT_SUCCESS) {

                            finish(
                                    false,
                                    false,
                                    "Channel WRITE status: " + status
                            );

                            return;
                        }

                        /*
                         * WRITE_DEFAULT:
                         * GATT 응답 확인.
                         *
                         * WRITE_NO_RESPONSE:
                         * 실제 장치 수신은 확인할 수 없으므로
                         * 예약된 settle 처리에서 완료한다.
                         */
                        if (!writingWithoutResponse) {

                            finish(
                                    true,
                                    true,
                                    "Channel WRITE acknowledged"
                            );
                        }
                    });
                }
            };

    /*
     * 현재 작업에 속한 GATT인지 확인한다.
     *
     * 이전 연결에서 늦게 도착한 콜백은 무시한다.
     */
    private boolean isCurrentGatt(BluetoothGatt gatt) {

        return busy
                && currentGatt != null
                && currentGatt == gatt;
    }

    /*
     * 작업 완료 처리.
     *
     * WRITE 성공 여부와 실제 채널 변경 성공 여부는
     * 서로 다른 개념이다.
     */
    private void finish(
            boolean accepted,
            boolean acknowledged,
            String reason) {

        if (!busy) {
            return;
        }

        AppLog.d(
                "[Channel] Finished"
                        + " / accepted = "
                        + accepted
                        + " / acknowledged = "
                        + acknowledged
                        + " / reason = "
                        + reason
        );

        Callback callback =
                currentCallback;

        busy = false;
        writeStarted = false;
        writingWithoutResponse = false;
        requestedRawChannel = -1;
        currentCallback = null;

        handler.removeCallbacks(
                commandTimeout
        );

        handler.removeCallbacks(
                noResponseSettle
        );

        closeGatt();

        if (callback != null) {

            callback.onFinished(
                    accepted,
                    acknowledged,
                    reason
            );
        }
    }

    /*
     * 현재 GATT 연결 정리.
     */
    @SuppressLint("MissingPermission")
    private void closeGatt() {

        BluetoothGatt gatt =
                currentGatt;

        currentGatt = null;

        if (gatt == null) {
            return;
        }

        try {
            gatt.disconnect();
        } catch (Exception ignored) {
        }

        try {
            gatt.close();
        } catch (Exception ignored) {
        }
    }

    /*
     * Activity 종료 또는 검색 결과 초기화 시 사용한다.
     *
     * 진행 중인 작업은 취소하고
     * GATT 자원을 정리한다.
     *
     * 취소 콜백은 호출하지 않는다.
     */
    public void close() {

        handler.removeCallbacks(
                commandTimeout
        );

        handler.removeCallbacks(
                noResponseSettle
        );

        busy = false;
        writeStarted = false;
        writingWithoutResponse = false;

        requestedRawChannel = -1;
        currentCallback = null;

        closeGatt();
    }
}