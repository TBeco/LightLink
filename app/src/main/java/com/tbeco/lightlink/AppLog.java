package com.tbeco.lightlink;

import android.util.Log;

/**
 * LightLink 공통 로그 유틸리티.
 *
 * Debug 빌드에서만 Logcat에 출력한다.
 * Release 빌드에서는 기기 주소 및 BLE 진단 정보가 노출되지 않도록 출력하지 않는다.
 *
 * 검색 태그: LightLink
 * 상세 기능 구분은 메시지 접두사([Identify] 등)를 사용한다.
 */
public final class AppLog {

    private static final String TAG = "LightLink";

    private AppLog() {
        // Utility class: 인스턴스 생성 방지.
    }

    /** 일반 디버그 로그. */
    public static void d(String message) {
        if (!BuildConfig.DEBUG) return;
        Log.d(TAG, message);
    }

    /** 예외 정보를 포함하는 디버그 로그. 기존 호출과 호환된다. */
    public static void d(String message, Throwable throwable) {
        if (!BuildConfig.DEBUG) return;
        Log.d(TAG, message, throwable);
    }

    /** 주의/실패 진단용 로그. Release에서는 출력하지 않는다. */
    public static void w(String message) {
        if (!BuildConfig.DEBUG) return;
        Log.w(TAG, message);
    }

    /** 예외 정보를 포함하는 주의 로그. */
    public static void w(String message, Throwable throwable) {
        if (!BuildConfig.DEBUG) return;
        Log.w(TAG, message, throwable);
    }
}
