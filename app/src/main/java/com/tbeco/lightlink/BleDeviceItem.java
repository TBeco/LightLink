package com.tbeco.lightlink;

// 검색된 Base Station 한 대의 정보를 저장
public class BleDeviceItem {

    private final String name;
    private final String address;
    private final int rssi;

    // 전원 상태
    private String status;


    public BleDeviceItem(
            String name,
            String address,
            int rssi) {

        this.name = name;
        this.address = address;
        this.rssi = rssi;

        // 발견 직후에는 아직 상태를 읽지 않았음
        this.status = "Checking...";
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


    public void setStatus(String status) {
        this.status = status;
    }
}