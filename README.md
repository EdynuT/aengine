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
