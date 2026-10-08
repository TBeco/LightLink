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

import java.util.UUID;


/*
 * Base Station 2.0의 전원 제어를 담당하는 클래스
 *
 * 역할:
 * - Wake 명령 전송
 * - Sleep 명령 전송
 * - GATT 연결 실패 시 안전한 범위에서 자동 재시도
 * - 명령 후 실제 Power State 확인
 * - 전환 중 중복 명령 방지
 */
public class BaseStationGattController {


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
     *
     * 연결 재시도 대기 시간도 이 10초 안에 포함된다.
     */
    private static final long COMMAND_TIMEOUT_MS =
            10000;


    /*
     * Power Write가 시작되기 전
     * GATT 연결이 일시적으로 실패했을 때
     * 추가로 재시도할 최대 횟수.
     *
     * 최초 연결 + 재시도 1회
     * = 최대 2번 연결 시도
     */
    private static final int MAX_CONNECT_RETRIES =
            1;


    /*
     * 연결 실패 직후 바로 다시 연결하지 않고
     * Bluetooth Stack이 정리될 시간을 조금 준다.
     */
    private static final long CONNECT_RETRY_DELAY_MS =
            1000;


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


    /*
     * Wake / Sleep에 필요한 모든 Write가
     * 정상적으로 끝났는지 여부.
     *
     * true가 된 뒤에는 실제 상태 확인 단계이다.
     */
    private boolean commandWritten = false;


    /*
     * Power Write가 한 번이라도
     * Android Bluetooth Stack에 접수됐는지 여부.
     *
     * 이 값이 true가 된 뒤에는
     * 장치가 일부 명령을 받았을 가능성이 있으므로
     * 전체 명령을 처음부터 자동 재시도하지 않는다.
     */
    private boolean powerWriteStarted = false;


    // 현재 Power State READ 결과를 기다리는지
    private boolean waitingForRead = false;


    /*
     * 현재 명령에서 이미 실행한
     * GATT 연결 재시도 횟수.
     */
    private int connectRetryCount = 0;


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
     * 현재 이 Controller가
     * 다른 전원 작업을 처리 중인지 확인
     */
    public boolean isBusy() {

        return busy;
    }


    /*
     * Wake / Sleep 명령 시작
     */
    public void setPowerState(
            BleDeviceItem device,
            TargetState target,
            CommandCallback callback) {


        /*
         * 하나의 Controller에서는
         * 동시에 두 명령을 처리하지 않는다.
         *
         * MainActivity에서는 Base Station마다
         * 별도의 Controller를 사용하므로
         * 서로 다른 장치는 독립적으로 동작한다.
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


        /*
         * 새로운 전원 작업 초기화
         */
        busy = true;

        commandWritten = false;
        powerWriteStarted = false;
        waitingForRead = false;

        connectRetryCount = 0;

        sleepWriteStep = 0;

        verifyDeadline = 0;


        currentDevice =
                device;

        currentTarget =
                target;

        currentCallback =
                callback;


        AppLog.d(
                "Power command start"
                        + " / device = "
                        + device.getName()
                        + " / target = "
                        + target
        );


        /*
         * 최초 연결부터 재시도까지 포함한
         * 전체 명령 시작 Timeout.
         */
        handler.postDelayed(
                commandTimeoutRunnable,
                COMMAND_TIMEOUT_MS
        );


        /*
         * 최초 GATT 연결 시도
         */
        connectForCommand();
    }


