/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include "HalProxySamsung.h"

#include <ConvertUtils.h>
#include <convertV2_1.h>
#include <dlfcn.h>
#include <sensors.sysprop.h>

#include <fstream>

// #define VERBOSE

using namespace ::android::sensors::samsung;

namespace aidl {
namespace android {
namespace hardware {
namespace sensors {
namespace implementation {

using ::aidl::android::hardware::sensors::AdditionalInfo;
using ::android::hardware::sensors::V2_0::implementation::ScopedWakelock;
using ::android::hardware::sensors::V2_1::SensorType;
using ::android::hardware::sensors::V2_1::implementation::convertToOldEvent;
using ::android::hardware::sensors::V2_1::implementation::ISensorsSubHal;

namespace {

constexpr char kSensorHubsType[] = "com.samsung.sensor.sensorhubs";

ISensorsSubHal* findSubHalForSensorType(const std::string& type) {
    for (const char* config : {"/vendor/etc/sensors/hals.conf", "/odm/etc/sensors/hals.conf"}) {
        std::ifstream stream(config);
        std::string library;
        while (stream >> library) {
            void* handle = nullptr;
            for (const std::string& dir : {"", "/vendor/lib64/hw/", "/odm/lib64/hw/"}) {
                handle = dlopen((dir + library).c_str(), RTLD_NOW | RTLD_NOLOAD);
                if (handle != nullptr) break;
            }
            if (handle == nullptr) continue;

            auto getSubHal = reinterpret_cast<decltype(&sensorsHalGetSubHal_2_1)>(
                    dlsym(handle, "sensorsHalGetSubHal_2_1"));
            if (getSubHal == nullptr) continue;

            uint32_t version;
            ISensorsSubHal* subHal = getSubHal(&version);
            bool found = false;
            subHal->getSensorsList_2_1([&](const auto& sensors) {
                for (const auto& sensor : sensors) {
                    if (sensor.typeAsString == type) found = true;
                }
            });
            if (found) return subHal;
        }
    }
    return nullptr;
}

}  // namespace

HalProxySamsung::HalProxySamsung() {
    using Workaround = SensorsHalProperties::device_orientation_workaround_values;
    auto workaround = SensorsHalProperties::device_orientation_workaround();
    if (workaround == Workaround::HIDE) {
        mHideDeviceOrientation = true;
    } else if (workaround == Workaround::SWAP_UPRIGHT_WHEN_FOLDED) {
        for (const auto& [handle, sensor] : getSensors()) {
            if (sensor.typeAsString == "com.samsung.sensor.folding_state") {
                mFoldingStateHandle = handle;
            }
        }
    }

    for (const auto& [handle, sensor] : getSensors()) {
        if (sensor.typeAsString == kSensorHubsType) {
            mSensorHubsHandle = handle;
            mSensorHubsSubHal = findSubHalForSensorType(kSensorHubsType);
        }
    }
}

void HalProxySamsung::postEventsToMessageQueue(const std::vector<Event>& events,
                                               size_t numWakeupEvents, ScopedWakelock wakelock) {
    if (mFoldingStateHandle < 0) {
        HalProxy::postEventsToMessageQueue(events, numWakeupEvents, std::move(wakelock));
        return;
    }

    for (const Event& event : events) {
        if (event.sensorHandle == mFoldingStateHandle) mFolded = event.u.scalar < 1.0f;
    }

    if (!mFolded) {
        HalProxy::postEventsToMessageQueue(events, numWakeupEvents, std::move(wakelock));
        return;
    }

    std::vector<Event> remapped(events);
    for (Event& event : remapped) {
        if (event.sensorType == SensorType::DEVICE_ORIENTATION) {
            // The cover screen faces the other way, so upright and upside down are swapped.
            event.u.scalar = (6 - static_cast<int>(event.u.scalar)) % 4;
        }
    }
    HalProxy::postEventsToMessageQueue(remapped, numWakeupEvents, std::move(wakelock));
}

/*
 * The vendor HAL passes raw context bytes (screen state etc.) on to the sensor hub
 * algorithms when it receives an event of the sensorhubs type, which HalProxy only
 * accepts in data injection mode. Accept them as a custom operation parameter
 * instead: SensorManager#setOperationParameter with floats {length, bytes...}.
 */
ndk::ScopedAStatus HalProxySamsung::injectSensorData(
        const ::aidl::android::hardware::sensors::Event& in_event) {
    using AidlEvent = ::aidl::android::hardware::sensors::Event;
    using AidlSensorType = ::aidl::android::hardware::sensors::SensorType;

    if (in_event.sensorHandle != mSensorHubsHandle || mSensorHubsSubHal == nullptr ||
        in_event.sensorType != AidlSensorType::ADDITIONAL_INFO) {
        Event hidlEvent;
        convertToHidlEvent(in_event, &hidlEvent);
        if (HalProxy::injectSensorData(convertToOldEvent(hidlEvent)) != Result::OK) {
            return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        }
        return ndk::ScopedAStatus::ok();
    }

    const auto& info = in_event.payload.get<AidlEvent::EventPayload::additional>();
    if (info.type != AdditionalInfo::AdditionalInfoType::AINFO_CUSTOM_START) {
        // Begin and end frames around the payload.
        return ndk::ScopedAStatus::ok();
    }

    const auto& values = info.payload.get<AdditionalInfo::AdditionalInfoPayload::dataFloat>().values;
    const int32_t length = static_cast<int32_t>(values[0]);
    if (length <= 0 || length >= static_cast<int32_t>(values.size())) {
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }

    // Layout expected by the vendor HAL: int32 0 (single message), int32 length, bytes.
    Event event{};
    event.timestamp = in_event.timestamp;
    event.sensorHandle = mSensorHubsHandle & 0xFFFFFF;
    event.sensorType = getSensors().at(mSensorHubsHandle).type;
    auto* data = reinterpret_cast<int32_t*>(event.u.data.data());
    data[0] = 0;
    data[1] = length;
    auto* bytes = reinterpret_cast<uint8_t*>(&data[2]);
    for (int32_t i = 0; i < length; i++) {
        bytes[i] = static_cast<uint8_t>(values[i + 1]);
    }

    mSensorHubsSubHal->injectSensorData_2_1(event);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus HalProxySamsung::getSensorsList(
        std::vector<::aidl::android::hardware::sensors::SensorInfo>* _aidl_return) {
    for (const auto& sensor : HalProxy::getSensors()) {
        SensorInfo dst = sensor.second;

        // Lets the framework derive screen orientation from the accelerometer instead.
        if (mHideDeviceOrientation && dst.type == SensorType::DEVICE_ORIENTATION) {
            continue;
        }

        if (dst.requiredPermission == "com.samsung.permission.SSENSOR") {
            dst.requiredPermission = "";
        }

        if (dst.typeAsString == "com.samsung.sensor.physical_proximity" ||
            dst.typeAsString == "com.samsung.sensor.hover_proximity") {
            ALOGI("Fixing %s", dst.typeAsString.c_str());
            dst.type = ::android::hardware::sensors::V2_1::SensorType::PROXIMITY;
            dst.typeAsString = SENSOR_STRING_TYPE_PROXIMITY;
            dst.maxRange = 1;
        }

#ifdef VERBOSE
        ALOGI("SENSOR NAME:%s           ", dst.name.c_str());
        ALOGI("       VENDOR:%s         ", dst.name.c_str());
        ALOGI("       TYPE:%d           ", (uint32_t)dst.type);
        ALOGI("       TYPE_AS_STRING:%s ", dst.typeAsString.c_str());
#endif

        _aidl_return->push_back(convertSensorInfo(dst));
    }

    return ndk::ScopedAStatus::ok();
}

}  // namespace implementation
}  // namespace sensors
}  // namespace hardware
}  // namespace android
}  // namespace aidl
