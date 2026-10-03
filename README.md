# Ængine

A high-performance, multi-API capable graphics engine built in Java, running as a single self-contained process.

The engine is engineered strictly as a decoupled reusable runtime infrastructure. The structural abstraction layer between application logic and the graphics hardware backend (`RendererAPI`, `ShaderAPI`, `TextureAPI`, `BufferAPI`) guarantees absolute isolation, allowing a seamless future migration from OpenGL to Vulkan without mutating game-space code blocks.

---

## Technical Stack

| Component | Specification |
|---|---|
| **Core Language** | Java 25 (OpenJDK) |
| **Editor Interface** | Dear ImGui (transitional) — migrating to an in-house framework, see [docs/UI_FRAMEWORK_ARCHITECTURE.md](docs/UI_FRAMEWORK_ARCHITECTURE.md) |
| **Build System** | Gradle 9.1+ (Wrapper orchestrated, 3-module graph) |
| **Graphics Platform** | OpenGL 3.3 Core Profile minimum (newer versions work; Mesa/ACO optimized) via LWJGL 3.3.4 |
| **Windowing / Input** | GLFW Native Layer (Wayland & Win32 native hardware deltas) |
| **Math Engine** | JOML 1.10.5 (SIMD aligned vector transformations) |
| **Asset Decoding** | STB Image via LWJGL (Direct native heap zero-copy extraction) |

---

## Engine Architecture Subsystems

### Module Layout
The build is split into three Gradle modules with a one-way dependency graph:

| Module | Contents | Depends on |
|---|---|---|
| `:core` | Renderer, ECS, physics, audio, assets, scripting | — |
| `:ui` | **Aegis**, the in-house UI framework (`com.aengine.aegis`) | `:core` |
| `:editor` | Editor surface and application host; Dear ImGui confined here | `:core`, `:ui` |

`:core` carries no UI toolkit. The engine loop drives the interface through the
`com.aengine.core.UILayer` interface, which the editor implements today with Dear ImGui and
Aegis will implement later without touching `Engine`.

`:ui` is additionally restricted to a subset of core packages, enforced by a
`checkBoundary` task that fails the build on a disallowed import.

### Single-Process Architecture
The engine, editor and interface run inside one JVM process, sharing one address space and one object graph. There is no interprocess bridge, no serialization hop and no external UI runtime: editor panels read engine state directly.

The interface currently renders through Dear ImGui as a transitional layer, and is being replaced by Aegis, the engine's own UI framework. The design and migration plan are in [docs/UI_FRAMEWORK_ARCHITECTURE.md](docs/UI_FRAMEWORK_ARCHITECTURE.md).

### Virtual File System (VFS) & Sandboxing
All hardware asset paths are evaluated via `FileSystem.resolve()`. It enforces strict boundary sandboxing using system path normalization to prevent directory traversal vulnerabilities. Native asset allocations bypass the JVM heap, using `MemoryUtil.memAlloc` and direct `FileChannel` streams to achieve zero-copy transfers straight to the GPU driver pipelines.

### Performance Diagnostic Logger
An asynchronous, per-system structured logging engine featuring compile-time priority filtering (`TRACE` to `ERROR`). It dynamically extracts runtime stack trace contexts down to the invocation site (`FileName.java:LineNumber`) using precise frame-skipping optimizations.

### Project Wizard Infrastructure
The initial bootstrap deploys a standardized layout blueprint for game development environments. Project segregation isolates source assets from engine library binaries:

```text
ProjectRoot/
├── .aengine/               # Local cache, metadata and system descriptors
│   └── cache/
├── assets/                 
│   ├── baked/              # Comompressed development assets
│   │   ├── textures/       # Source bitmaps (.atex)
│   │   ├── shaders/        
│   │   ├── models/         
│   │   └── audio/          # Compiled audio (.aaud)
│   ├── src/
│   │   ├── textures/       # Image textures (.png, .jpg, .jpeg)
│   │   ├── models/         # Custom objects (.obj)
│   │   └── audio/          # Sound wave arrays (.wav, .ogg)
│   ├── data/
│   ├── prefabs/
│   ├── scenes/
│   └── scripts/
├── config/
│   └── project.json        # Manifest descriptor (Project Name, Version, Target API)
├── logs/                   
└── build/                  # Compiled target distribution packs
```
---

