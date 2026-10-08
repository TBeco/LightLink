# LightLink

A simple Android BLE power manager for SteamVR Base Station 2.0.

LightLink is an Android app for discovering SteamVR Base Station 2.0 devices
over Bluetooth Low Energy (BLE), checking their current power state,
and controlling Wake / Sleep states.

SteamVR Base Station 2.0을 Bluetooth Low Energy(BLE)로 검색하고,
현재 전원 상태를 확인하거나 Wake / Sleep 상태를 제어할 수 있는 Android 앱입니다.


## Features

- Discover nearby Base Station 2.0 devices
- Read the current power state of supported base stations
- Wake / Sleep individual base stations
- Control multiple base stations independently
- Manual Wake / Sleep control for base stations where Power State READ is unavailable, which may occur on some older Base Station firmware versions
- Manual search and search stop
- Automatic retry for transient GATT connection failures
- Verify the actual power state after sending a power command when Power State READ is supported
- Display the last successful state check time for each device


## 주요 기능

- 주변 Base Station 2.0 검색
- Power State READ를 지원하는 Base Station의 현재 전원 상태 확인
- 개별 Base Station Wake / Sleep 제어
- 여러 Base Station 독립 제어
- 일부 이전 Base Station 펌웨어 버전에서 발생할 수 있는 Power State READ 미지원 장치의 수동 Wake / Sleep 제어
- 수동 검색 및 검색 중지
- 일시적인 GATT 연결 실패 시 자동 재시도
- Power State READ를 지원하는 장치에서 전원 명령 전송 후 실제 상태 확인
- 각 장치의 마지막 상태 확인 시간 표시


## Supported Devices

- SteamVR Base Station 2.0

Base Station 1.0 is currently not supported.

LightLink is currently developed and tested for SteamVR Base Station 2.0.
Base Station 1.0 is not supported at this time.

현재 LightLink는 SteamVR Base Station 2.0을 대상으로 개발 및 테스트하고 있습니다.
Base Station 1.0은 현재 지원하지 않습니다.


## Requirements

- Android 8.0 (API 26) or later
- Bluetooth Low Energy (BLE)
- Bluetooth and location permissions required for device discovery

- Android 8.0 (API 26) 이상
- Bluetooth Low Energy(BLE) 지원
- 장치 검색을 위한 Bluetooth 및 위치 권한 필요


## Tested Android Devices

LightLink has currently been tested on the following Android devices:

- Samsung Galaxy S9
- Samsung Galaxy Z Fold2
- Samsung Galaxy Z Fold4
- Samsung Galaxy Z Fold8

Testing on these devices does not mean that LightLink is limited to them.
Compatibility with other Android devices may vary depending on the Bluetooth implementation and Android version.

현재 LightLink는 다음 Android 기기에서 테스트되었습니다.

- Samsung Galaxy S9
- Samsung Galaxy Z Fold2
- Samsung Galaxy Z Fold4
- Samsung Galaxy Z Fold8

위 기기에서 동작을 확인했지만,
LightLink가 해당 기기에서만 동작한다는 의미는 아닙니다.

다른 Android 기기에서는 Bluetooth 구현 및 Android 버전에 따라
동작 차이가 있을 수 있습니다.


## Development Status

LightLink is currently in beta development and testing.

Base Station discovery, power state reading, Wake / Sleep control,
and compatibility with different Base Station firmware versions are still being tested.

Some older Base Station firmware versions may not support Power State READ.
In these cases, LightLink can provide manual Wake / Sleep control when the required BLE write capability is available.

현재 LightLink는 베타 개발 및 테스트 단계입니다.

Base Station 검색, 전원 상태 확인, Wake / Sleep 제어,
그리고 서로 다른 Base Station 펌웨어 버전과의 호환성을 계속 테스트하고 있습니다.

일부 이전 Base Station 펌웨어 버전에서는 Power State READ가 지원되지 않을 수 있습니다.
이 경우 필요한 BLE Write 기능을 사용할 수 있다면
LightLink에서 수동 Wake / Sleep 제어를 사용할 수 있습니다.


## Privacy

LightLink does not require an account, server, or cloud connection.

Base Station control is performed locally over Bluetooth Low Energy.

LightLink는 계정, 서버 또는 클라우드 연결을 필요로 하지 않으며,
Base Station 제어는 로컬 Bluetooth Low Energy 연결을 통해 이루어집니다.