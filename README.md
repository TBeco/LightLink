# LightLink

A simple Android BLE power manager for SteamVR Base Station 2.0.

LightLink is an Android app for discovering SteamVR Base Station 2.0 devices
over Bluetooth Low Energy (BLE), checking their current power state,
and controlling Wake / Sleep states.

SteamVR Base Station 2.0을 Bluetooth Low Energy(BLE)로 검색하고,
현재 전원 상태를 확인하거나 Wake / Sleep 상태를 제어할 수 있는 Android 앱입니다.


## Features

- Discover nearby Base Station 2.0 devices
- Read the current power state of each base station
- Wake / Sleep individual base stations
- Control multiple base stations independently
- Manual search and search stop
- Automatic retry for transient GATT connection failures
- Verify the actual power state after sending a power command
- Display the last successful state check time for each device


## 주요 기능

- 주변 Base Station 2.0 검색
- 각 Base Station의 현재 전원 상태 확인
- 개별 Base Station Wake / Sleep 제어
- 여러 Base Station 독립 제어
- 수동 검색 및 검색 중지
- 일시적인 GATT 연결 실패 시 자동 재시도
- 전원 명령 전송 후 실제 상태 확인
- 각 장치의 마지막 상태 확인 시간 표시


## Supported Devices

- SteamVR Base Station 2.0

Base Station 1.0 is currently not supported.

현재 Base Station 2.0을 대상으로 개발 및 테스트하고 있으며,
Base Station 1.0은 지원하지 않습니다.


## Requirements

- Android 8.0 (API 26) or later
- Bluetooth Low Energy (BLE)
- Bluetooth and location permissions required for device discovery

- Android 8.0 (API 26) 이상
- Bluetooth Low Energy(BLE) 지원
- 장치 검색을 위한 Bluetooth 및 위치 권한 필요


## Development Status

LightLink is currently under development and testing.

BLE behavior and device compatibility are being tested on multiple Android devices.

현재 개발 및 테스트 중입니다.

여러 Android 기기에서 BLE 검색, 상태 확인 및 전원 제어 동작을 테스트하고 있습니다.


## Privacy

LightLink does not require an account, server, or cloud connection.

Base Station control is performed locally over Bluetooth Low Energy.

LightLink는 계정, 서버 또는 클라우드 연결을 필요로 하지 않으며,
Base Station 제어는 로컬 Bluetooth Low Energy 연결을 통해 이루어집니다.