package com.tbeco.lightlink;

import android.Manifest;
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
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class MainActivity extends AppCompatActivity {


    // BLE 검색 시간: 10초
    private static final long SCAN_TIME = 10000;

    /*
    * 최근 검색 시간을 화면에 표시할 형식
    *
    * 예:
    * 2026-10-08 15:32:14
     */
    private static final DateTimeFormatter LAST_SCAN_TIME_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss"
            );

    /*
     * BLE 검색 자동 종료 타이머
     *
     * 별도의 Runnable로 보관해야
     * 사용자가 직접 검색을 중지했을 때
     * 이전 타이머도 확실하게 취소할 수 있다.
     */
    private final Runnable scanTimeoutRunnable =
            this::stopBleScan;


    // Base Station 한 대의 상태 확인 최대 대기 시간
    private static final long STATUS_TIMEOUT = 10000;


    /*
     * 초기 Power State 조회가 일시적으로 실패했을 때
     * 추가로 다시 시도할 최대 횟수.
     *
     * 최초 시도 + 재시도 3회 = 최대 4번 확인한다.
     */
    private static final int STATUS_MAX_RETRIES = 3;


    /*
     * 초기 상태 조회 재시도 사이의 대기 시간.
     *
     * 오래된 휴대폰이나 일시적으로 불안정한 GATT 연결이
     * 바로 다시 연결되면서 연속 실패하는 것을 줄이기 위해
     * 잠깐 쉬었다가 다시 시도한다.
     */
    private static final long STATUS_RETRY_DELAY = 1200;


    /*
     * Base Station 2.0 Control Service
     */
    private static final UUID CONTROL_SERVICE_UUID =
            UUID.fromString(
                    "00001523-1212-EFDE-1523-785FEABCD124"
            );


    /*
     * Base Station 2.0 Power State Characteristic
     */
    private static final UUID POWER_STATE_UUID =
            UUID.fromString(
                    "00001525-1212-EFDE-1523-785FEABCD124"
            );


    // Android Bluetooth 기능
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;


    // 화면의 View
    private MaterialButton buttonScan;
    private TextView textEmpty;
    private TextView textLastScan;
    private RecyclerView recyclerBaseStations;


    /*
     * RecyclerView에 표시할 Base Station 목록
     *
     * 현재는 앱을 종료하거나 다시 검색하면 사라지는
     * 임시 목록이다.
     */
    private final List<BleDeviceItem> deviceList =
            new ArrayList<>();


    private BleDeviceAdapter deviceAdapter;


    /*
     * 이미 발견한 Base Station의 MAC 주소
     *
     * BLE 광고는 반복해서 들어오기 때문에
     * 같은 Base Station이 RecyclerView에 여러 번
     * 추가되는 것을 방지한다.
     */
    private final Set<String> seenAddresses =
            new HashSet<>();


    /*
     * 상태 조회 대기열
     *
     * Base Station이 여러 대 발견되어도
     * 동시에 연결하지 않고 한 대씩 순서대로 처리한다.
     */
    private final Queue<BleDeviceItem> statusQueue =
            new ArrayDeque<>();


    /*
     * Base Station별 초기 상태 조회 재시도 횟수.
     *
     * Key   : MAC 주소
     * Value : 이미 실행한 추가 재시도 횟수
     */
    private final Map<String, Integer> statusRetryCounts =
            new HashMap<>();


    /*
     * 새 Search가 시작되면 이전 Search에서 예약해 둔
     * 지연 재시도가 뒤늦게 실행되지 않도록 구분하는 번호.
     */
    private int statusReadGeneration = 0;


    private final Handler handler =
            new Handler(Looper.getMainLooper());


    // 현재 BLE 검색 중인지 여부
    private boolean scanning = false;


    // 현재 Base Station 상태를 읽고 있는지 여부
    private boolean readingStatus = false;


    // 현재 상태를 확인하고 있는 Base Station
    private BleDeviceItem currentStatusDevice;


    // 현재 연결되어 있는 GATT
    private BluetoothGatt currentGatt;

    /*
     * Base Station별 전원 제어 Controller.
     *
     * Key   : Base Station MAC 주소
     * Value : 해당 장치만 담당하는 Controller
     *
     * 장치마다 Controller를 따로 두기 때문에
     * 서로 다른 Base Station의 Wake / Sleep 작업은
     * 서로 기다리지 않고 독립적으로 진행할 수 있다.
     *
     * 검색 결과 개수에는 별도의 제한을 두지 않는다.
     */
    private final Map<String, BaseStationGattController> powerControllers =
            new HashMap<>();


    /*
     * 상태 확인이 너무 오래 걸리면
     * 다음 Base Station으로 넘어가기 위한 Timeout
     */
    private final Runnable statusTimeoutRunnable = () -> {

        if (!readingStatus) {
            return;
        }


        AppLog.d(
                "Power state read timeout"
        );


        /*
         * currentGatt가 null인 예외적인 상황에서도
         * 현재 상태 조회 작업을 끝내서 Queue가 멈추지 않게 한다.
         */
        finishStatusRead(
                currentGatt,
                "Timeout"
        );
    };


    /*
     * Bluetooth / 위치 권한 요청 결과
     */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {

                        boolean allGranted = true;


                        // 요청한 권한 중 하나라도 거부됐는지 확인
                        for (Boolean granted : result.values()) {

                            if (!granted) {

                                allGranted = false;
                                break;
                            }
                        }


                        if (allGranted) {

                            // 모든 권한이 있으면 검색 시작
                            startBleScan();

                        } else {

                            textEmpty.setVisibility(View.VISIBLE);

                            textEmpty.setText(
                                    "Bluetooth permission required"
                            );
                        }
                    }
            );


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);


        /*
         * 상태바 / 내비게이션바 영역을
         * 화면 내용이 침범하지 않도록 Padding 적용
         */
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (v, insets) -> {

                    Insets systemBars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                            );


                    v.setPadding(
                            systemBars.left,
                            systemBars.top,
                            systemBars.right,
                            systemBars.bottom
                    );


                    return insets;
                }
        );


        // XML의 View 가져오기
        buttonScan =
                findViewById(R.id.buttonScan);

        textEmpty =
                findViewById(R.id.textEmpty);

        textLastScan =
                findViewById(R.id.textLastScan);

        recyclerBaseStations =
                findViewById(R.id.recyclerBaseStations);


        /*
         * RecyclerView 설정
         *
         * Base Station을 위에서 아래 방향으로 표시
         */
        recyclerBaseStations.setLayoutManager(
                new LinearLayoutManager(this)
        );


        // RecyclerView와 데이터 연결
        deviceAdapter =
                new BleDeviceAdapter(
                        deviceList,

                        // 각 Base Station의 전원 버튼이 눌렸을 때 호출
                        device -> {

                            // 버튼을 누르기 직전의 상태 저장
                            String previousStatus =
                                    device.getStatus();


                            AppLog.d(
                                    "Power button clicked"
                                            + " / name = "
                                            + device.getName()
                                            + " / address = "
                                            + device.getAddress()
                                            + " / status = "
                                            + previousStatus
                            );


                            /*
                             * 이 Base Station 전용 Controller를 가져온다.
                             *
                             * 다른 Base Station은 각자 다른 Controller를 사용하므로
                             * 서로 독립적으로 Wake / Sleep 작업을 진행할 수 있다.
                             */
                            BaseStationGattController controller =
                                    getPowerController(
                                            device
                                    );


                            if (controller == null) {

                                AppLog.d(
                                        "Power command ignored: controller unavailable"
                                );

                                return;
                            }


                            /*
                             * 같은 Base Station에 대해서만
                             * 중복 전원 명령을 막는다.
                             */
                            if (controller.isBusy()) {

                                AppLog.d(
                                        "Power command ignored: this device is already busy"
                                );

                                return;
                            }


                            /*
                             * 검색이나 다른 Base Station의 초기 상태 조회가
                             * 진행 중이어도 이 장치의 상태가 Awake / Sleep으로
                             * 확정되어 있다면 전원 명령을 허용한다.
                             *
                             * 같은 장치의 중복 명령은 위의 controller.isBusy()로 막고,
                             * 아직 상태가 확정되지 않은 장치는 아래의 상태 검사에서
                             * 자연스럽게 제외된다.
                             */


                            /*
                             * Awake / Sleep 상태에서만
                             * 전원 명령을 처리한다.
                             */
                            if (!"Awake".equals(previousStatus)
                                    && !"Sleep".equals(previousStatus)) {

                                return;
                            }


                            /*
                             * 명령을 전송하기 시작하면
                             * 실제 결과가 확인될 때까지 Checking...으로 변경.
                             *
                             * Adapter는 Checking... 상태에서
                             * 전원 버튼을 자동으로 비활성화한다.
                             */
                            device.setStatus(
                                    "Checking..."
                            );


                            // 변경된 장치의 위치 찾기
                            int position =
                                    deviceList.indexOf(device);


                            // 해당 RecyclerView 항목만 다시 그림
                            if (position >= 0) {

                                deviceAdapter.notifyItemChanged(
                                        position
                                );
                            }


                            /*
                             * 다음 단계:
                             *
                             * Awake였으면 → Sleep 명령
                             * Sleep이었으면 → Wake 명령
                             */
                            if ("Awake".equals(previousStatus)) {

                                // Sleep 명령
                                sendPowerCommand(
                                        controller,
                                        device,
                                        BaseStationGattController.TargetState.SLEEP,
                                        previousStatus
                                );

                            } else if ("Sleep".equals(previousStatus)) {

                                // Wake 명령
                                sendPowerCommand(
                                        controller,
                                        device,
                                        BaseStationGattController.TargetState.AWAKE,
                                        previousStatus
                                );
                            }
                        }
                );


        recyclerBaseStations.setAdapter(
                deviceAdapter
        );


        /*
         * Android 시스템의 Bluetooth 기능 가져오기
         */
        BluetoothManager bluetoothManager =
                getSystemService(BluetoothManager.class);


        bluetoothAdapter =
                bluetoothManager.getAdapter();

        /*
         * 앱 시작 시 Search 버튼 초기 상태 설정
         */
        updateScanButton();


        /*
         * 검색 버튼
         *
         * 검색 중:
         * Stop 버튼으로 동작
         *
         * 검색 중이 아님:
         * 새로운 검색 시작
         */
        buttonScan.setOnClickListener(v -> {

            if (scanning) {

                stopBleScan();

            } else {

                checkPermissionAndScan();
            }
        });
    }

    /*
     * Base Station 전원 명령 전송
     */
    private void sendPowerCommand(
            BaseStationGattController controller,
            BleDeviceItem device,
            BaseStationGattController.TargetState target,
            String previousStatus) {


        /*
         * 여기서 Search 버튼을 무조건 비활성화하지 않는다.
         *
         * 검색 중이라면 사용자가 언제든 Stop을 누를 수 있어야 하고,
         * 검색 중이 아니라면 아래 setPowerState() 호출 뒤
         * updateScanButton()이 전원 작업 상태를 보고 Search만 잠근다.
         */


        controller.setPowerState(
                device,
                target,

                new BaseStationGattController.CommandCallback() {


                    /*
                     * BLE Write는 성공했음.
                     *
                     * 아직 실제 상태 확인 중이므로
                     * 화면은 Checking... 유지.
                     */
                    @Override
                    public void onCommandSent(
                            BleDeviceItem device,
                            BaseStationGattController.TargetState target) {


                        AppLog.d(
                                "Command sent successfully"
                                        + " / device = "
                                        + device.getName()
                                        + " / target = "
                                        + target
                        );
                    }


                    /*
                     * 실제 Base Station의 상태까지
                     * 목표 상태로 변경된 것을 확인함.
                     */
                    @Override
                    public void onStateConfirmed(
                            BleDeviceItem device,
                            BaseStationGattController.TargetState target) {


                        String confirmedStatus;


                        if (target
                                == BaseStationGattController.TargetState.AWAKE) {

                            confirmedStatus =
                                    "Awake";

                        } else {

                            confirmedStatus =
                                    "Sleep";
                        }


                        device.setStatus(
                                confirmedStatus
                        );

                        /*
                         * Wake / Sleep 전환 후
                         * 실제 목표 상태까지 확인했으므로
                         * 마지막 상태 확인 시간을 갱신한다.
                         */
                        device.setLastStateCheckedAt(
                                LocalDateTime.now()
                        );


                        updateDeviceRow(
                                device
                        );


                        AppLog.d(
                                "Power state confirmed"
                                        + " / device = "
                                        + device.getName()
                                        + " / status = "
                                        + confirmedStatus
                        );


                        restoreSearchButton();
                    }


                    /*
                     * 명령 자체는 전송됐지만
                     * 실제 상태를 확인하지 못한 경우.
                     *
                     * 이전 상태라고 가정하면 안 되므로
                     * Unknown으로 둔다.
                     */
                    @Override
                    public void onStateConfirmationFailed(
                            BleDeviceItem device,
                            String reason) {


                        device.setStatus(
                                "Unknown"
                        );


                        updateDeviceRow(
                                device
                        );


                        AppLog.d(
                                "Unable to confirm power state"
                                        + " / device = "
                                        + device.getName()
                                        + " / reason = "
                                        + reason
                        );


                        restoreSearchButton();
                    }


                    /*
                     * Write 자체가 실패한 경우에는
                     * 실제 상태가 바뀌지 않았다고 보고
                     * 기존 상태로 복구.
                     */
                    @Override
                    public void onCommandFailed(
                            BleDeviceItem device,
                            String reason) {


                        device.setStatus(
                                previousStatus
                        );


                        updateDeviceRow(
                                device
                        );


                        AppLog.d(
                                "Command failed"
                                        + " / device = "
                                        + device.getName()
                                        + " / reason = "
                                        + reason
                        );


                        restoreSearchButton();
                    }
                }
        );


        /*
         * setPowerState()가 Controller의 busy 상태를 설정한 뒤
         * Search / Stop 버튼 상태를 다시 계산한다.
         *
         * - 검색 중    : Stop 버튼을 계속 활성화
         * - 검색 중 아님: 전원 작업이 끝날 때까지 새 Search만 비활성화
         */
        updateScanButton();
    }


    /*
     * 특정 Base Station의 RecyclerView 항목만 갱신
     */
    private void updateDeviceRow(
            BleDeviceItem device) {


        /*
         * GATT Callback은 메인 스레드가 아닐 수 있으므로
         * RecyclerView 갱신은 항상 UI 스레드에서 실행한다.
         */
        runOnUiThread(() -> {

            int position =
                    deviceList.indexOf(
                            device
                    );


            if (position >= 0) {

                deviceAdapter.notifyItemChanged(
                        position
                );
            }
        });
    }


    /*
     * 특정 Base Station 전용 Power Controller를 가져온다.
     *
     * 아직 생성되지 않은 장치라면 이 시점에 새로 만든다.
     * Controller는 MAC 주소별로 하나씩 유지한다.
     */
    private BaseStationGattController getPowerController(
            BleDeviceItem device) {


        if (device == null
                || bluetoothAdapter == null) {

            return null;
        }


        String address =
                device.getAddress();


        BaseStationGattController controller =
                powerControllers.get(
                        address
                );


        if (controller == null) {

            controller =
                    new BaseStationGattController(
                            this,
                            bluetoothAdapter
                    );


            powerControllers.put(
                    address,
                    controller
            );
        }


        return controller;
    }


    /*
     * Base Station 중 하나라도
     * Wake / Sleep 전환을 처리 중인지 확인한다.
     *
     * Search는 전원 전환이 하나라도 진행 중이면
     * 새로 시작하지 않도록 한다.
     */
    private boolean isAnyPowerControllerBusy() {

        for (BaseStationGattController controller
                : powerControllers.values()) {

            if (controller.isBusy()) {

                return true;
            }
        }


        return false;
    }


    /*
     * 현재 생성되어 있는 모든 Power Controller를 정리한다.
     *
     * 새 Search를 시작하면 이전 검색 결과를 모두 지우므로
     * 이전 장치용 Controller도 함께 정리한다.
     */
    private void clearPowerControllers() {

        for (BaseStationGattController controller
                : powerControllers.values()) {

            try {

                controller.close();

            } catch (Exception ignored) {
            }
        }


        powerControllers.clear();
    }


    /*
     * Search 버튼의 현재 상태를 다시 계산
     */
    private void restoreSearchButton() {

        /*
         * Power Controller의 Callback은 메인 스레드가 아닐 수 있으므로
         * View를 건드리는 updateScanButton()은 항상 UI 스레드에서 실행한다.
         */
        runOnUiThread(
                this::updateScanButton
        );
    }

    /*
     * Android 버전에 맞는 권한 확인
     */
    private void checkPermissionAndScan() {

        /*
         * 실제 Wake / Sleep 전환을 확인하는 동안에는
         * 새로운 Scan을 시작하지 않는다.
         */
        if (isAnyPowerControllerBusy()) {

            AppLog.d(
                    "Scan ignored: power command in progress"
            );

            return;
        }


        /*
         * Android 12 이상
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            boolean scanPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED;


            boolean connectPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED;


            boolean locationPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;


            /*
             * 필요한 권한이 하나라도 없다면 요청
             */
            if (!scanPermission
                    || !connectPermission
                    || !locationPermission) {

                permissionLauncher.launch(
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        }
                );

                return;
            }

        } else {

            /*
             * Android 11 이하
             *
             * BLE 검색에 위치 권한 사용
             */
            boolean locationPermission =
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED;


            if (!locationPermission) {

                permissionLauncher.launch(
                        new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION
                        }
                );

                return;
            }
        }


        // 필요한 권한이 이미 있음
        startBleScan();
    }


    /*
     * BLE 검색 시작
     */
    @SuppressLint("MissingPermission")
    private void startBleScan() {

        /*
         * 권한 요청 화면을 거친 사이 전원 작업이 시작됐을 수도 있으므로
         * 실제 Scan 직전에도 한 번 더 확인한다.
         */
        if (isAnyPowerControllerBusy()) {

            AppLog.d(
                    "Scan ignored: power command in progress"
            );

            restoreSearchButton();
            return;
        }


        /*
         * Bluetooth 자체를 지원하지 않는 기기
         */
        if (bluetoothAdapter == null) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Bluetooth not supported");

            return;
        }


        /*
         * Bluetooth가 꺼져 있음
         */
        if (!bluetoothAdapter.isEnabled()) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Bluetooth is OFF");

            return;
        }


        /*
         * BLE Scanner 가져오기
         */
        bleScanner =
                bluetoothAdapter.getBluetoothLeScanner();


        if (bleScanner == null) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("BLE scanner unavailable");

            return;
        }


        /*
         * 혹시 이전 검색에서 진행 중이던
         * 상태 조회가 있다면 초기화
         */
        resetStatusReads();


        /*
         * 새 Search에서는 이전 검색 결과를 사용하지 않으므로
         * 이전 장치용 Power Controller도 함께 정리한다.
         *
         * 전원 작업 중에는 startBleScan() 진입 자체가 막혀 있으므로
         * 진행 중인 명령을 여기서 끊는 일은 없다.
         */
        clearPowerControllers();


        /*
         * 새로운 검색이므로
         * 이전 목록과 중복 기록을 모두 제거
         */
        deviceList.clear();
        seenAddresses.clear();

        deviceAdapter.notifyDataSetChanged();


        scanning =
                true;


        /*
         * 검색 중 안내
         */
        textEmpty.setVisibility(
                View.VISIBLE
        );

        textEmpty.setText(
                "Scanning..."
        );


        /*
         * 검색 버튼을 Stop 버튼으로 변경
         *
         * 검색 중에도 버튼은 계속 눌릴 수 있어야 한다.
         */
        updateScanButton();


        /*
         * 실제 BLE 검색 시작
         */
        bleScanner.startScan(
                scanCallback
        );


        /*
         * 이번 BLE 검색이 시작된 시간을 기록
         */
         updateLastScanTime();


        /*
         * 혹시 남아있는 이전 검색 타이머가 있다면 제거
         */
        handler.removeCallbacks(
                scanTimeoutRunnable
        );


        /*
         * 10초 후 자동 검색 종료
         */
        handler.postDelayed(
                scanTimeoutRunnable,
                SCAN_TIME
        );
    }


    /*
     * BLE 검색 결과
     */
    private final ScanCallback scanCallback =
            new ScanCallback() {

                @SuppressLint("MissingPermission")
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {


                    /*
                     * BluetoothDevice가 알고 있는 이름
                     */
                    String deviceName =
                            result.getDevice().getName();


                    /*
                     * BLE 광고 패킷 안의 이름
                     */
                    String advertisedName = null;


                    if (result.getScanRecord() != null) {

                        advertisedName =
                                result.getScanRecord()
                                        .getDeviceName();
                    }


                    // MAC 주소
                    String address =
                            result.getDevice().getAddress();


                    // 신호 세기
                    int rssi =
                            result.getRssi();


                    /*
                     * 디버깅을 위해
                     * Logcat에는 주변 BLE 장치를 모두 출력
                     */
                    AppLog.d(
                            "Device"
                                    + " / deviceName = "
                                    + deviceName
                                    + " / advertisedName = "
                                    + advertisedName
                                    + " / address = "
                                    + address
                                    + " / RSSI = "
                                    + rssi
                    );


                    /*
                     * 화면에 사용할 이름 결정
                     *
                     * 광고 이름을 우선 사용하고,
                     * 없으면 BluetoothDevice 이름 사용
                     */
                    String displayName;


                    if (advertisedName != null) {

                        displayName =
                                advertisedName;

                    } else if (deviceName != null) {

                        displayName =
                                deviceName;

                    } else {

                        displayName =
                                "(No name)";
                    }


                    /*
                     * Base Station 2.0만 사용
                     *
                     * 현재 Base Station 2.0은
                     * LHB- 로 시작하는 이름으로 검색된다.
                     */
                    if (!displayName.startsWith("LHB-")) {

                        return;
                    }


                    /*
                     * 같은 MAC 주소가 이미 검색됐다면
                     * 다시 추가하지 않는다.
                     */
                    if (seenAddresses.contains(address)) {

                        return;
                    }


                    // 처음 발견한 Base Station
                    seenAddresses.add(address);


                    /*
                     * 새로운 Base Station 객체 생성
                     *
                     * 생성 직후 상태는 Checking...
                     */
                    BleDeviceItem newDevice =
                            new BleDeviceItem(
                                    displayName,
                                    address,
                                    rssi
                            );


                    // RecyclerView 데이터에 추가
                    deviceList.add(
                            newDevice
                    );


                    // 새 항목 추가 알림
                    deviceAdapter.notifyItemInserted(
                            deviceList.size() - 1
                    );


                    /*
                     * Base Station을 하나라도 찾았으므로
                     * Scanning... 문구 숨김
                     */
                    textEmpty.setVisibility(
                            View.GONE
                    );


                    /*
                     * 상태 조회 대기열에 추가
                     */
                    statusQueue.offer(
                            newDevice
                    );


                    /*
                     * 현재 다른 Base Station의 상태를
                     * 확인하고 있지 않다면 바로 시작
                     */
                    processNextStatusRead();
                }


                /*
                 * BLE 검색 자체가 실패한 경우
                 */
                @Override
                public void onScanFailed(int errorCode) {

                    scanning =
                            false;


                    /*
                     * 실패한 Scan의 자동 종료 타이머 제거
                     */
                    handler.removeCallbacks(
                            scanTimeoutRunnable
                    );


                    runOnUiThread(() -> {

                        textEmpty.setVisibility(
                                View.VISIBLE
                        );


                        textEmpty.setText(
                                "BLE scan failed: "
                                        + errorCode
                        );


                        updateScanButton();
                    });
                }
            };


    /*
     * BLE 검색 종료
     *
     * 현재까지 발견한 Base Station 목록은
     * 지우지 않는다.
     */
    @SuppressLint("MissingPermission")
    private void stopBleScan() {

        if (!scanning) {

            return;
        }


        scanning =
                false;


        /*
         * 자동 종료 타이머 제거
         *
         * 사용자가 직접 Stop을 누른 경우
         * 이전 타이머가 다음 검색에 영향을 주는 것을 방지한다.
         */
        handler.removeCallbacks(
                scanTimeoutRunnable
        );


        if (bleScanner != null) {

            try {

                bleScanner.stopScan(
                        scanCallback
                );

            } catch (Exception ignored) {
            }
        }


        /*
         * Stop 아이콘을 다시 Search 아이콘으로 변경
         */
        updateScanButton();


        /*
         * 검색 중 Base Station을 하나도 찾지 못한 경우
         */
        if (deviceList.isEmpty()) {

            textEmpty.setVisibility(
                    View.VISIBLE
            );

            textEmpty.setText(
                    "No Base Station found"
            );
        }
    }


    /*
     * 상태 조회 Queue에서
     * Base Station을 한 대씩 꺼내 처리
     */
    @SuppressLint("MissingPermission")
    private void processNextStatusRead() {

        /*
         * 이미 다른 Base Station의
         * 상태를 읽고 있다면 기다림
         */
        if (readingStatus) {

            return;
        }


        /*
         * Queue에서 다음 Base Station 가져오기
         */
        BleDeviceItem nextDevice =
                statusQueue.poll();


        /*
         * 더 이상 확인할 Base Station이 없음
         */
        if (nextDevice == null) {

            /*
             * Scan도 끝났고 모든 초기 상태 확인도 끝났다면
             * Search 버튼을 다시 사용할 수 있게 한다.
             */
            restoreSearchButton();

            return;
        }


        currentStatusDevice =
                nextDevice;

        readingStatus =
                true;


        AppLog.d(
                "Connecting for status: "
                        + currentStatusDevice.getName()
                        + " / "
                        + currentStatusDevice.getAddress()
        );


        /*
         * MAC 주소를 이용해 BluetoothDevice를 가져오고
         * Base Station과 BLE GATT 연결을 시작한다.
         *
         * false:
         * autoConnect를 사용하지 않고 즉시 연결 시도
         *
         * TRANSPORT_LE:
         * BLE 연결 사용
         */
        try {

            BluetoothDevice bluetoothDevice =
                    bluetoothAdapter.getRemoteDevice(
                            currentStatusDevice.getAddress()
                    );


            currentGatt =
                    bluetoothDevice.connectGatt(
                            this,
                            false,
                            gattCallback,
                            BluetoothDevice.TRANSPORT_LE
                    );


            if (currentGatt == null) {

                finishStatusRead(
                        null,
                        "Connection failed"
                );

                return;
            }

        } catch (Exception e) {

            AppLog.d(
                    "Status GATT connection error",
                    e
            );


            finishStatusRead(
                    null,
                    "Connection failed"
            );

            return;
        }


        /*
         * 연결 또는 상태 확인이 너무 오래 걸릴 경우
         * 다음 Base Station으로 넘어가기 위한 Timeout
         */
        handler.postDelayed(
                statusTimeoutRunnable,
                STATUS_TIMEOUT
        );
    }


    /*
     * Base Station과의 GATT 통신 Callback
     */
    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {


                /*
                 * GATT 연결 상태 변경
                 */
                @SuppressLint("MissingPermission")
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt gatt,
                        int status,
                        int newState) {


                    /*
                     * 이미 끝난 이전 연결의 Callback이라면 무시
                     */
                    if (gatt != currentGatt) {

                        try {
                            gatt.close();
                        } catch (Exception ignored) {
                        }

                        return;
                    }


                    /*
                     * 연결 자체에서 오류 발생
                     */
                    if (status != BluetoothGatt.GATT_SUCCESS) {

                        AppLog.d(
                                "GATT connection failed: "
                                        + status
                        );


                        finishStatusRead(
                                gatt,
                                "Connection failed"
                        );

                        return;
                    }


                    /*
                     * Base Station 연결 성공
                     */
                    if (newState
                            == BluetoothProfile.STATE_CONNECTED) {


                        String deviceName =
                                currentStatusDevice != null
                                        ? currentStatusDevice.getName()
                                        : "(unknown)";


                        AppLog.d(
                                "GATT connected: "
                                        + deviceName
                        );


                        /*
                         * Base Station이 제공하는
                         * GATT Service 검색
                         */
                        boolean started =
                                gatt.discoverServices();


                        if (!started) {

                            finishStatusRead(
                                    gatt,
                                    "Service discovery failed"
                            );
                        }

                    } else if (
                            newState
                                    == BluetoothProfile.STATE_DISCONNECTED) {


                        /*
                         * 우리가 아직 상태를 읽는 중인데
                         * 장치가 먼저 연결을 끊은 경우
                         */
                        if (readingStatus) {

                            finishStatusRead(
                                    gatt,
                                    "Disconnected"
                            );
                        }
                    }
                }


                /*
                 * GATT Service 검색 완료
                 */
                @SuppressLint("MissingPermission")
                @Override
                public void onServicesDiscovered(
                        BluetoothGatt gatt,
                        int status) {


                    if (gatt != currentGatt) {

                        return;
                    }


                    if (status != BluetoothGatt.GATT_SUCCESS) {

                        finishStatusRead(
                                gatt,
                                "Service error"
                        );

                        return;
                    }


                    /*
                     * Base Station Control Service 찾기
                     */
                    BluetoothGattService controlService =
                            gatt.getService(
                                    CONTROL_SERVICE_UUID
                            );


                    if (controlService == null) {

                        finishStatusRead(
                                gatt,
                                "Control service not found"
                        );

                        return;
                    }


                    /*
                     * Power State Characteristic 찾기
                     */
                    BluetoothGattCharacteristic powerState =
                            controlService.getCharacteristic(
                                    POWER_STATE_UUID
                            );


                    if (powerState == null) {

                        finishStatusRead(
                                gatt,
                                "Power state not found"
                        );

                        return;
                    }


                    /*
                     * 현재 Base Station Firmware에서
                     * Power State READ를 지원하는지 확인
                     */
                    boolean readable =
                            (
                                    powerState.getProperties()
                                            & BluetoothGattCharacteristic.PROPERTY_READ
                            ) != 0;


                    if (!readable) {

                        finishStatusRead(
                                gatt,
                                "State read unsupported"
                        );

                        return;
                    }


                    /*
                     * 실제 Power State 읽기 요청
                     */
                    boolean started =
                            gatt.readCharacteristic(
                                    powerState
                            );


                    if (!started) {

                        finishStatusRead(
                                gatt,
                                "Read failed"
                        );
                    }
                }


                /*
                 * Power State 읽기 완료
                 *
                 * Android 12 이하에서 사용하는 기존 Callback.
                 */
                @SuppressWarnings("deprecation")
                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {


                    byte[] value =
                            characteristic.getValue();


                    handleStatusCharacteristicRead(
                            gatt,
                            characteristic,
                            value,
                            status
                    );
                }


                /*
                 * Android 13 이상에서 사용하는 새 Callback.
                 *
                 * value를 Callback 인자로 직접 받기 때문에
                 * 최신 Android에서도 같은 처리 로직을 사용할 수 있다.
                 */
                @Override
                public void onCharacteristicRead(
                        BluetoothGatt gatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value,
                        int status) {


                    handleStatusCharacteristicRead(
                            gatt,
                            characteristic,
                            value,
                            status
                    );
                }
            };


    /*
     * Android 버전과 관계없이
     * 초기 Power State READ 결과를 한 곳에서 처리한다.
     */
    private void handleStatusCharacteristicRead(
            BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic,
            byte[] value,
            int status) {


        if (gatt != currentGatt) {
            return;
        }


        /*
         * 우리가 요청한 Power State가 아니면 무시
         */
        if (!POWER_STATE_UUID.equals(
                characteristic.getUuid())) {

            return;
        }


        if (status != BluetoothGatt.GATT_SUCCESS) {

            finishStatusRead(
                    gatt,
                    "Read error"
            );

            return;
        }


        if (value == null
                || value.length == 0) {

            finishStatusRead(
                    gatt,
                    "Unknown"
            );

            return;
        }


        /*
         * Java byte는 signed이므로
         * 0~255 범위의 unsigned 값으로 변환
         */
        int powerValue =
                value[0] & 0xFF;


        String powerState =
                convertPowerState(
                        powerValue
                );

        /*
         * 실제 Power State가 확정된 경우
         * 이 Base Station의 마지막 상태 확인 시간을 갱신한다.
         *
         * Checking... 또는 Unknown 상태에서는 갱신하지 않는다.
         */
        if (currentStatusDevice != null
                && ("Awake".equals(powerState)
                || "Sleep".equals(powerState)
                || "Standby".equals(powerState))) {

            currentStatusDevice.setLastStateCheckedAt(
                    LocalDateTime.now()
            );
        }


        String deviceName =
                currentStatusDevice != null
                        ? currentStatusDevice.getName()
                        : "(unknown)";


        AppLog.d(
                "Power state: "
                        + deviceName
                        + " = 0x"
                        + String.format(
                        "%02X",
                        powerValue
                )
                        + " / "
                        + powerState
        );


        finishStatusRead(
                gatt,
                powerState
        );
    }


    /*
     * Base Station의 Power State 값을
     * 사람이 보기 쉬운 문자열로 변환
     */
    private String convertPowerState(int value) {

        switch (value) {

            case 0x00:

                return "Sleep";


            case 0x02:

                return "Standby";


            case 0x09:
            case 0x0B:

                return "Awake";


            /*
             * 일부 Awake / 기동 과정에서
             * 관찰될 수 있는 값
             */
            case 0x01:

                return "Checking...";


            default:

                return String.format(
                        "Unknown (0x%02X)",
                        value
                );
        }
    }


    /*
     * 초기 상태 조회 결과가 일시적인 BLE / GATT 문제이거나
     * 아직 전환 중인 상태라면 자동 재확인한다.
     *
     * State read unsupported처럼 장치가 기능 자체를
     * 지원하지 않는 경우에는 재시도하지 않는다.
     */
    private boolean shouldRetryStatus(
            String statusText) {


        if (statusText == null) {
            return true;
        }


        return "Connection failed".equals(statusText)
                || "Disconnected".equals(statusText)
                || "Timeout".equals(statusText)
                || "Service error".equals(statusText)
                || "Service discovery failed".equals(statusText)
                || "Control service not found".equals(statusText)
                || "Power state not found".equals(statusText)
                || "Read failed".equals(statusText)
                || "Read error".equals(statusText)
                || "Unknown".equals(statusText)
                || "Checking...".equals(statusText)
                || statusText.startsWith("Unknown (");
    }


    /*
     * 현재 Base Station 상태 확인 완료
     *
     * 1. RecyclerView 상태 변경
     * 2. GATT 연결 종료
     * 3. 다음 Base Station 상태 확인
     */
    @SuppressLint("MissingPermission")
    private void finishStatusRead(
            BluetoothGatt gatt,
            String statusText) {


        /*
         * Timeout이나 늦은 Callback 때문에
         * 같은 연결이 두 번 종료되는 것을 방지한다.
         *
         * connectGatt() 자체가 실패한 경우에는
         * gatt와 currentGatt가 둘 다 null일 수도 있다.
         */
        if (!readingStatus
                || gatt != currentGatt) {

            return;
        }


        // 이번 상태 조회의 Timeout 취소
        handler.removeCallbacks(
                statusTimeoutRunnable
        );


        /*
         * 현재 장치와 GATT를 지역 변수로 보관한다.
         *
         * 아래에서 currentStatusDevice / currentGatt를
         * 초기화한 뒤에도 종료 및 재시도 처리에 사용한다.
         */
        BleDeviceItem finishedDevice =
                currentStatusDevice;


        BluetoothGatt finishedGatt =
                currentGatt;


        // 현재 상태 조회 작업 종료 처리
        readingStatus =
                false;

        currentStatusDevice =
                null;

        currentGatt =
                null;


        /*
         * 상태 확인만 필요했으므로
         * 이번 GATT 연결은 먼저 종료한다.
         *
         * 재시도가 필요하면 잠시 뒤 새 연결을 만든다.
         */
        if (finishedGatt != null) {

            try {

                finishedGatt.disconnect();

            } catch (Exception ignored) {
            }


            try {

                finishedGatt.close();

            } catch (Exception ignored) {
            }
        }


        boolean retryScheduled =
                false;


        if (finishedDevice != null
                && shouldRetryStatus(statusText)) {


            String address =
                    finishedDevice.getAddress();


            int retryCount =
                    statusRetryCounts.getOrDefault(
                            address,
                            0
                    );


            /*
             * 최대 재시도 횟수가 남아 있으면
             * 실패를 확정하지 않고 Checking... 상태를 유지한다.
             */
            if (retryCount < STATUS_MAX_RETRIES) {

                int nextRetryCount =
                        retryCount + 1;


                statusRetryCounts.put(
                        address,
                        nextRetryCount
                );


                retryScheduled =
                        true;


                finishedDevice.setStatus(
                        "Checking..."
                );


                updateDeviceRow(
                        finishedDevice
                );


                AppLog.d(
                        "Status read retry scheduled"
                                + " / device = "
                                + finishedDevice.getName()
                                + " / retry = "
                                + nextRetryCount
                                + "/"
                                + STATUS_MAX_RETRIES
                                + " / reason = "
                                + statusText
                );


                /*
                 * 현재 Search 세대를 기억한다.
                 *
                 * 재시도 대기 중 새 Search가 시작되면
                 * statusReadGeneration 값이 바뀌므로
                 * 이전 Search의 재시도는 자동으로 무시된다.
                 */
                int retryGeneration =
                        statusReadGeneration;


                handler.postDelayed(
                        () -> {

                            if (retryGeneration
                                    != statusReadGeneration) {

                                return;
                            }


                            /*
                             * 그 사이 새 Search로 목록이 교체됐다면
                             * 오래된 장치를 다시 Queue에 넣지 않는다.
                             */
                            if (!deviceList.contains(
                                    finishedDevice
                            )) {

                                return;
                            }


                            statusQueue.offer(
                                    finishedDevice
                            );


                            processNextStatusRead();
                        },
                        STATUS_RETRY_DELAY
                );
            }
        }


        /*
         * 재시도를 예약하지 않았다면
         * 이번 결과를 최종 상태로 표시한다.
         */
        if (finishedDevice != null
                && !retryScheduled) {


            statusRetryCounts.remove(
                    finishedDevice.getAddress()
            );


            /*
             * 0x01(Checking...)이 최대 재시도 후에도 계속되면
             * 영원히 Checking...으로 보이지 않도록
             * 최종적으로 Unknown으로 표시한다.
             */
            String finalStatus =
                    "Checking...".equals(statusText)
                            ? "Unknown"
                            : statusText;


            finishedDevice.setStatus(
                    finalStatus
            );


            updateDeviceRow(
                    finishedDevice
            );


            AppLog.d(
                    "Status read finished"
                            + " / device = "
                            + finishedDevice.getName()
                            + " / status = "
                            + finalStatus
            );
        }


        /*
         * 다른 Base Station이 Queue에 있다면 바로 처리한다.
         *
         * 방금 실패한 장치의 재시도는 STATUS_RETRY_DELAY 후
         * Queue 뒤쪽에 다시 들어오기 때문에 다른 장치를
         * 불필요하게 기다리게 하지 않는다.
         */
        runOnUiThread(
                this::processNextStatusRead
        );
    }


    /*
     * 이전 상태 조회 작업 초기화
     *
     * 새로운 Search를 시작할 때 사용
     */
    @SuppressLint("MissingPermission")
    private void resetStatusReads() {

        statusQueue.clear();


        /*
         * 이전 Search에서 예약된 재시도를 무효화하고
         * 장치별 재시도 횟수도 초기화한다.
         */
        statusReadGeneration++;

        statusRetryCounts.clear();


        handler.removeCallbacks(
                statusTimeoutRunnable
        );


        if (currentGatt != null) {

            try {

                currentGatt.disconnect();

            } catch (Exception ignored) {
            }


            try {

                currentGatt.close();

            } catch (Exception ignored) {
            }
        }


        currentGatt =
                null;

        currentStatusDevice =
                null;

        readingStatus =
                false;
    }


    /*
     * Activity 종료 시
     * BLE 작업도 함께 정리
     */
    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {

        if (bleScanner != null
                && scanning) {

            try {

                bleScanner.stopScan(
                        scanCallback
                );

            } catch (Exception ignored) {
            }
        }


        resetStatusReads();


        clearPowerControllers();


        handler.removeCallbacksAndMessages(
                null
        );


        super.onDestroy();
    }

    /*
     * 현재 검색 상태에 따라
     * 검색 버튼 아이콘과 활성 상태를 변경
     */
    private void updateScanButton() {

        /*
         * BLE 검색 중
         *
         * 다시 누르면 검색을 중지할 수 있으므로
         * 버튼은 반드시 활성화한다.
         */
        if (scanning) {

            buttonScan.setIconResource(
                    R.drawable.ic_stop
            );

            buttonScan.setContentDescription(
                    "Stop scan"
            );

            buttonScan.setEnabled(
                    true
            );

            return;
        }


        /*
         * 검색 중이 아님
         */
        buttonScan.setIconResource(
                R.drawable.ic_search
        );

        buttonScan.setContentDescription(
                "Search"
        );


        /*
         * 실제 Base Station 전원 전환 중일 때만
         * 새로운 검색을 막는다.
         *
         * 단순 상태 조회 중에는 다시 검색 가능.
         */
        boolean powerBusy =
                isAnyPowerControllerBusy();


        buttonScan.setEnabled(
                !powerBusy
        );
    }

    /*
     * 마지막 BLE 검색 완료 시간을 화면에 표시한다.
     *
     * 실제 BLE 검색이 시작된 직후 호출한다.
     */
    private void updateLastScanTime() {

        String timeText =
                LocalDateTime.now().format(
                        LAST_SCAN_TIME_FORMAT
                );


        textLastScan.setText(
                "최근 검색 | " + timeText
        );
    }
}