    /*
     * 현재 Base Station에
     * Power 명령용 GATT 연결을 시작한다.
     *
     * 최초 연결과 자동 재연결 모두
     * 이 메서드를 공통으로 사용한다.
     */
    @SuppressLint("MissingPermission")
    private void connectForCommand() {

        if (!busy
                || currentDevice == null) {

            return;
        }


        try {

            BluetoothDevice bluetoothDevice =
                    bluetoothAdapter.getRemoteDevice(
                            currentDevice.getAddress()
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


            /*
             * connectGatt() 자체가
             * GATT 객체를 생성하지 못한 경우.
             *
             * 아직 Power Write 전이므로
             * 안전하게 연결 재시도를 검토한다.
             */
            if (currentGatt == null) {

                retryConnectionOrFail(
                        "Unable to create GATT connection"
                );
            }

        } catch (Exception e) {

            AppLog.d(
                    "Power connection error",
                    e
            );


            /*
             * 예외 역시 Write 이전 연결 단계라면
             * 한 번 자동 재시도를 허용한다.
             */
            retryConnectionOrFail(
                    "Connection error"
            );
        }
    }


    /*
     * Power Write가 시작되기 전 발생한
     * GATT 연결 실패를 처리한다.
     *
     * 재시도 가능:
     * - 아직 어떤 Power Write도 시작되지 않음
     * - 최대 재시도 횟수가 남아 있음
     *
     * 재시도 불가:
     * - Write가 이미 시작됨
     * - 모든 Write가 완료되어 상태 확인 중
     * - 최대 재시도 횟수를 이미 사용함
     */
    private void retryConnectionOrFail(
            String reason) {


        if (!busy) {
            return;
        }


        /*
         * 모든 Write가 끝난 뒤 연결이 끊겼다면
         * 명령 재전송이 아니라 상태 확인 실패이다.
         */
        if (commandWritten) {

            failConfirmation(
                    reason
            );

            return;
        }


        /*
         * Write가 한 번이라도 시작됐다면
         * 실제 장치가 일부 명령을 받았을 가능성이 있다.
         *
         * 특히 Sleep의 0x01 → 0x00 과정 때문에
         * 이 시점에서는 전체 명령을 자동 재실행하지 않는다.
         */
        if (powerWriteStarted) {

            failCommand(
                    reason
            );

            return;
        }


        /*
         * 허용된 재시도 횟수를 모두 사용했다면
         * 최종 실패 처리.
         */
        if (connectRetryCount
                >= MAX_CONNECT_RETRIES) {

            failCommand(
                    reason
            );

            return;
        }


        connectRetryCount++;


        AppLog.d(
                "Power connection retry scheduled"
                        + " / retry = "
                        + connectRetryCount
                        + "/"
                        + MAX_CONNECT_RETRIES
                        + " / reason = "
                        + reason
        );


        /*
         * 실패한 GATT 연결을 먼저 완전히 정리한다.
         *
         * currentDevice / currentTarget / currentCallback은
         * 유지해야 같은 명령을 다시 연결할 수 있다.
         */
        closeGattForRetry();


        /*
         * 실패 직후 바로 재연결하지 않고
         * Bluetooth Stack이 정리될 시간을 준다.
         */
        handler.removeCallbacks(
                connectionRetryRunnable
        );


        handler.postDelayed(
                connectionRetryRunnable,
                CONNECT_RETRY_DELAY_MS
        );
    }


    /*
     * 예약된 Power GATT 연결 재시도.
     */
    private final Runnable connectionRetryRunnable =
            () -> {

                if (!busy
                        || commandWritten
                        || powerWriteStarted
                        || currentDevice == null) {

                    return;
                }


                AppLog.d(
                        "Power connection retry start"
                                + " / retry = "
                                + connectRetryCount
                                + "/"
                                + MAX_CONNECT_RETRIES
                                + " / device = "
                                + currentDevice.getName()
                );


                connectForCommand();
            };


    /*
     * 연결 재시도를 위해
     * 현재 실패한 GATT만 정리한다.
     *
     * 전체 작업 정보는 유지한다.
     */
    @SuppressLint("MissingPermission")
    private void closeGattForRetry() {

        BluetoothGatt gatt =
                currentGatt;


        /*
         * 먼저 현재 연결 참조를 제거한다.
         *
         * 이후 이전 GATT에서 늦은 Callback이 들어오더라도
         * gatt != currentGatt 검사에서 무시된다.
         */
        currentGatt =
                null;

        currentPowerCharacteristic =
                null;

        waitingForRead =
                false;

        sleepWriteStep =
                0;


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


                    /*
                     * 이미 정리된 이전 GATT의
                     * 늦은 Callback이라면 무시한다.
                     */
                    if (gatt != currentGatt) {

                        try {

                            gatt.close();

                        } catch (Exception ignored) {
                        }

                        return;
                    }


                    /*
                     * GATT 연결 오류
                     */
                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {


                        /*
                         * 명령 Write가 모두 끝나고
                         * 실제 상태 확인 중에 끊긴 경우.
                         */
                        if (commandWritten) {

                            failConfirmation(
                                    "Disconnected while verifying"
                            );


                            /*
                             * 아직 어떤 Power Write도
                             * 시작하지 않은 연결 단계 오류.
                             *
                             * 133 같은 일시적인 GATT 연결 실패는
                             * 여기서 한 번 자동 재시도한다.
                             */
                        } else if (!powerWriteStarted) {

                            retryConnectionOrFail(
                                    "GATT connection failed: "
                                            + status
                            );


                            /*
                             * Write가 이미 시작된 뒤의 오류.
                             *
                             * 일부 명령이 장치에 전달됐을 가능성이 있으므로
                             * 명령 전체를 처음부터 자동 재시도하지 않는다.
                             */
                        } else {

                            failCommand(
                                    "GATT connection failed: "
                                            + status
                            );
                        }


                        return;
                    }


                    /*
                     * Base Station 연결 성공
                     */
                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {


                        AppLog.d(
                                "Power command GATT connected"
                                        + " / device = "
                                        + (
                                        currentDevice != null
                                                ? currentDevice.getName()
                                                : "(unknown)"
                                )
                        );


                        boolean started =
                                gatt.discoverServices();


                        if (!started) {

                            /*
                             * 연결 자체는 성공한 뒤
                             * Service 검색 시작이 실패한 경우.
                             *
                             * 현재는 자동 재연결 대상에 포함하지 않고
                             * 명령 실패로 처리한다.
                             */
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


                        /*
                         * 명령 전송 완료 후
                         * 상태 확인 중 연결 종료.
                         */
                        if (commandWritten) {

                            failConfirmation(
                                    "Disconnected while verifying"
                            );


                            /*
                             * Write 시작 전 연결 종료.
                             *
                             * 안전하게 전체 연결을 다시 시도할 수 있다.
                             */
                        } else if (!powerWriteStarted) {

                            retryConnectionOrFail(
                                    "Disconnected"
                            );


                            /*
                             * Write 시작 이후 연결 종료.
                             *
                             * 실제 장치가 일부 값을 받았을 가능성이 있으므로
                             * 자동 명령 재전송은 하지 않는다.
                             */
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


                        sleepWriteStep =
                                1;


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


                    /*
                     * Write 요청은 Bluetooth Stack에
                     * 접수됐지만 결과가 실패한 경우.
                     *
                     * 장치가 값을 받았는지 확실히 알 수 없으므로
                     * 자동으로 전체 명령을 재실행하지 않는다.
                     */
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


                        sleepWriteStep =
                                2;


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


        AppLog.d(
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


        /*
         * Bluetooth Stack이 Write 요청 자체를
         * 받아들이지 못한 경우.
         */
        if (!started) {

            failCommand(
                    "Unable to start power write"
            );

            return;
        }


        /*
         * 여기까지 왔다면 Android Bluetooth Stack이
         * 실제 Write 요청을 접수한 상태.
         *
         * 이 시점부터는 연결이 끊겨도
         * 전체 Wake / Sleep 명령을 처음부터
         * 자동으로 재실행하지 않는다.
         */
        powerWriteStarted =
                true;
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


        commandWritten =
                true;


        verifyDeadline =
                SystemClock.elapsedRealtime()
                        + VERIFY_TIMEOUT_MS;


        BleDeviceItem device =
                currentDevice;

        TargetState target =
                currentTarget;

        CommandCallback callback =
                currentCallback;


        AppLog.d(
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


        waitingForRead =
                true;


        AppLog.d(
                "Checking actual power state..."
        );


        boolean started =
                currentGatt.readCharacteristic(
                        currentPowerCharacteristic
                );


        if (!started) {

            waitingForRead =
                    false;


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


        waitingForRead =
                false;


        /*
         * READ가 일시적으로 실패했다면
         * 바로 전체 실패로 처리하지 않고 재확인한다.
         */
        if (status
                != BluetoothGatt.GATT_SUCCESS) {


            AppLog.d(
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


        AppLog.d(
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
     *
     * 연결 재시도 대기 시간도
     * 이 Timeout에 포함된다.
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


        AppLog.d(
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


        AppLog.d(
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


        AppLog.d(
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
     * 현재 전원 작업 전체 정리
     */
    @SuppressLint("MissingPermission")
    private void cleanupOperation() {

        handler.removeCallbacks(
                commandTimeoutRunnable
        );

        handler.removeCallbacks(
                connectionRetryRunnable
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


        busy =
                false;

        commandWritten =
                false;

        powerWriteStarted =
                false;

        waitingForRead =
                false;


        connectRetryCount =
                0;

        sleepWriteStep =
                0;

        verifyDeadline =
                0;


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