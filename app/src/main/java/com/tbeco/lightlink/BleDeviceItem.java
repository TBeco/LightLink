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


    // 검색 당시 RSSI
    private final int rssi;


    /*
     * 현재 화면에 표시할 전원 상태
     *
     * 예:
     * Checking...
     * Awake
     * Sleep
     * Standby
     * Unknown
     */
    private String status;


    /*
     * 실제 Power State 확인에
     * 마지막으로 성공한 시간.
     *
     * 아직 상태를 한 번도 확인하지 못했다면 null.
     */
    private LocalDateTime lastStateCheckedAt;


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
}