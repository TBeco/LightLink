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

import java.util.Locale;
import java.util.UUID;


/*
 * Base Station 2.0의 전원 제어를 담당하는 클래스
 *
 * 역할:
 * - Wake 명령 전송
 * - Sleep 명령 전송
 * - GATT 연결 실패 시 안전한 범위에서 자동 재시도
 * - READ 지원 장치는 명령 후 실제 Power State 확인
 * - READ 미지원 장치는 Write 완료까지만 처리
 * - 전원 명령이 끝날 때마다 가능한 경우 RF Channel을 다시 확인
 * - 전원 명령 완료 시 가능한 경우 최신 RSSI도 다시 확인
 * - Channel / RSSI 확인 실패가 전원 명령 결과를 바꾸지 않도록 분리 처리
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
     * Base Station 2.0 Mode / RF Channel Characteristic.
     *
     * 현재는 채널 변경에는 사용하지 않고,
     * Wake / Sleep 작업이 끝날 때 현재 채널을 다시 READ해서
     * BleDeviceItem의 표시 값을 최신 상태로 갱신하는 데만 사용한다.
     */
    private static final UUID MODE_UUID =
            UUID.fromString(
                    "00001524-1212-EFDE-1523-785FEABCD124"
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


    /*
     * 전원 명령의 본래 결과가 결정된 뒤
     * RF Channel READ만 기다리는 최대 시간.
     *
     * Channel은 Power State와 별개의 부가 정보이므로
     * 이 시간이 지나도 이미 확인된 Wake / Sleep 결과를
     * 실패로 바꾸지 않는다.
     *
     * Timeout이 나면 기존 Channel 값을 유지하고
     * 다음 부가 정보인 RSSI 확인으로 넘어간다.
     */
    private static final long CHANNEL_TIMEOUT_MS =
            2000;


    /*
     * 전원 명령 자체가 끝난 뒤
     * 최신 RSSI 확인만 기다리는 최대 시간.
     *
     * RSSI는 부가 정보이므로 이 시간이 지나면
     * 기존 RSSI를 유지하고 전원 명령 결과는 그대로 완료한다.
     */
    private static final long RSSI_TIMEOUT_MS =
            2000;


    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;


    private final Handler handler =
            new Handler(Looper.getMainLooper());


    private BluetoothGatt currentGatt;

    private BluetoothGattCharacteristic currentPowerCharacteristic;

    /*
     * 현재 Power 명령용 GATT 연결에서 발견한
     * Mode / RF Channel Characteristic.
     *
     * 장치가 Characteristic을 제공하지 않으면 null이며,
     * 이 경우 Channel 갱신만 생략하고 전원 명령은 정상 진행한다.
     */
    private BluetoothGattCharacteristic currentModeCharacteristic;

    private BleDeviceItem currentDevice;

    private TargetState currentTarget;

    private CommandCallback currentCallback;


    // 현재 전원 작업 중인지
    private boolean busy = false;


    /*
     * Wake / Sleep 명령 후
     * 실제 Power State를 다시 READ해서 확인할지 여부.
     *
     * true:
     * 일반 장치처럼 명령 후 실제 상태까지 확인한다.
     *
     * false:
     * READ를 지원하지 않는 장치에서
     * Write 완료까지만 확인하고 작업을 종료한다.
     */
    private boolean stateVerificationEnabled = true;


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
     * 전원 명령의 본래 결과가 결정된 뒤
     * Mode / RF Channel READ 결과를 기다리고 있는지 여부.
     *
     * Channel은 부가 정보이므로 실패하거나 Timeout이 나도
     * 이미 성공한 Write / 상태 확인 결과를 실패로 바꾸지 않는다.
     */
    private boolean waitingForChannel = false;


    /*
     * Wake / Sleep 처리가 사실상 끝난 뒤
     * 최신 RSSI 결과만 기다리고 있는지 여부.
     *
     * RSSI는 부가 정보이므로 실패하거나 Timeout이 나도
     * 이미 성공한 Write / 상태 확인 결과를 실패로 바꾸지 않는다.
     */
    private boolean waitingForRssi = false;


    /*
     * Channel / RSSI 갱신이 모두 끝난 뒤
     * 어떤 종류의 완료 Callback으로 이어져야 하는지 구분한다.
     *
     * CONFIRMED:
     * Power State READ로 실제 목표 상태까지 확인한 일반 장치.
     *
     * WRITE_ONLY:
     * Power State READ 미지원으로 Write 완료까지만 확인한 장치.
     */
    private enum OptionalCompletionType {
        NONE,
        CONFIRMED,
        WRITE_ONLY
    }


    private OptionalCompletionType optionalCompletionType =
            OptionalCompletionType.NONE;


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
     * 다음 전원 명령에서
     * 실제 Power State 확인을 수행할지 설정한다.
     *
     * Controller가 이미 작업 중이라면
     * 현재 작업의 동작이 중간에 바뀌지 않도록 무시한다.
     */
    public void setStateVerificationEnabled(
            boolean enabled) {

        if (busy) {
            return;
        }


        stateVerificationEnabled =
                enabled;
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
        waitingForChannel = false;
        waitingForRssi = false;

        optionalCompletionType =
                OptionalCompletionType.NONE;

        currentModeCharacteristic =
                null;

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

        currentModeCharacteristic =
                null;

        waitingForRead =
                false;

        waitingForChannel =
                false;

        waitingForRssi =
                false;

        optionalCompletionType =
                OptionalCompletionType.NONE;

        handler.removeCallbacks(
                channelTimeoutRunnable
        );

        handler.removeCallbacks(
                rssiTimeoutRunnable
        );

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
                         * 전원 명령 결과는 이미 결정됐고
                         * 부가 정보인 RSSI만 확인하던 중이라면
                         * 연결 오류를 전원 명령 실패로 바꾸지 않는다.
                         */
                        if (waitingForChannel
                                || waitingForRssi) {

                            AppLog.d(
                                    "Optional device info refresh interrupted by GATT error: "
                                            + status
                            );


                            completeAfterOptionalInfo();

                            return;
                        }


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
                         * 전원 처리 결과는 이미 결정됐고
                         * RSSI만 기다리던 중 연결이 끊긴 경우.
                         *
                         * RSSI는 갱신하지 못하지만
                         * 전원 명령 자체는 기존 결과대로 완료한다.
                         */
                        if (waitingForChannel
                                || waitingForRssi) {

                            AppLog.d(
                                    "Optional device info refresh interrupted by disconnect"
                            );


                            completeAfterOptionalInfo();

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
                     * Mode / RF Channel Characteristic도 같이 찾아둔다.
                     *
                     * 채널은 전원 제어 자체에 필수 정보가 아니므로
                     * Characteristic이 없거나 READ를 지원하지 않아도
                     * Wake / Sleep Write는 그대로 진행한다.
                     *
                     * 실제 Channel READ는 전원 명령의 결과가 결정된 뒤
                     * 같은 GATT 연결을 닫기 직전에 한 번 실행한다.
                     */
                    currentModeCharacteristic =
                            controlService.getCharacteristic(
                                    MODE_UUID
                            );


                    if (currentModeCharacteristic != null) {

                        int modeProperties =
                                currentModeCharacteristic.getProperties();


                        boolean modeReadable =
                                (
                                        modeProperties
                                                & BluetoothGattCharacteristic.PROPERTY_READ
                                ) != 0;


                        boolean modeWritable =
                                (
                                        modeProperties
                                                & BluetoothGattCharacteristic.PROPERTY_WRITE
                                ) != 0;


                        boolean modeNotifiable =
                                (
                                        modeProperties
                                                & BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                ) != 0;


                        AppLog.d(
                                "Power command Mode properties"
                                        + " / device = "
                                        + (currentDevice != null
                                        ? currentDevice.getName()
                                        : "(unknown)")
                                        + " / properties = 0x"
                                        + Integer.toHexString(modeProperties).toUpperCase(Locale.ROOT)
                                        + " / READ = "
                                        + modeReadable
                                        + " / WRITE = "
                                        + modeWritable
                                        + " / NOTIFY = "
                                        + modeNotifiable
                        );

                    } else {

                        AppLog.d(
                                "Power command Mode characteristic not found"
                                        + " / device = "
                                        + (currentDevice != null
                                        ? currentDevice.getName()
                                        : "(unknown)")
                        );
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
                     *
                     * READ를 지원하는 일반 장치는
                     * 기존처럼 실제 상태까지 확인한다.
                     *
                     * READ를 지원하지 않는 수동 제어 장치는
                     * Write 완료까지만 확인하고 종료한다.
                     */
                    if (stateVerificationEnabled) {

                        beginStateVerification();

                    } else {

                        finishCommandWithoutVerification();
                    }
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


                /*
                 * 전원 명령 완료 직전, Channel 갱신 다음 단계로 요청한
                 * 최신 RSSI 확인 결과.
                 */
                @Override
                public void onReadRemoteRssi(
                        BluetoothGatt gatt,
                        int rssi,
                        int status) {


                    if (gatt != currentGatt
                            || !busy
                            || !waitingForRssi) {

                        return;
                    }


                    /*
                     * RSSI 확인 성공.
                     *
                     * BleDeviceItem에는 검색 당시 값 대신
                     * 현재 GATT 연결에서 확인한 최신 값을 저장한다.
                     */
                    if (status == BluetoothGatt.GATT_SUCCESS
                            && currentDevice != null) {


                        currentDevice.setRssi(
                                rssi
                        );


                        AppLog.d(
                                "Power command RSSI updated"
                                        + " / device = "
                                        + currentDevice.getName()
                                        + " / RSSI = "
                                        + rssi
                                        + " dBm"
                        );

                    } else {

                        /*
                         * RSSI 실패는 부가 정보 실패일 뿐이다.
                         * 이미 성공한 Wake / Sleep 결과에는 영향을 주지 않는다.
                         */
                        AppLog.d(
                                "Power command RSSI read failed"
                                        + " / status = "
                                        + status
                        );
                    }


                    completeAfterOptionalInfo();
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
     * Power State READ를 지원하지 않는 장치용.
     *
     * Wake / Sleep에 필요한 Write가 모두 정상적으로 끝났으므로
     * 명령 전송 자체는 성공으로 처리한다.
     *
     * 다만 실제 Base Station이 목표 상태에 도달했는지는
     * 확인할 수 없으므로 onStateConfirmed()는 호출하지 않는다.
     */
    private void finishCommandWithoutVerification() {

        if (!busy) {
            return;
        }


        AppLog.d(
                "Power command completed without state verification"
                        + " / device = "
                        + (currentDevice != null
                        ? currentDevice.getName()
                        : "(unknown)")
                        + " / target = "
                        + currentTarget
        );


        /*
         * 실제 Power State는 확인할 수 없지만 필요한 Write는 끝났다.
         *
         * GATT를 닫기 전에 가능한 경우
         * Channel → RSSI 순서로 부가 정보를 최신화한다.
         *
         * 3002 같은 READ 미지원 장치라도
         * Power State READ와 Mode READ는 서로 다른 Capability이므로
         * Mode READ가 가능하다면 Channel은 정상적으로 갱신될 수 있다.
         *
         * Channel / RSSI 실패는 Write 성공 결과를 바꾸지 않는다.
         */
        beginOptionalInfoRefresh(
                OptionalCompletionType.WRITE_ONLY
        );
    }


    /*
     * READ 미지원 장치의 최종 완료 처리.
     *
     * 이 메서드가 호출되는 시점에는
     * Channel / RSSI 확인도 끝났거나 생략 / 실패 / Timeout 처리된 상태다.
     */
    private void completeWriteOnlyOperation() {

        if (!busy) {
            return;
        }


        BleDeviceItem device =
                currentDevice;

        TargetState target =
                currentTarget;

        CommandCallback callback =
                currentCallback;


        cleanupOperation();


        /*
         * MainActivity에는
         * Write가 정상적으로 끝났다는 사실만 전달한다.
         *
         * 실제 Awake / Sleep 상태가 확인됐다는 의미는 아니다.
         */
        handler.post(() -> {

            if (callback != null) {

                callback.onCommandSent(
                        device,
                        target
                );
            }
        });
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


        if (waitingForRead
                || waitingForChannel
                || waitingForRssi) {
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
                || characteristic == null) {

            return;
        }


        UUID characteristicUuid =
                characteristic.getUuid();


        /*
         * 전원 명령 후 실제 목표 상태를 확인하기 위한
         * Power State READ 결과.
         */
        if (POWER_STATE_UUID.equals(
                characteristicUuid)) {


            handlePowerStateVerificationRead(
                    gatt,
                    value,
                    status
            );


            return;
        }


        /*
         * 전원 명령의 본래 결과가 이미 결정된 뒤 실행한
         * Mode / RF Channel READ 결과.
         */
        if (MODE_UUID.equals(
                characteristicUuid)) {


            handleOptionalChannelRead(
                    gatt,
                    value,
                    status
            );
        }
    }


    /*
     * Power State READ 결과 처리.
     *
     * 이 READ는 단순 정보 갱신이 아니라
     * Wake / Sleep 명령이 실제 목표 상태에 도달했는지
     * 확인하기 위한 핵심 검증 단계다.
     */
    private void handlePowerStateVerificationRead(
            BluetoothGatt gatt,
            byte[] value,
            int status) {


        if (gatt != currentGatt
                || !busy
                || !commandWritten
                || !waitingForRead) {

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
         * 목표 상태에 실제로 도달했는지 확인.
         */
        if (isTargetStateReached(
                powerValue
        )) {


            finishConfirmed();

            return;
        }


        /*
         * 아직 기동 중이거나 이전 상태라면
         * 잠시 기다렸다가 Power State만 다시 확인한다.
         *
         * 이 반복 검증 중에는 Channel을 매번 읽지 않는다.
         * 최종 목표 상태가 확인된 시점에 Channel을 한 번 읽는다.
         */
        scheduleNextVerification();
    }


    /*
     * 전원 명령 완료 뒤 실행한 Mode / RF Channel READ 결과 처리.
     *
     * 사용자가 LightLink 밖의 SteamVR 또는 다른 도구에서
     * 채널을 바꿨을 가능성도 있으므로 전원 버튼 작업이 끝날 때마다
     * 가능한 경우 현재 값을 다시 읽어서 덮어쓴다.
     *
     * Channel은 부가 정보이므로 실패해도
     * Wake / Sleep 성공 결과를 실패로 바꾸지 않는다.
     */
    private void handleOptionalChannelRead(
            BluetoothGatt gatt,
            byte[] value,
            int status) {


        if (gatt != currentGatt
                || !busy
                || !waitingForChannel) {

            return;
        }


        waitingForChannel =
                false;


        handler.removeCallbacks(
                channelTimeoutRunnable
        );


        if (status == BluetoothGatt.GATT_SUCCESS
                && value != null
                && value.length > 0) {


            int channel =
                    value[0] & 0xFF;


            AppLog.d(
                    "Power command Channel read"
                            + " / device = "
                            + (currentDevice != null
                            ? currentDevice.getName()
                            : "(unknown)")
                            + " / raw = "
                            + channel
            );


            /*
             * Base Station 2.0의 정상 RF Channel 범위만 저장한다.
             *
             * 이상한 값이 들어오면 추측해서 보정하지 않고
             * 기존 Channel 값을 유지한다.
             */
            if (channel >= 1
                    && channel <= 16
                    && currentDevice != null) {


                currentDevice.setChannel(
                        channel
                );


                AppLog.d(
                        "Power command Channel updated"
                                + " / device = "
                                + currentDevice.getName()
                                + " / channel = "
                                + channel
                );

            } else {

                AppLog.d(
                        "Power command Channel value ignored"
                                + " / raw = "
                                + channel
                );
            }

        } else {

            AppLog.d(
                    "Power command Channel read failed"
                            + " / status = "
                            + status
            );
        }


        /*
         * Channel 성공 여부와 관계없이
         * 마지막 부가 정보인 RSSI 갱신으로 이어간다.
         */
        beginOptionalRssiRead();
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
                || !commandWritten
                || waitingForChannel
                || waitingForRssi) {

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
                        && !commandWritten
                        && !waitingForChannel
                        && !waitingForRssi) {


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
                        && commandWritten
                        && !waitingForChannel
                        && !waitingForRssi) {


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


        AppLog.d(
                "Power state confirmed"
                        + " / device = "
                        + (currentDevice != null
                        ? currentDevice.getName()
                        : "(unknown)")
                        + " / target = "
                        + currentTarget
        );


        /*
         * 목표 상태까지 확인했으므로 전원 명령은 이미 성공이다.
         *
         * GATT를 닫기 전에 Channel → RSSI 순서로
         * 현재 장치 정보를 한 번 더 최신화한다.
         *
         * 사용자가 SteamVR 등에서 Channel을 바꿨더라도
         * 다음 전원 버튼 동작이 끝날 때 LightLink 표시가 따라잡을 수 있다.
         *
         * Channel / RSSI 실패가 이 성공 결과를 바꾸지는 않는다.
         */
        beginOptionalInfoRefresh(
                OptionalCompletionType.CONFIRMED
        );
    }


    /*
     * 실제 상태 확인까지 성공한 장치의 최종 완료 처리.
     *
     * Channel / RSSI 확인 성공 여부와 관계없이
     * 기존 onStateConfirmed() 의미를 그대로 유지한다.
     */
    private void completeConfirmedOperation() {

        if (!busy) {
            return;
        }


        BleDeviceItem device =
                currentDevice;

        TargetState target =
                currentTarget;

        CommandCallback callback =
                currentCallback;


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
     * 전원 명령의 본래 결과가 결정된 뒤
     * GATT 연결을 닫기 전에 최신 RSSI를 한 번 확인한다.
     *
     * completionType은 RSSI 확인 뒤 이어갈 완료 종류:
     * - CONFIRMED : 실제 Power State까지 확인 성공
     * - WRITE_ONLY: READ 미지원으로 Write 완료만 확인
     */
    @SuppressLint("MissingPermission")
    private void beginOptionalInfoRefresh(
            OptionalCompletionType completionType) {


        if (!busy
                || completionType == null
                || completionType == OptionalCompletionType.NONE) {

            return;
        }


        /*
         * 여기부터는 Power 명령 자체의 성공 여부가 이미 결정됐다.
         *
         * 따라서 기존 Command / Verification Timeout이
         * Channel 또는 RSSI를 기다리는 동안
         * 이미 결정된 성공 결과를 실패로 덮지 못하도록 제거한다.
         */
        handler.removeCallbacks(
                commandTimeoutRunnable
        );

        handler.removeCallbacks(
                verificationTimeoutRunnable
        );

        handler.removeCallbacks(
                verifyRunnable
        );


        waitingForRead =
                false;

        waitingForChannel =
                false;

        waitingForRssi =
                false;

        optionalCompletionType =
                completionType;


        /*
         * GATT가 이미 없다면 추가 정보를 읽을 수 없다.
         * 이 경우에는 원래 결정된 Power 명령 결과대로 바로 완료한다.
         */
        if (currentGatt == null) {

            AppLog.d(
                    "Optional device info refresh skipped: GATT unavailable"
            );


            completeAfterOptionalInfo();

            return;
        }


        /*
         * Mode Characteristic이 없으면 Channel만 생략하고
         * RSSI 확인은 계속 시도한다.
         */
        if (currentModeCharacteristic == null) {

            AppLog.d(
                    "Power command Channel refresh skipped: characteristic unavailable"
            );


            beginOptionalRssiRead();

            return;
        }


        int modeProperties =
                currentModeCharacteristic.getProperties();


        boolean modeReadable =
                (
                        modeProperties
                                & BluetoothGattCharacteristic.PROPERTY_READ
                ) != 0;


        /*
         * Mode Characteristic이 있어도 READ Capability가 없다면
         * 기존 Channel 값을 유지하고 RSSI 단계로 넘어간다.
         *
         * 특히 구형 펌웨어에서는 Characteristic별 Capability가
         * 서로 다를 수 있으므로 Power State READ 여부만 보고
         * Channel READ 가능 여부를 추측하지 않는다.
         */
        if (!modeReadable) {

            AppLog.d(
                    "Power command Channel refresh skipped: READ unsupported"
            );


            beginOptionalRssiRead();

            return;
        }


        waitingForChannel =
                true;


        boolean started;


        try {

            started =
                    currentGatt.readCharacteristic(
                            currentModeCharacteristic
                    );

        } catch (Exception e) {

            AppLog.d(
                    "Power command Channel read start error",
                    e
            );


            started =
                    false;
        }


        /*
         * Bluetooth Stack이 Channel READ 요청 자체를
         * 시작하지 못했더라도 Power 결과에는 영향이 없다.
         * 바로 RSSI 확인으로 넘어간다.
         */
        if (!started) {

            waitingForChannel =
                    false;


            AppLog.d(
                    "Power command Channel read could not start"
            );


            beginOptionalRssiRead();

            return;
        }


        handler.removeCallbacks(
                channelTimeoutRunnable
        );


        handler.postDelayed(
                channelTimeoutRunnable,
                CHANNEL_TIMEOUT_MS
        );
    }


    /*
     * Channel 확인이 끝났거나 생략된 뒤
     * 같은 GATT 연결에서 최신 RSSI를 한 번 확인한다.
     *
     * RSSI 역시 부가 정보이므로
     * 실패 / 시작 실패 / Timeout이 발생해도
     * 이미 결정된 Power 명령 결과는 그대로 유지한다.
     */
    @SuppressLint("MissingPermission")
    private void beginOptionalRssiRead() {


        if (!busy
                || optionalCompletionType == OptionalCompletionType.NONE) {

            return;
        }


        waitingForChannel =
                false;

        handler.removeCallbacks(
                channelTimeoutRunnable
        );


        waitingForRssi =
                true;


        if (currentGatt == null) {

            AppLog.d(
                    "Power command RSSI skipped: GATT unavailable"
            );


            completeAfterOptionalInfo();

            return;
        }


        boolean started;


        try {

            started =
                    currentGatt.readRemoteRssi();

        } catch (Exception e) {

            AppLog.d(
                    "Power command RSSI read start error",
                    e
            );


            started =
                    false;
        }


        if (!started) {

            AppLog.d(
                    "Power command RSSI read could not start"
            );


            completeAfterOptionalInfo();

            return;
        }


        handler.removeCallbacks(
                rssiTimeoutRunnable
        );


        handler.postDelayed(
                rssiTimeoutRunnable,
                RSSI_TIMEOUT_MS
        );
    }


    /*
     * Channel / RSSI 확인 성공, 실패, 시작 실패, 연결 종료, Timeout 후
     * 원래 예정되어 있던 전원 명령 완료 처리를 이어간다.
     *
     * Channel과 RSSI는 어디까지나 부가 정보이므로
     * 여기서는 전원 명령의 성공 종류를 바꾸지 않는다.
     */
    private void completeAfterOptionalInfo() {

        if (!busy) {
            return;
        }


        OptionalCompletionType completionType =
                optionalCompletionType;


        /*
         * Channel / RSSI Timeout과 실제 Callback이
         * 거의 동시에 들어와도 완료 처리가 두 번 실행되지 않도록
         * 먼저 모든 부가 정보 대기 상태를 지운다.
         */
        waitingForChannel =
                false;

        waitingForRssi =
                false;

        optionalCompletionType =
                OptionalCompletionType.NONE;


        handler.removeCallbacks(
                channelTimeoutRunnable
        );

        handler.removeCallbacks(
                rssiTimeoutRunnable
        );


        if (completionType == OptionalCompletionType.CONFIRMED) {

            completeConfirmedOperation();

        } else if (completionType == OptionalCompletionType.WRITE_ONLY) {

            completeWriteOnlyOperation();
        }
    }


    /*
     * Channel은 부가 정보이므로 오래 기다리지 않는다.
     *
     * Timeout이 나면 기존 Channel 값을 유지한 채
     * RSSI 갱신 단계로 넘어간다.
     */
    private final Runnable channelTimeoutRunnable =
            () -> {

                if (!busy
                        || !waitingForChannel
                        || optionalCompletionType == OptionalCompletionType.NONE) {

                    return;
                }


                AppLog.d(
                        "Power command Channel read timeout"
                );


                waitingForChannel =
                        false;


                beginOptionalRssiRead();
            };


    /*
     * RSSI는 부가 정보이므로 오래 기다리지 않는다.
     *
     * Timeout이 나면 기존 RSSI를 유지한 채
     * 이미 결정된 Wake / Sleep 결과대로 완료한다.
     */
    private final Runnable rssiTimeoutRunnable =
            () -> {

                if (!busy
                        || !waitingForRssi
                        || optionalCompletionType == OptionalCompletionType.NONE) {

                    return;
                }


                AppLog.d(
                        "Power command RSSI read timeout"
                );


                completeAfterOptionalInfo();
            };


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

        handler.removeCallbacks(
                channelTimeoutRunnable
        );

        handler.removeCallbacks(
                rssiTimeoutRunnable
        );


        BluetoothGatt gatt =
                currentGatt;


        currentGatt =
                null;

        currentPowerCharacteristic =
                null;

        currentModeCharacteristic =
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

        waitingForChannel =
                false;

        waitingForRssi =
                false;

        optionalCompletionType =
                OptionalCompletionType.NONE;


        connectRetryCount =
                0;

        sleepWriteStep =
                0;

        verifyDeadline =
                0;


        /*
         * 다음 일반 명령에 이전 수동 제어 설정이
         * 남지 않도록 기본값으로 복구한다.
         */
        stateVerificationEnabled =
                true;


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
