package com.tbeco.lightlink;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;


// BLE 검색 결과를 RecyclerView에 표시해주는 Adapter
public class BleDeviceAdapter
        extends RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder> {


    /*
     * 전원 버튼 상태별 색상
     *
     * Awake:
     * Base Station 작동 중 → 초록색
     *
     * Sleep:
     * Base Station 절전 상태 → 파란색
     *
     * Checking / Processing / Unknown / 오류:
     * 상태를 확정할 수 없거나 제어할 수 없음 → 회색
     *
     * Stuck (0x01):
     * 연속된 0x01 응답으로 상태 확인 불가 → 빨간색
     */
    private static final int POWER_COLOR_AWAKE =
            Color.parseColor("#43A047");

    private static final int POWER_COLOR_SLEEP =
            Color.parseColor("#4285F4");

    /*
     * Sleep 상태에서 Identify 명령을 처리 중일 때 사용한다.
     *
     * 실제 전원 상태는 여전히 Sleep이지만,
     * Identify GATT 작업 중에는 전원 버튼을 사용할 수 없으므로
     * 일반 Sleep보다 어두운 파란색으로 구분한다.
     */
    private static final int POWER_COLOR_SLEEP_IDENTIFY =
            Color.parseColor("#2D4F78");

    /*
     * 현재 Power State는 읽을 수 없지만
     * 수동 Wake / Sleep 명령은 보낼 수 있는 상태.
     */
    private static final int POWER_COLOR_MANUAL =
            Color.parseColor("#FB8C00");

    private static final int POWER_COLOR_UNAVAILABLE =
            Color.parseColor("#616161");

    /*
     * Stuck (0x01)
     *
     * Power State READ에서 0x01이 반복되어
     * 현재 상태를 확정할 수 없는 경우.
     *
     * 일반적인 상태 확인 실패와 구분하기 위해
     * 빨간색으로 표시한다.
     */
    private static final int POWER_COLOR_STUCK =
            Color.parseColor("#C62828");


    /*
     * Bluetooth RSSI를 사용자에게 보여줄 때 사용하는
     * 간단한 3단계 기준.
     *
     * RSSI는 주변 환경과 휴대폰 방향에 따라 계속 흔들릴 수 있으므로
     * 너무 많은 단계로 세분화하지 않고
     * 강함 / 보통 / 약함 세 단계만 사용한다.
     *
     * 강함:
     * -65 dBm 이상
     *
     * 보통:
     * -66 ~ -78 dBm
     *
     * 약함:
     * -79 dBm 이하
     */
    private static final int SIGNAL_STRONG_RSSI =
            -65;

    private static final int SIGNAL_NORMAL_RSSI =
            -78;


    /*
     * 마지막 상태 확인 시간을
     * 화면에 표시할 형식
     *
     * 예:
     * 2026-10-08 16:05:42
     */
    private static final DateTimeFormatter LAST_STATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM-dd HH:mm:ss"
            );


    // RecyclerView에 표시할 Base Station 목록
    private final List<BleDeviceItem> deviceList;


    /*
     * 전원 버튼이 눌렸을 때
     * MainActivity 등 바깥쪽에 알려주기 위한 Listener
     */
    private final OnPowerClickListener powerClickListener;

    // 왼쪽 장치 정보 영역 클릭을 MainActivity에 전달한다.
    private final OnDeviceClickListener deviceClickListener;

    // 장치별 Identify 진행 여부를 확인한다.
    private final IdentifyBusyChecker identifyBusyChecker;


    /*
     * Adapter 생성자
     *
     * deviceList:
     * 화면에 표시할 Base Station 목록
     *
     * powerClickListener:
     * 전원 버튼 클릭을 처리할 Listener
     *
     * deviceClickListener:
     * 장치 관리 화면 열기를 처리할 Listener
     *
     * identifyBusyChecker:
     * MainActivity에서 특정 장치의 Identify 처리 여부를 조회한다.
     * Controller에 직접 접근하지 않고 전원 버튼 UI에만 반영한다.
     */
    public BleDeviceAdapter(
            List<BleDeviceItem> deviceList,
            OnPowerClickListener powerClickListener,
            OnDeviceClickListener deviceClickListener,
            IdentifyBusyChecker identifyBusyChecker) {

        this.deviceList =
                deviceList;

        this.powerClickListener =
                powerClickListener;

        this.deviceClickListener =
                deviceClickListener;

        this.identifyBusyChecker =
                identifyBusyChecker;
    }


    /*
     * item_ble_device.xml을
     * 실제 RecyclerView 항목으로 생성
     */
    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType) {


        View view =
                LayoutInflater
                        .from(parent.getContext())
                        .inflate(
                                R.layout.item_ble_device,
                                parent,
                                false
                        );


        return new DeviceViewHolder(
                view
        );
    }


    /*
     * 현재 Base Station 정보를
     * RecyclerView 항목에 표시
     */
    @Override
    public void onBindViewHolder(
            @NonNull DeviceViewHolder holder,
            int position) {


        BleDeviceItem device =
                deviceList.get(position);


        /*
         * 현재 상태를 한 번만 가져와서
         * 아래 UI 처리에 공통으로 사용
         */
        String status =
                device.getStatus();


        // Base Station 이름
        holder.textDeviceName.setText(
                device.getName()
        );


        // 현재 전원 상태
        holder.textDeviceStatus.setText(
                status
        );


        /*
         * Bluetooth MAC 주소
         *
         * RSSI는 아래의 별도 Radio 정보 줄에 표시한다.
         */
        holder.textDeviceInfo.setText(
                device.getAddress()
        );


        /*
         * RF 채널과 Bluetooth RSSI를 한 줄에 표시한다.
         * 채널 조회가 실패했거나 미지원이면 '-'로 표시한다.
         */
        configureDeviceRadioInfo(
                holder,
                device
        );


        /*
         * 마지막으로 실제 Power State를
         * 확인하는 데 성공한 시간을 표시한다.
         */
        configureLastStateChecked(
                holder,
                device,
                status
        );


        /*
         * 현재 상태에 맞게
         * 전원 버튼 설정
         *
         * RecyclerView는 View를 재사용하므로
         * 각 상태마다 색상과 Enabled를
         * 반드시 다시 설정해야 한다.
         */
        configurePowerButton(
                holder,
                device,
                status
        );


        /*
         * 전원 버튼 클릭
         *
         * Adapter가 직접 BLE 명령을 보내는 것이 아니라
         * 어떤 Base Station의 버튼이 눌렸는지만
         * MainActivity에 전달한다.
         */
        holder.buttonPower.setOnClickListener(v -> {

            if (powerClickListener != null) {

                powerClickListener.onPowerClick(
                        device
                );
            }
        });

        /*
         * 전원 버튼과 분리된 왼쪽 정보 영역 클릭.
         * BLE 작업은 실행하지 않고 MainActivity에 선택한 장치만 전달한다.
         *
         * RecyclerView가 ViewHolder를 재사용하므로
         * 바인딩할 때마다 해당 장치에 맞는 클릭 대상을 설정한다.
         */
        holder.layoutDeviceInfo.setContentDescription(
                device.getName() + " 장치 관리 열기"
        );

        holder.layoutDeviceInfo.setOnClickListener(v -> {

            if (deviceClickListener != null) {

                deviceClickListener.onDeviceClick(
                        device
                );
            }
        });
    }


    /*
     * Base Station의 RF Channel과 Bluetooth RSSI를
     * 한 줄에 함께 표시한다.
     *
     * RSSI는 0에 가까울수록 강하고,
     * 음수 값이 더 작아질수록 약하다.
     *
     * 예:
     * 채널 3 | 신호 강함 · -54 dBm
     * 채널 8 | 신호 보통 · -70 dBm
     * 채널 - | 신호 약함 · -83 dBm
     *
     * 채널이 null이면 아직 확인하지 못했거나
     * 해당 장치에서 Mode Characteristic READ를
     * 사용할 수 없는 상태이므로 "-"로 표시한다.
     */
    private void configureDeviceRadioInfo(
            DeviceViewHolder holder,
            BleDeviceItem device) {


        int rssi =
                device.getRssi();


        String signalText;


        if (rssi >= SIGNAL_STRONG_RSSI) {

            signalText =
                    "강함";

        } else if (rssi >= SIGNAL_NORMAL_RSSI) {

            signalText =
                    "보통";

        } else {

            signalText =
                    "약함";
        }


        /*
         * MainActivity에서 Mode Characteristic READ에 성공하면
         * 1~16 범위의 채널 번호가 저장된다.
         *
         * 아직 확인되지 않았다면 null이다.
         */
        Integer channel =
                device.getChannel();


        String channelText;


        if (channel != null) {

            channelText =
                    String.valueOf(
                            channel
                    );

        } else {

            channelText =
                    "-";
        }


        holder.textDeviceRadioInfo.setText(
                "채널 "
                        + channelText
                        + " | 신호 "
                        + signalText
                        + " · "
                        + rssi
                        + " dBm"
        );
    }


    /*
     * 마지막 상태 확인 시간을 표시한다.
     *
     * Power State READ 자체가 지원되지 않는 경우에는
     * 일반적인 상태 확인 실패와 구분하여
     * "지원되지 않음"을 표시한다.
     *
     * 아직 한 번도 실제 Power State 확인에
     * 성공하지 못했다면 현재 상태에 따라
     * "확인 중..." 또는 "확인되지 않음"을 표시한다.
     *
     * 이전에 한 번이라도 성공한 적이 있다면
     * 현재 Checking... 상태이더라도
     * 마지막으로 성공했던 시간을 계속 표시한다.
     */
    private void configureLastStateChecked(
            DeviceViewHolder holder,
            BleDeviceItem device,
            String status) {


        /*
         * Power State characteristic은 존재하지만
         * READ 기능 자체가 지원되지 않는 경우.
         *
         * 단순한 연결 실패나 Read 실패와는 다르므로
         * "확인되지 않음"이 아니라
         * "지원되지 않음"으로 따로 표시한다.
         */
        if (device.isStateReadUnsupported()) {

            holder.textLastStateChecked.setText(
                    "마지막 상태 확인 | 지원되지 않음"
            );


            return;
        }


        LocalDateTime lastStateCheckedAt =
                device.getLastStateCheckedAt();


        /*
         * 실제 상태 확인 성공 기록이
         * 아직 한 번도 없는 경우
         */
        if (lastStateCheckedAt == null) {

            /*
             * 현재 상태를 확인하고 있는 중
             */
            if ("Checking...".equals(status)) {

                holder.textLastStateChecked.setText(
                        "마지막 상태 확인 | 확인 중..."
                );

            } else {

                /*
                 * 상태 확인 시도가 끝났지만
                 * 실제 Power State를 확정하지 못한 경우
                 */
                holder.textLastStateChecked.setText(
                        "마지막 상태 확인 | 확인되지 않음"
                );
            }


            return;
        }


        /*
         * 이전에 실제 Power State 확인에
         * 성공한 기록이 있는 경우
         */
        String formattedTime =
                lastStateCheckedAt.format(
                        LAST_STATE_TIME_FORMAT
                );


        holder.textLastStateChecked.setText(
                "마지막 상태 확인 | "
                        + formattedTime
        );
    }


    /*
     * Base Station 상태에 따라
     * 전원 버튼의 색상과 활성 여부를 설정
     */
    private void configurePowerButton(
            DeviceViewHolder holder,
            BleDeviceItem device,
            String status) {


        /*
         * 현재 BLE 명령 처리 중.
         *
         * READ 미지원 장치도 수동 명령을 보내는 동안에는
         * Checking... 상태가 되므로 반드시 가장 먼저 확인한다.
         *
         * 이 분기가 수동 제어 분기보다 먼저 있어야
         * 명령 처리 중 주황색 버튼이 다시 활성화되지 않는다.
         */
        if ("Checking...".equals(status)) {

            holder.buttonPower.setEnabled(
                    false
            );


            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            POWER_COLOR_UNAVAILABLE
                    )
            );


            holder.buttonPower.setContentDescription(
                    device.getName()
                            + " power command in progress"
            );


            return;
        }


        /*
         * Awake
         *
         * Base Station이 정상적으로 작동 중.
         * 버튼 사용 가능 + 초록색.
         */
        if ("Awake".equals(status)) {

            holder.buttonPower.setEnabled(
                    true
            );


            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            POWER_COLOR_AWAKE
                    )
            );


            holder.buttonPower.setContentDescription(
                    device.getName()
                            + " power off"
            );


            return;
        }


        /*
         * Sleep
         *
         * 일반적인 Sleep 상태에서는 파란색 전원 버튼을 표시한다.
         *
         * 단, 동일한 Base Station에서 Identify 명령을
         * 처리 중이라면 전원 버튼을 일시적으로 비활성화한다.
         *
         * 장치의 실제 상태값은 변경하지 않는다.
         */
        if ("Sleep".equals(status)) {

            /*
             * Identify GATT 작업이 진행 중인 경우.
             *
             * 연결, 재시도, WRITE 처리 중에는
             * 전원 버튼을 회색으로 표시하고 클릭을 막는다.
             */
            boolean identifyBusy =
                    identifyBusyChecker != null
                            && identifyBusyChecker.isIdentifyBusy(device);

            if (identifyBusy) {

                holder.buttonPower.setEnabled(false);

                holder.buttonPower.setBackgroundTintList(
                        ColorStateList.valueOf(
                                POWER_COLOR_SLEEP_IDENTIFY
                        )
                );

                holder.buttonPower.setContentDescription(
                        device.getName()
                                + " power unavailable during Identify"
                );

                return;
            }

            /*
             * Identify 작업이 진행 중이지 않은
             * 일반적인 Sleep 상태.
             */
            holder.buttonPower.setEnabled(true);

            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            POWER_COLOR_SLEEP
                    )
            );

            holder.buttonPower.setContentDescription(
                    device.getName()
                            + " power on"
            );

            return;
        }

        /*
         * Stuck (0x01)
         *
         * 0x01이 반복적으로 확인되어
         * 앱에서 현재 전원 상태를 확정할 수 없는 경우.
         *
         * 전원 명령은 허용하지 않지만,
         * 일반적인 비활성 상태와 구분하기 위해
         * 빨간색 배경을 사용한다.
         */
        if ("Stuck (0x01)".equals(status)) {

            holder.buttonPower.setEnabled(false);

            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            POWER_COLOR_STUCK
                    )
            );

            holder.buttonPower.setContentDescription(
                    device.getName()
                            + " power state stuck"
            );

            return;
        }


        /*
         * Power State READ는 지원하지 않지만
         * 일반 WRITE는 가능한 장치.
         *
         * 현재 Awake / Sleep 상태를 알 수 없으므로
         * 일반 토글처럼 반대 상태를 추측하지 않는다.
         *
         * 주황색 버튼을 누르면 MainActivity에서
         * Wake / Sleep을 사용자가 직접 선택한다.
         */
        if (device.isStateReadUnsupported()
                && device.isManualPowerControlAvailable()) {

            holder.buttonPower.setEnabled(
                    true
            );


            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            POWER_COLOR_MANUAL
                    )
            );


            holder.buttonPower.setContentDescription(
                    device.getName()
                            + " manual power control"
            );


            return;
        }


        /*
         * 그 외 모든 상태
         *
         * Unknown
         * Timeout
         * Connection failed
         * Standby
         * Processing (0x01)
         * READ 미지원 + WRITE 미지원
         * 기타 알 수 없는 상태
         *
         * 현재 상태를 확실하게 알 수 없거나
         * 안전하게 전원 명령을 보낼 수 없으므로
         * 전원 버튼을 비활성화한다.
         */
        holder.buttonPower.setEnabled(
                false
        );


        holder.buttonPower.setBackgroundTintList(
                ColorStateList.valueOf(
                        POWER_COLOR_UNAVAILABLE
                )
        );


        holder.buttonPower.setContentDescription(
                device.getName()
                        + " power unavailable"
        );
    }


    /*
     * RecyclerView에 표시할 항목 개수
     */
    @Override
    public int getItemCount() {

        return deviceList.size();
    }


    /*
     * item_ble_device.xml 안에 있는
     * View들을 보관한다.
     */
    static class DeviceViewHolder
            extends RecyclerView.ViewHolder {


        TextView textDeviceName;
        TextView textDeviceStatus;
        TextView textDeviceInfo;
        TextView textDeviceRadioInfo;
        TextView textLastStateChecked;

        ImageButton buttonPower;
        View layoutDeviceInfo;


        public DeviceViewHolder(
                @NonNull View itemView) {

            super(itemView);


            textDeviceName =
                    itemView.findViewById(
                            R.id.textDeviceName
                    );


            textDeviceStatus =
                    itemView.findViewById(
                            R.id.textDeviceStatus
                    );


            textDeviceInfo =
                    itemView.findViewById(
                            R.id.textDeviceInfo
                    );


            textDeviceRadioInfo =
                    itemView.findViewById(
                            R.id.textDeviceRadioInfo
                    );


            textLastStateChecked =
                    itemView.findViewById(
                            R.id.textLastStateChecked
                    );


            buttonPower =
                    itemView.findViewById(
                            R.id.buttonPower
                    );

            layoutDeviceInfo =
                    itemView.findViewById(
                            R.id.layoutDeviceInfo
                    );
        }
    }


    /*
     * 전원 버튼 클릭을
     * 외부에 전달하기 위한 인터페이스
     */
    public interface OnPowerClickListener {

        void onPowerClick(
                BleDeviceItem device
        );
    }


    /*
     * 장치 관리 영역 클릭을 외부에 전달한다.
     * 전원 버튼 이벤트와는 독립적인 Listener다.
     */
    public interface OnDeviceClickListener {

        void onDeviceClick(
                BleDeviceItem device
        );
    }


    /*
     * 특정 Base Station에서 Identify 명령이
     * 진행 중인지 외부(MainActivity)에 확인한다.
     *
     * Adapter는 BLE Controller를 직접 관리하지 않는다.
     */
    public interface IdentifyBusyChecker {

        boolean isIdentifyBusy(
                BleDeviceItem device
        );
    }
}

