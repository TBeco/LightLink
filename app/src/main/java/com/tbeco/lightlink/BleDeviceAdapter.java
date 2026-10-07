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


        // MAC 주소 + RSSI
        holder.textDeviceInfo.setText(
                device.getAddress()
                        + "    "
                        + device.getRssi()
                        + " dBm"
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

            /*
             * Enabled=false 상태에서는
             * 기본적으로 클릭 이벤트가 실행되지 않는다.
             *
             * 그래도 Listener가 존재하는지
             * 한 번 더 확인한다.
             */
            if (powerClickListener != null) {

                powerClickListener.onPowerClick(
                        device
                );
            }
        });
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


            /*
             * 나중에 TalkBack 등의
             * 접근성 기능에서도 의미를 알 수 있도록 설정
             */
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
     * View들을 보관
     */
    static class DeviceViewHolder
            extends RecyclerView.ViewHolder {


        TextView textDeviceName;
        TextView textDeviceStatus;
        TextView textDeviceInfo;

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