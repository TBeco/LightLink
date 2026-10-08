package com.tbeco.lightlink;

import java.time.LocalDateTime;
import java.util.List;


/*
 * Debug 빌드에서만 사용하는
 * Base Station UI 테스트용 가상 장치 관리 클래스
 *
 * 실제 Base Station이 없어도
 * 주요 상태별 UI를 확인할 수 있도록
 * 가상의 Base Station을 생성한다.
 *
 * 현재 테스트하는 상태:
 *
 * Awake
 * Sleep
 * State read unsupported + WRITE 가능
 * Checking...
 * Unknown
 * State read unsupported + WRITE 불가
 */
public final class DebugMockDevices {


    /*
     * Mock 장치를 구분하기 위한 이름 Prefix.
     *
     * 나중에 Mock 버튼을 눌렀을 때
     * 실제 BLE 명령이 전송되지 않도록
     * 구분하는 데 사용할 수 있다.
     */
    private static final String MOCK_NAME_PREFIX =
            "LHB-MOCK-";


    /*
     * 객체를 직접 생성해서 사용할 클래스가 아니므로
     * 외부에서 생성하지 못하도록 막는다.
     */
    private DebugMockDevices() {
    }


    /*
     * UI 테스트용 가상 Base Station을
     * 실제 화면의 장치 목록에 추가한다.
     */
    public static void addMockDevices(
            List<BleDeviceItem> deviceList) {


        /*
         * Mock을 생성하는 순간의 시간을 저장한다.
         *
         * Awake / Sleep은 실제 Power State READ에
         * 성공한 상태를 재현하므로
         * 같은 마지막 확인 시간을 표시하도록 한다.
         */
        LocalDateTime mockCheckedAt =
                LocalDateTime.now();


        /*
         * -------------------------------------------------
         * 1. Awake 상태 Mock
         * -------------------------------------------------
         *
         * 실제 Power State READ에 성공하여
         * Base Station이 현재 작동 중인 상황.
         *
         * 예상 UI:
         *
         * Awake
         * 마지막 상태 확인 | 시간
         * 초록색 전원 버튼
         */
        BleDeviceItem awakeDevice =
                new BleDeviceItem(
                        "LHB-MOCK-AWAKE",
                        "00:00:00:00:00:01",
                        -48
                );


        awakeDevice.setStatus(
                "Awake"
        );


        awakeDevice.setLastStateCheckedAt(
                mockCheckedAt
        );


        deviceList.add(
                awakeDevice
        );


        /*
         * -------------------------------------------------
         * 2. Sleep 상태 Mock
         * -------------------------------------------------
         *
         * 실제 Power State READ에 성공하여
         * Base Station이 Sleep 상태인 상황.
         *
         * 예상 UI:
         *
         * Sleep
         * 마지막 상태 확인 | 시간
         * 파란색 전원 버튼
         */
        BleDeviceItem sleepDevice =
                new BleDeviceItem(
                        "LHB-MOCK-SLEEP",
                        "00:00:00:00:00:02",
                        -55
                );


        sleepDevice.setStatus(
                "Sleep"
        );


        sleepDevice.setLastStateCheckedAt(
                mockCheckedAt
        );


        deviceList.add(
                sleepDevice
        );


        /*
         * -------------------------------------------------
         * 3. READ 불가 + WRITE 가능 Mock
         * -------------------------------------------------
         *
         * Power State READ는 지원하지 않지만
         * WRITE는 가능한 상황.
         *
         * 실제 구형 Base Station에서 확인한
         * 수동 전원 제어 상태를 재현한다.
         *
         * 예상 UI:
         *
         * State read unsupported
         * 마지막 상태 확인 | 지원되지 않음
         * 주황색 전원 버튼
         *
         * 버튼을 누르면
         * 수동 Wake / Sleep 선택창이 표시된다.
         */
        BleDeviceItem readUnsupportedDevice =
                new BleDeviceItem(
                        "LHB-MOCK-READLESS",
                        "00:00:00:00:00:03",
                        -62
                );


        readUnsupportedDevice.setStatus(
                "State read unsupported"
        );


        readUnsupportedDevice.setStateReadUnsupported(
                true
        );


        readUnsupportedDevice.setManualPowerControlAvailable(
                true
        );


        deviceList.add(
                readUnsupportedDevice
        );


        /*
         * -------------------------------------------------
         * 4. Checking... 상태 Mock
         * -------------------------------------------------
         *
         * 현재 Power State를 확인하고 있거나
         * 전원 명령을 처리하고 있는 중인 상황.
         *
         * 예상 UI:
         *
         * Checking...
         * 마지막 상태 확인 | 확인 중...
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem checkingDevice =
                new BleDeviceItem(
                        "LHB-MOCK-CHECKING",
                        "00:00:00:00:00:04",
                        -67
                );


        checkingDevice.setStatus(
                "Checking..."
        );


        deviceList.add(
                checkingDevice
        );


        /*
         * -------------------------------------------------
         * 5. Unknown 상태 Mock
         * -------------------------------------------------
         *
         * Power State 확인을 시도했지만
         * 실제 상태를 확정하지 못한 상황.
         *
         * 예상 UI:
         *
         * Unknown
         * 마지막 상태 확인 | 확인되지 않음
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem unknownDevice =
                new BleDeviceItem(
                        "LHB-MOCK-UNKNOWN",
                        "00:00:00:00:00:05",
                        -73
                );


        unknownDevice.setStatus(
                "Unknown"
        );


        deviceList.add(
                unknownDevice
        );


        /*
         * -------------------------------------------------
         * 6. READ 불가 + WRITE 불가 Mock
         * -------------------------------------------------
         *
         * Power State READ도 지원하지 않고
         * 현재 LightLink에서 사용할 수 있는
         * WRITE 방식도 확인되지 않은 상황.
         *
         * READ가 불가능하다는 점은
         * 주황색 Mock과 동일하지만,
         *
         * manualPowerControlAvailable = false
         *
         * 이므로 전원 버튼은 활성화되지 않는다.
         *
         * 예상 UI:
         *
         * State read unsupported
         * 마지막 상태 확인 | 지원되지 않음
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem readUnsupportedNoWriteDevice =
                new BleDeviceItem(
                        "LHB-MOCK-READLESS-NOWRITE",
                        "00:00:00:00:00:06",
                        -78
                );


        readUnsupportedNoWriteDevice.setStatus(
                "State read unsupported"
        );


        readUnsupportedNoWriteDevice.setStateReadUnsupported(
                true
        );


        /*
         * false가 기본값이므로
         * 반드시 설정할 필요는 없지만,
         * Mock의 의도를 명확하게 하기 위해
         * 명시적으로 false를 설정한다.
         */
        readUnsupportedNoWriteDevice
                .setManualPowerControlAvailable(
                        false
                );


        deviceList.add(
                readUnsupportedNoWriteDevice
        );
    }


    /*
     * 전달받은 장치가
     * Debug용 Mock Base Station인지 확인한다.
     *
     * 다음 단계에서 Mock 전원 버튼을 눌렀을 때
     * 실제 Bluetooth 연결을 시도하지 않도록
     * 구분하는 데 사용할 예정이다.
     */
    public static boolean isMockDevice(
            BleDeviceItem device) {


        if (device == null
                || device.getName() == null) {

            return false;
        }


        return device
                .getName()
                .startsWith(
                        MOCK_NAME_PREFIX
                );
    }
}