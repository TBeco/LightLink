package com.tbeco.lightlink;

import java.util.List;

/*
 * Base Station RF Channel 중복 감지 클래스.
 *
 * 현재 검색된 Base Station 목록에서
 * 동일한 RF 채널을 사용하는 장치가
 * 2대 이상 존재하는지 확인한다.
 *
 * 이 클래스는 이미 조회된 채널 정보만 사용한다.
 *
 * 실제 BLE 연결이나 GATT READ / WRITE는
 * 수행하지 않는다.
 */
public final class RfChannelConflictChecker {

    /*
     * 유틸리티 클래스이므로
     * 외부에서 객체를 생성하지 못하도록 한다.
     */
    private RfChannelConflictChecker() {
    }

    /*
     * 현재 장치 목록에서
     * 특정 RF 채널의 중복 여부를 확인한다.
     *
     * deviceList:
     * 현재 검색된 Base Station 목록.
     *
     * channel:
     * 중복 여부를 확인할 RF 채널 번호.
     *
     * 반환값:
     * true  → 동일한 채널을 사용하는 장치가 2대 이상.
     * false → 중복이 확인되지 않음.
     *
     * 채널이 null이거나 유효 범위(1~16)를 벗어난 경우
     * 중복 검사에서 제외한다.
     */
    public static boolean isChannelDuplicated(
            List<BleDeviceItem> deviceList,
            Integer channel) {

        if (deviceList == null
                || channel == null
                || channel < 1
                || channel > 16) {

            return false;
        }

        int duplicateCount = 0;

        /*
         * 현재 검색된 모든 장치의
         * RF 채널을 순서대로 비교한다.
         */
        for (BleDeviceItem device : deviceList) {

            if (device == null) {
                continue;
            }

            Integer deviceChannel =
                    device.getChannel();

            /*
             * 채널 정보가 없거나
             * 유효 범위를 벗어나면 제외한다.
             */
            if (deviceChannel == null
                    || deviceChannel < 1
                    || deviceChannel > 16) {

                continue;
            }

            /*
             * 동일한 채널을 사용하는
             * 장치 수를 계산한다.
             */
            if (channel.equals(deviceChannel)) {

                duplicateCount++;

                /*
                 * 동일 채널이 2대 이상이면
                 * 더 이상 확인할 필요 없이
                 * 중복으로 판단한다.
                 */
                if (duplicateCount >= 2) {
                    return true;
                }
            }
        }

        return false;
    }
}