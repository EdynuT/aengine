# AEngine

A high-performance, multi-API capable graphics engine built in Java and driven by a lightweight, cross-platform frontend orchestrated via Rust and Tauri v2.

The engine is engineered strictly as a decoupled reusable runtime infrastructure. The structural abstraction layer between application logic and the graphics hardware backend (`RendererAPI`, `ShaderAPI`, `TextureAPI`, `BufferAPI`) guarantees absolute isolation, allowing a seamless future migration from OpenGL to Vulkan without mutating game-space code blocks.

---

## Technical Stack

| Component | Specification |
|---|---|
| **Core Language** | Java 25 (OpenJDK) |
| **System Orchestrator** | Rust 1.80+ / Tauri v2 (Native OS Interprocess Management) |
| **Interface Frontend** | Static HTML5 / CSS3 (Grid & Flexbox) / Vanilla JS (Zero-Framework WebKit) |
| **Build System** | Gradle 9.1+ (Wrapper orchestrated) & Cargo (Rust Package Manager) |
| **Graphics Platform** | OpenGL 4.6 Core Profile (Mesa/ACO Optimized) via LWJGL 3.3.4 |
| **Windowing / Input** | GLFW Native Layer (Wayland & Win32 native hardware deltas) |
| **Math Engine** | JOML 1.10.5 (SIMD aligned vector transformations) |
| **Asset Decoding** | STB Image via LWJGL (Direct native heap zero-copy extraction) |

---

## Engine Architecture Subsystems

