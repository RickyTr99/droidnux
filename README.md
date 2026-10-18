# DroidNux

DroidNux runs a real Linux desktop on Android, without root. It is built for everyday use with a keyboard and a trackpad, first on tablets and then on phones.

> [!WARNING]
> DroidNux is in early development. Nothing here is usable yet.

## Goals

- **From install to desktop without opening a terminal.**
- **Performance first:** GPU-accelerated, zero-copy rendering with Mesa Turnip and Zink on Adreno.
- **Low battery use:** frames are drawn only when something changes, paced on the display's vsync.
- **A clean, modern look:** a Material 3 app and a matching desktop theme.
- **Light:** a small APK; distributions and components are downloaded on demand.

## How it works

- **Container:** a standard distribution (Debian first) runs under [proot](https://github.com/termux/proot), with no root needed.
- **Display:** a native presentation core shares GPU buffers with the container. X11 is the first protocol front-end, built from [Termux:X11](https://github.com/termux/termux-x11). Wayland with Xwayland is the planned next one.
- **Audio:** PipeWire in the container, played through AAudio.

## Building

Requirements: JDK 21 and the Android SDK. Gradle installs the NDK and CMake versions listed in `gradle/libs.versions.toml` if they are missing.

```sh
git clone --recurse-submodules https://github.com/RickyTr99/droidnux.git
cd droidnux
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Only `arm64-v8a` devices are supported.

## Credits

- [proot](https://github.com/termux/proot) (Termux fork), GPL-2.0
- [talloc](https://talloc.samba.org/), LGPL-3.0
- [Termux:X11](https://github.com/termux/termux-x11), the base of the X11 front-end
- [Mesa](https://mesa3d.org/): Turnip and Zink
- [Debian](https://www.debian.org/) root filesystems from [debuerreotype](https://github.com/debuerreotype/docker-debian-artifacts)

## License

DroidNux is licensed under the [GPL-3.0](LICENSE).
