package com.tbeco.lightlink;

import java.time.LocalDateTime;


/*
 * 검색된 Base Station 한 대의 정보를 저장한다.
 */
public class BleDeviceItem {


    // Base Station 이름
    private final String name;


    // Bluetooth MAC 주소
    private final String address;


    /*
     * 가장 최근에 확인한 Bluetooth RSSI
     *
     * 처음에는 BLE 검색 결과의 RSSI를 저장하고,
     * 이후 상태 확인용 GATT 연결에서 RSSI를 다시 읽으면
     * 최신 값으로 갱신한다.
     */
    private int rssi;


    /*
     * Base Station 2.0의 현재 RF Channel.
     *
     * null:
     * 아직 채널을 확인하지 못했거나
     * Mode Characteristic READ를 지원하지 않음.
     *
     * 1 ~ 16:
     * 정상적으로 확인된 RF Channel.
     *
     * 채널 READ 실패만으로 전원 상태까지
     * 실패한 것으로 취급하지 않는다.
     */
    private Integer channel;


    /*
     * 현재 화면에 표시할 전원 상태
     *
     * 예:
     * Checking...
     * Awake
     * Sleep
     * Standby
     * Unknown
     * State read unsupported
     */
    private String status;


    /*
     * 실제 Power State 확인에
     * 마지막으로 성공한 시간.
     *
     * 아직 상태를 한 번도 확인하지 못했다면 null.
     */
    private LocalDateTime lastStateCheckedAt;


    /*
     * 이 Base Station의 Power State characteristic이
     * READ 기능을 지원하지 않는지 여부.
     *
     * false:
     * 아직 확인되지 않았거나 READ를 지원함.
     *
     * true:
     * Power State characteristic은 존재하지만
     * PROPERTY_READ가 없어 상태 읽기가 지원되지 않음.
     *
     * 단순한 GATT 연결 실패나 Read 실패와는 구분한다.
     */
    private boolean stateReadUnsupported;


    /*
     * 현재 Power State를 읽을 수는 없지만
     * Power State WRITE는 가능한지 여부.
     *
     * true:
     * 현재 상태 확인은 불가능하지만
     * Wake / Sleep 명령을 수동으로 보낼 수 있음.
     *
     * false:
     * 아직 확인되지 않았거나
     * 수동 전원 제어를 지원하지 않음.
     */
    private boolean manualPowerControlAvailable;


    public BleDeviceItem(
            String name,
            String address,
            int rssi) {


        this.name =
                name;


        this.address =
                address;


        this.rssi =
                rssi;


        /*
         * 발견 직후에는 아직 Mode Characteristic을
         * 읽지 않았으므로 채널은 알 수 없다.
         */
        this.channel =
                null;


        /*
         * 발견 직후에는 아직 실제 Power State를
         * 읽지 않았으므로 Checking... 상태로 시작한다.
         */
        this.status =
                "Checking...";


        /*
         * 발견한 것만으로는 상태 확인에 성공한 것이 아니므로
         * 마지막 상태 확인 시간은 아직 없다.
         */
        this.lastStateCheckedAt =
                null;


        /*
         * 발견 직후에는 아직 Power State READ 지원 여부를
         * 확인하지 않았으므로 false로 시작한다.
         */
        this.stateReadUnsupported =
                false;


        /*
         * 발견 직후에는 아직 Power State WRITE 지원 여부도
         * 확인하지 않았으므로 false로 시작한다.
         */
        this.manualPowerControlAvailable =
                false;
    }


    public String getName() {

        return name;
    }


    public String getAddress() {

        return address;
    }


    public int getRssi() {

        return rssi;
    }


    /*
     * Base Station의 RSSI를 새로 확인했을 때
     * 가장 최근 값으로 갱신한다.
     */
    public void setRssi(
            int rssi) {

        this.rssi =
                rssi;
    }


    /*
     * 현재 확인된 Base Station RF Channel.
     *
     * 아직 확인되지 않았다면 null을 반환한다.
     */
    public Integer getChannel() {

        return channel;
    }


    /*
     * Mode Characteristic READ에 성공했을 때
     * 확인된 RF Channel을 저장한다.
     *
     * 정상 범위 검증은 실제 BLE 값을 해석하는
     * MainActivity에서 수행한다.
     */
    public void setChannel(
            Integer channel) {

        this.channel =
                channel;
    }


    public String getStatus() {

        return status;
    }


    public void setStatus(
            String status) {

        this.status =
                status;
    }


    /*
     * 마지막으로 실제 상태 확인에 성공한 시간
     */
    public LocalDateTime getLastStateCheckedAt() {

        return lastStateCheckedAt;
    }


    /*
     * 실제 Power State 확인이 성공했을 때
     * 마지막 확인 시간을 갱신한다.
     */
    public void setLastStateCheckedAt(
            LocalDateTime lastStateCheckedAt) {

        this.lastStateCheckedAt =
                lastStateCheckedAt;
    }


    /*
     * Power State READ가 지원되지 않는지 확인
     */
    public boolean isStateReadUnsupported() {

        return stateReadUnsupported;
    }


    /*
     * Power State characteristic에
     * PROPERTY_READ가 없을 때 true로 설정한다.
     */
    public void setStateReadUnsupported(
            boolean stateReadUnsupported) {

        this.stateReadUnsupported =
                stateReadUnsupported;
    }


    /*
     * 현재 상태 확인 없이
     * 수동 Wake / Sleep 제어가 가능한지 확인
     */
    public boolean isManualPowerControlAvailable() {

        return manualPowerControlAvailable;
    }


    /*
     * Power State READ는 지원하지 않지만
     * WRITE는 가능한 경우 true로 설정한다.
     */
    public void setManualPowerControlAvailable(
            boolean manualPowerControlAvailable) {

        this.manualPowerControlAvailable =
                manualPowerControlAvailable;
    }
}
