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
import android.util.Log;
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
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;


public class MainActivity extends AppCompatActivity {

    // Logcat에서 사용할 태그
    private static final String TAG = "LightLink";


    // BLE 검색 시간: 10초
    private static final long SCAN_TIME = 10000;


    // Base Station 한 대의 상태 확인 최대 대기 시간
    private static final long STATUS_TIMEOUT = 10000;


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

    private BaseStationGattController powerController;


    /*
     * 상태 확인이 너무 오래 걸리면
     * 다음 Base Station으로 넘어가기 위한 Timeout
     */
    private final Runnable statusTimeoutRunnable = () -> {

        if (!readingStatus) {
            return;
        }


        Log.d(
                TAG,
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


                            Log.d(
                                    TAG,
                                    "Power button clicked"
                                            + " / name = "
                                            + device.getName()
                                            + " / address = "
                                            + device.getAddress()
                                            + " / status = "
                                            + previousStatus
                            );


                            /*
                             * Controller가 아직 준비되지 않았거나
                             * 다른 전원 작업을 처리 중이면 새 명령을 시작하지 않는다.
                             */
                            if (powerController == null
                                    || powerController.isBusy()) {

                                Log.d(
                                        TAG,
                                        "Power command ignored: controller busy or unavailable"
                                );

                                return;
                            }


                            /*
                             * 초기 Power State 조회와 전원 Write가
                             * 동시에 진행되지 않도록 한다.
                             */
                            if (scanning
                                    || readingStatus
                                    || !statusQueue.isEmpty()) {

                                Log.d(
                                        TAG,
                                        "Power command ignored: scan/status read in progress"
                                );

                                return;
                            }


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
                                        device,
                                        BaseStationGattController.TargetState.SLEEP,
                                        previousStatus
                                );

                            } else if ("Sleep".equals(previousStatus)) {

                                // Wake 명령
                                sendPowerCommand(
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
         * Base Station 전원 제어 Controller 생성
         */
        powerController =
                new BaseStationGattController(
                        this,
                        bluetoothAdapter
                );


        /*
         * Search 버튼
         *
         * 권한 확인 후 BLE 검색 시작
         */
        buttonScan.setOnClickListener(
                v -> checkPermissionAndScan()
        );
    }

    /*
     * Base Station 전원 명령 전송
     */
    private void sendPowerCommand(
            BleDeviceItem device,
            BaseStationGattController.TargetState target,
            String previousStatus) {


        /*
         * 전원 전환 중에는
         * 새로운 Search를 시작하지 못하게 함.
         */
        buttonScan.setEnabled(
                false
        );


        powerController.setPowerState(
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


                        Log.d(
                                TAG,
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


                        updateDeviceRow(
                                device
                        );


                        Log.d(
                                TAG,
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


                        Log.d(
                                TAG,
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


                        Log.d(
                                TAG,
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
    }


    /*
     * 특정 Base Station의 RecyclerView 항목만 갱신
     */
    private void updateDeviceRow(
            BleDeviceItem device) {


        int position =
                deviceList.indexOf(
                        device
                );


        if (position >= 0) {

            deviceAdapter.notifyItemChanged(
                    position
            );
        }
    }


    /*
     * 전원 작업 종료 후 Search 버튼 복구
     *
     * 아직 BLE Scan 자체가 진행 중이라면
     * Scan 종료 시 stopBleScan()이 복구하도록 둔다.
     */
    private void restoreSearchButton() {

        /*
         * Scan 중이면 Search 버튼은 계속 잠근다.
         */
        if (scanning) {

            buttonScan.setText(
                    "Scanning..."
            );

            buttonScan.setEnabled(
                    false
            );

            return;
        }


        buttonScan.setText(
                "Search"
        );


        /*
         * 전원 전환 확인이 진행 중이라면
         * Scan이 끝났더라도 Search를 다시 활성화하지 않는다.
         */
        boolean powerBusy =
                powerController != null
                        && powerController.isBusy();


        boolean statusBusy =
                readingStatus
                        || !statusQueue.isEmpty();


        buttonScan.setEnabled(
                !powerBusy
                        && !statusBusy
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
        if (powerController != null
                && powerController.isBusy()) {

            Log.d(
                    TAG,
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
        if (powerController != null
                && powerController.isBusy()) {

            Log.d(
                    TAG,
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
         * 새로운 검색이므로
         * 이전 목록과 중복 기록을 모두 제거
         */
        deviceList.clear();
        seenAddresses.clear();

        deviceAdapter.notifyDataSetChanged();


        scanning = true;


        // 아직 Base Station을 찾기 전
        textEmpty.setVisibility(View.VISIBLE);
        textEmpty.setText("Scanning...");


        buttonScan.setText("Scanning...");
        buttonScan.setEnabled(false);


        /*
         * 실제 BLE 검색 시작
         */
        bleScanner.startScan(
                scanCallback
        );


        /*
         * 10초 후 자동으로 검색 종료
         */
        handler.postDelayed(
                this::stopBleScan,
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
                    Log.d(
                            TAG,
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

                    scanning = false;


                    runOnUiThread(() -> {

                        textEmpty.setVisibility(
                                View.VISIBLE
                        );


                        textEmpty.setText(
                                "BLE scan failed: "
                                        + errorCode
                        );


                        restoreSearchButton();
                    });
                }
            };


    /*
     * BLE 검색 종료
     */
    @SuppressLint("MissingPermission")
    private void stopBleScan() {

        if (!scanning) {

            return;
        }


        scanning = false;


        if (bleScanner != null) {

            bleScanner.stopScan(
                    scanCallback
            );
        }


        restoreSearchButton();


        /*
         * 검색 시간 동안 Base Station을
         * 하나도 발견하지 못함
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


        Log.d(
                TAG,
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

            Log.d(
                    TAG,
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

                        Log.d(
                                TAG,
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


                        Log.d(
                                TAG,
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


        String deviceName =
                currentStatusDevice != null
                        ? currentStatusDevice.getName()
                        : "(unknown)";


        Log.d(
                TAG,
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
         * 같은 연결이 두 번 종료되는 것을 방지
         */
        if (!readingStatus
                || gatt != currentGatt) {

            return;
        }


        // Timeout 취소
        handler.removeCallbacks(
                statusTimeoutRunnable
        );


        /*
         * 현재 장치를 지역 변수로 보관
         *
         * 아래에서 currentStatusDevice를 null로 만들더라도
         * RecyclerView 업데이트에 사용할 수 있다.
         */
        BleDeviceItem finishedDevice =
                currentStatusDevice;


        BluetoothGatt finishedGatt =
                currentGatt;


        // 현재 작업 종료 처리
        readingStatus =
                false;

        currentStatusDevice =
                null;

        currentGatt =
                null;


        /*
         * 읽어온 상태 저장
         */
        if (finishedDevice != null) {

            finishedDevice.setStatus(
                    statusText
            );


            runOnUiThread(() -> {

                int position =
                        deviceList.indexOf(
                                finishedDevice
                        );


                if (position >= 0) {

                    deviceAdapter.notifyItemChanged(
                            position
                    );
                }
            });
        }


        /*
         * 상태 확인만 필요했으므로
         * GATT 연결 종료
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


        /*
         * 다음 Base Station 상태 확인
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


        if (powerController != null) {

            powerController.close();
        }


        handler.removeCallbacksAndMessages(
                null
        );


        super.onDestroy();
    }
}
