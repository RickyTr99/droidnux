<p align="center">
  <img src=".github/banner.png" alt="DroidNux" width="640">
</p>

DroidNux runs a real Linux desktop on Android, without root: KDE Plasma on Debian, with the GPU, sound and video
decoding of the device. It is built for everyday use with a keyboard and a trackpad, on tablets first and then on
phones.

> [!WARNING]
> DroidNux is in early development and has no release yet. It is tested on a Galaxy Tab S9+ and a Galaxy S26 Ultra.

<p align="center">
  <img src=".github/screenshots/desktop.png" alt="Plasma with Dolphin, System Monitor and Konsole" width="100%">
</p>

## What you get

**A desktop set up from the app**
- Plasma 6.3 on Wayland on Debian 13, installed by a first-run wizard with KDE's apps and the GPU drivers: no
  terminal needed.
- A Material 3 home screen with your favorite apps, an app store with Debian's catalog (about 2,000 desktop apps),
  VS Code and command-line tools, and settings for the screen, keyboard, account and components.
- Linux apps on Android's launcher, opened straight into the desktop.

**Fast on the GPU**
- Vulkan (Turnip) and OpenGL (Zink) on Adreno, with zero-copy presentation: KWin renders into Android hardware
  buffers that the display shows as they are. Scrolling and animations run at 120 fps.
- Firefox renders with WebRender on the GPU; mpv and ffmpeg decode video in hardware (VA-API on Android's MediaCodec).

**Everyday apps**
- Firefox, LibreOffice, GIMP, Inkscape, Thunderbird, VS Code and KDE's apps, with their own sandboxes working.
  X11 apps run through Xwayland.
- Flatpak apps from Flathub, in their sandbox (rendered in software for now).

**Made for touch, keyboard and trackpad**
- An in-session panel (Back, or Ctrl+Alt+Space): keyboard, trackpad, screenshots, statistics, brightness, volume,
  resolution and scale, pause and end.
- A full PC keyboard on screen, with Esc, function keys, Ctrl, Alt and Super, beside Android's keyboard in Linux text
  fields.
- Touch, mouse, physical keyboard with system keys, and the trackpad with pointer capture.

**Integrated with Android**
- Sound in and out through Android (PipeWire in the container, AAudio in the app), with the volume keys and audio
  focus.
- Shared clipboard, the device's storage in the home folder, Android's language and time zone, the battery and
  network in Plasma.

## Screenshots

<p align="center">
  <img src=".github/screenshots/home.png" alt="Home: the desktop, favorites and all apps" width="49%">
  <img src=".github/screenshots/store.png" alt="Store: featured apps and categories" width="49%">
</p>
<p align="center">
  <img src=".github/screenshots/session-menu.png" alt="The in-session panel" width="49%">
  <img src=".github/screenshots/keyboard.png" alt="The PC keyboard over the desktop" width="49%">
</p>

## Requirements

- Android 10 or later, arm64, with a **Snapdragon (Adreno) GPU**.
- Linux kernel 5.10 or later.
- A few GB of free space: the base install takes about 2 GB.
- In Android's developer options, "Disable child process restrictions": without it, Android stops the desktop once it
  runs more than 32 processes. The app checks it.

## How it works

- **Container:** [Burrow](https://github.com/RickyTr99/burrow), DroidNux's own runtime, runs an unmodified Debian
  arm64. A seccomp filter traps the system calls that touch paths and users, and a signal handler emulates them in the
  same process: no ptrace, no root, about a microsecond per call. It also emulates what bwrap's sandboxes need, so
  Flatpak works without user namespaces.
- **Display:** a native Wayland compositor in the app hosts Plasma's KWin, nested. GPU clients render into Android
  hardware buffers that KWin composes and Android presents on the display's vsync.
- **GPU:** Mesa with DroidNux's patches (Turnip on Qualcomm's KGSL, Zink, GBM), shipped as a component the app
  downloads.
- **Plasma:** a second component with patched KDE libraries: fewer file lookups, KWin passing on how the trackpad
  scrolls, and System Monitor plugins for Android.
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
- [KDE Plasma](https://kde.org/plasma-desktop/), KWin and KDE Frameworks, patched in the Plasma component
- [Wayland](https://wayland.freedesktop.org/), [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols),
  [plasma-wayland-protocols](https://invent.kde.org/libraries/plasma-wayland-protocols) and
  [wlr-protocols](https://gitlab.freedesktop.org/wlroots/wlr-protocols)
- [libffi](https://sourceware.org/libffi/), MIT
- [PipeWire](https://pipewire.org/) headers, MIT
- [Debian](https://www.debian.org/) root filesystems from [debuerreotype](https://github.com/debuerreotype/docker-debian-artifacts)
- [Quicksand](https://github.com/andrew-paglinawan/QuicksandFamily) (OFL) in the banner

## License

DroidNux is licensed under the [GPL-3.0](LICENSE).

The Android robot in DroidNux's icon is reproduced or modified from work created and shared by Google and used
according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