## Running the Development Workspace
Clone the repository to your workspace

* **Windows**
  ```powershell
  git clone https://github.com/EdynuT/aengine.git
  cd .\aengine
  ```

* **Linux**
  ```bash
  git clone https://github.com/EdynuT/aengine.git
  cd ./aengine
  ```

### Prerequisites

Building and running the engine requires a single toolchain:

* **Java 25 (OpenJDK)** configured in your global system environment path.

Everything else — LWJGL, GLFW, OpenGL, OpenAL and the native binaries for your platform — is resolved by the Gradle wrapper. No native compiler, no Rust toolchain and no web runtime are needed.

**Graphics hardware:** a GPU and driver that expose **OpenGL 3.3 Core Profile or newer** (GLSL `#version 330 core`). If yours does not, see [Troubleshooting: OpenGL not supported](#troubleshooting-opengl-not-supported).
### Executing the Runtime Environment
The framework dynamically switches execution pipelines at startup using JVM command-line arguments. You can pass these parameters straight through Gradle using the `--args` flag.

* **1. Standard 3D Pipeline (Default)**
  This initializes native hardware depth testing (`glEnable(GL_DEPTH_TEST)`), binds the custom isolated static VRAM geometry allocations, and deploys the infinite screen-space analytic wireframe grid.

  ```bash
  ./gradlew :editor:run
  ```
  Or

  ```bash
  ./gradlew :editor:run --args="--3d"
  ```

* **2. Hybrid Core 2D Perspective Pipeline**
    Spawns the application inside the multi-API agnostic 2D batching renderer ecosystem. Optimal for flat sprites, UI layouts, and standard 2D ECS validation layouts.

  ```bash
  ./gradlew :editor:run --args="--2d"
  ```

---

## Troubleshooting: OpenGL not supported

The engine requests an **OpenGL 3.3 Core** context. When the CPU/GPU or its driver cannot provide one, the window never opens and the log shows one of:

| Log line | Meaning |
|---|---|
| `GLFW Error [0x10006]: WGL: The driver does not appear to support OpenGL` | No usable OpenGL driver is loaded: missing, broken, or too old for the OS. |
| `GLFW Error [0x10007]: ... version ... is not available` | A driver exists but tops out below OpenGL 3.3. |
| `Critical: Window creation rejected by display server.` | Follows either error above. |

A process that exits with `NullPointerException ... physicsThread is null` right after these lines is a consequence of the failed window, not a separate problem.

### 1. Check what your machine supports

* **Windows:** run `dxdiag`, or in PowerShell:
  ```powershell
  Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion, DriverDate
  ```
* **Linux:** install `mesa-utils` (Debian/Ubuntu) or `mesa-demos` (Fedora/Arch), then:
  ```bash
  glxinfo -B | grep -E "OpenGL renderer|OpenGL core profile version"
  ```
  If the renderer reads `llvmpipe` or `softpipe` on a machine with a real GPU, the GPU driver is not being used (see the Linux notes below).

Typical cases that fall short of 3.3 on Windows: Intel HD Graphics 2000/3000 (Sandy Bridge, OpenGL 3.1 maximum), GPUs with no driver for the installed Windows version, and virtual machines or remote sessions without GPU acceleration.

### 2. Try the supported fixes first

1. Install the latest driver from the GPU vendor (Intel, NVIDIA, AMD), not the generic Windows one.
2. Run on the physical console, not a Remote Desktop session or a VM without GPU passthrough.
3. If the GPU is Sandy Bridge or older, no driver update can provide OpenGL 3.3. Use another machine or the software renderer below.

**Linux notes**

* **Install the Mesa DRI drivers.** Without them GLFW falls back to software or fails to create a context: `libgl1-mesa-dri` (Debian/Ubuntu), `mesa-dri-drivers` (Fedora), `mesa` (Arch). Intel Ivy Bridge and newer, and AMD GCN and newer, are supported by Mesa out of the box.
* **NVIDIA:** use the proprietary driver from your distribution (`nvidia-driver`, `akmod-nvidia`, `nvidia-utils`); the open-source `nouveau` driver may not reach 3.3 on older cards. Reboot after installing.
* **Hybrid graphics (laptops):** the integrated GPU may be chosen by default. Offload to the discrete one:
  ```bash
  # AMD / Intel discrete
  DRI_PRIME=1 ./gradlew :editor:run
  # NVIDIA
  __NV_PRIME_RENDER_OFFLOAD=1 __GLX_VENDOR_LIBRARY_NAME=nvidia ./gradlew :editor:run
  ```
* **Virtual machines:** enable 3D acceleration in the hypervisor settings (VirtualBox: VMSVGA with *Enable 3D Acceleration*; VMware: *Accelerate 3D graphics*; QEMU/KVM: `virtio-gpu` with `virgl`). Without it the guest only has software rendering.
* **WSL2:** needs WSLg and a GPU driver with WSL support on the Windows host. If no GPU is exposed, use the software renderer below.
* **Wayland and X11:** GLFW picks the platform automatically. If a context fails on Wayland, test again on an X11 session before assuming a driver problem.
* **Do not use `MESA_GL_VERSION_OVERRIDE`** to fake 3.3 on hardware that lacks it: context creation may succeed, but shaders will fail to compile or render incorrectly.

### 3. Fallback: Mesa software renderer (llvmpipe)

Mesa implements OpenGL 4.6 on the CPU. It needs no GPU support, but it is slow: expect single-digit to a few dozen FPS on an older dual-core CPU. Use it to run and develop, not to judge performance.

**Linux**

```bash
LIBGL_ALWAYS_SOFTWARE=1 ./gradlew :editor:run
```

If that is not enough, force the llvmpipe driver explicitly:

```bash
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./gradlew :editor:run
```

Confirm with `glxinfo -B`: the renderer must read `llvmpipe` and the core profile version must be 3.3 or higher. Mesa comes from the distribution packages above, so no extra download is needed.

**Windows** — Windows loads `opengl32.dll` from the folder of the `java.exe` that is running before the system one, so the Mesa DLLs only have to sit next to it.

1. Download the latest `mesa3d-<version>-release-msvc.7z` from the [mesa-dist-win releases](https://github.com/pal1000/mesa-dist-win/releases) and extract it (Windows 10/11 `tar -xf file.7z` works, or use 7-Zip).
2. From the extracted **`x64`** folder take `opengl32.dll` and `libgallium_wgl.dll`.
3. Put them next to `java.exe` of the JDK that runs the engine. Pick one:
   * **With administrator rights:** copy both files into the `bin` folder of your JDK 25 (for example `C:\Program Files\Java\jdk-25\bin`). To undo, delete them.
   * **Without administrator rights:** copy the whole JDK folder to a user folder (for example `%USERPROFILE%\jdk25-mesa`), then copy the two DLLs into its `bin` folder. Create `%USERPROFILE%\mesa-run.init.gradle` containing:
     ```groovy
     allprojects { tasks.withType(JavaExec).configureEach { executable = 'C:/Users/<you>/jdk25-mesa/bin/java.exe' } }
     ```
     and start the engine with:
     ```powershell
     .\gradlew.bat :editor:run -I "$env:USERPROFILE\mesa-run.init.gradle"
     ```
4. Confirm it worked. The startup log should show:
   ```text
   Graphics Accelerator  : llvmpipe (LLVM ..., 256 bits)
   OpenGL Driver Scope   : 4.6 (Core Profile) Mesa ...
   ```

The line `MESA: error: ZINK: vkCreateInstance failed (VK_ERROR_INCOMPATIBLE_DRIVER)` is harmless: Mesa tried Vulkan first and fell back to llvmpipe.

`gradlew.bat` uses the JDK from the `JAVA_HOME` environment variable (or `java` on the `PATH` when it is unset), so it must point to a JDK 25 installation. The init script above only changes which `java.exe` runs the engine; Gradle itself still starts from `JAVA_HOME`.
