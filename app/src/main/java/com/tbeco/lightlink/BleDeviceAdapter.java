package com.tbeco.lightlink;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import androidx.annotation.StringRes;


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
     * Awake 상태에서 채널 변경 중 사용할 어두운 초록색.
     */
    private static final int POWER_COLOR_AWAKE_CHANNEL =
            Color.parseColor("#2B6731");

    /*
     * 수동 전원 제어 장치에서 채널 변경 중 사용할 어두운 주황색.
     */
    private static final int POWER_COLOR_MANUAL_CHANNEL =
            Color.parseColor("#9A5800");

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
     * RF 채널 정보만 부분 갱신하기 위한 RecyclerView Payload.
     *
     * 일반적인 notifyItemChanged()와 달리
     * 장치 전체 UI를 다시 바인딩하지 않고
     * 채널 및 RSSI 정보만 갱신할 때 사용한다.
     */
    private static final String PAYLOAD_RF_CHANNEL =
            "payload_rf_channel";


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
     * 특정 Base Station의 채널 변경 및 확인 작업 진행 여부.
     *
     * 실제 작업 상태는 MainActivity에서 관리하며,
     * Adapter는 전원 버튼 표시를 위해서만 조회한다.
     */
    private ChannelVerificationBusyChecker channelVerificationBusyChecker;

    /*
     * MainActivity에서 장치별 채널 변경 상태 확인 함수를 연결한다.
     */
    public void setChannelVerificationBusyChecker(
            ChannelVerificationBusyChecker checker) {

        this.channelVerificationBusyChecker = checker;
    }


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
     * TextView 내부에 표시되는 Vector Drawable을
     * 텍스트의 수직 중앙에 정렬하기 위한 ImageSpan.
     *
     * 기본 ImageSpan은 ALIGN_BASELINE 또는 ALIGN_BOTTOM을
     * 사용하면 아이콘이 글자보다 위쪽으로 치우쳐 보일 수 있다.
     *
     * Android API 26부터 동일한 정렬 방식을 사용하기 위해
     * 직접 텍스트의 FontMetrics를 계산한다.
     *
     * 한국어 / 영어 및 시스템 글꼴 크기가 변경되더라도
     * 현재 TextView의 글꼴을 기준으로 위치를 계산한다.
     */
    private static class CenteredImageSpan extends ImageSpan {

        public CenteredImageSpan(Drawable drawable) {
            super(drawable, ImageSpan.ALIGN_BASELINE);
        }

        /*
         * ImageSpan으로 인해 TextView의 줄 높이가
         * 불필요하게 확장되지 않도록 한다.
         *
         * 기본 ImageSpan은 이미지 높이가 글꼴보다 크면
         * FontMetrics를 수정하여 줄 높이를 늘릴 수 있다.
         *
         * 여기서는 기존 TextView의 FontMetrics를 유지하고
         * 이미지가 차지하는 가로 너비만 반환한다.
         *
         * 실제 이미지의 수직 위치는
         * draw()에서 별도로 계산한다.
         */
        @Override
        public int getSize(
                @NonNull Paint paint,
                CharSequence text,
                int start,
                int end,
                Paint.FontMetricsInt fm) {

            return getDrawable().getBounds().width();
        }

        @Override
        public void draw(
                @NonNull Canvas canvas,
                CharSequence text,
                int start,
                int end,
                float x,
                int top,
                int y,
                int bottom,
                @NonNull Paint paint) {

            Drawable drawable =
                    getDrawable();

            /*
             * 현재 텍스트의 글꼴 정보를 가져온다.
             *
             * ascent:
             * 기준선보다 위쪽에 해당하는 글꼴 높이.
             *
             * descent:
             * 기준선보다 아래쪽에 해당하는 글꼴 높이.
             */
            Paint.FontMetricsInt fm =
                    paint.getFontMetricsInt();

            /*
             * 현재 글꼴의 수직 중앙 위치를 계산한다.
             *
             * y는 텍스트의 Baseline 위치다.
             */
            float textCenterY =
                    y + (fm.ascent + fm.descent) / 2f;

            /*
             * Vector 아이콘의 높이를 가져온다.
             */
            int iconHeight =
                    drawable.getBounds().height();

            /*
             * 아이콘의 중앙이
             * 텍스트의 수직 중앙과 일치하도록 배치한다.
             */
            float iconTop =
                    textCenterY - iconHeight / 2f;

            canvas.save();

            canvas.translate(
                    x,
                    iconTop
            );

            drawable.draw(canvas);

            canvas.restore();
        }
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
        /*
         * 장치 관리 영역의 접근성 설명.
         *
         * 여러 장치가 등록된 경우에도
         * 어느 장치의 관리 화면인지 구분할 수 있도록
         * 실제 장치 이름을 포함한다.
         */
        holder.layoutDeviceInfo.setContentDescription(
                holder.itemView.getContext().getString(
                        R.string.device_management_open_named,
                        device.getName()
                )
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
     * 특정 Base Station의 RF 채널 정보만 갱신한다.
     *
     * RecyclerView Payload를 사용하여
     * 기존 장치 이름, Power State, 전원 버튼 등의
     * UI를 다시 바인딩하지 않는다.
     *
     * RF 채널 중복 경고가 변경된 장치에만 호출한다.
     */
    public void notifyChannelWarningChanged(int position) {

        if (position < 0
                || position >= deviceList.size()) {
            return;
        }

        notifyItemChanged(
                position,
                PAYLOAD_RF_CHANNEL
        );
    }


    /*
     * RecyclerView 부분 갱신 처리.
     *
     * RF 채널 관련 Payload만 전달된 경우에는
     * configureDeviceRadioInfo()만 실행한다.
     *
     * 일반적인 전체 바인딩이 필요한 경우에는
     * 기존 onBindViewHolder()를 그대로 사용한다.
     *
     * 이를 통해 불필요한 변경 애니메이션과
     * 전체 UI 재설정을 줄인다.
     */
    @Override
    public void onBindViewHolder(
            @NonNull DeviceViewHolder holder,
            int position,
            @NonNull List<Object> payloads) {

        if (!payloads.isEmpty()) {

            boolean radioOnly = true;

            for (Object payload : payloads) {

                if (!PAYLOAD_RF_CHANNEL.equals(payload)) {
                    radioOnly = false;
                    break;
                }
            }

            if (radioOnly) {

                BleDeviceItem device =
                        deviceList.get(position);

                configureDeviceRadioInfo(
                        holder,
                        device
                );

                return;
            }
        }

        /*
         * 부분 갱신 대상이 아니라면
         * 기존 전체 바인딩 로직을 실행한다.
         */
        onBindViewHolder(
                holder,
                position
        );
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
                    holder.itemView.getContext().getString(
                            R.string.device_signal_strong
                    );

        } else if (rssi >= SIGNAL_NORMAL_RSSI) {

            signalText =
                    holder.itemView.getContext().getString(
                            R.string.device_signal_normal
                    );

        } else {

            signalText =
                    holder.itemView.getContext().getString(
                            R.string.device_signal_weak
                    );
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


        /*
         * Base Station RF 채널 및 Bluetooth RSSI 표시 문자열.
         *
         * 기존 strings.xml을 사용하므로
         * 한국어 / 영어 번역은 그대로 유지한다.
         */
        String radioInfo =
                holder.itemView.getContext().getString(
                        R.string.device_radio_info_format,
                        channelText,
                        signalText,
                        rssi
                );

        /*
         * RF 채널 중복 경고 아이콘 표시 여부.
         *
         * 현재 검색된 Base Station 목록에서
         * 동일한 RF 채널을 사용하는 장치가
         * 2대 이상 존재하면 경고를 표시한다.
         *
         * 채널 정보가 없거나 유효하지 않은 장치는
         * RfChannelConflictChecker에서 자동 제외한다.
         *
         * Debug Mock과 실제 Base Station 모두
         * 동일한 중복 감지 로직을 사용한다.
         */
        boolean showChannelWarning =
                RfChannelConflictChecker.isChannelDuplicated(
                        deviceList,
                        channel
                );

        /*
         * RF 채널 중복 판단 디버그 로그.
         *
         * RecyclerView가 각 장치를 다시 표시할 때
         * 실제 채널 값과 중복 판단 결과를 확인한다.
         *
         * 중복 감지 기능에 영향을 주지 않으며,
         * 문제가 해결되면 제거할 수 있다.
         */
        AppLog.d(
                "RF Channel Conflict"
                        + " / device = "
                        + device.getName()
                        + " / channel = "
                        + channel
                        + " / duplicated = "
                        + showChannelWarning
                        + " / deviceCount = "
                        + deviceList.size()
        );

        /*
         * 경고 아이콘을 표시하지 않는 경우에는
         * 기존 텍스트를 그대로 사용한다.
         *
         * RecyclerView의 ViewHolder 재사용 과정에서
         * 이전 ImageSpan이 남지 않도록 한다.
         */
        if (!showChannelWarning) {

            holder.textDeviceRadioInfo.setText(
                    radioInfo
            );

            return;
        }

        /*
         * RF 채널 중복 경고용 Vector Drawable을 가져온다.
         */
        Drawable warningIcon =
                AppCompatResources.getDrawable(
                        holder.itemView.getContext(),
                        R.drawable.ic_rf_channel_warning
                );

        if (warningIcon == null) {

            holder.textDeviceRadioInfo.setText(
                    radioInfo
            );

            return;
        }

        /*
         * TextView에 적용된 실제 글꼴 크기를 기준으로
         * 경고 아이콘 크기를 계산한다.
         *
         * getTextSize()는 픽셀 단위를 반환하므로
         * 시스템 글꼴 크기 설정도 반영된다.
         *
         * 1.35f는 아이콘 크기 조절 비율이다.
         * 실제 화면을 확인한 뒤 변경할 수 있다.
         */
        float textSize =
                holder.textDeviceRadioInfo.getTextSize();

        int iconSize =
                Math.round(textSize * 1.35f);

        warningIcon.setBounds(
                0,
                0,
                iconSize,
                iconSize
        );

        /*
         * 기존 RF 채널 문자열 앞에
         * ImageSpan이 들어갈 자리 하나를 추가한다.
         *
         * \uFFFC:
         * 이미지로 치환할 특수 문자.
         *
         * 뒤의 공백:
         * 경고 아이콘과 채널 문자열 사이의 간격.
         */
        SpannableString styledText =
                new SpannableString(
                        "\uFFFC  " + radioInfo
                );

        /*
         * 첫 번째 문자 자리에
         * 수직 중앙 정렬된 Vector 아이콘을 삽입한다.
         */
        styledText.setSpan(
                new CenteredImageSpan(
                        warningIcon
                ),
                0,
                1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        /*
         * RF 채널 및 신호 정보는 기존 색상을 유지하고,
         * 경고 아이콘만 추가된 문자열을 표시한다.
         */
        holder.textDeviceRadioInfo.setText(
                styledText
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
     *
     * 사용자에게 표시하는 문자열은
     * strings.xml의 언어별 리소스를 사용한다.
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
                    R.string.device_last_checked_unsupported
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
             *
             * 주의:
             * "Checking..."은 내부 상태값이므로
             * 번역하거나 변경하지 않는다.
             */
            if ("Checking...".equals(status)) {

                holder.textLastStateChecked.setText(
                        R.string.device_last_checked_checking
                );

            } else {

                /*
                 * 상태 확인 시도가 끝났지만
                 * 실제 Power State를 확정하지 못한 경우
                 */
                holder.textLastStateChecked.setText(
                        R.string.device_last_checked_unknown
                );
            }

            return;
        }


        /*
         * 이전에 실제 Power State 확인에
         * 성공한 기록이 있는 경우
         *
         * 기존 날짜 형식을 유지한다.
         */
        String formattedTime =
                lastStateCheckedAt.format(
                        LAST_STATE_TIME_FORMAT
                );

        /*
         * 마지막 상태 확인 시각을
         * 현재 시스템 언어에 맞게 표시한다.
         */
        holder.textLastStateChecked.setText(
                holder.itemView.getContext().getString(
                        R.string.device_last_checked_format,
                        formattedTime
                )
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


            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_processing
            );


            return;
        }

        /*
         * 채널 변경 및 확인 작업 중인 Base Station의
         * 전원 버튼을 일시적으로 비활성화한다.
         *
         * 실제 전원 상태는 변경하지 않는다.
         *
         * Awake:
         * 기존 초록색보다 어두운 초록색 사용.
         *
         * State read unsupported:
         * 기존 주황색보다 어두운 주황색 사용.
         *
         * 다른 Base Station의 전원 버튼에는 영향을 주지 않는다.
         */
        boolean channelBusy =
                channelVerificationBusyChecker != null
                        && channelVerificationBusyChecker
                        .isChannelVerificationBusy(device);

        /*
         * 채널 변경이 허용되는 두 가지 상태에서만
         * 별도의 비활성화 색상을 적용한다.
         */
        if (channelBusy
                && ("Awake".equals(status)
                || (device.isStateReadUnsupported()
                && device.isManualPowerControlAvailable()))) {

            int disabledColor =
                    "Awake".equals(status)
                            ? POWER_COLOR_AWAKE_CHANNEL
                            : POWER_COLOR_MANUAL_CHANNEL;

            /*
             * 해당 장치의 전원 버튼만 비활성화한다.
             */
            holder.buttonPower.setEnabled(false);

            holder.buttonPower.setBackgroundTintList(
                    ColorStateList.valueOf(
                            disabledColor
                    )
            );

            /*
             * 전원 제어를 현재 사용할 수 없음을 안내한다.
             */
            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_unavailable
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


            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_sleep
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
             * 전원 버튼을 어두운 파란색으로 표시하고 클릭을 막는다.
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

                setPowerButtonDescription(
                        holder,
                        device,
                        R.string.device_power_identify_unavailable
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

            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_wake
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

            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_stuck
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


            setPowerButtonDescription(
                    holder,
                    device,
                    R.string.device_power_manual
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


        setPowerButtonDescription(
                holder,
                device,
                R.string.device_power_unavailable
        );
    }

    /*
     * Base Station 전원 버튼의 접근성 설명을 설정한다.
     *
     * 장치 이름과 현재 상태에 맞는 문자열 리소스를 조합한다.
     *
     * 시스템 언어에 따라 적절한 strings.xml을 사용한다.
     *
     * 이 메서드는 접근성 설명만 변경하며
     * 버튼 활성화 여부나 BLE 명령에는 영향을 주지 않는다.
     */
    private void setPowerButtonDescription(
            DeviceViewHolder holder,
            BleDeviceItem device,
            @StringRes int descriptionResId) {

        holder.buttonPower.setContentDescription(
                holder.itemView.getContext().getString(
                        descriptionResId,
                        device.getName()
                )
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

    /*
     * 특정 Base Station의 채널 변경 및
     * 후속 확인 작업 진행 여부를 조회한다.
     *
     * 다른 Base Station의 작업 상태는 영향을 주지 않는다.
     */
    public interface ChannelVerificationBusyChecker {

        boolean isChannelVerificationBusy(
                BleDeviceItem device
        );
    }
}

