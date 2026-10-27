# <img src="assets/droidnux.svg" width="48" alt="" align="top"> DroidNux

DroidNux runs a real Linux desktop on Android, without root: KDE Plasma on Debian, with the GPU, sound and video
decoding of the device. It is built for everyday use with a keyboard and a trackpad, first on tablets and then on
phones.

> [!WARNING]
> DroidNux is in early development and has no release yet. It is tested on one device (Galaxy Tab S9+).

## What works

- **Plasma 6.3 on Wayland**, set up from the app: a first-run wizard installs Debian 13, Plasma, KDE's apps and the
  GPU drivers without a terminal.
- **GPU acceleration:** Vulkan (Turnip) and OpenGL (Zink) on Adreno, with zero-copy presentation of the desktop.
  Firefox renders with WebRender on the GPU; scrolling and animations run at 120 fps.
- **Everyday apps:** Firefox, LibreOffice, GIMP, Inkscape, Thunderbird, VS Code, KDE's apps. X11 apps run
  through Xwayland.
- **Sound in and out** through Android (PipeWire in the container, AAudio in the app), with Android's volume keys and
  audio focus.
- **Hardware video decoding** for mpv and ffmpeg (VA-API on Android's MediaCodec).
- **Integration:** shared clipboard, the device's storage in the home folder, Linux apps on Android's launcher,
  Android's keyboard in Linux text fields, touch, mouse, physical keyboard and pointer capture.

## Requirements

- Android 10 or later, arm64, with a **Snapdragon (Adreno) GPU**.
- Kernel 5.10 or later.
- A few GB of free space: the base install takes about 2 GB.
- In Android's developer options, "Disable child process restrictions": without it, Android stops the desktop once it
  runs more than 32 processes. The app checks it.

## How it works

- **Container:** Burrow, DroidNux's own runtime, runs an unmodified Debian arm64. Every program runs with a seccomp
  filter that traps the system calls touching paths and users, and a signal handler emulates them inside the same
  process: no ptrace, no root, and the guest's own sandboxes (Firefox, Chromium) keep working.
- **Display:** a native Wayland compositor in the app hosts Plasma's KWin, nested. GPU clients render into Android
  hardware buffers that KWin composes and Android shows as they are, on the display's vsync.
- **GPU:** Mesa with DroidNux's patches (Turnip on Qualcomm's KGSL, Zink, GBM), shipped as a separate component.
- **Sound:** a PipeWire module in the container sends audio to the app, which plays it with AAudio.

## Building

Requirements: JDK 21 and the Android SDK. Gradle installs the NDK and CMake versions listed in
`gradle/libs.versions.toml` if they are missing.

```sh
git clone --recurse-submodules https://github.com/RickyTr99/droidnux.git
cd droidnux
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Only `arm64-v8a` is supported. The Mesa and Plasma
components are built by CI from `guest/` and downloaded by the app.

## Credits

- [Mesa](https://mesa3d.org/): Turnip and Zink
- [Wayland](https://wayland.freedesktop.org/), [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols),
  [plasma-wayland-protocols](https://invent.kde.org/libraries/plasma-wayland-protocols) and
  [wlr-protocols](https://gitlab.freedesktop.org/wlroots/wlr-protocols)
- [libffi](https://sourceware.org/libffi/), MIT
- [PipeWire](https://pipewire.org/) headers, MIT
- [KDE Plasma](https://kde.org/plasma-desktop/) and KDE Frameworks, patched in the Plasma component
- [Debian](https://www.debian.org/) root filesystems from [debuerreotype](https://github.com/debuerreotype/docker-debian-artifacts)

## License

DroidNux is licensed under the [GPL-3.0](LICENSE).

The Android robot in DroidNux's icon is reproduced or modified from work created and shared by Google and used
according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
