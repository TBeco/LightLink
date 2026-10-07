package com.tbeco.lightlink;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;


// BLE 검색 결과를 RecyclerView에 표시해주는 Adapter
public class BleDeviceAdapter
        extends RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder> {

    // RecyclerView에 표시할 장치 목록
    private final List<BleDeviceItem> deviceList;


    public BleDeviceAdapter(List<BleDeviceItem> deviceList) {
        this.deviceList = deviceList;
    }


    // 각 장치 한 줄의 XML을 실제 View로 생성
    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType) {

        View view = LayoutInflater
                .from(parent.getContext())
                .inflate(
                        R.layout.item_ble_device,
                        parent,
                        false
                );

        return new DeviceViewHolder(view);
    }


    // 현재 장치 정보를 화면에 표시
    @Override
    public void onBindViewHolder(
            @NonNull DeviceViewHolder holder,
            int position) {

        BleDeviceItem device = deviceList.get(position);

        holder.textDeviceName.setText(device.getName());

        holder.textDeviceInfo.setText(
                device.getAddress()
                        + "    "
                        + device.getRssi()
                        + " dBm"
        );
    }


    // RecyclerView에 표시할 항목 개수
    @Override
    public int getItemCount() {
        return deviceList.size();
    }


    // item_ble_device.xml 안의 View를 보관
    static class DeviceViewHolder extends RecyclerView.ViewHolder {

        TextView textDeviceName;
        TextView textDeviceInfo;


        public DeviceViewHolder(@NonNull View itemView) {
            super(itemView);

            textDeviceName =
                    itemView.findViewById(R.id.textDeviceName);

            textDeviceInfo =
                    itemView.findViewById(R.id.textDeviceInfo);
        }
    }
}