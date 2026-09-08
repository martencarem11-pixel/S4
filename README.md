# Spatial 3D & MR/AR Android Application

An advanced Android application built with **Jetpack Compose**, **Google Filament 3D Engine**, **CameraX**, and **OpenGL ES 2.0** for rendering interactive 3D exhibits in **3D Object Mode**, **Augmented Reality (AR)**, and **Mixed Reality (MR / Stereoscopic Dual-Camera Headset Mode)**.

---

## 🚀 Features

- **3 Display Modes**:
  - **3D Object Mode**: Interactive orbital inspection of 3D glTF/GLB models with real-time lighting, rotation, and zoom.
  - **AR Mode**: Real-time camera passthrough overlay rendering models placed in the real world with device orientation tracking.
  - **MR (Mixed Reality) Mode**: Stereoscopic split-screen rendering designed for VR/MR headsets:
    - Hardware-accelerated **Dual-Camera Passthrough** duplicating real-time camera feeds for Left and Right eyes.
    - True stereoscopic dual-viewport 3D rendering with calculated Interpupillary Distance (IPD).
    - Clean viewport divider without visual distractions.

- **3D Engine (Google Filament)**:
  - Physically Based Rendering (PBR).
  - Clean memory management and dynamic asset loading (`.glb`).
  - Transparent overlay rendering (`PixelFormat.TRANSLUCENT`).
  - Reset & Clear scene capabilities.

- **Modern Android Architecture**:
  - **Jetpack Compose** & **Material Design 3**.
  - **Kotlin Coroutines & Flow**.
  - **CameraX** with hardware-accelerated GL surface delivery.
  - Sensor fusion fallback (Accelerometer & Gyroscope) for orientation tracking.

---

## 🛠️ Requirements & Building

- **Android Studio**: Ladybug / Koala or newer recommended
- **Compile SDK**: 35
- **Min SDK**: 26 (Android 8.0+)
- **Target SDK**: 35
- **Gradle Version**: 8.9+
- **JDK**: Java 17 or Java 21

### How to Run in Android Studio:
1. Clone this repository or open the unzipped directory in **Android Studio**.
2. Let Gradle sync dependencies.
3. Connect a physical Android device (recommended for camera and sensors) or start an emulator with camera emulation enabled.
4. Click **Run** (`Shift + F10`).

---

## 🧪 Testing

Run JVM unit and Robolectric tests:
```bash
./gradlew :app:testDebugUnitTest
```
