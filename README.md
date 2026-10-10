# LightLink

**A simple Android BLE power manager for SteamVR Base Station 2.0.**

**Current Version:** `v0.6.0-beta.1`
**Development Status:** Beta

LightLink is an Android application for discovering SteamVR Base Station 2.0 devices over Bluetooth Low Energy (BLE), checking their current power state, and controlling Wake / Sleep states.

LightLink는 Bluetooth Low Energy(BLE)를 이용하여 SteamVR Base Station 2.0을 검색하고, 전원 상태를 확인하거나 Wake / Sleep 상태를 제어할 수 있는 Android 앱입니다.

## Why LightLink?

LightLink started as a personal project to address device discovery issues I experienced while using an existing Base Station power management application.

Some Base Stations were occasionally missing from scan results, even after searching again.

I developed LightLink to improve device discovery reliability and provide a simple way to manage multiple Base Stations from an Android device.

The initial discovery issue has been improved in my own environment, and further testing is ongoing to evaluate compatibility and reliability across different Android devices and Base Station firmware versions.

### 개발 배경

LightLink는 기존 Base Station 전원 관리 앱을 사용하면서 경험했던 장치 검색 문제를 해결하기 위해 시작한 개인 프로젝트입니다.

일부 Base Station이 검색되지 않거나, 다시 검색해도 나타나지 않는 문제가 간헐적으로 발생했습니다.

이러한 검색 불안정성을 개선하고 여러 Base Station을 Android 기기에서 간편하게 관리하기 위해 LightLink를 개발했습니다.

현재 개인 사용 환경에서는 초기 검색 문제가 개선되었으며, 다양한 Android 기기와 Base Station 펌웨어에서의 호환성 및 안정성을 계속 검증하고 있습니다.

---

## Features

* Discover nearby SteamVR Base Station 2.0 devices
* Read the current power state of supported base stations
* Wake or Sleep base stations individually
* Control multiple base stations independently
* Manually start and stop device discovery
* Display RF channel and Bluetooth signal strength (RSSI)
* Display the last successful power state check time
* Automatically retry temporary Bluetooth connection failures
* Verify the actual power state after Wake / Sleep commands when supported
* Provide manual Wake / Sleep control for some older firmware versions that do not support Power State READ

### 주요 기능

* 주변 SteamVR Base Station 2.0 검색
* Power State READ를 지원하는 Base Station의 현재 전원 상태 확인
* Base Station별 Wake / Sleep 제어
* 여러 Base Station 독립 제어
* 장치 검색 시작 및 중지
* RF 채널 및 Bluetooth 신호 강도(RSSI) 표시
* 마지막 전원 상태 확인 성공 시간 표시
* 일시적인 Bluetooth 연결 실패 시 자동 재시도
* Wake / Sleep 명령 후 지원되는 장치에서는 실제 전원 상태 재확인
* Power State READ를 지원하지 않는 일부 이전 펌웨어에서 수동 Wake / Sleep 제어 지원

---

## Supported Devices

* **SteamVR Base Station 2.0**

Base Station 1.0 is currently not supported.

LightLink is developed and tested primarily for SteamVR Base Station 2.0. Compatibility may vary depending on the Base Station firmware version.

현재 LightLink는 SteamVR Base Station 2.0을 대상으로 개발 및 테스트하고 있습니다.

Base Station 1.0은 지원하지 않으며, Base Station 펌웨어 버전에 따라 일부 기능의 지원 여부가 달라질 수 있습니다.

---

## Requirements

* Android 8.0 (API 26) or later
* Bluetooth Low Energy (BLE) support
* Appropriate Bluetooth permissions for device discovery and connection
* Location permission where required by the Android version

### 실행 환경

* Android 8.0 (API 26) 이상
* Bluetooth Low Energy(BLE) 지원
* 장치 검색 및 연결을 위한 Bluetooth 권한
* Android 버전에 따라 필요한 위치 권한

---

## Tested Android Devices

LightLink has currently been tested on the following Android devices:

* Samsung Galaxy S9
* Samsung Galaxy A51 5G
* Samsung Galaxy S23 Ultra
* Samsung Galaxy S25 Edge
* Samsung Galaxy Z Flip6
* Samsung Galaxy Z Fold2
* Samsung Galaxy Z Fold4
* Samsung Galaxy Z Fold8

Testing on these devices does not mean that LightLink is limited to them.

Compatibility with other Android devices may vary depending on the Bluetooth implementation, Android version, and device manufacturer.

### 테스트한 Android 기기

위 Android 기기에서 LightLink의 동작을 테스트했습니다.

해당 기기에서 테스트했다는 것이 다른 Android 기기를 지원하지 않는다는 의미는 아닙니다.

Android 버전, 제조사 및 Bluetooth 구현 방식에 따라 동작에 차이가 있을 수 있습니다.

---

## Development Status

LightLink is currently in beta development.

The core features, including Base Station discovery, power state reading, and individual Wake / Sleep control, have been implemented.

The initial device discovery issue that motivated this project has been improved in the developer's testing environment.

Further testing is ongoing to evaluate reliability and compatibility across different Android devices and Base Station firmware versions.

Some older Base Station firmware versions may not support Power State READ.

In these cases, LightLink can provide manual Wake / Sleep control when the required BLE write capability is available.

**Note:** When Power State READ is unavailable, LightLink cannot independently verify the actual power state after sending a command.

### 개발 상태

LightLink는 현재 베타 개발 단계입니다.

Base Station 검색, 전원 상태 확인, 개별 Wake / Sleep 제어 등 핵심 기능이 구현되어 있습니다.

개발을 시작하게 된 원인인 장치 검색 문제는 개발자의 사용 환경에서 개선된 상태입니다.

현재는 다양한 Android 기기와 Base Station 펌웨어 환경에서의 안정성 및 호환성을 계속 검증하고 있습니다.

일부 이전 Base Station 펌웨어에서는 Power State READ를 지원하지 않을 수 있습니다.

이 경우 필요한 BLE Write 기능을 사용할 수 있다면 수동 Wake / Sleep 제어를 사용할 수 있습니다.

**참고:** Power State READ를 지원하지 않는 장치에서는 명령 전송 후 실제 전원 상태를 독립적으로 확인할 수 없습니다.

---

## Privacy

LightLink does not require an account, server, or cloud connection.

Base Station discovery and power control are performed locally over Bluetooth Low Energy (BLE).

### 개인정보

LightLink는 계정, 서버 또는 클라우드 연결을 필요로 하지 않습니다.

Base Station 검색 및 전원 제어는 로컬 Bluetooth Low Energy(BLE) 통신을 통해 수행됩니다.

---

## Feedback

LightLink is a personal project currently undergoing beta testing.

Bug reports, compatibility feedback, and suggestions are welcome through GitHub Issues.

When reporting an issue, please include the Android device model, Android version, and Base Station firmware version if available.

### 피드백

LightLink는 현재 베타 테스트를 진행 중인 개인 프로젝트입니다.

버그 제보, 호환성 문제 및 개선 제안은 GitHub Issues를 통해 전달할 수 있습니다.

문제를 제보할 때 Android 기기 모델, Android 버전, Base Station 펌웨어 버전을 함께 알려주시면 문제를 분석하는 데 도움이 됩니다.

---

## Disclaimer

LightLink is an independent personal project and is not affiliated with or endorsed by Valve Corporation.

LightLink는 Valve Corporation과 관계없는 독립적인 개인 개발 프로젝트이며, Valve의 공식 앱이 아닙니다.
