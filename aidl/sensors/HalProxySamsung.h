/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <HalProxyAidl.h>
#include <V2_1/SubHal.h>

#include <atomic>

namespace aidl {
namespace android {
namespace hardware {
namespace sensors {
namespace implementation {

class HalProxySamsung : public HalProxyAidl {
  public:
    HalProxySamsung();

  private:
    ndk::ScopedAStatus injectSensorData(
            const ::aidl::android::hardware::sensors::Event& in_event) override;

    ndk::ScopedAStatus getSensorsList(
            std::vector<::aidl::android::hardware::sensors::SensorInfo>* _aidl_return) override;

    void postEventsToMessageQueue(
            const std::vector<Event>& events, size_t numWakeupEvents,
            ::android::hardware::sensors::V2_0::implementation::ScopedWakelock wakelock) override;

    bool mHideDeviceOrientation = false;
    int32_t mFoldingStateHandle = -1;
    std::atomic<bool> mFolded = false;
    int32_t mSensorHubsHandle = -1;
    ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal* mSensorHubsSubHal = nullptr;
};

}  // namespace implementation
}  // namespace sensors
}  // namespace hardware
}  // namespace android
}  // namespace aidl
