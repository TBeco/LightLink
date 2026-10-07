package com.tbeco.lightlink;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class MainActivity extends AppCompatActivity {

    // Logcat에서 BLE 검색 결과를 확인할 때 사용할 태그
    private static final String TAG = "LightLink";

    // 한 번 검색할 시간: 10초
    private static final long SCAN_TIME = 10000;


    // Android Bluetooth 기능
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;


    // 화면의 View
    private MaterialButton buttonScan;
    private TextView textEmpty;
    private RecyclerView recyclerBaseStations;


    // RecyclerView에 표시할 BLE 장치 목록
    private final List<BleDeviceItem> deviceList =
            new ArrayList<>();

    private BleDeviceAdapter deviceAdapter;


    /*
     * 이미 발견한 MAC 주소를 저장한다.
     *
     * BLE 장치는 광고 패킷을 반복해서 보내기 때문에
     * 같은 장치가 여러 번 onScanResult()에 들어온다.
     *
     * 같은 MAC 주소가 이미 여기에 존재하면
     * RecyclerView에는 다시 추가하지 않는다.
     */
    private final Set<String> seenAddresses =
            new HashSet<>();


    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean scanning = false;


    // Bluetooth / 위치 권한 요청 결과
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {

                        boolean allGranted = true;

                        // 요청했던 권한 중 하나라도 거부됐는지 확인
                        for (Boolean granted : result.values()) {

                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }


                        // 필요한 권한을 모두 받았다면 바로 검색 시작
                        if (allGranted) {

                            startBleScan();

                        } else {

                            textEmpty.setVisibility(View.VISIBLE);

                            textEmpty.setText(
                                    "Bluetooth permission required"
                            );
                        }
                    });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EdgeToEdge.enable(this);

        setContentView(R.layout.activity_main);


        // 상태바 / 내비게이션바 영역을 침범하지 않도록 Padding 적용
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
                });


        // XML의 View 가져오기
        buttonScan =
                findViewById(R.id.buttonScan);

        textEmpty =
                findViewById(R.id.textEmpty);

        recyclerBaseStations =
                findViewById(R.id.recyclerBaseStations);


        /*
         * RecyclerView 초기 설정
         *
         * LinearLayoutManager:
         * 장치를 위에서 아래 방향으로 한 줄씩 배치한다.
         */
        recyclerBaseStations.setLayoutManager(
                new LinearLayoutManager(this)
        );


        // RecyclerView와 장치 목록을 연결할 Adapter 생성
        deviceAdapter =
                new BleDeviceAdapter(deviceList);

        recyclerBaseStations.setAdapter(deviceAdapter);


        // Android 시스템의 Bluetooth 기능 가져오기
        BluetoothManager bluetoothManager =
                getSystemService(BluetoothManager.class);

        bluetoothAdapter =
                bluetoothManager.getAdapter();


        // Search 버튼을 누르면 권한을 확인한 뒤 BLE 검색
        buttonScan.setOnClickListener(
                v -> checkPermissionAndScan()
        );
    }


    // 현재 Android 버전에 필요한 권한 확인
    private void checkPermissionAndScan() {

        // Android 12 이상
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


            // 필요한 권한이 하나라도 없다면 요청
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

            // Android 11 이하에서는 BLE 검색에 위치 권한 사용
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


        // 권한이 이미 있다면 바로 검색
        startBleScan();
    }


    // BLE 검색 시작
    @SuppressLint("MissingPermission")
    private void startBleScan() {

        // Bluetooth 자체를 지원하지 않는 기기
        if (bluetoothAdapter == null) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Bluetooth not supported");

            return;
        }


        // Bluetooth가 꺼져 있음
        if (!bluetoothAdapter.isEnabled()) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Bluetooth is OFF");

            return;
        }


        // BLE Scanner 가져오기
        bleScanner =
                bluetoothAdapter.getBluetoothLeScanner();


        if (bleScanner == null) {

            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("BLE scanner unavailable");

            return;
        }


        /*
         * 새 검색을 시작하므로
         * 이전 검색 결과를 모두 비운다.
         */
        deviceList.clear();
        seenAddresses.clear();

        deviceAdapter.notifyDataSetChanged();


        scanning = true;


        // 아직 장치를 찾기 전에는 Scanning... 표시
        textEmpty.setVisibility(View.VISIBLE);
        textEmpty.setText("Scanning...");


        buttonScan.setText("Scanning...");
        buttonScan.setEnabled(false);


        // 실제 BLE 검색 시작
        bleScanner.startScan(scanCallback);


        // 10초 뒤 자동으로 검색 종료
        handler.postDelayed(
                this::stopBleScan,
                SCAN_TIME
        );
    }


    // BLE 검색 결과를 받는 Callback
    private final ScanCallback scanCallback =
            new ScanCallback() {

                @SuppressLint("MissingPermission")
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {


                    /*
                     * BluetoothDevice 자체가 알고 있는 이름
                     */
                    String deviceName =
                            result.getDevice().getName();


                    /*
                     * BLE 광고 패킷 안에 들어있는 이름
                     *
                     * 어떤 장치는 deviceName은 null이어도
                     * 광고 데이터에는 이름이 들어있을 수 있다.
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


                    // 수신 신호 세기
                    int rssi =
                            result.getRssi();


                    /*
                     * Logcat에는 일단 모든 BLE 장치를 출력한다.
                     *
                     * 나중에 검색 문제를 확인할 때
                     * 주변에 어떤 BLE 장치가 잡히는지 보기 위함.
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
                     * RecyclerView에 표시할 이름 결정
                     *
                     * 광고 이름이 있으면 우선 사용하고,
                     * 없다면 BluetoothDevice 이름을 사용한다.
                     *
                     * 둘 다 없다면 이름 없는 장치로 처리한다.
                     */
                    String displayName;

                    if (advertisedName != null) {

                        displayName = advertisedName;

                    } else if (deviceName != null) {

                        displayName = deviceName;

                    } else {

                        displayName = "(No name)";
                    }


                    /*
                     * Base Station 2.0만 RecyclerView에 표시
                     *
                     * 현재 확인한 Base Station 2.0은
                     * 이름이 "LHB-"로 시작한다.
                     *
                     * 다른 BLE 장치는 여기서 무시한다.
                     */
                    if (!displayName.startsWith("LHB-")) {
                        return;
                    }


                    /*
                     * BLE 장치는 광고 신호를 반복해서 보낸다.
                     *
                     * 같은 Base Station이 여러 번 검색되더라도
                     * RecyclerView에는 한 번만 표시하기 위해
                     * MAC 주소를 기준으로 중복을 확인한다.
                     */
                    if (seenAddresses.contains(address)) {
                        return;
                    }


                    // 처음 발견한 Base Station의 MAC 주소 저장
                    seenAddresses.add(address);


                    /*
                     * RecyclerView에 표시할 Base Station 정보 추가
                     *
                     * 현재는:
                     * - 이름
                     * - MAC 주소
                     * - RSSI
                     *
                     * 세 가지 정보만 저장한다.
                     */
                    deviceList.add(
                            new BleDeviceItem(
                                    displayName,
                                    address,
                                    rssi
                            )
                    );


                    /*
                     * RecyclerView에게
                     * 새로운 항목 하나가 추가됐다고 알려준다.
                     */
                    deviceAdapter.notifyItemInserted(
                            deviceList.size() - 1
                    );


                    /*
                     * Base Station을 하나라도 발견했다면
                     * 가운데의 "Scanning..." 문구는 숨긴다.
                     */
                    textEmpty.setVisibility(View.GONE);
                }


                /*
                 * BLE 검색 자체가 실패한 경우 호출
                 */
                @Override
                public void onScanFailed(int errorCode) {

                    scanning = false;


                    runOnUiThread(() -> {

                        textEmpty.setVisibility(View.VISIBLE);

                        textEmpty.setText(
                                "BLE scan failed: "
                                        + errorCode
                        );


                        buttonScan.setText("Search");
                        buttonScan.setEnabled(true);
                    });
                }
            };


    // BLE 검색 종료
    @SuppressLint("MissingPermission")
    private void stopBleScan() {

        if (!scanning) {
            return;
        }


        scanning = false;


        // 실제 BLE Scanner 정지
        if (bleScanner != null) {

            bleScanner.stopScan(scanCallback);
        }


        buttonScan.setText("Search");
        buttonScan.setEnabled(true);


        /*
         * 10초 동안 아무 장치도 발견하지 못했다면
         * 안내 문구 표시.
         */
        if (deviceList.isEmpty()) {

            textEmpty.setVisibility(View.VISIBLE);

            textEmpty.setText(
                    "No BLE device found"
            );
        }
    }
}