package com.tbeco.lightlink;


// BLE 검색 결과 하나를 저장하는 간단한 데이터 클래스
public class BleDeviceItem {

    private final String name;
    private final String address;
    private final int rssi;


    public BleDeviceItem(
            String name,
            String address,
            int rssi) {

        this.name = name;
        this.address = address;
        this.rssi = rssi;
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
}