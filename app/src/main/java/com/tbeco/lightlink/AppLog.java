package com.tbeco.lightlink;

import android.util.Log;


/*
 * LightLink 공통 Debug Log
 *
 * Debug 빌드:
 * Logcat에 로그 출력
 *
 * Release 빌드:
 * 로그를 출력하지 않음
 */
public final class AppLog {

    private static final String TAG = "LightLink";


    /*
     * 외부에서 객체를 만들 필요가 없는 Utility Class이므로
     * 생성자를 private로 막는다.
     */
    private AppLog() {
    }


    /*
     * 일반 Debug Log
     */
    public static void d(String message) {

        if (!BuildConfig.DEBUG) {
            return;
        }


        Log.d(
                TAG,
                message
        );
    }


    /*
     * Exception 정보까지 함께 출력하는 Debug Log
     */
    public static void d(
            String message,
            Throwable throwable) {

        if (!BuildConfig.DEBUG) {
            return;
        }


        Log.d(
                TAG,
                message,
                throwable
        );
    }
}