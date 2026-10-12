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
 * - Awake
 * - Sleep
 * - State read unsupported + WRITE 가능
 * - Checking...
 * - Unknown
 * - State read unsupported + WRITE 불가
 * - Processing (0x01)
 * - Stuck (0x01)
 *
 * 추가로 다음 UI도 함께 확인한다.
 * - RF Channel 표시
 * - Channel 미확인 표시
 * - Bluetooth 신호 강함 / 보통 / 약함 표시
 *
 * 주의:
 * 이 Mock은 지정한 상태를 화면에 표시하기 위한 것이다.
 * 실제 GATT READ나 재시도를 실행하거나
 * Processing에서 Stuck으로 자동 전환하지는 않는다.
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
         *
         * Processing / Stuck 역시 Power State READ에 성공해
         * 실제 0x01을 수신한 상황을 재현하므로
         * 마지막 상태 확인 시간을 표시한다.
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
         * Channel READ도 정상적으로 성공한 상태.
         *
         * RSSI:
         * -48 dBm → 강함
         *
         * 예상 UI:
         * LHB-MOCK-AWAKE
         * Awake
         * 00:00:00:00:00:01
         * 채널 1 | 신호 강함 · -48 dBm
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

        awakeDevice.setChannel(
                1
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
         * Channel READ도 정상적으로 성공한 상태.
         *
         * RSSI:
         * -55 dBm → 강함
         *
         * 예상 UI:
         * LHB-MOCK-SLEEP
         * Sleep
         * 00:00:00:00:00:02
         * 채널 2 | 신호 강함 · -55 dBm
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

        sleepDevice.setChannel(
                2
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
         * Power State READ는 불가능하지만
         * Mode / Channel READ는 가능할 수도 있는 경우를
         * 재현하기 위해 Channel 3을 설정한다.
         *
         * RSSI:
         * -62 dBm → 강함
         *
         * 예상 UI:
         * LHB-MOCK-READLESS
         * State read unsupported
         * 00:00:00:00:00:03
         * 채널 3 | 신호 강함 · -62 dBm
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

        readUnsupportedDevice.setChannel(
                3
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
         * Channel은 이전에 정상적으로 확인된 값이
         * 이미 존재하는 상황도 테스트한다.
         *
         * RSSI:
         * -67 dBm → 보통
         *
         * 예상 UI:
         * LHB-MOCK-CHECKING
         * Checking...
         * 00:00:00:00:00:04
         * 채널 4 | 신호 보통 · -67 dBm
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

        checkingDevice.setChannel(
                4
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
         * Channel은 별도로 확인된 상태를 재현한다.
         *
         * RSSI:
         * -73 dBm → 보통
         *
         * 예상 UI:
         * LHB-MOCK-UNKNOWN
         * Unknown
         * 00:00:00:00:00:05
         * 채널 8 | 신호 보통 · -73 dBm
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

        unknownDevice.setChannel(
                8
        );

        deviceList.add(
                unknownDevice
        );


        /*
         * -------------------------------------------------
         * 6. READ 불가 + WRITE 불가 + 약한 신호 Mock
         * -------------------------------------------------
         *
         * Power State READ도 지원하지 않고
         * 현재 LightLink에서 사용할 수 있는
         * WRITE 방식도 확인되지 않은 상황.
         *
         * Channel도 확인하지 못한 상태를 재현하기 위해
         * setChannel()을 호출하지 않는다.
         *
         * BleDeviceItem의 기본 Channel 값은 null이므로
         * Adapter에서는 다음과 같이 표시된다.
         *
         * 채널 -
         *
         * RSSI:
         * -88 dBm → 약함
         *
         * 따라서 이 Mock은 다음을 한 번에 확인한다.
         * - Power State READ 불가
         * - 수동 전원 제어 불가
         * - Channel 미확인
         * - Bluetooth 신호 약함
         *
         * 예상 UI:
         * LHB-MOCK-READLESS-NOWRITE
         * State read unsupported
         * 00:00:00:00:00:06
         * 채널 - | 신호 약함 · -88 dBm
         * 마지막 상태 확인 | 지원되지 않음
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem readUnsupportedNoWriteDevice =
                new BleDeviceItem(
                        "LHB-MOCK-READLESS-NOWRITE",
                        "00:00:00:00:00:06",
                        -88
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

        /*
         * Channel은 일부러 설정하지 않는다.
         *
         * channel == null 상태를 유지해서
         * Adapter의 "채널 -" 표시를 테스트한다.
         */

        deviceList.add(
                readUnsupportedNoWriteDevice
        );


        /*
         * -------------------------------------------------
         * 7. Processing (0x01) 상태 Mock
         * -------------------------------------------------
         *
         * Power State READ에 성공했지만
         * 응답값이 0x01인 상황을 재현한다.
         *
         * LightLink가 아직 추가 상태 확인을 진행 중이므로
         * Processing (0x01)으로 표시한다.
         *
         * 이 Mock은 Processing 상태를 고정해서 보여준다.
         * 실제로 READ 재시도를 실행하거나 Stuck으로 바뀌지는 않는다.
         *
         * Channel READ에 성공한 상황을 가정하여 Channel 9를 표시한다.
         *
         * RSSI:
         * -68 dBm → 보통
         *
         * 예상 UI:
         * LHB-MOCK-PROCESSING
         * Processing (0x01)
         * 00:00:00:00:00:07
         * 채널 9 | 신호 보통 · -68 dBm
         * 마지막 상태 확인 | 시간
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem processingDevice =
                new BleDeviceItem(
                        "LHB-MOCK-PROCESSING",
                        "00:00:00:00:00:07",
                        -68
                );

        processingDevice.setStatus(
                "Processing (0x01)"
        );

        processingDevice.setChannel(
                9
        );

        processingDevice.setLastStateCheckedAt(
                mockCheckedAt
        );

        deviceList.add(
                processingDevice
        );


        /*
         * -------------------------------------------------
         * 8. Stuck (0x01) 상태 Mock
         * -------------------------------------------------
         *
         * Power State READ는 성공했지만
         * 여러 번 연속 0x01이 반환되어
         * 초기 상태 재시도가 끝난 상황을 재현한다.
         *
         * 실제 모터가 켜져 있는지/꺼져 있는지와는 별개로
         * 현재 Power State가 정상적인 Awake/Sleep으로
         * 확인되지 않았다는 의미다.
         *
         * 이 Mock은 Stuck 상태를 고정해서 보여준다.
         * 실제 장치 장애나 펌웨어 고장을 재현하는 것은 아니다.
         *
         * Channel READ에 성공한 상황을 가정하여 Channel 11을 표시한다.
         *
         * RSSI:
         * -73 dBm → 보통
         *
         * 예상 UI:
         * LHB-MOCK-STUCK
         * Stuck (0x01)
         * 00:00:00:00:00:08
         * 채널 11 | 신호 보통 · -73 dBm
         * 마지막 상태 확인 | 시간
         * 회색 전원 버튼
         * 버튼 비활성화
         */
        BleDeviceItem stuckDevice =
                new BleDeviceItem(
                        "LHB-MOCK-STUCK",
                        "00:00:00:00:00:08",
                        -73
                );

        stuckDevice.setStatus(
                "Stuck (0x01)"
        );

        stuckDevice.setChannel(
                11
        );

        stuckDevice.setLastStateCheckedAt(
                mockCheckedAt
        );

        deviceList.add(
                stuckDevice
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
