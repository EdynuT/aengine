# AEngine

A high-performance, multi-API capable graphics engine built in Java, running as a single self-contained process.

The engine is engineered strictly as a decoupled reusable runtime infrastructure. The structural abstraction layer between application logic and the graphics hardware backend (`RendererAPI`, `ShaderAPI`, `TextureAPI`, `BufferAPI`) guarantees absolute isolation, allowing a seamless future migration from OpenGL to Vulkan without mutating game-space code blocks.

---

## Technical Stack

| Component | Specification |
|---|---|
| **Core Language** | Java 25 (OpenJDK) |
| **Editor Interface** | Dear ImGui (transitional) — migrating to an in-house framework, see [docs/UI_FRAMEWORK_ARCHITECTURE.md](docs/UI_FRAMEWORK_ARCHITECTURE.md) |
| **Build System** | Gradle 9.1+ (Wrapper orchestrated, 3-module graph) |
| **Graphics Platform** | OpenGL 4.6 Core Profile (Mesa/ACO Optimized) via LWJGL 3.3.4 |
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
| `:ui` | In-house UI framework (empty until Phase 2) | `:core` |
| `:editor` | Editor surface and application host; Dear ImGui confined here | `:core`, `:ui` |

`:core` carries no UI toolkit. The engine loop drives the interface through the
`com.aengine.core.UILayer` interface, which the editor implements today with Dear ImGui and
the in-house framework will implement later without touching `Engine`.

`:ui` is additionally restricted to a subset of core packages, enforced by a
`checkBoundary` task that fails the build on a disallowed import.

### Single-Process Architecture
The engine, editor and interface run inside one JVM process, sharing one address space and one object graph. There is no interprocess bridge, no serialization hop and no external UI runtime: editor panels read engine state directly.

The interface currently renders through Dear ImGui as a transitional layer, and is being replaced by an in-house UI framework. The design and migration plan are in [docs/UI_FRAMEWORK_ARCHITECTURE.md](docs/UI_FRAMEWORK_ARCHITECTURE.md).

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

- [x] ~~Decouple UI/Launcher via WebKit Architecture — Integrated Tauri v2 runtime.~~ **Superseded:** the Tauri/WebKit frontend was removed in favour of a single-process in-house interface.

- [x] ~~Native Subprocess Handshake — IPC Command Pipeline mapping between Rust/JS and JVM Argument injection.~~ **Superseded** alongside the Tauri frontend.

- [x] Decouple 2D/3D Specialized Render Pipelines: Enforce strict segregation between 2D Batching and 3D Mesh Pipelines. Project Initialization manifests (project.json) must explicitly declare target dimensions to cull unnecessary buffer overheads.

- [x] Dynamic Vertex Layout Texture Slating: Complete integration of texture indices (in float a_TexIndex) inside Renderer2D/3D batches to enforce single draw-call execution frames using GPU hardware slots dynamically.

- [x] Procedural Infinite Grid Pipeline — Implemented an angle-aware screen-space analytic grid (1.0f, 4.0f, 16.0f units) utilizing isotropic hardware derivatives (fwidth) to negate sub-sampling aliasing.

- [x] ECS Cache Locality Optimization: Definitively bridge Entity and Component update loops into contiguous memory tables to guarantee optimal CPU L1/L2 cache locality.

- [x] Asynchronous Multi-Threaded Asset Streamer: Move stbi_load_from_memory decoding routines to an asynchronous Thread Pool Worker queue, restricting Main Thread execution exclusively to final high-speed VRAM blitting operations (glTexImage2D).

- [x] Asset Baking & Packaging Pipeline: Develop an offline tool to compile raw .png and text assets into optimized, compressed, custom .atex binary chunks and single .pak file streams for distribution.

- [x] Data-Driven Scene & Prefab Architecture: Implement deterministic JSON parsers (using libraries like Gson/Jackson) to instantiate ECS components dynamically from `.entity` and `.scene` files, completely decoupling level design from hardcoded Java execution.

- [x] Kernel-Level Asset Hot-Reloading: Deploy an OS-level `WatchService` (inotify/ReadDirectoryChangesW) daemon on the `assets_src` directory to trigger automatic recompilation via `AssetBaker` and instant VRAM injection during runtime with zero polling overhead.

- [x] Scripting Language Bridge: Explore polyglot execution (e.g., LuaJ or GraalVM JS) to allow hot-pluggable gameplay scripts that can mutate ECS state without recompiling the Java Core.

- [x] ~~Local Socket IPC Daemon: loopback TCP telemetry stream between the Tauri frontend and the Java Core.~~ **Superseded:** with the UI in-process, telemetry is read directly from engine state.

- [x] Physics & Collision Pipeline: Integrate a dedicated physics thread (evaluating custom AABB/SAT solvers or native Box2D/Jolt bindings) synchronized with the ECS Transform components using fixed-timestep interpolation.

- [x] Broad Phase Physics (Spatial Hashing): Replace the current $O(N^2)$ brute-force intersection loop with a deterministic Spatial Hash Grid to rescue CPU cycles.
  - Implement an $O(1)$ insertion pipeline converting 2D/3D Transform spatial coordinates into 1D HashMap bucket IDs using prime number hashing (e.g., `(floor(x / cellSize) * 73856093) ^ (floor(y / cellSize) * 19349663)`).
  - Restrict Narrow Phase (AABB/SAT) evaluations strictly to entities sharing the same or adjacent spatial buckets.
  - Prepare the isolated Collision Resolution solver (Impulse/Velocity projection) to execute immediately after the Broad Phase filter.

- [x] ~~Architecture Realignment — Segregate ImGui Dependencies: restrict Dear ImGui to intra-viewport debug overlays, shifting window-frame layout to the WebKit/Tauri frontend.~~ **Superseded:** Dear ImGui now owns the full editor surface as a transitional layer until the in-house framework replaces it.

- [x] Spatial Audio Engine: Implement OpenAL native bindings for 3D positional audio, streaming `.ogg` files through the async worker pool to prevent Main Thread stuttering during heavy soundscape decoding.

- [x] Native OpenAL Audio Pipeline (.aaud Engine): Instantiate the LWJGL OpenAL context and develop a dedicated `AudioSystem` to consume the custom `.aaud` binary format. Route Raw PCM payloads directly into static `alBufferData` for zero-latency SFX, and implement an async worker thread with Ring-Buffers for streaming heavy BGM tracks without stalling the Main Thread.

- [x] ~~Zero-Copy Viewport Bridge (Memory-Mapped IPC): stream FBO pixels to the Tauri frontend through `/dev/shm` at monitor refresh rate.~~ **Superseded:** the readback is gone entirely — the in-process UI samples the FBO texture directly in VRAM.

- [ ] ECS Relational Scene Graph (Transform Hierarchies): Expand the currently flat DOD Registry to support parent-child entity relationships. Implement a dirty-flag topological sort algorithm to efficiently compute Global Transforms from Local Transforms in contiguous memory blocks, enabling complex nested prefabs in the Editor.

- [ ] In-House UI Framework: Build AEngine's own editor interface framework — SDF-based draw list, MSDF text stack, flex layout and a retained widget tree, under a zero-allocation frame-loop budget. Full design and phased migration plan in [docs/UI_FRAMEWORK_ARCHITECTURE.md](docs/UI_FRAMEWORK_ARCHITECTURE.md).

- [ ] Advanced Rendering Techniques: Expand the Shader subsystem to support Framebuffer Objects (FBOs) for post-processing, Shadow Mapping, and a rudimentary Physically Based Rendering (PBR) pipeline decoupled from the 2D Batch Renderer.
