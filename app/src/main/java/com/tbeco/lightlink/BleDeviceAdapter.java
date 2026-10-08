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
     * Checking / Unknown / 오류:
     * 상태를 확정할 수 없음 → 회색
     */
    private static final int POWER_COLOR_AWAKE =
            Color.parseColor("#43A047");

    private static final int POWER_COLOR_SLEEP =
            Color.parseColor("#4285F4");

    private static final int POWER_COLOR_UNAVAILABLE =
            Color.parseColor("#616161");


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


    /*
     * Adapter 생성자
     *
     * deviceList:
     * 화면에 표시할 Base Station 목록
     *
     * powerClickListener:
     * 전원 버튼 클릭을 처리할 Listener
     */
    public BleDeviceAdapter(
            List<BleDeviceItem> deviceList,
            OnPowerClickListener powerClickListener) {

        this.deviceList =
                deviceList;

        this.powerClickListener =
                powerClickListener;
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
         * MAC 주소 + RSSI
         *
         * 예:
         * AA:BB:CC:DD:EE:FF    -52 dBm
         */
        holder.textDeviceInfo.setText(
                device.getAddress()
                        + "    "
                        + device.getRssi()
                        + " dBm"
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
    }


    /*
     * 마지막 상태 확인 시간을 표시한다.
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
         * Base Station이 Sleep 상태.
         * 버튼 사용 가능 + 파란색.
         */
        if ("Sleep".equals(status)) {

            holder.buttonPower.setEnabled(
                    true
            );


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
         * 그 외 모든 상태
         *
         * Checking...
         * Unknown
         * Timeout
         * Connection failed
         * Standby
         * 기타 알 수 없는 상태
         *
         * 현재 상태를 확실하게 알 수 없으므로
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
        TextView textLastStateChecked;

        ImageButton buttonPower;


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


            textLastStateChecked =
                    itemView.findViewById(
                            R.id.textLastStateChecked
                    );


            buttonPower =
                    itemView.findViewById(
                            R.id.buttonPower
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
}