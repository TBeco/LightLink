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
import android.os.SystemClock;
import android.util.Log;

import java.util.UUID;


/*
 * Base Station 2.0의 전원 제어를 담당하는 클래스
 *
 * 역할:
 * - Wake 명령 전송
 * - Sleep 명령 전송
 * - 명령 후 실제 Power State 확인
 * - 전환 중 중복 명령 방지
 */
public class BaseStationGattController {

    private static final String TAG = "LightLink";


    // Base Station 2.0 Control Service
    private static final UUID CONTROL_SERVICE_UUID =
            UUID.fromString(
                    "00001523-1212-EFDE-1523-785FEABCD124"
            );


    // Base Station 2.0 Power State Characteristic
    private static final UUID POWER_STATE_UUID =
            UUID.fromString(
                    "00001525-1212-EFDE-1523-785FEABCD124"
            );


    /*
     * GATT 연결 + 명령 전송 자체의 최대 대기 시간
     */
    private static final long COMMAND_TIMEOUT_MS =
            10000;


    /*
     * 명령 전송 후 Base Station이
     * 상태를 변경할 시간을 조금 기다린다.
     */
    private static final long VERIFY_FIRST_DELAY_MS =
            1000;


    /*
     * 상태가 아직 목표 상태가 아니라면
     * 다음 상태 확인까지 기다리는 시간
     */
    private static final long VERIFY_INTERVAL_MS =
            1500;


    /*
     * Wake / Sleep 명령 후
     * 실제 상태가 바뀌었는지 확인하는 최대 시간
     */
    private static final long VERIFY_TIMEOUT_MS =
            20000;


    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;


    private final Handler handler =
            new Handler(Looper.getMainLooper());


    private BluetoothGatt currentGatt;

    private BluetoothGattCharacteristic currentPowerCharacteristic;

    private BleDeviceItem currentDevice;

    private TargetState currentTarget;

    private CommandCallback currentCallback;


    // 현재 전원 작업 중인지
    private boolean busy = false;


    // 실제 Write가 끝났는지
    private boolean commandWritten = false;


    // 현재 Power State READ 결과를 기다리는지
    private boolean waitingForRead = false;


    /*
     * Sleep은
     *
     * 0x01
     * ↓
     * 0x00
     *
     * 두 번의 별도 Write가 필요하다.
     */
    private int sleepWriteStep = 0;


    // 상태 확인 종료 시각
    private long verifyDeadline = 0;


    public enum TargetState {
        AWAKE,
        SLEEP
    }


    /*
     * Controller의 진행 상황을
     * MainActivity에 전달
     */
    public interface CommandCallback {

        /*
         * BLE Write 자체가 완료됨.
         *
         * 아직 실제 Base Station 상태가
         * 변경됐다는 의미는 아니다.
         */
        void onCommandSent(
                BleDeviceItem device,
                TargetState target
        );


        /*
         * 실제 Power State까지 확인 완료
         */
        void onStateConfirmed(
                BleDeviceItem device,
                TargetState target
        );


        /*
         * 명령은 전송됐지만
         * 실제 상태를 확인하지 못함
         */
        void onStateConfirmationFailed(
                BleDeviceItem device,
                String reason
        );


        /*
         * 명령 전송 자체가 실패
         */
        void onCommandFailed(
                BleDeviceItem device,
                String reason
        );
    }


    public BaseStationGattController(
            Context context,
            BluetoothAdapter bluetoothAdapter) {

        this.context =
                context.getApplicationContext();

        this.bluetoothAdapter =
                bluetoothAdapter;
    }


    /*
     * 현재 다른 전원 작업을 처리 중인지 확인
     */
    public boolean isBusy() {

        return busy;
    }


    /*
     * Wake / Sleep 명령 시작
     */
    @SuppressLint("MissingPermission")
    public void setPowerState(
            BleDeviceItem device,
            TargetState target,
            CommandCallback callback) {


        /*
         * 동시에 두 Base Station에
         * 전원 명령을 보내지 않는다.
         */
        if (busy) {

            handler.post(() ->
                    callback.onCommandFailed(
                            device,
                            "Controller busy"
                    )
            );

            return;
        }


        if (bluetoothAdapter == null) {

            handler.post(() ->
                    callback.onCommandFailed(
                            device,
                            "Bluetooth unavailable"
                    )
            );

            return;
        }


        busy = true;

        commandWritten = false;
        waitingForRead = false;

        sleepWriteStep = 0;


        currentDevice =
                device;

        currentTarget =
                target;

        currentCallback =
                callback;


        Log.d(
                TAG,
                "Power command start"
                        + " / device = "
                        + device.getName()
                        + " / target = "
                        + target
        );


        try {

            BluetoothDevice bluetoothDevice =
                    bluetoothAdapter.getRemoteDevice(
                            device.getAddress()
                    );


            /*
             * Base Station에 한 번 연결한다.
             *
             * 전원 명령을 보낸 뒤에도 이 연결을 유지하고
             * 같은 연결을 이용해 Power State를 확인한다.
             */
            currentGatt =
                    bluetoothDevice.connectGatt(
                            context,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE
                    );


            if (currentGatt == null) {

                failCommand(
                        "Unable to create GATT connection"
                );

                return;
            }


            handler.postDelayed(
                    commandTimeoutRunnable,
                    COMMAND_TIMEOUT_MS
            );

        } catch (Exception e) {

            failCommand(
                    "Connection error"
            );
        }
    }


