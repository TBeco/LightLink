/*
 * LightLink - MainActivity
 *
 * 역할
 * 1. Android 런타임 권한 및 Bluetooth/위치 설정 확인
 * 2. 주변 Base Station 2.0(LHB- 이름)의 BLE 광고 검색 및 화면 표시
 * 3. 검색된 장치의 Power State/채널/RSSI 조회(장치별 순차 처리)
 * 4. BaseStationGattController를 통한 장치별 Wake/Sleep 명령
 * 5. 장치 관리 Bottom Sheet와 Identify 명령
 *
 * 설계상 주의사항
 * - BLE 검색과 초기 상태 조회는 별도의 단계다. 발견 직후 상태 조회가 진행된다.
 * - 전원 명령은 장치별 Controller가 담당하며, 초기 상태 조회 GATT와 분리된다.
 * - Android 8 이상 모든 지원 버전에서 현재 LightLink는 정확한 위치(FINE)를 요구한다.
 *   Android 12 이상에서는 BLUETOOTH_SCAN/CONNECT도 필요하다.
 * - 정확한 위치 '권한'과 휴대전화 전체의 위치 '서비스 ON/OFF'는 별개다.
 *   현재의 위치 기반 BLE 스캔 구성(neverForLocation 미사용)은 둘 다 확인한다.
 * - 검색 시작 직전뿐 아니라 화면 복귀 및 위치 서비스 변경 시에도 상태를 확인한다.
 *   위치 서비스가 꺼져 있으면 진행 중인 광고 검색만 중단하고 GATT는 임의로 끊지 않는다.
 * - '대략적인 위치'만 허용했을 때 Base Station 검색에 실패한 사례가 있다.
 *   이 결과는 해당 테스트 환경에 한정되며 모든 단말의 동작을 뜻하지 않는다.
 * - Android Settings의 앱 정보 화면 '권한' 항목에는 비공개 인자를 통해
 *   Ripple 강조를 요청한다. 일부 Galaxy에서 동작했지만 모든 기기에서 보장되지는 않는다.
 * - 스마트폰 전체 위치 서비스 설정은 공개 Intent로 이동한다.
 *   효과가 확인되지 않은 위치 스위치 Ripple 실험 코드는 사용하지 않는다.
 * - 앱 내부의 안내창은 dialog_settings_action.xml 하나로 스타일을 통일한다.
 *   Android OS 자체의 런타임 권한/Bluetooth 승인창은 시스템 UI를 사용한다.
 *
 * 이 파일의 사용자 문구는 추후 다국어 지원 시 strings.xml로 이관하는 것이 좋다.
 */
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.os.SystemClock;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetDialog;

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


    // 하나의 BLE 광고 검색 세션은 최대 10초 동안 유지한다(단위: ms).
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
     * Power State READ에서 0x01을 실제로 확인했을 때 표시하는 상태.
     * 'Stuck'은 연속된 조회에서 0x01이 유지되었다는 앱의 판단이며,
     * 장치 자체의 고장이나 공식 펌웨어 오류 코드를 확정하는 표현은 아니다.
     */
    private static final String STATUS_PROCESSING = "Processing (0x01)";
    private static final String STATUS_STUCK = "Stuck (0x01)";


    /*
     * 초기 상태 조회 재시도 사이의 대기 시간.
     *
     * 오래된 휴대폰이나 일시적으로 불안정한 GATT 연결이
     * 바로 다시 연결되면서 연속 실패하는 것을 줄이기 위해
     * 잠깐 쉬었다가 다시 시도한다.
     */
    private static final long STATUS_RETRY_DELAY = 1200;


    /*
     * READ 미지원 장치에서 수동 Wake / Sleep 명령이 끝난 뒤
     * 같은 전원 버튼을 다시 사용할 수 있게 하기 전의 짧은 대기 시간.
     *
     * 실제 상태를 READ해서 확인할 수 없는 장치이므로
     * Write 완료 직후 반대 명령을 연속으로 보내는 것을 줄인다.
     */
    private static final long MANUAL_POWER_REENABLE_DELAY_MS =
            1500;


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


    /*
     * Base Station 2.0 Mode / RF Channel Characteristic
     */
    private static final UUID MODE_UUID =
            UUID.fromString(
                    "00001524-1212-EFDE-1523-785FEABCD124"
            );


    /*
     * Android Bluetooth 객체.
     * Adapter는 Bluetooth 활성 상태/원격 장치 접근에 사용하고,
     * Scanner는 현재 BLE 광고 수신 세션을 시작·종료할 때 사용한다.
     */
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;

    /*
     * 권한 요청 이력 저장용 키 접두사.
     * shouldShowRequestPermissionRationale()은 최초 요청 전과 재요청 차단 상태에서
     * 모두 false를 반환할 수 있어, 최초 요청 여부를 별도로 저장한다.
     * 단, 이 이력은 시스템 권한 상태를 대신하지 않는다.
     */
    private static final String PERMISSION_PREF_PREFIX = "requested_permission_";

    /*
     * Android Settings의 '항목 강조' 내부 인자.
     * 공식 공개 API가 아니므로 삼성 One UI/Android 버전에 따라 무시될 수 있다.
     * Intent를 실행하는 기능과 강조 애니메이션은 서로 별개다.
     */
    private static final String SETTINGS_FRAGMENT_ARG_KEY =
            ":settings:fragment_args_key";
    private static final String SETTINGS_SHOW_FRAGMENT_ARGS =
            ":settings:show_fragment_args";

    /*
     * 예전 AOSP 앱 정보 화면의 '권한' 항목 키.
     * 제조사·OS에 따라 항목 키나 화면 구현이 다를 수 있다.
     */
    private static final String SETTINGS_PERMISSION_ITEM_KEY =
            "permission_settings";

    /*
     * 시스템 Bluetooth ON/OFF 전환 방송 수신.
     * OFF 전환 중에는 과거 검색 결과와 GATT가 더 이상 유효하지 않으므로 정리한다.
     * ON이 된 사실만으로 사용자의 의사 없이 자동 검색을 시작하지는 않는다.
     */
    private boolean bluetoothReceiverRegistered = false;
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            int state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
            );

            if (state == BluetoothAdapter.STATE_TURNING_OFF
                    || state == BluetoothAdapter.STATE_OFF) {
                handleBluetoothTurnedOff();
            } else if (state == BluetoothAdapter.STATE_ON) {
                if (!scanning && deviceList.isEmpty()) {
                    textEmpty.setVisibility(View.VISIBLE);
                    textEmpty.setText("Bluetooth is ON. Tap Search to scan.");
                }
                updateScanButton();
                refreshIdentifyButton();
            }
        }
    };


    /*
     * 기기 전체 위치 서비스 스위치가 바뀌면 실행되는 Android 시스템 방송 수신기.
     * MODE_CHANGED_ACTION은 Android 4.4(API 19)부터 제공되어 최소 지원 8.0에서도 사용 가능하다.
     *
     * 방송은 Activity가 보일 때만 수신한다. 설정 앱으로 이동한 동안의 변경은
     * onResume()에서 별도로 확인하므로 방송 누락에 의존하지 않는다.
     */
    private boolean locationReceiverRegistered = false;
    private final BroadcastReceiver locationStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) {
                return;
            }
            handleLocationServiceStateChanged();
        }
    };

    /*
     * 사용자가 Search에서 위치 설정 화면을 연 경우에만 복귀 후 검색을 이어가기 위한 플래그.
     * 단순히 위치 기능을 수동으로 켰다는 이유만으로 무작정 자동 검색하지 않는다.
     * Fold 등의 화면 회전 중에도 유지하도록 onSaveInstanceState()에 저장한다.
     */
    private static final String STATE_WAITING_FOR_LOCATION_SETTINGS =
            "waiting_for_location_settings";
    private boolean waitingForLocationSettings = false;

    // 화면 요소: 검색 버튼, 비어 있는 목록 안내, 최근 검색 시간, 장치 목록.
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


    // 장치 모델(deviceList)을 RecyclerView 각 행에 연결하고 상태 변화를 반영한다.
    private BleDeviceAdapter deviceAdapter;

    /* Identify GATT와 장치별 3초 중복 입력 제한. */
    private static final long IDENTIFY_COOLDOWN_MS = 3000L;
    private final Map<String, BaseStationIdentifyController> identifyControllers =
            new HashMap<>();
    private final Map<String, Long> identifyCooldownUntil = new HashMap<>();

    /*
     * 현재 열려 있는 장치 관리 Bottom Sheet.
     *
     * 장치 상태가 변경되면 식별 버튼과
     * READ 미지원 안내 문구를 함께 갱신한다.
     */
    private BottomSheetDialog currentDeviceSheet;
    private BleDeviceItem currentSheetDevice;
    private View currentIdentifyButton;

    // READ 미지원 장치 전용 안내 문구
    private View currentIdentifyWarningUnsupported;


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
     * Base Station별 연속 0x01 수신 횟수.
     * 단순 재시도 횟수가 아니라 성공적으로 0x01을 READ한 횟수만 센다.
     * 다른 상태나 연결/READ 오류가 발생하면 연속 횟수는 초기화한다.
     */
    private final Map<String, Integer> consecutiveProcessingReadCounts =
            new HashMap<>();


    /*
     * 새 Search가 시작되면 이전 Search에서 예약해 둔
     * 지연 재시도가 뒤늦게 실행되지 않도록 구분하는 번호.
     */
    private int statusReadGeneration = 0;


    // 타임아웃·재시도·UI 예약 작업은 메인 Looper에 등록한다.
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
     * 현재 상태 조회용 GATT 연결에서 찾은
     * Base Station Mode / RF Channel Characteristic.
     *
     * 채널 READ가 지원되지 않거나 Characteristic이 없으면 null이다.
     */
    private BluetoothGattCharacteristic currentModeCharacteristic;


    /*
     * Power State 확인은 끝났지만
     * 최신 RSSI 확인을 기다리고 있을 때 보관하는 상태값.
     *
     * 예:
     * Awake
     * Sleep
     * Standby
     * State read unsupported
     *
     * RSSI 확인이 실패해도 이 상태값은 그대로 사용한다.
     */
    private String pendingStatusAfterRssiRead;


    /*
     * Power State 확인은 끝났지만
     * 채널 READ 결과를 기다리고 있을 때
     * 이미 확인한 Power State 결과를 임시 보관한다.
     *
     * 채널은 부가 정보이므로
     * 채널 READ가 실패해도 Power State 결과는 유지한다.
     */
    private String pendingStatusAfterChannelRead;


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


        /*
         * Power State 확인은 이미 끝났고
         * RSSI 결과만 기다리던 중이었다면,
         *
         * RSSI Timeout 때문에 이미 확인한
         * Power State 결과까지 버리지 않는다.
         */
        if (pendingStatusAfterRssiRead != null) {


            String confirmedStatus =
                    pendingStatusAfterRssiRead;


            pendingStatusAfterRssiRead =
                    null;


            AppLog.d(
                    "RSSI read timeout"
                            + " / keeping status = "
                            + confirmedStatus
            );


            finishStatusRead(
                    currentGatt,
                    confirmedStatus
            );


            return;
        }


        /*
         * Power State 확인은 끝났고
         * Channel READ 결과만 기다리던 중이라면,
         *
         * 채널은 부가 정보이므로
         * 이미 확인한 Power State 결과를 유지하고 종료한다.
         *
         * 전체 상태 조회 Timeout에 이미 도달했으므로
         * 이 경우에는 RSSI READ를 새로 시작하지 않는다.
         */
        if (pendingStatusAfterChannelRead != null) {


            String confirmedStatus =
                    pendingStatusAfterChannelRead;


            pendingStatusAfterChannelRead =
                    null;


            AppLog.d(
                    "Channel read timeout"
                            + " / keeping status = "
                            + confirmedStatus
            );


            finishStatusRead(
                    currentGatt,
                    confirmedStatus
            );


            return;
        }


        /*
         * 아직 Power State 자체를 확인하지 못한 상태에서
         * Timeout이 발생한 경우.
         */
        AppLog.d(
                "Power state read timeout"
        );


        finishStatusRead(
                currentGatt,
                "Timeout"
        );
    };


    /*
     * 런타임 권한 요청 결과 콜백.
     * 반환 Map만으로 판단하지 않고 현재 OS가 부여한 권한을 다시 조회한다.
     * 필요한 권한이 모두 충족되면 같은 검색 진입점으로 돌아가며,
     * 부족한 권한이 남으면 화면에 재요청/설정 이동 안내를 표시한다.
     */
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if (getMissingPermissions().isEmpty()) {
                            checkPermissionAndScan();
                        } else {
                            showPermissionRequiredMessage();
                        }
                    }
            );

    /*
     * Bluetooth 활성화용 Android 시스템 확인창의 결과.
     * 사용자가 동의한 경우에만 권한·위치 조건을 다시 확인하고 BLE 검색을 시도한다.
     * Activity가 임의로 Bluetooth를 강제 활성화하지는 않는다.
     */
    private final ActivityResultLauncher<Intent> bluetoothEnableLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            checkPermissionAndScan();
                        } else {
                            textEmpty.setVisibility(View.VISIBLE);
                            textEmpty.setText("Bluetooth is OFF. Tap Search to enable it.");
                            updateScanButton();
                        }
                    }
            );

    /*
     * Activity 첫 생성 시 뷰를 연결하고 어댑터, 검색 버튼 및 장치 제어 콜백을 구성한다.
     * 실제 BLE 검색은 자동 시작하지 않고 사용자가 Search를 누르면 시작한다.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 설정 화면으로 이동한 사이 Activity가 재생성되더라도 검색 재개 의도를 복원한다.
        if (savedInstanceState != null) {
            waitingForLocationSettings = savedInstanceState.getBoolean(
                    STATE_WAITING_FOR_LOCATION_SETTINGS, false
            );
        }

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

        TextView textAppVersion =
                findViewById(R.id.textAppVersion);

        textAppVersion.setText(
                "v" + BuildConfig.VERSION_NAME
        );


        /*
         * RecyclerView 설정
         *
         * Base Station을 위에서 아래 방향으로 표시
         */
        recyclerBaseStations.setLayoutManager(
                new LinearLayoutManager(this)
        );

        /*
         * Debug 빌드에서는 UI 테스트용
         * 가상 Base Station을 목록에 추가한다.
         *
         * Release 빌드에서는
         * DebugMockDevices의 빈 구현이 사용되므로
         * 아무 장치도 추가되지 않는다.
         */
        DebugMockDevices.addMockDevices(
                deviceList
        );

        /*
         * 장치가 하나라도 있으면
         * 빈 목록 안내 문구를 숨긴다.
         */
        textEmpty.setVisibility(
                deviceList.isEmpty()
                        ? View.VISIBLE
                        : View.GONE
        );


        // RecyclerView와 데이터 연결
        deviceAdapter =
                new BleDeviceAdapter(
                        deviceList,

                        // 각 Base Station의 전원 버튼이 눌렸을 때 호출
                        device -> {

                            // 같은 장치의 Identify 전송 중에는 Power 명령을 시작하지 않는다.
                            if (isIdentifyBusy(device)) {
                                AppLog.d("Power ignored: Identify GATT is busy");
                                return;
                            }

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
                             * Power State READ는 지원하지 않지만
                             * WRITE는 가능한 장치.
                             *
                             * 현재 상태를 추측해서 반대 명령을 보내지 않고,
                             * 사용자가 Wake / Sleep을 직접 선택하도록 한다.
                             */
                            if (device.isStateReadUnsupported()
                                    && device.isManualPowerControlAvailable()) {

                                showManualPowerDialog(
                                        controller,
                                        device
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
                            refreshIdentifyButtonFor(device);


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
                        },

                        // 장치 정보 영역을 누르면 관리용 Bottom Sheet를 연다.
                        this::showDeviceManagementSheet,

                        // 해당 장치의 Identify GATT 진행 여부 확인
                        this::isIdentifyBusy
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
     * 장치 관리 Bottom Sheet. 식별 진행 타이머/진행 문구는 표시하지 않는다.
     * 단순 상태 표시(버튼 어둡게/클릭 불가)만 장치 상태에 맞춰 관리한다.
     */
    private void showDeviceManagementSheet(BleDeviceItem device) {
        if (currentDeviceSheet != null) currentDeviceSheet.dismiss();

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_device, null);
        dialog.setContentView(sheetView);

        TextView deviceName = sheetView.findViewById(R.id.textSheetDeviceName);
        View buttonClose = sheetView.findViewById(R.id.buttonSheetClose);
        View buttonIdentify = sheetView.findViewById(R.id.buttonIdentify);

        /*
         * Power State READ 미지원 장치 전용 안내.
         *
         * 기본 안내 문구는 XML에서 항상 표시하고,
         * 이 추가 안내만 장치 상태에 따라 표시한다.
         */
        View identifyWarningUnsupported =
                sheetView.findViewById(
                        R.id.textIdentifyWarningUnsupported
                );

        deviceName.setText(device.getName());


        currentDeviceSheet = dialog;
        currentSheetDevice = device;
        currentIdentifyButton = buttonIdentify;

        // READ 미지원 안내 문구 연결
        currentIdentifyWarningUnsupported = identifyWarningUnsupported;

        buttonClose.setOnClickListener(v -> dialog.dismiss());
        buttonIdentify.setOnClickListener(v -> startIdentify(device));

        dialog.setOnDismissListener(ignored -> {
            if (currentDeviceSheet == dialog) {
                currentDeviceSheet = null;
                currentSheetDevice = null;
                currentIdentifyButton = null;

                // Bottom Sheet 종료 시 참조 정리
                currentIdentifyWarningUnsupported = null;
            }
        });
        dialog.setOnShowListener(ignored -> {
            View bottomSheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) bottomSheet.setBackgroundColor(Color.TRANSPARENT);
        });

        refreshIdentifyButton();
        dialog.show();
    }

    /*
     * 식별 허용: Awake, Sleep, State read unsupported(수동 Power WRITE 가능)만.
     * Checking / Processing / Stuck / NOWRITE / Connection failed 등은 비활성.
     * 실제 Identify Characteristic 지원 여부는 BLE 연결 후에 별도로 확인한다.
     */
    private boolean canIdentify(BleDeviceItem device) {
        if (device == null || !deviceList.contains(device)
                || bluetoothAdapter == null || !canCheckBluetoothState()) return false;
        try {
            if (!bluetoothAdapter.isEnabled()) return false;
        } catch (SecurityException | IllegalStateException e) {
            return false;
        }
        String status = device.getStatus();
        boolean allowedState = "Awake".equals(status) || "Sleep".equals(status)
                || ("State read unsupported".equals(status)
                && device.isStateReadUnsupported()
                && device.isManualPowerControlAvailable());
        if (!allowedState || isIdentifyBusy(device)) return false;

        BaseStationGattController powerController =
                powerControllers.get(device.getAddress());
        if (powerController != null && powerController.isBusy()) return false;
        return SystemClock.elapsedRealtime() >= identifyCooldownUntil.getOrDefault(
                device.getAddress(), 0L);
    }

    /*
     * 현재 열려 있는 Bottom Sheet의 식별 관련 UI 갱신.
     *
     * 1. Identify 버튼 활성화 여부
     * 2. Power State READ 미지원 안내 표시 여부
     *
     * 장치 상태 변경 시 기존 refreshIdentifyButtonFor()를 통해
     * 이 메서드가 호출된다.
     */
    private void refreshIdentifyButton() {

        if (currentIdentifyButton == null
                || currentSheetDevice == null) {
            return;
        }

        /*
         * Identify 버튼 활성화 상태 갱신.
         *
         * 기존 canIdentify() 조건은 그대로 사용한다.
         */
        boolean enabled = canIdentify(currentSheetDevice);

        currentIdentifyButton.setEnabled(enabled);

        // 비활성 상태를 시각적으로 명확하게 표시
        currentIdentifyButton.setAlpha(
                enabled ? 1.0f : 0.43f
        );


        /*
         * Power State READ 미지원 장치 전용 안내.
         *
         * 상태 조회를 지원하지 않으면서
         * 수동 전원 제어가 가능한 장치에서만 표시한다.
         *
         * Checking, Processing, Stuck 등에서는 표시하지 않는다.
         */
        if (currentIdentifyWarningUnsupported != null) {

            boolean showUnsupportedWarning =
                    "State read unsupported".equals(
                            currentSheetDevice.getStatus()
                    )
                            && currentSheetDevice.isStateReadUnsupported()
                            && currentSheetDevice.isManualPowerControlAvailable();

            currentIdentifyWarningUnsupported.setVisibility(
                    showUnsupportedWarning
                            ? View.VISIBLE
                            : View.GONE
            );
        }
    }

    private void refreshIdentifyButtonFor(BleDeviceItem device) {
        if (device == currentSheetDevice) refreshIdentifyButton();
    }

    private boolean isIdentifyBusy(BleDeviceItem device) {
        BaseStationIdentifyController controller =
                identifyControllers.get(device.getAddress());
        return controller != null && controller.isBusy();
    }

    private boolean isAnyIdentifyBusy() {
        for (BaseStationIdentifyController controller : identifyControllers.values()) {
            if (controller.isBusy()) return true;
        }
        return false;
    }

    /*
     * Identify Characteristic(00008421...)에 0x01 한 바이트만 전송한다.
     * 3초 쿨다운은 LED 점멸 시간과 별개로 중복 입력만 제한한다.
     */
    private void startIdentify(BleDeviceItem device) {
        if (!canIdentify(device)) {
            refreshIdentifyButton();
            return; // 비활성 버튼 입력은 별도의 Toast 없이 무시.
        }

        final String address = device.getAddress();
        final String previousStatus = device.getStatus();
        BaseStationIdentifyController controller = identifyControllers.get(address);
        if (controller == null) {
            controller = new BaseStationIdentifyController(this, bluetoothAdapter);
            identifyControllers.put(address, controller);
        }

        identifyCooldownUntil.put(address,
                SystemClock.elapsedRealtime() + IDENTIFY_COOLDOWN_MS);
        controller.identify(address, (success, reason) -> {
            if (!deviceList.contains(device)) return; // 재검색으로 사라진 장치
            AppLog.d((success ? "Identify sent" : "Identify failed")
                    + " / " + device.getName() + " / " + reason);
            if (!success) {

                // 버튼 비활성 시에는 아무 메시지가 없지만 통신 실패는 표시한다.
                Toast.makeText(
                        this,
                        "식별 명령 실패: " + reason,
                        Toast.LENGTH_SHORT
                ).show();

                /*
                 * Identify 실패 후 전원 버튼 UI 복구.
                 *
                 * 실제 장치 상태는 변경하지 않았으므로
                 * Sleep 상태였다면 다시 파란색 버튼으로 돌아간다.
                 */
                updateDeviceRow(device);

            } else if ("Sleep".equals(previousStatus)
                    && "Sleep".equals(device.getStatus())) {
                // 실제 식별 명령으로 Sleep 장치가 깨어날 수 있으므로 다시 읽어 확인한다.
                device.setStatus("Checking...");
                updateDeviceRow(device);
                final int generation = statusReadGeneration;
                handler.postDelayed(() -> {
                    if (generation != statusReadGeneration
                            || !deviceList.contains(device)
                            || !"Checking...".equals(device.getStatus())) return;
                    statusQueue.offer(device);
                    processNextStatusRead();
                }, 2500L);
            }
            refreshIdentifyButtonFor(device);
            updateScanButton();
        });

        AppLog.d("Identify requested / " + device.getName());

        /*
         * Identify GATT가 시작되었으므로
         * 해당 장치의 전원 버튼 UI를 다시 갱신한다.
         *
         * Sleep 상태라면 어두운 파란색 + 비활성화로 표시된다.
         */
        updateDeviceRow(device);

        refreshIdentifyButtonFor(device);
        updateScanButton();

        handler.postDelayed(() -> refreshIdentifyButtonFor(device),
                IDENTIFY_COOLDOWN_MS + 30L);
    }

    private void clearIdentifyControllers() {
        for (BaseStationIdentifyController controller : identifyControllers.values()) {
            controller.close();
        }
        identifyControllers.clear();
        identifyCooldownUntil.clear();
        refreshIdentifyButton();
    }

    /*
     * Base Station 전원 명령 전송
     */
    private void sendPowerCommand(
            BaseStationGattController controller,
            BleDeviceItem device,
            BaseStationGattController.TargetState target,
            String previousStatus) {

        if (isIdentifyBusy(device)) {
            AppLog.d("Power command ignored: Identify GATT is busy");
            return;
        }


        /*
         * 여기서 Search 버튼을 무조건 비활성화하지 않는다.
         *
         * 검색 중이라면 사용자가 언제든 Stop을 누를 수 있어야 하고,
         * 검색 중이 아니라면 아래 setPowerState() 호출 뒤
         * updateScanButton()이 전원 작업 상태를 보고 Search만 잠근다.
         */


        /*
         * 정상적으로 Power State READ가 가능한 장치는
         * 기존처럼 명령 후 실제 상태까지 다시 확인한다.
         */
        controller.setStateVerificationEnabled(
                true
        );


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
     * Power State READ를 지원하지 않는 장치에서
     * 사용자가 Wake / Sleep 목표 상태를 직접 선택한다.
     *
     * 현재 상태를 알 수 없으므로
     * 하나의 토글 버튼처럼 반대 상태를 추측하지 않는다.
     *
     * 기본 AlertDialog UI 대신
     * LightLink 스타일의 커스텀 레이아웃을 사용한다.
     */
    private void showManualPowerDialog(
            BaseStationGattController controller,
            BleDeviceItem device) {


        /*
         * dialog_manual_power.xml을
         * 실제 Dialog 안에서 사용할 View로 생성한다.
         */
        View dialogView =
                getLayoutInflater().inflate(
                        R.layout.dialog_manual_power,
                        null
                );


        /*
         * 커스텀 레이아웃을 사용하는 AlertDialog 생성.
         *
         * 제목 / 메시지 / 기본 버튼은 사용하지 않고
         * 모든 UI를 dialog_manual_power.xml에서 관리한다.
         */
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setView(dialogView)
                        .create();


        /*
         * 커스텀 Dialog 안의 버튼 가져오기
         */
        View buttonCancel =
                dialogView.findViewById(
                        R.id.buttonManualCancel
                );

        View buttonSleep =
                dialogView.findViewById(
                        R.id.buttonManualSleep
                );

        View buttonWake =
                dialogView.findViewById(
                        R.id.buttonManualWake
                );


        /*
         * 취소
         *
         * 아무 명령도 보내지 않고 Dialog만 닫는다.
         */
        buttonCancel.setOnClickListener(v ->
                dialog.dismiss()
        );


        /*
         * Sleep
         */
        buttonSleep.setOnClickListener(v -> {

            /*
             * 먼저 Dialog를 닫고
             * 사용자가 선택한 Sleep 명령을 전송한다.
             */
            dialog.dismiss();


            sendManualPowerCommand(
                    controller,
                    device,
                    BaseStationGattController.TargetState.SLEEP
            );
        });


        /*
         * Wake
         */
        buttonWake.setOnClickListener(v -> {

            /*
             * 먼저 Dialog를 닫고
             * 사용자가 선택한 Wake 명령을 전송한다.
             */
            dialog.dismiss();


            sendManualPowerCommand(
                    controller,
                    device,
                    BaseStationGattController.TargetState.AWAKE
            );
        });


        /*
         * Dialog 표시
         */
        dialog.show();


        /*
         * AlertDialog 자체의 기본 배경을 투명하게 만든다.
         *
         * 그래야 dialog_manual_power.xml에 적용한
         * 둥근 모서리와 어두운 배경이 그대로 보이고,
         * 뒤에 기본 흰색 Dialog 배경이 남지 않는다.
         */
        if (dialog.getWindow() != null) {

            dialog.getWindow()
                    .setBackgroundDrawableResource(
                            android.R.color.transparent
                    );
        }
    }


    /*
     * Power State READ가 지원되지 않는 장치에
     * 사용자가 선택한 Wake / Sleep 명령을 전송한다.
     *
     * 이 경우에는 실제 상태를 다시 READ할 수 없으므로
     * BLE Write 완료까지만 확인한다.
     *
     * 따라서 명령 성공 후에도
     * Awake / Sleep이라고 확정해서 표시하지 않는다.
     */
    private void sendManualPowerCommand(
            BaseStationGattController controller,
            BleDeviceItem device,
            BaseStationGattController.TargetState target) {


        if (controller == null
                || controller.isBusy()) {

            return;
        }


        /*
         * 버튼 중복 입력을 막기 위해
         * 명령 처리 중에는 Checking...으로 표시한다.
         *
         * stateReadUnsupported 플래그는 그대로 유지되므로
         * 마지막 상태 확인 문구는 "지원되지 않음"으로 유지된다.
         */
        device.setStatus(
                "Checking..."
        );


        updateDeviceRow(
                device
        );


        /*
         * 이 장치는 Power State READ 자체를 지원하지 않으므로
         * Write 뒤의 상태 검증 단계를 사용하지 않는다.
         */
        controller.setStateVerificationEnabled(
                false
        );


        controller.setPowerState(
                device,
                target,

                new BaseStationGattController.CommandCallback() {


                    /*
                     * Wake 또는 Sleep Write가 정상적으로 완료됨.
                     *
                     * 실제 목표 상태를 READ해서 확인한 것은 아니므로
                     * Awake / Sleep으로 확정하지 않는다.
                     *
                     * 또한 Write 직후 바로 같은 버튼을 다시 누르지 못하도록
                     * 잠깐 Checking... 상태를 유지한 뒤
                     * 다시 수동 제어 가능 상태로 되돌린다.
                     */
                    @Override
                    public void onCommandSent(
                            BleDeviceItem device,
                            BaseStationGattController.TargetState target) {


                        AppLog.d(
                                "Manual power command sent"
                                        + " / device = "
                                        + device.getName()
                                        + " / target = "
                                        + target
                        );


                        scheduleManualPowerControlRestore(
                                device
                        );


                        restoreSearchButton();
                    }


                    /*
                     * 수동 제어에서는 실제 상태 검증을 하지 않으므로
                     * 정상적으로는 호출되지 않는다.
                     *
                     * 예외적으로 호출되더라도 실제 상태를 추측하지 않는다.
                     */
                    @Override
                    public void onStateConfirmed(
                            BleDeviceItem device,
                            BaseStationGattController.TargetState target) {


                        scheduleManualPowerControlRestore(
                                device
                        );


                        restoreSearchButton();
                    }


                    /*
                     * 수동 제어에서는 상태 검증 단계를 사용하지 않으므로
                     * 정상적으로는 호출되지 않는다.
                     */
                    @Override
                    public void onStateConfirmationFailed(
                            BleDeviceItem device,
                            String reason) {


                        scheduleManualPowerControlRestore(
                                device
                        );


                        AppLog.d(
                                "Manual power state confirmation unexpectedly failed"
                                        + " / device = "
                                        + device.getName()
                                        + " / reason = "
                                        + reason
                        );


                        restoreSearchButton();
                    }


                    /*
                     * BLE 연결 또는 Write 자체가 실패한 경우.
                     *
                     * 상태는 여전히 읽을 수 없으므로
                     * State read unsupported 상태를 유지한다.
                     */
                    @Override
                    public void onCommandFailed(
                            BleDeviceItem device,
                            String reason) {


                        scheduleManualPowerControlRestore(
                                device
                        );


                        AppLog.d(
                                "Manual power command failed"
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
         * Controller가 busy 상태가 된 뒤
         * Search / Stop 버튼 상태를 다시 계산한다.
         */
        updateScanButton();
    }


    /*
     * READ 미지원 장치의 수동 전원 명령이 끝난 뒤
     * 짧은 재입력 방지 시간을 거쳐
     * 주황색 수동 제어 버튼을 다시 사용할 수 있게 한다.
     *
     * 명령이 진행되는 동안과 이 대기 시간 동안에는
     * status가 Checking...이므로 Adapter에서 버튼이
     * 회색 + 비활성 상태로 유지된다.
     */
    private void scheduleManualPowerControlRestore(
            BleDeviceItem device) {


        handler.postDelayed(
                () -> {

                    /*
                     * 그 사이 새 Search가 시작되어
                     * 이전 장치가 목록에서 사라졌다면 아무것도 하지 않는다.
                     */
                    if (!deviceList.contains(
                            device
                    )) {

                        return;
                    }


                    /*
                     * 다른 처리에서 상태가 이미 바뀌었다면
                     * 오래된 지연 작업으로 덮어쓰지 않는다.
                     */
                    if (!"Checking...".equals(
                            device.getStatus()
                    )) {

                        return;
                    }


                    device.setStatus(
                            "State read unsupported"
                    );


                    updateDeviceRow(
                            device
                    );


                    AppLog.d(
                            "Manual power control re-enabled"
                                    + " / device = "
                                    + device.getName()
                    );
                },
                MANUAL_POWER_REENABLE_DELAY_MS
        );
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
            refreshIdentifyButtonFor(device);
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
     * 현재 LightLink의 BLE 검색/연결에 필요한 권한 가운데 미허용 항목만 반환한다.
     *
     * Android 12 이상 : BLUETOOTH_SCAN, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION
     * Android 8~11   : ACCESS_FINE_LOCATION (구형 Bluetooth 권한은 Manifest에 선언)
     *
     * Android 12 이상의 '대략적인 위치(COARSE)'는 현재 FINE 요구를 대신하지 못한다.
     * 이 메서드는 자체 권한 사전 검사이며, 실제 startScan()의 성공까지 보장하지 않는다.
     * neverForLocation으로 정책을 바꾸려면 Manifest와 실제 Base Station 검색을
     * 별도로 검증해야 한다. 단순히 검사 권한만 낮추지 않는다.
     */
    private List<String> getMissingPermissions() {
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
    }

    /*
     * 해당 권한을 이전에 요청한 적이 있는지 앱 로컬 기록을 조회한다.
     * OS가 현재 권한을 허용했는지는 이 값으로 판단하지 않는다.
     */
    private boolean wasPermissionRequested(String permission) {
        return getPreferences(MODE_PRIVATE).getBoolean(
                PERMISSION_PREF_PREFIX + permission, false
        );
    }

    /*
     * 아직 부여되지 않은 런타임 권한만 Android 시스템 다이얼로그로 요청한다.
     *
     * Android 12 이상에서 FINE 요청에는 COARSE를 함께 포함해야 한다.
     * 권한 요청 직전 요청 이력을 저장하여 반복 거부 후 설정 안내에 사용한다.
     */
    private void requestMissingPermissions(List<String> missing) {
        List<String> toRequest = new ArrayList<>(missing);

        // Android 12+: 정확한 위치를 요청한다면 대략적 위치도 함께 요청해야 한다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && toRequest.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            toRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        android.content.SharedPreferences.Editor editor =
                getPreferences(MODE_PRIVATE).edit();
        for (String permission : missing) {
            editor.putBoolean(PERMISSION_PREF_PREFIX + permission, true);
        }
        editor.apply();
        permissionLauncher.launch(toRequest.toArray(new String[0]));
    }

    /*
     * 시스템 권한 팝업 대신 앱 설정 안내가 필요한지 판단한다.
     *
     * 과거 요청 이력이 있고 rationale을 표시할 수 없는 권한을 감지한다.
     * Android의 권한 응답/정책은 OS별로 차이가 있으므로 이는 보수적인 휴리스틱이며,
     * 반드시 시스템이 '영구 거부' 상태라는 증명은 아니다.
     */
    private boolean mustOpenSettingsForPermission(List<String> missing) {
        for (String permission : missing) {
            if (wasPermissionRequested(permission)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    this, permission)) {
                // 최초 요청 전에는 false가 나오므로 위에서 요청 이력도 확인한다.
                return true;
            }
        }
        return false;
    }

    /*
     * 권한 부족으로 검색을 시작하지 못했을 때 빈 목록 영역에 이유를 표시한다.
     * Search 버튼은 다시 눌러 권한 요청 또는 앱 설정 안내를 확인할 수 있도록 유지한다.
     */
    private void showPermissionRequiredMessage() {
        boolean settingsRequired = mustOpenSettingsForPermission(
                getMissingPermissions()
        );
        textEmpty.setVisibility(View.VISIBLE);
        textEmpty.setText(settingsRequired
                ? "권한이 제한되어 있습니다. 검색 버튼을 눌러 앱 설정을 여세요."
                : "블루투스 또는 정확한 위치 권한이 필요합니다. 검색 버튼을 눌러 다시 요청하세요.");
        updateScanButton();
    }

    /*
     * 권한을 앱 설정 화면에서 직접 변경해야 하는 경우 보여 주는 안내창.
     *
     * Android 12 이상에서 대략적인 위치(COARSE)만 승인한 경우에는
     * 정확한 위치(FINE)가 필요한 이유와 설정 경로를 구체적으로 안내한다.
     * 나머지 권한 부족은 공통 문구를 사용한다.
     *
     * UI는 showStyledActionDialog()를 통해 수동 전원 제어 팝업과 동일한
     * 배경/버튼 drawable을 사용한다. 시스템 설정의 '권한' 항목 Ripple 요청은
     * 기존 openAppSettingsWithHighlight()에서 별도로 처리한다.
     */
    private void showAppPermissionSettingsDialog() {
        boolean approximateOnly =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                        && ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED;

        String title;
        String message;

        if (approximateOnly) {
            title = "정확한 위치 권한 필요";
            message = "현재 대략적인 위치만 허용되어 있습니다.\n\n"
                    + "LightLink에서 베이스스테이션을 검색하려면 "
                    + "정확한 위치 권한이 필요합니다.\n\n"
                    + "앱 정보 → 권한 → 위치에서 '정확한 위치 사용'을 활성화해 주세요.";
        } else {
            title = "권한 설정 필요";
            message = "LightLink에서 베이스스테이션을 검색하고 "
                    + "제어하려면 필요한 권한을 허용해야 합니다.\n\n"
                    + "앱 정보 → 권한 메뉴에서 필요한 권한을 허용해 주세요.";
        }

        showStyledActionDialog(
                title,
                message,
                "설정 열기",
                this::openAppSettingsWithHighlight
        );
    }

    /*
     * Android '앱 정보' 화면으로 이동하면서 가능한 경우 '권한' 항목을 강조한다.
     *
     * SETTINGS_FRAGMENT_ARG_KEY/SETTINGS_SHOW_FRAGMENT_ARGS는 Android Settings의
     * 비공개 Intent 인자다. 일부 AOSP/One UI 구현에서는 항목으로 스크롤하거나
     * Ripple 강조를 보여주지만, 제조사와 Android 버전에 따른 지원 여부는 알 수 없다.
     *
     * 1차: 앱 정보 화면 + 권한 항목 강조 힌트
     * 2차: 앱 정보 화면(추가 인자 없음)
     * 3차: 일반 Android 설정 화면
     *
     * 중요: startActivity() 성공은 Ripple이 표시됐다는 뜻이 아니다.
     * 시스템이 강조 인자를 무시하더라도 화면만 정상적으로 열리면 성공으로 처리한다.
     * 이 메서드로 '권한 → 위치 → 정확한 위치 사용'까지 자동 조작할 수는 없다.
     */
    private void openAppSettingsWithHighlight() {

        // 현재 LightLink 패키지의 앱 정보 화면으로 이동한다.
        Uri packageUri = Uri.parse("package:" + getPackageName());
        Intent highlightedIntent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                packageUri
        );

        // Settings 내부 구현에 '권한' 행을 강조해 달라는 힌트를 전달한다.
        Bundle highlightArgs = new Bundle();
        highlightArgs.putString(
                SETTINGS_FRAGMENT_ARG_KEY,
                SETTINGS_PERMISSION_ITEM_KEY
        );
        highlightedIntent.putExtra(
                SETTINGS_FRAGMENT_ARG_KEY,
                SETTINGS_PERMISSION_ITEM_KEY
        );
        highlightedIntent.putExtra(
                SETTINGS_SHOW_FRAGMENT_ARGS,
                highlightArgs
        );

        try {
            startActivity(highlightedIntent);
            return;
        } catch (RuntimeException e) {
            // 강조 Intent 자체를 열 수 없는 기기에서만 대체 경로로 이동한다.
            AppLog.d("Highlighted app settings unavailable", e);
        }

        try {
            // 추가 인자 없이 공식 앱 정보 화면을 다시 연다.
            startActivity(new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    packageUri
            ));
            return;
        } catch (RuntimeException e) {
            AppLog.d("App settings unavailable", e);
        }

        try {
            // 매우 예외적인 경우에는 일반 Android 설정 화면이라도 연다.
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (RuntimeException e) {
            AppLog.d("Android settings unavailable", e);
        }
    }

    /*
     * 시스템이 권한 재요청을 허용할 때 먼저 보여 주는 설명 창.
     *
     * 이 커스텀 창의 '권한 허용' 버튼을 눌러야 Android의 실제 시스템
     * 권한 요청 창(permissionLauncher)이 열린다. 권한을 직접 부여하지 않는다.
     * missing은 팝업을 연 시점의 미허용 권한 목록이다.
     */
    private void showPermissionExplanationDialog(List<String> missing) {
        showStyledActionDialog(
                "권한 허용 필요",
                "LightLink에서 베이스스테이션을 검색하고 "
                        + "제어하려면 블루투스 및 정확한 위치 권한이 필요합니다.\n\n"
                        + "권한을 허용해야 장치를 검색할 수 있습니다.",
                "권한 허용",
                () -> requestMissingPermissions(missing)
        );
    }

    /*
     * 권한 안내/권한 설정 이동/위치 서비스 활성화 안내에서 공통으로 사용하는
     * LightLink 스타일의 2버튼 다이얼로그.
     *
     * - res/layout/dialog_settings_action.xml에 제목, 본문, 취소/동작 버튼을 배치한다.
     * - res/drawable/dialog_manual_power_background를 팝업 배경으로 재사용한다.
     * - res/drawable/dialog_action_button_background를 두 버튼에 재사용한다.
     *   버튼의 눌림/Ripple 효과는 해당 drawable 구현에 의해 결정된다.
     * - Android 시스템 권한 요청창 또는 시스템 설정 화면에는 스타일을 적용하지 않는다.
     *
     * 버튼 동작:
     * - 취소: 팝업만 닫고 아무 작업도 하지 않는다.
     * - 동작: 팝업을 닫은 뒤 요청한 Runnable을 한 번 실행한다.
     *
     * 다른 장치와의 GATT 연결이나 BLE 검색 흐름은 변경하지 않는다.
     */
    private void showStyledActionDialog(
            String title,
            String message,
            String positiveLabel,
            Runnable positiveAction) {

        View dialogView = getLayoutInflater().inflate(
                R.layout.dialog_settings_action,
                null
        );

        TextView textTitle = dialogView.findViewById(R.id.textSettingsDialogTitle);
        TextView textMessage = dialogView.findViewById(R.id.textSettingsDialogMessage);
        TextView buttonCancel = dialogView.findViewById(R.id.buttonSettingsDialogCancel);
        TextView buttonAction = dialogView.findViewById(R.id.buttonSettingsDialogAction);

        textTitle.setText(title);
        textMessage.setText(message);
        buttonAction.setText(positiveLabel);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        buttonCancel.setOnClickListener(v -> dialog.dismiss());
        buttonAction.setOnClickListener(v -> {
            dialog.dismiss();
            positiveAction.run();
        });

        dialog.show();

        // 기본 AlertDialog 창 배경을 제거해야 XML에 정의한 둥근 모서리가 보인다.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent
            );
        }
    }

    /*
     * 기기 전체의 위치 서비스(상단 빠른 설정 '위치') ON/OFF 여부를 확인한다.
     * ACCESS_FINE_LOCATION 런타임 권한 허용 여부와는 독립적이다.
     *
     * Android 9(API 28) 이상: isLocationEnabled()로 전체 스위치를 조회한다.
     * Android 8(API 26~27)   : GPS/네트워크 위치 공급자의 활성 여부로 판단한다.
     * 서비스 조회에 실패하면 검색을 무조건 시작하기보다 OFF로 취급한다.
     *
     * 현재 앱은 neverForLocation을 선언하지 않았으므로 OS 버전과 무관하게
     * BLE 광고 검색 시작 전에 위치 권한 + 기기 위치 서비스를 확인한다.
     */
    private boolean areLocationServicesEnabled() {
        LocationManager locationManager =
                (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled();
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /*
     * 위치 서비스가 꺼져 있을 때 검색 화면에 표시할 비차단 안내.
     *
     * 목록이 이미 있는 경우에는 장치 상태/전원 버튼 영역을 갑자기 덮지 않는다.
     * 사용자는 Search 버튼을 다시 누르면 별도의 설정 안내창을 볼 수 있다.
     */
    private void showLocationServicesDisabledMessage() {
        if (deviceList.isEmpty()) {
            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText(
                    "휴대전화 위치 기능이 꺼져 있습니다. 검색 버튼을 눌러 설정을 여세요."
            );
        }
        updateScanButton();
    }

    /*
     * 스마트폰 전체 위치 서비스가 OFF일 때 표시하는 LightLink 스타일 안내창.
     *
     * 앱의 위치 접근 권한(정확한 위치)과는 다른 설정이므로,
     * '설정 열기'는 앱 정보 화면이 아닌 시스템의 전체 위치 설정을 연다.
     * 이번 UI 변경에서는 공식 Intent만 사용하며, Fold2에서 효과가 없었던
     * 위치 스위치용 실험 Ripple 인자는 전달하지 않는다.
     */
    private void showLocationServicesDisabledDialog() {
        showStyledActionDialog(
                "위치 서비스 비활성화",
                "현재 스마트폰의 위치 기능이 꺼져 있습니다.\n\n"
                        + "LightLink에서 베이스스테이션을 검색하려면 "
                        + "위치 기능을 활성화해 주세요.",
                "설정 열기",
                this::openDeviceLocationSettings
        );
    }

    /*
     * Android의 전체 '위치' 설정 화면을 연다.
     *
     * LightLink가 Search 후 이 화면을 열었다는 사실을 저장하여
     * 설정에서 돌아왔을 때 onResume()이 위치 상태를 재검사하고
     * 사용자가 켜 두었다면 요청했던 BLE 검색을 한 번 재개하도록 한다.
     *
     * 위치 설정 화면을 열 수 없으면 일반 Android 설정 화면을 연다.
     * 두 화면 모두 열리지 않으면 검색 재개 대기 상태를 취소한다.
     * 앱 정보 '권한' 화면의 Ripple 강조 로직과는 독립적이다.
     */
    private void openDeviceLocationSettings() {
        waitingForLocationSettings = true;

        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        } catch (RuntimeException e) {
            AppLog.d("Location settings unavailable", e);
        }

        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (RuntimeException e) {
            waitingForLocationSettings = false;
            AppLog.d("Android settings unavailable", e);
        }
    }

    /*
     * 위치 서비스 스위치 변경에 대응한다.
     *
     * OFF: 실행 중인 BLE 광고 스캔만 종료한다. 이미 발견한 Base Station의
     *      초기 상태 조회/GATT 전원 제어까지 취소하면 원치 않는 동작이 생기므로 보존한다.
     * ON : 앱이 자동으로 새 Scan을 시작하지 않는다. 사용자의 Search 입력을 기다린다.
     *      단, LightLink가 연 위치 설정 화면에서 복귀한 경우는 onResume()에서 따로 처리한다.
     */
    private void handleLocationServiceStateChanged() {
        if (!areLocationServicesEnabled()) {
            if (scanning) {
                AppLog.d("Location services disabled: stopping BLE advertisement scan");
                stopBleScan();
            }
            showLocationServicesDisabledMessage();
        } else if (deviceList.isEmpty() && !scanning
                && getMissingPermissions().isEmpty()
                && bluetoothAdapter != null && canCheckBluetoothState()) {
            // Bluetooth가 켜진 상황에서만 위치 ON 안내로 갱신한다.
            // BT OFF 상황에서 "Bluetooth OFF" 안내를 덮어쓰지 않도록 확인한다.
            try {
                if (bluetoothAdapter.isEnabled()) {
                    textEmpty.setVisibility(View.VISIBLE);
                    textEmpty.setText("위치 기능이 켜졌습니다. 검색 버튼을 눌러 장치를 검색하세요.");
                }
            } catch (SecurityException e) {
                AppLog.d("Unable to check Bluetooth after location change", e);
            }
        }
    }

    /*
     * Bluetooth가 OFF로 바뀐 사이 정확한 위치 권한까지 바뀌었더라도
     * Android 12+ CONNECT 권한만 있으면 Bluetooth 전원 상태를 확인할 수 있다.
     * Android 8~11의 일반 Bluetooth 권한은 Manifest에서 부여된다.
     */
    private boolean canCheckBluetoothState() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * Search 버튼/권한 요청 결과/Bluetooth 활성화 결과/위치 설정 복귀에서
     * 공통으로 호출하는 검색 시작 진입점.
     *
     * 검사 순서:
     * 1) 진행 중인 장치 Wake/Sleep 작업 여부
     * 2) Android 버전에 맞는 런타임 권한 (현재 정책은 항상 정확한 위치 필요)
     * 3) Android 8~최신 버전 모두 기기 위치 서비스 ON/OFF 여부
     * 4) BLE 스캐너 준비 및 Bluetooth 활성화 여부 (startBleScan 내부)
     * 5) 실제 BLE 광고 검색
     *
     * 중요: 위치 권한(FINE 허용)과 위치 서비스(전체 스위치 ON)는 별개다.
     *      Android 12 이상도 현재 Manifest 설정에서는 서비스 OFF를 건너뛰지 않는다.
     *      앱은 위치 서비스를 강제로 켜지 않고 시스템 설정 화면으로 안내한다.
     */
    private void checkPermissionAndScan() {
        if (isAnyPowerControllerBusy() || isAnyIdentifyBusy()) {
            AppLog.d("Scan ignored: power command in progress");
            return;
        }

        List<String> missing = getMissingPermissions();
        if (!missing.isEmpty()) {
            if (mustOpenSettingsForPermission(missing)) {
                showAppPermissionSettingsDialog();
                return;
            }

            boolean rationaleNeeded = false;
            for (String permission : missing) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this, permission)) {
                    rationaleNeeded = true;
                    break;
                }
            }
            if (rationaleNeeded) {
                showPermissionExplanationDialog(missing);
            } else {
                requestMissingPermissions(missing);
            }
            return;
        }

        /*
         * Android 12 이상을 포함해 기기 위치 서비스도 항상 확인한다.
         * 위치 권한이 허용됐더라도 서비스 스위치가 OFF일 수 있다.
         * 위치 서비스가 OFF라면 검색을 시작하지 않고 위치 설정을 안내한다.
         */
        if (!areLocationServicesEnabled()) {
            showLocationServicesDisabledDialog();
            return;
        }

        startBleScan();
    }

    /*
     * 실제 BLE 스캐너를 구성하고 10초간 주변 광고 패킷 수신을 시작한다.
     * 새 검색을 시작할 때 이전 결과/상태 조회/전원 Controller를 초기화한다.
     *
     * 권한을 사전 검사하더라도 시스템 설정이 도중에 바뀔 수 있으므로
     * BluetoothLeScanner 접근과 startScan()은 예외 처리를 유지한다.
     */
    @SuppressLint("MissingPermission")
    private void startBleScan() {

        /*
         * 권한 요청 화면을 거친 사이 전원 작업이 시작됐을 수도 있으므로
         * 실제 Scan 직전에도 한 번 더 확인한다.
         */
        if (isAnyPowerControllerBusy() || isAnyIdentifyBusy()) {

            AppLog.d(
                    "Scan ignored: power command in progress"
            );

            restoreSearchButton();
            return;
        }


        // 권한 요청 도중 사용자가 Android 설정에서 권한을 변경했을 수 있다.
        if (!getMissingPermissions().isEmpty()) {
            showPermissionRequiredMessage();
            return;
        }

        /*
         * 1차 검사(checkPermissionAndScan) 직후 위치 서비스를 끈 경우의 마지막 방어선.
         * 이 함수에서는 설정 다이얼로그를 연속으로 띄우지 않고 화면 안내만 갱신한다.
         */
        if (!areLocationServicesEnabled()) {
            showLocationServicesDisabledMessage();
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
            textEmpty.setText("Bluetooth is OFF. Turn it on to scan.");
            bluetoothEnableLauncher.launch(
                    new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            );
            return;
        }


        /*
         * BLE Scanner 가져오기
         */
        try {
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        } catch (SecurityException | IllegalStateException e) {
            AppLog.d("BLE scanner unavailable", e);
            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Bluetooth scanner unavailable. Check permissions.");
            updateScanButton();
            return;
        }


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
        clearIdentifyControllers();


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
        try {
            bleScanner.startScan(scanCallback);
        } catch (SecurityException | IllegalStateException e) {
            AppLog.d("BLE scan start failed", e);
            scanning = false;
            bleScanner = null;
            textEmpty.setVisibility(View.VISIBLE);
            textEmpty.setText("Unable to start BLE scan. Check Bluetooth permissions.");
            updateScanButton();
            return;
        }


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
     * BLE 스캔 콜백: 광고 이름을 얻어 LHB- 장치만 목록에 추가한다.
     * 발견한 장치는 MAC 주소로 중복을 제거하고 초기 상태 조회 큐에 넣는다.
     * Debug 빌드의 가상 장치와 달리 이 콜백은 실제 주변 BLE 광고를 처리한다.
     */
    private final ScanCallback scanCallback =
            new ScanCallback() {

                @SuppressLint("MissingPermission")
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {

                    // Bluetooth가 꺼진 직후 늦게 도착한 검색 결과를 무시한다.
                    if (!scanning || !getMissingPermissions().isEmpty()) {
                        return;
                    }

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

                    // OFF 또는 수동 종료 이후 도착한 오래된 콜백은 무시한다.
                    if (!scanning) {
                        return;
                    }

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
     * BLE 광고 검색 종료(수동 Stop 또는 자동 타임아웃).
     *
     * Scanner와 타임아웃만 정리하고 이미 발견한 Base Station 목록과
     * 시작된 상태 조회 과정은 유지한다. Bluetooth 전원을 끈 경우에는
     * handleBluetoothTurnedOff()에서 나머지 작업을 별도로 정리한다.
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
     * 검색 도중 시스템 Bluetooth를 끄면 대기열/GATT와 검색 결과를 비운다.
     * 과거 Awake/Sleep 결과로 버튼을 누르게 하지 않기 위한 처리다.
     */
    private void handleBluetoothTurnedOff() {
        if (scanning) {
            stopBleScan();
        }
        handler.removeCallbacks(scanTimeoutRunnable);
        resetStatusReads();
        clearPowerControllers();
        clearIdentifyControllers();
        bleScanner = null;
        seenAddresses.clear();
        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        textEmpty.setVisibility(View.VISIBLE);
        textEmpty.setText("Bluetooth is OFF. Tap Search to enable it.");
        updateScanButton();
    }

    /*
     * 초기 상태 확인 큐에서 Base Station을 하나씩 꺼내 GATT 연결을 시작한다.
     * 여러 장치가 발견되어도 최초 상태 조회는 동시 연결하지 않고 순차 실행한다.
     * 이 흐름은 Wake/Sleep 전용 BaseStationGattController와 별도다.
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
                     * Base Station Mode / RF Channel Characteristic 찾기.
                     *
                     * Power State와 달리 채널은 부가 정보이므로
                     * Characteristic이 없더라도 전체 상태 조회를
                     * 실패로 처리하지 않는다.
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
                                "Mode properties"
                                        + " / device = "
                                        + (
                                        currentStatusDevice != null
                                                ? currentStatusDevice.getName()
                                                : "(unknown)"
                                )
                                        + " / properties = 0x"
                                        + Integer.toHexString(modeProperties).toUpperCase()
                                        + " / READ = "
                                        + modeReadable
                                        + " / WRITE = "
                                        + modeWritable
                                        + " / NOTIFY = "
                                        + modeNotifiable
                        );

                    } else {

                        AppLog.d(
                                "Mode characteristic not found"
                                        + " / device = "
                                        + (
                                        currentStatusDevice != null
                                                ? currentStatusDevice.getName()
                                                : "(unknown)"
                                )
                        );
                    }


                    /*
                     * 현재 Base Station의 Power State Characteristic이
                     * 어떤 기능을 지원하는지 확인한다.
                     *
                     * READ:
                     * 현재 Power State 확인 가능.
                     *
                     * WRITE:
                     * 응답이 있는 일반 Write 가능.
                     * 현재 LightLink의 Wake / Sleep 제어는
                     * 이 방식을 기준으로 사용한다.
                     *
                     * WRITE_NO_RESPONSE:
                     * 응답 없는 Write 지원 여부.
                     * 진단용으로만 기록하고,
                     * 현재 수동 제어 가능 판정에는 사용하지 않는다.
                     */
                    int properties =
                            powerState.getProperties();


                    boolean readable =
                            (
                                    properties
                                            & BluetoothGattCharacteristic.PROPERTY_READ
                            ) != 0;


                    boolean writable =
                            (
                                    properties
                                            & BluetoothGattCharacteristic.PROPERTY_WRITE
                            ) != 0;


                    boolean writableWithoutResponse =
                            (
                                    properties
                                            & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            ) != 0;


                    /*
                     * 진단용 로그
                     *
                     * 실제 장치에서 READ / WRITE 지원 형태를
                     * 나중에 확인할 수 있도록 남긴다.
                     */
                    AppLog.d(
                            "Power State properties"
                                    + " / device = "
                                    + (
                                    currentStatusDevice != null
                                            ? currentStatusDevice.getName()
                                            : "(unknown)"
                            )
                                    + " / properties = 0x"
                                    + Integer.toHexString(properties).toUpperCase()
                                    + " / READ = "
                                    + readable
                                    + " / WRITE = "
                                    + writable
                                    + " / WRITE_NO_RESPONSE = "
                                    + writableWithoutResponse
                    );


                    /*
                     * 장치별 Capability를 BleDeviceItem에 저장한다.
                     *
                     * READ가 없다는 사실과,
                     * READ는 없지만 일반 WRITE가 가능해서
                     * 수동 Wake / Sleep을 사용할 수 있다는 사실을
                     * 서로 따로 보관한다.
                     */
                    if (currentStatusDevice != null) {

                        currentStatusDevice.setStateReadUnsupported(
                                !readable
                        );


                        currentStatusDevice.setManualPowerControlAvailable(
                                !readable && writable
                        );
                    }


                    /*
                     * Power State Characteristic은 존재하지만
                     * READ 기능 자체가 없는 경우.
                     *
                     * 단순한 연결 실패나 Read 실패와 구분하며,
                     * 이 경우에는 상태 조회를 재시도하지 않는다.
                     *
                     * WRITE를 지원한다면 Adapter에서
                     * 주황색 수동 전원 버튼을 사용할 수 있다.
                     */
                    if (!readable) {

                        /*
                         * Power State READ는 지원하지 않더라도
                         * Mode / RF Channel READ는 별도 Capability이므로
                         * 채널 확인을 먼저 시도한 뒤 RSSI를 확인한다.
                         */
                        readChannelThenRssiAndFinish(
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

                /*
                 * 현재 GATT 연결의 RSSI 확인 완료
                 *
                 * readRemoteRssi()의 결과가 이 Callback으로 들어온다.
                 */
                @Override
                public void onReadRemoteRssi(
                        BluetoothGatt gatt,
                        int rssi,
                        int status) {


                    /*
                     * 이미 종료된 이전 GATT 연결의 Callback이면 무시한다.
                     */
                    if (gatt != currentGatt) {

                        return;
                    }


                    /*
                     * 현재 RSSI 결과를 기다리고 있지 않다면
                     * 예상하지 않은 Callback이므로 무시한다.
                     */
                    if (pendingStatusAfterRssiRead == null) {

                        return;
                    }


                    /*
                     * RSSI 확인 전에 이미 알아낸
                     * Power State 결과를 임시 변수에 보관한다.
                     */
                    String statusText =
                            pendingStatusAfterRssiRead;


                    /*
                     * 이제 RSSI Callback을 받았으므로
                     * 대기 상태를 먼저 해제한다.
                     */
                    pendingStatusAfterRssiRead =
                            null;


                    /*
                     * RSSI 확인 성공
                     */
                    if (status == BluetoothGatt.GATT_SUCCESS
                            && currentStatusDevice != null) {


                        /*
                         * 검색 당시 RSSI를
                         * 현재 GATT 연결에서 측정한 최신 RSSI로 교체한다.
                         */
                        currentStatusDevice.setRssi(
                                rssi
                        );


                        AppLog.d(
                                "RSSI updated"
                                        + " / device = "
                                        + currentStatusDevice.getName()
                                        + " / RSSI = "
                                        + rssi
                                        + " dBm"
                        );

                    } else {

                        /*
                         * RSSI는 부가 정보이므로
                         * 실패했다고 Power State까지 실패로 만들지 않는다.
                         */
                        AppLog.d(
                                "RSSI read failed"
                                        + " / status = "
                                        + status
                        );
                    }


                    /*
                     * RSSI 성공 여부와 관계없이
                     * 앞에서 이미 확인한 Power State 결과로
                     * 이번 상태 조회를 정상 종료한다.
                     */
                    finishStatusRead(
                            gatt,
                            statusText
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


        if (gatt != currentGatt
                || characteristic == null) {

            return;
        }


        UUID characteristicUuid =
                characteristic.getUuid();


        /*
         * Power State READ 결과
         */
        if (POWER_STATE_UUID.equals(
                characteristicUuid)) {


            handlePowerStateCharacteristicRead(
                    gatt,
                    value,
                    status
            );


            return;
        }


        /*
         * Mode / RF Channel READ 결과
         */
        if (MODE_UUID.equals(
                characteristicUuid)) {


            handleChannelCharacteristicRead(
                    gatt,
                    value,
                    status
            );
        }
    }


    /*
     * Power State Characteristic READ 결과 처리
     */
    private void handlePowerStateCharacteristicRead(
            BluetoothGatt gatt,
            byte[] value,
            int status) {


        if (gatt != currentGatt
                || !readingStatus) {

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
         * Processing (0x01)은 최종 전원 상태를 확정하지는 못했지만,
         * READ 자체는 성공했으므로 마지막 상태 확인 시간을 갱신한다.
         */
        if (currentStatusDevice != null
                && ("Awake".equals(powerState)
                || "Sleep".equals(powerState)
                || "Standby".equals(powerState)
                || STATUS_PROCESSING.equals(powerState))) {

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


        /*
         * Power State 확인이 끝났으므로
         * 같은 GATT 연결에서 채널을 확인한 뒤
         * 마지막으로 RSSI를 확인한다.
         */
        readChannelThenRssiAndFinish(
                gatt,
                powerState
        );
    }


    /*
     * Mode / RF Channel Characteristic READ 결과 처리
     *
     * 채널 정보는 부가 정보이므로
     * 실패하더라도 Power State 결과는 그대로 유지한다.
     */
    private void handleChannelCharacteristicRead(
            BluetoothGatt gatt,
            byte[] value,
            int status) {


        if (gatt != currentGatt
                || !readingStatus) {

            return;
        }


        /*
         * 현재 Channel READ 결과를 기다리던 상태가 아니라면
         * 늦게 도착한 Callback 등으로 보고 무시한다.
         */
        if (pendingStatusAfterChannelRead == null) {

            return;
        }


        String statusText =
                pendingStatusAfterChannelRead;


        pendingStatusAfterChannelRead =
                null;


        if (status == BluetoothGatt.GATT_SUCCESS
                && value != null
                && value.length > 0) {


            /*
             * Mode Characteristic의 첫 byte를
             * 현재 RF Channel 번호로 해석한다.
             */
            int channel =
                    value[0] & 0xFF;


            String deviceName =
                    currentStatusDevice != null
                            ? currentStatusDevice.getName()
                            : "(unknown)";


            AppLog.d(
                    "Channel read"
                            + " / device = "
                            + deviceName
                            + " / raw = "
                            + channel
            );


            /*
             * Base Station 2.0 RF Channel의 정상 범위만 저장한다.
             *
             * 범위를 벗어난 값은 추측해서 보정하지 않고
             * 로그만 남기며 기존 채널 값을 유지한다.
             */
            if (channel >= 1
                    && channel <= 16
                    && currentStatusDevice != null) {


                currentStatusDevice.setChannel(
                        channel
                );


                AppLog.d(
                        "Channel updated"
                                + " / device = "
                                + currentStatusDevice.getName()
                                + " / channel = "
                                + channel
                );

            } else {

                AppLog.d(
                        "Channel value ignored"
                                + " / raw = "
                                + channel
                );
            }

        } else {

            AppLog.d(
                    "Channel read failed"
                            + " / status = "
                            + status
            );
        }


        /*
         * Channel READ 성공 여부와 관계없이
         * 마지막 부가 정보인 RSSI 확인으로 이어간다.
         */
        readRssiThenFinishStatus(
                gatt,
                statusText
        );
    }


    /*
     * Power State 확인이 끝난 뒤
     * Mode / RF Channel을 한 번 읽고,
     * 그 다음 RSSI 확인으로 이어간다.
     *
     * Mode Characteristic이 없거나 READ를 지원하지 않는 경우,
     * 또는 READ 요청 시작에 실패한 경우에는
     * 채널 때문에 전체 상태 조회를 실패시키지 않고
     * 곧바로 RSSI 확인으로 넘어간다.
     */
    @SuppressLint("MissingPermission")
    private void readChannelThenRssiAndFinish(
            BluetoothGatt gatt,
            String statusText) {


        if (!readingStatus
                || gatt != currentGatt) {

            return;
        }


        BluetoothGattCharacteristic modeCharacteristic =
                currentModeCharacteristic;


        /*
         * Mode Characteristic 자체가 없는 장치.
         */
        if (modeCharacteristic == null) {

            AppLog.d(
                    "Channel read skipped: characteristic unavailable"
            );


            readRssiThenFinishStatus(
                    gatt,
                    statusText
            );


            return;
        }


        int properties =
                modeCharacteristic.getProperties();


        boolean readable =
                (
                        properties
                                & BluetoothGattCharacteristic.PROPERTY_READ
                ) != 0;


        /*
         * Mode Characteristic은 존재하지만
         * READ를 지원하지 않는 장치.
         */
        if (!readable) {

            AppLog.d(
                    "Channel read skipped: READ unsupported"
            );


            readRssiThenFinishStatus(
                    gatt,
                    statusText
            );


            return;
        }


        /*
         * Channel READ 결과가 돌아올 때까지
         * 이미 확인한 Power State 결과를 임시 보관한다.
         */
        pendingStatusAfterChannelRead =
                statusText;


        boolean started;


        try {

            started =
                    gatt.readCharacteristic(
                            modeCharacteristic
                    );

        } catch (Exception e) {

            AppLog.d(
                    "Channel read start error",
                    e
            );


            started =
                    false;
        }


        /*
         * Channel READ 요청 자체를 시작하지 못했다면
         * Power State 결과는 유지하고 RSSI 단계로 넘어간다.
         */
        if (!started) {

            pendingStatusAfterChannelRead =
                    null;


            readRssiThenFinishStatus(
                    gatt,
                    statusText
            );
        }
    }



    /*
     * Power State 확인이 끝난 뒤
     * 현재 GATT 연결의 RSSI를 한 번 더 확인한다.
     *
     * RSSI는 부가 정보이므로,
     * RSSI 읽기를 시작하지 못하더라도
     * 이미 확인한 Power State 결과까지 실패로 처리하지 않는다.
     */
    @SuppressLint("MissingPermission")
    private void readRssiThenFinishStatus(
            BluetoothGatt gatt,
            String statusText) {


        /*
         * 이미 끝난 연결의 늦은 Callback 등은 무시한다.
         */
        if (!readingStatus
                || gatt != currentGatt) {

            return;
        }


        /*
         * RSSI 결과가 돌아올 때까지
         * 이미 확인한 Power State 결과를 임시 보관한다.
         */
        pendingStatusAfterRssiRead =
                statusText;


        boolean started;


        try {

            /*
             * 현재 연결된 Base Station의
             * 최신 RSSI 확인 요청.
             */
            started =
                    gatt.readRemoteRssi();

        } catch (Exception e) {

            AppLog.d(
                    "RSSI read start error",
                    e
            );


            started =
                    false;
        }


        /*
         * RSSI 요청 자체를 시작하지 못했다면
         * 기존 Power State 결과를 그대로 사용해 종료한다.
         */
        if (!started) {

            pendingStatusAfterRssiRead =
                    null;


            finishStatusRead(
                    gatt,
                    statusText
            );
        }
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
             * 0x01의 정확한 펌웨어 의미는 아직 확정되지 않았다.
             * 최초 READ 성공 시 Processing으로 처리하고,
             * 반복 판정은 finishStatusRead()에서 수행한다.
             */
            case 0x01:

                return STATUS_PROCESSING;


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
                || STATUS_PROCESSING.equals(statusText)
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

        /*
         * 이번 상태 조회가 끝나므로
         * Channel / RSSI 결과를 기다릴 때 사용한
         * 임시 상태도 함께 정리한다.
         */
        pendingStatusAfterChannelRead =
                null;

        pendingStatusAfterRssiRead =
                null;


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

        currentModeCharacteristic =
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


        /*
         * 0x01을 실제로 연속 수신한 횟수만 별도로 추적한다.
         * GATT 연결 실패, Timeout 등은 0x01을 읽은 것이 아니므로
         * Stuck 판정에 사용하지 않는다.
         */
        int consecutiveProcessingReads = 0;

        if (finishedDevice != null) {

            String address = finishedDevice.getAddress();

            if (STATUS_PROCESSING.equals(statusText)) {

                consecutiveProcessingReads =
                        consecutiveProcessingReadCounts.getOrDefault(address, 0) + 1;

                consecutiveProcessingReadCounts.put(
                        address,
                        consecutiveProcessingReads
                );

                AppLog.d(
                        "Power state 0x01 observed"
                                + " / device = "
                                + finishedDevice.getName()
                                + " / consecutive reads = "
                                + consecutiveProcessingReads
                );

            } else {

                consecutiveProcessingReadCounts.remove(address);
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
             * 0x01을 읽었다면 첫 번째 성공 응답부터 Processing으로 보여준다.
             * 최종 재시도가 끝날 때까지 전원 명령 없이 READ만 반복한다.
             * 연결 및 READ 실패로 재시도할 때는 기존 Checking...을 사용한다.
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
                        STATUS_PROCESSING.equals(statusText)
                                ? STATUS_PROCESSING
                                : "Checking..."
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


            String address = finishedDevice.getAddress();

            statusRetryCounts.remove(address);
            consecutiveProcessingReadCounts.remove(address);


            /*
             * 최초 1회 + 추가 재시도 3회, 총 4회 모두 연속 0x01일 때만
             * Stuck (0x01)으로 표시한다. 이는 '연속 관찰된 상태'이지
             * 펌웨어 오류 코드나 물리적인 정지를 확정하는 것은 아니다.
             *
             * 중간에 GATT 오류가 있었다면 연속 0x01 횟수를 초기화하므로
             * 마지막에 0x01을 읽어도 Processing (0x01)을 유지한다.
             */
            String finalStatus = statusText;

            if (STATUS_PROCESSING.equals(statusText)
                    && consecutiveProcessingReads >= STATUS_MAX_RETRIES + 1) {

                finalStatus = STATUS_STUCK;
            }


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
        consecutiveProcessingReadCounts.clear();


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

        currentModeCharacteristic =
                null;

        pendingStatusAfterChannelRead =
                null;

        pendingStatusAfterRssiRead =
                null;

        readingStatus =
                false;
    }


    /*
     * Activity가 화면에 나타나면 Bluetooth 및 기기 위치 스위치 방송을 등록한다.
     * 설정 앱이 열려 있는 동안에는 방송을 받지 않을 수 있으므로 onResume에서도
     * 위치 서비스 상태를 재확인한다.
     *
     * Bluetooth가 설정 앱에서 꺼졌다면 지난 검색 결과/GATT를 정리한다.
     * 이때 FINE 위치 권한은 Bluetooth 전원 상태를 읽는 데 필요하지 않다.
     */
    @Override
    protected void onStart() {
        super.onStart();

        registerReceiver(
                bluetoothStateReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        );
        bluetoothReceiverRegistered = true;

        registerReceiver(
                locationStateReceiver,
                new IntentFilter(LocationManager.MODE_CHANGED_ACTION)
        );
        locationReceiverRegistered = true;

        // Bluetooth 연결 권한이 있으면 위치 권한 상태와 관계없이 전원을 확인한다.
        if (bluetoothAdapter != null && canCheckBluetoothState()) {
            try {
                if (!bluetoothAdapter.isEnabled()) {
                    handleBluetoothTurnedOff();
                }
            } catch (SecurityException e) {
                showPermissionRequiredMessage();
            }
        }
    }

    /*
     * 앱이 다시 전면으로 복귀할 때 위치 설정 변경 누락을 보완한다.
     *
     * - 검색 중에 위치 기능이 꺼졌다면 즉시 광고 검색을 중단한다.
     * - LightLink가 위치 설정을 열었던 경우, 위치 기능을 켠 뒤 돌아오면
     *   별도 Search 입력 없이 원래 사용자가 요청한 검색을 한 번만 재개한다.
     * - 사용자가 위치 기능을 켜지 않았다면 팝업을 반복해서 띄우지 않는다.
     */
    @Override
    protected void onResume() {
        super.onResume();

        if (!areLocationServicesEnabled()) {
            if (scanning) {
                stopBleScan();
            }
            showLocationServicesDisabledMessage();
        } else if (scanning && !getMissingPermissions().isEmpty()) {
            // 설정 화면에서 검색 권한이 회수된 경우 지난 스캔을 계속하지 않는다.
            stopBleScan();
            showPermissionRequiredMessage();
        }

        if (waitingForLocationSettings) {
            // 복귀 후에는 재진입을 막기 위해 대기 상태를 먼저 해제한다.
            waitingForLocationSettings = false;

            if (areLocationServicesEnabled()) {
                if (getMissingPermissions().isEmpty()) {
                    checkPermissionAndScan();
                } else {
                    showPermissionRequiredMessage();
                }
            }
            // 여전히 위치 OFF라면 위의 화면 안내만 유지하고 다시 요청하지 않는다.
        }
    }

    /*
     * 위치 설정 화면에서 Activity가 재생성되는 경우에도
     * '설정 후 검색 재개' 의도를 유지한다.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(
                STATE_WAITING_FOR_LOCATION_SETTINGS,
                waitingForLocationSettings
        );
        super.onSaveInstanceState(outState);
    }

    /*
     * Activity가 화면에서 사라지면 동적 시스템 방송 수신기를 해제한다.
     * 다시 화면으로 돌아오면 onStart()에서 재등록한다.
     * 검색/GATT 자체는 이 생명주기 콜백만으로 강제로 종료하지 않는다.
     */
    @Override
    protected void onStop() {
        if (bluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothStateReceiver);
            bluetoothReceiverRegistered = false;
        }
        if (locationReceiverRegistered) {
            unregisterReceiver(locationStateReceiver);
            locationReceiverRegistered = false;
        }
        super.onStop();
    }

    /*
     * Activity 완전 종료 시 BLE 스캔, 초기 상태 확인 GATT,
     * 장치별 전원 Controller 및 Handler 예약 작업을 정리한다.
     * 화면 이동(onStop)과 Activity 소멸(onDestroy)은 구분한다.
     * 위치/Bluetooth 변경 방송 수신기는 onStop()에서 별도 해제한다.
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
        clearIdentifyControllers();


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
                isAnyPowerControllerBusy() || isAnyIdentifyBusy();


        buttonScan.setEnabled(
                !powerBusy
        );
    }

    /*
     * 마지막 검색 시간을 화면에 표시한다.
     *
     * 날짜 및 시간 형식은 기존 형식을 유지한다.
     *
     * 사용자에게 표시하는 안내 문구는
     * 시스템 언어에 맞는 strings.xml 리소스를 사용한다.
     */
    private void updateLastScanTime() {

        String timeText =
                LocalDateTime.now().format(
                        LAST_SCAN_TIME_FORMAT
                );

        textLastScan.setText(
                getString(
                        R.string.last_scan_format,
                        timeText
                )
        );
    }
}
