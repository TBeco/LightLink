package com.tbeco.lightlink;

import java.util.List;


/*
 * Release 빌드용 빈 구현.
 *
 * 실제 사용자에게 배포되는 앱에서는
 * Mock Base Station을 생성하지 않는다.
 */
public final class DebugMockDevices {


    private DebugMockDevices() {
    }


    public static void addMockDevices(
            List<BleDeviceItem> deviceList) {

        // Release에서는 Mock 장치를 추가하지 않는다.
    }
}