### Hybrid IPC Orchestration
The application architecture splits into two main layers: the Hub Launcher and the Graphics Engine Core. The Frontend Hub uses Tauri v2 (WebKitGtk on Linux / WebView2 on Windows) to manage project state, configuration, and project initialization. When a project is launched, the Rust backend spawns the high-performance Java JVM runtime as an isolated, detached background subprocess, passing target environment variables and VFS paths directly via command-line arguments.

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
│   │   └── audio/          
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
  git clone https://github.com/EdynuT/AEngine.git
  
  cd .\path\to\AEngine
  ```

* **Linux**
  ```bash
  git clone https://github.com/EdynuT/AEngine.git

  cd ./path/to/AEngine
  ```

### Prerequisites

Before launching the development workspace, ensure your target operating system has the required compilers and native web rendering runtimes installed:

#### 1. Core Languages & Toolchains

* **Java 25 (OpenJDK)** configured in your global system environment path.
* **Rust Toolchain (v1.80+)** installed via rustup:
  ```bash
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
  ```
#### 2. Native WebKit Runtimes 

* **Windows 10 / 11 (PowerShell Elevated)**
Windows requires the Microsoft Edge WebView2 runtime (usually pre-installed on Windows 11). If missing, install it along with the C++ build tools using C++ core desktop workloads via Visual Studio Installer or terminal:

  ```powershell
  winget install Microsoft.EdgeWebView2Runtime
  winget install Microsoft.VisualStudio.2022.BuildTools --override "--add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
  ```

  Install tauri-cli for inteface development
  ```powershell
  cargo install tauri-cli --version "^2.0.0"
  ```

  If you use Node
  ```powershell
  npm install -g @tauri-apps/cli@next
  ```

* **Arch Based**

  ```bash
  sudo pacman -Syu --needed base-devel webkit2gtk-4.1
  ```

  ```bash
  sudo pacman -S tauri-cli
  ```

  Or you can install via Cargo

  ```bash
  cargo install tauri-cli --version "^2.0.0"
  ```

* **Debian Based**

  ```bash
  sudo apt update
  sudo apt install -y build-essential libwebkit2gtk-4.1-dev
  ```

  ```bash
  cargo install tauri-cli --version "^2.0.0"
  ```

  NPM alternative if you use Node

  ```bash
  npm install -g @tauri-apps/cli@next
  ```

### Executing the Runtime Environment
The framework dynamically switches execution pipelines at startup using JVM command-line arguments. You can pass these parameters straight through Gradle using the `--args` flag.

* **1. Standard 3D Pipeline (Default)**
  This initializes native hardware depth testing (`glEnable(GL_DEPTH_TEST)`), binds the custom isolated static VRAM geometry allocations, and deploys the infinite screen-space analytic wireframe grid.

  ```bash
  ./gradlew run
  ```
  Or

  ```bash
  ./gradlew run --args="--3d"
  ```

* **2. Hybrid Core 2D Perspective Pipeline**
    Spawns the application inside the multi-API agnostic 2D batching renderer ecosystem. Optimal for flat sprites, UI layouts, and standard 2D ECS validation layouts.

  ```bash
  ./gradlew run --args="--2d"
  ```

### Starting the Interface Development Workspace
To kickstart the Tauri v2 Hub Wizard in development mode (which automatically watches for changes in both the Rust backend and the HTML/CSS frontend assets):

* **1. Running the Interface**

  ```bash
  tauri dev
  ```
  **_Note:_** If you installed via `cargo install`, the bare alias might require you to run `cargo tauri dev`, which works natively and identically across all platforms.

* **2. Compiling for Distribuition:**

  ```bash
  tauri build
  ```

  Or

  ```bash
  cargo tauri build
  ```

  **_Note:_** For more information about interface updates check [docs/FRONTEND_INTEGRATION.md](docs/FRONTEND_INTEGRATION.md)

---

## Roadmap


- [x] Game loop with delta time

- [x] Input system (keyboard + mouse)

- [x] Shader compilation + uniform cache

- [x] Renderer2D — colored and textured quads

- [x] Texture loading (STB Image)

- [x] Camera — orthographic 2D and perspective 3D

- [x] Library publishing (Maven Local)

- [x] Logger with per-system log levels

- [x] GLFW Native Window & OpenGL 4.6 Core Profile Initialization.

- [x] Fixed-timestep Game Loop with precise high-resolution delta-time tracking.

- [x] Multi-API Agnostic abstraction layout wrappers (VAO, VBO, EBO).

- [x] Hardware Mouse Delta Traps (GLFW_CURSOR_DISABLED) stable on Wayland/Linux.

- [x] Direct zero-copy VFS layout for sandboxed asset routing.

- [x] Automatic Project Layout Wizard deployment and manifesto serialization.

- [x] Dynamic hardware texture slot query (glGetIntegerv optimization).

- [x] Context-aware line-number identifying debugging Logger.

- [x] ECS (Entity Component System) State Architecture — Core Handshake.

- [x] Blender-style Viewport Navigation: State-driven Editor Camera (CameraSystem).

- [x] Decouple UI/Launcher via WebKit Architecture — Integrated Tauri v2 runtime.

- [x] Native Subprocess Handshake — IPC Command Pipeline mapping between Rust/JS and JVM Argument injection.

- [x] Decouple 2D/3D Specialized Render Pipelines: Enforce strict segregation between 2D Batching and 3D Mesh Pipelines. Project Initialization manifests (project.json) must explicitly declare target dimensions to cull unnecessary buffer overheads.

- [x] Dynamic Vertex Layout Texture Slating: Complete integration of texture indices (in float a_TexIndex) inside Renderer2D/3D batches to enforce single draw-call execution frames using GPU hardware slots dynamically.

- [x] Procedural Infinite Grid Pipeline — Implemented an angle-aware screen-space analytic grid (1.0f, 4.0f, 16.0f units) utilizing isotropic hardware derivatives (fwidth) to negate sub-sampling aliasing.

- [x] ECS Cache Locality Optimization: Definitively bridge Entity and Component update loops into contiguous memory tables to guarantee optimal CPU L1/L2 cache locality.

- [x] Asynchronous Multi-Threaded Asset Streamer: Move stbi_load_from_memory decoding routines to an asynchronous Thread Pool Worker queue, restricting Main Thread execution exclusively to final high-speed VRAM blitting operations (glTexImage2D).

- [x] Asset Baking & Packaging Pipeline: Develop an offline tool to compile raw .png and text assets into optimized, compressed, custom .atex binary chunks and single .pak file streams for distribution.

- [x] Data-Driven Scene & Prefab Architecture: Implement deterministic JSON parsers (using libraries like Gson/Jackson) to instantiate ECS components dynamically from `.entity` and `.scene` files, completely decoupling level design from hardcoded Java execution.

- [x] Kernel-Level Asset Hot-Reloading: Deploy an OS-level `WatchService` (inotify/ReadDirectoryChangesW) daemon on the `assets_src` directory to trigger automatic recompilation via `AssetBaker` and instant VRAM injection during runtime with zero polling overhead.

- [ ] Tauri WebKit Editor Dashboard: Finalize the Rust/Svelte (or Vue/React) frontend wrapper to intercept the 10Hz TCP telemetry loopback, visualizing real-time ECS allocation metrics, FPS graphs, and intercepted Logger streams.

- [ ] Physics & Collision Pipeline: Integrate a dedicated physics thread (evaluating custom AABB/SAT solvers or native Box2D/Jolt bindings) synchronized with the ECS Transform components using fixed-timestep interpolation.

- [ ] Spatial Audio Engine: Implement OpenAL native bindings for 3D positional audio, streaming `.ogg` files through the async worker pool to prevent Main Thread stuttering during heavy soundscape decoding.

- [ ] Advanced Rendering Techniques: Expand the Shader subsystem to support Framebuffer Objects (FBOs) for post-processing, Shadow Mapping, and a rudimentary Physically Based Rendering (PBR) pipeline decoupled from the 2D Batch Renderer.

- [ ] Scripting Language Bridge: Explore polyglot execution (e.g., LuaJ or GraalVM JS) to allow hot-pluggable gameplay scripts that can mutate ECS state without recompiling the Java Core.

- [ ] Architecture Realignment — Segregate ImGui Dependencies: Isolate and deprecate Dear ImGui from structural window wrappers. Retain ImGui execution paths exclusively for intra-viewport debug overlays running inside the active LWJGL hardware thread, shifting window-frame layout responsibility entirely to the WebKit/Tauri frontend context.

- [ ] Local Socket IPC Daemon: Implement a lightweight local loopback TCP socket connection between the Tauri frontend wrapper and the Java Core to stream real-time framework telemetry (FPS counters, active ECS allocations, and structural logs) directly into the UI dashboard.