    /*
     * Base Station GATT Callback
     */
    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {


                /*
                 * 연결 상태 변경
                 */
                @SuppressLint("MissingPermission")
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int status,
                        int newState) {


                    if (gatt != currentGatt) {

                        try {
                            gatt.close();
                        } catch (Exception ignored) {
                        }

                        return;
                    }


                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {

                        if (commandWritten) {

                            failConfirmation(
                                    "Disconnected while verifying"
                            );

                        } else {

                            failCommand(
                                    "GATT connection failed: "
                                            + status
                            );
                        }

                        return;
                    }


                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {


                        Log.d(
                                TAG,
                                "Power command GATT connected"
                        );


                        boolean started =
                                gatt.discoverServices();


                        if (!started) {

                            failCommand(
                                    "Service discovery failed"
                            );
                        }


                    } else if (
                            newState
                                    == BluetoothProfile.STATE_DISCONNECTED) {


                        if (!busy) {
                            return;
                        }


                        if (commandWritten) {

                            failConfirmation(
                                    "Disconnected while verifying"
                            );

                        } else {

                            failCommand(
                                    "Disconnected"
                            );
                        }
                    }
                }


                /*
                 * Service 검색 완료
                 */
                @SuppressLint("MissingPermission")
                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int status) {


                    if (gatt != currentGatt
                            || !busy) {

                        return;
                    }


                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {

                        failCommand(
                                "Service discovery error"
                        );

                        return;
                    }


                    BluetoothGattService controlService =
                            gatt.getService(
                                    CONTROL_SERVICE_UUID
                            );


                    if (controlService == null) {

                        failCommand(
                                "Control service not found"
                        );

                        return;
                    }


                    currentPowerCharacteristic =
                            controlService.getCharacteristic(
                                    POWER_STATE_UUID
                            );


                    if (currentPowerCharacteristic == null) {

                        failCommand(
                                "Power characteristic not found"
                        );

                        return;
                    }


                    /*
                     * Wake
                     */
                    if (currentTarget
                            == TargetState.AWAKE) {


                        writePowerValue(
                                (byte) 0x01
                        );


                        /*
                         * Sleep
                         */
                    } else if (
                            currentTarget
                                    == TargetState.SLEEP) {


                        sleepWriteStep = 1;


                        /*
                         * Sleep 첫 번째 Write
                         */
                        writePowerValue(
                                (byte) 0x01
                        );
                    }
                }


                /*
                 * Power State Write 완료
                 */
                @SuppressLint("MissingPermission")
                @Override
                public void onCharacteristicWrite(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {


                    if (gatt != currentGatt
                            || !busy) {

                        return;
                    }


                    if (!POWER_STATE_UUID.equals(
                            characteristic.getUuid())) {

                        return;
                    }


                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {

                        failCommand(
                                "Power write failed: "
                                        + status
                        );

                        return;
                    }


                    /*
                     * Sleep 첫 번째 0x01 Write 성공
                     *
                     * 이어서 0x00 전송
                     */
                    if (currentTarget
                            == TargetState.SLEEP
                            && sleepWriteStep == 1) {


                        sleepWriteStep = 2;


                        writePowerValue(
                                (byte) 0x00
                        );


                        return;
                    }


                    /*
                     * Wake:
                     * 0x01 완료
                     *
                     * Sleep:
                     * 0x01 → 0x00 완료
                     */
                    beginStateVerification();
                }


                /*
                 * Android 12 이하에서 사용하는
                 * Power State READ Callback
                 */
                @SuppressWarnings("deprecation")
                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {


                    byte[] value =
                            characteristic.getValue();


                    handleCharacteristicRead(
                            gatt,
                            characteristic,
                            value,
                            status
                    );
                }


                /*
                 * Android 13 이상에서 사용하는
                 * Power State READ Callback
                 */
                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value,
                        int status) {


                    handleCharacteristicRead(
                            gatt,
                            characteristic,
                            value,
                            status
                    );
                }
            };


    /*
     * Power characteristic에 한 byte Write
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void writePowerValue(
            byte value) {


        if (currentGatt == null
                || currentPowerCharacteristic == null) {

            failCommand(
                    "Power characteristic unavailable"
            );

            return;
        }


        Log.d(
                TAG,
                String.format(
                        "Write power value: 0x%02X",
                        value & 0xFF
                )
        );


        boolean started;


        /*
         * Android 13 이상
         */
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.TIRAMISU) {


            int result =
                    currentGatt.writeCharacteristic(
                            currentPowerCharacteristic,
                            new byte[]{value},
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    );


            started =
                    result
                            == BluetoothStatusCodes.SUCCESS;


            /*
             * Android 12 이하
             */
        } else {


            currentPowerCharacteristic.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            );


            currentPowerCharacteristic.setValue(
                    new byte[]{value}
            );


            started =
                    currentGatt.writeCharacteristic(
                            currentPowerCharacteristic
                    );
        }


        if (!started) {

            failCommand(
                    "Unable to start power write"
            );
        }
    }


    /*
     * Wake / Sleep Write가 모두 끝났음.
     *
     * 여기부터 실제 상태 확인 단계.
     */
    private void beginStateVerification() {

        if (!busy) {
            return;
        }


        handler.removeCallbacks(
                commandTimeoutRunnable
        );


        commandWritten = true;


        verifyDeadline =
                SystemClock.elapsedRealtime()
                        + VERIFY_TIMEOUT_MS;


        BleDeviceItem device =
                currentDevice;

        TargetState target =
                currentTarget;

        CommandCallback callback =
                currentCallback;


        Log.d(
                TAG,
                "Power command sent"
                        + " / target = "
                        + target
        );


        /*
         * MainActivity에는
         * 명령 전송 완료만 알린다.
         *
         * 화면 상태는 아직 Checking...
         */
        handler.post(() -> {

            if (callback != null) {

                callback.onCommandSent(
                        device,
                        target
                );
            }
        });


        /*
         * 실제 상태 확인 전체 Timeout
         */
        handler.postDelayed(
                verificationTimeoutRunnable,
                VERIFY_TIMEOUT_MS
        );


        /*
         * 모터 / 레이저 상태가 바뀔 시간을
         * 조금 준 뒤 첫 확인
         */
        handler.postDelayed(
                verifyRunnable,
                VERIFY_FIRST_DELAY_MS
        );
    }


    /*
     * 실제 Power State READ 시작
     */
    @SuppressLint("MissingPermission")
    private void requestPowerStateRead() {

        if (!busy
                || !commandWritten
                || currentGatt == null
                || currentPowerCharacteristic == null) {

            return;
        }


        if (waitingForRead) {
            return;
        }


        if (SystemClock.elapsedRealtime()
                >= verifyDeadline) {

            failConfirmation(
                    "Unable to confirm state"
            );

            return;
        }


        waitingForRead = true;


        Log.d(
                TAG,
                "Checking actual power state..."
        );


        boolean started =
                currentGatt.readCharacteristic(
                        currentPowerCharacteristic
                );


        if (!started) {

            waitingForRead = false;

            scheduleNextVerification();
        }
    }


    /*
     * Power State READ 결과 처리
     */
    private void handleCharacteristicRead(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int status) {


        if (gatt != currentGatt
                || !busy
                || !commandWritten
                || !waitingForRead) {

            return;
        }


        if (!POWER_STATE_UUID.equals(
                characteristic.getUuid())) {

            return;
        }


        waitingForRead = false;


        /*
         * READ가 일시적으로 실패했다면
         * 바로 전체 실패로 처리하지 않고 재확인한다.
         */
        if (status
                != BluetoothGatt.GATT_SUCCESS) {


            Log.d(
                    TAG,
                    "Power state read failed: "
                            + status
            );


            scheduleNextVerification();

            return;
        }


        if (value == null
                || value.length == 0) {


            scheduleNextVerification();

            return;
        }


        int powerValue =
                value[0] & 0xFF;


        Log.d(
                TAG,
                "Power state verification"
                        + " / value = 0x"
                        + String.format(
                        "%02X",
                        powerValue
                )
                        + " / target = "
                        + currentTarget
        );


        /*
         * 목표 상태에 실제로 도달했는지 확인
         */
        if (isTargetStateReached(
                powerValue
        )) {


            finishConfirmed();

            return;
        }


        /*
         * 아직 기동 중이거나
         * 이전 상태라면 잠시 기다렸다가 재확인
         */
        scheduleNextVerification();
    }


    /*
     * 목표 상태 판정
     */
    private boolean isTargetStateReached(
            int powerValue) {


        if (currentTarget
                == TargetState.SLEEP) {


            return powerValue
                    == 0x00;
        }


        if (currentTarget
                == TargetState.AWAKE) {


            /*
             * 0x09 / 0x0B만
             * 확실한 Awake로 인정
             *
             * 0x01은 확정 상태로 보지 않는다.
             */
            return powerValue == 0x09
                    || powerValue == 0x0B;
        }


        return false;
    }


    /*
     * 아직 목표 상태가 아니라면
     * 다음 Power State 확인 예약
     */
    private void scheduleNextVerification() {

        if (!busy
                || !commandWritten) {

            return;
        }


        if (SystemClock.elapsedRealtime()
                >= verifyDeadline) {


            failConfirmation(
                    "Unable to confirm state"
            );

            return;
        }


        handler.postDelayed(
                verifyRunnable,
                VERIFY_INTERVAL_MS
        );
    }


    /*
     * 상태 확인 실행
     */
    private final Runnable verifyRunnable =
            this::requestPowerStateRead;


    /*
     * 명령 연결 / Write Timeout
     */
    private final Runnable commandTimeoutRunnable =
            () -> {

                if (busy
                        && !commandWritten) {


                    failCommand(
                            "Command timeout"
                    );
                }
            };


    /*
     * 실제 상태 확인 Timeout
     */
    private final Runnable verificationTimeoutRunnable =
            () -> {

                if (busy
                        && commandWritten) {


                    failConfirmation(
                            "Unable to confirm state"
                    );
                }
            };


    /*
     * 실제 목표 상태까지 확인 성공
     */
    private void finishConfirmed() {

        if (!busy) {
            return;
        }


        BleDeviceItem device =
                currentDevice;

        TargetState target =
                currentTarget;

        CommandCallback callback =
                currentCallback;


        Log.d(
                TAG,
                "Power state confirmed"
                        + " / device = "
                        + device.getName()
                        + " / target = "
                        + target
        );


        cleanupOperation();


        handler.post(() -> {

            if (callback != null) {

                callback.onStateConfirmed(
                        device,
                        target
                );
            }
        });
    }


    /*
     * 명령은 전송됐지만
     * 최종 상태를 확인하지 못함
     */
    private void failConfirmation(
            String reason) {

        if (!busy) {
            return;
        }


        BleDeviceItem device =
                currentDevice;

        CommandCallback callback =
                currentCallback;


        Log.d(
                TAG,
                "Power state confirmation failed"
                        + " / reason = "
                        + reason
        );


        cleanupOperation();


        handler.post(() -> {

            if (callback != null) {

                callback.onStateConfirmationFailed(
                        device,
                        reason
                );
            }
        });
    }


    /*
     * 명령 자체가 전송되지 못함
     */
    private void failCommand(
            String reason) {

        if (!busy) {
            return;
        }


        BleDeviceItem device =
                currentDevice;

        CommandCallback callback =
                currentCallback;


        Log.d(
                TAG,
                "Power command failed"
                        + " / reason = "
                        + reason
        );


        cleanupOperation();


        handler.post(() -> {

            if (callback != null) {

                callback.onCommandFailed(
                        device,
                        reason
                );
            }
        });
    }


    /*
     * 현재 GATT 작업 정리
     */
    @SuppressLint("MissingPermission")
    private void cleanupOperation() {

        handler.removeCallbacks(
                commandTimeoutRunnable
        );

        handler.removeCallbacks(
                verificationTimeoutRunnable
        );

        handler.removeCallbacks(
                verifyRunnable
        );


        BluetoothGatt gatt =
                currentGatt;


        currentGatt =
                null;

        currentPowerCharacteristic =
                null;

        currentDevice =
                null;

        currentTarget =
                null;

        currentCallback =
                null;


        busy = false;

        commandWritten = false;
        waitingForRead = false;

        sleepWriteStep = 0;

        verifyDeadline = 0;


        if (gatt != null) {

            try {

                gatt.disconnect();

            } catch (Exception ignored) {
            }


            try {

                gatt.close();

            } catch (Exception ignored) {
            }
        }
    }


    /*
     * Activity 종료 시 호출
     */
    public void close() {

        cleanupOperation();

        handler.removeCallbacksAndMessages(
                null
        );
    }
}