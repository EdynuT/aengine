# AEngine: Frontend UI Integration Guide

## Frontend UI Integration Guide (Tauri / WebKit)

### 1. Architectural Overview
AEngine operates on a strictly decoupled architecture. The Core Engine (Java/OpenGL) handles high-performance rendering, memory management (ECS), and native hardware I/O. The Editor UI and Dashboard are entirely delegated to a Tauri (Rust + WebKit) frontend.

The two systems do not share memory. They communicate via a zero-allocation, local TCP Loopback connection (IPC). This ensures that the UI can crash, reload, or freeze without ever dropping a frame in the Core Engine's execution loop.

**Rendering layers (innermost to outermost):**

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│  Tauri / WebKit window  (OS window frame, editor panels, dashboard)         │
│                                                                            │
│    ┌────────────────────────────────────────────────────────────────────────┐   │
│    │  LWJGL / GLFW native window  (embedded in Tauri via webview)         │   │
│    │                                                                    │   │
│    │    Layer 1: OpenGL scene → renders to FBO texture (VRAM)           │   │
│    │    Layer 2: ImGui overlay → composites FBO texture + debug panels   │   │
│    │             onto the default framebuffer (GLFW window)             │   │
│    └────────────────────────────────────────────────────────────────────────┘   │
│                                                                            │
│    TCP 127.0.0.1:8080 ─────────────────── FPS, ECS metrics, Logger    │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. The Telemetry IPC Daemon (Runtime-Driven Stream)
The Core Engine hosts an asynchronous, non-blocking TCP socket server bound to `127.0.0.1:8080`.

To prevent multi-threading data races, the Telemetry Server operates completely passively. It does not possess an internal timer.

> **Thread-safety note (implemented):** All ECS reads in `onUpdate()` (ScriptSystem, CameraSystem, TelemetryServer) are now guarded by the `PhysicsThread.syncLock`. This ensures the physics thread cannot mutate shared ECS state while the main thread is serialising data for the TCP dispatch.

#### 2.1. The Data Contract (JSON)
The telemetry dispatch is explicitly controlled by the Engine's primary Runtime (Main Thread). 
To prevent choking the frontend WebKit DOM with 160+ JSON parses per second (which would freeze the Editor UI), the engine's `onUpdate()` loop utilizes a delta-time accumulator. It throttles the dispatch to a UI-friendly interval (typically every 0.1 seconds), capturing the exact memory state of that specific frame.

Payload Schema:

```json
{
  "type": "TELEMETRY_TICK",
  "data": {
    "performance": {
      "fps": x
    },
    "ecs": {
      "activeEntities": 4
    },
    "logs": [
      "[14:45:29.420] INFO  [ASSET   ] [AssetBaker.java:47] Engaging Multi-Threaded Asset Baker.",
      "[14:45:29.670] DEBUG [CORE    ] [Registry.java:47] Allocated absolute Entity ID sequence index: 3"
    ]
  }
}
```

Note on Logs: The `logs` array contains all console outputs generated within the last 100ms window. Once transmitted, the backend clears its queue. The frontend should append these strings to a rolling terminal UI component.

#### 2.2. The Rust-to-WebKit Bridge Strategy
Because standard web browsers (WebKit) cannot open raw TCP sockets, the Tauri Rust backend must act as the translation layer.

Recommended Implementation Flow:

- Rust Backend (Tauri Setup): Spawn a background Tokio task in Rust that connects to `127.0.0.1:8080` via `TcpStream`.

- Stream Parsing: Read the incoming byte stream and parse the UTF-8 JSON strings.

- Event Emitting: Use Tauri's `app_handle.emit_all()` to broadcast the parsed JSON to the WebKit frontend.

- Frontend (JS/TS): Listen to the Tauri event and bind the data to your reactive framework (Svelte, Vue, React).

Example Rust Bridge Logic:

```rust
// Inside tauri::Builder::default().setup()
tauri::async_runtime::spawn(async move {
    if let Ok(mut stream) = TcpStream::connect("127.0.0.1:8080").await {
        let mut reader = BufReader::new(stream);
        let mut line = String::new();
        // Read the incoming JSON payloads from Java
        while let Ok(bytes_read) = reader.read_line(&mut line).await {
            if bytes_read == 0 { break; }
            // Forward to the WebKit UI
            app.emit_all("engine-telemetry", &line).unwrap();
            line.clear();
        }
    }
});
```

### 3. ImGui Debug Overlay (Java-side only — no Tauri action required)

A Dear ImGui debug overlay is embedded directly inside the LWJGL/GLFW native window. It renders **on top of the OpenGL scene** into the default framebuffer, after the FBO pass completes.

**This is entirely Java-side. The Tauri/Rust developer does not need to implement anything for this.**

#### 3.1. What is rendered

| Panel | Contents | Closeable? |
|---|---|---|
| **Viewport** | The engine scene FBO displayed as a dockable ImGui image (fills available panel area, UV-flipped for OpenGL convention) | Yes |
| **Engine Stats** | FPS, render mode (2D/3D), active ECS entity count | Yes |
| **Physics Debug** | PhysicsThread status (Running @ 120 Hz / Stopped) | Yes |

Panels are dockable (Dear ImGui docking enabled). Layout is persisted to `imgui_aengine.ini` next to the engine binary between runs.

#### 3.2. Enable / disable

The overlay is **enabled by default** in all builds. To disable it in a release or headless build, pass the JVM flag:

```
-Dengine.debug=false
```

When disabled, all `DebugOverlay.*` methods return immediately with zero overhead — no ImGui context is created.

#### 3.3. Relationship to the FBO Bridge (section 4)

The ImGui overlay renders to the **default GLFW framebuffer**, not into the FBO texture. This means:

- The FBO texture (game scene only, no ImGui panels) is still available for the Tauri FBO Bridge described in section 4.
- When the Tauri editor viewport is built, the game scene displayed inside Tauri will show the **clean scene** without ImGui panels. The ImGui overlay is a development-only tool visible only in the native GLFW window.
- ImGui and the Tauri FBO bridge can coexist without conflict.

#### 3.4. Extending the overlay

To add custom debug panels, override `onDebugRender(int viewportTextureID)` in `Main.java` and call `super.onDebugRender()` first to preserve the Viewport panel:

```java
@Override
protected void onDebugRender(int viewportTextureID) {
    super.onDebugRender(viewportTextureID); // keeps the Viewport panel

    if (!DebugOverlay.ENABLED) return;

    ImGui.begin("My Custom Panel", DebugOverlay.showEnginePanel());
    ImGui.text("Hello from a custom debug panel");
    ImGui.end();
}
```

Source files:
- `com.aengine.debug.DebugOverlay` — ImGui lifecycle wrapper
- `com.aengine.core.Engine#onDebugRender` — frame hook (called after FBO unbind, before buffer swap)
- `com.aengine.Main#onDebugRender` — current override (Engine Stats + Physics Debug)

---

### 4. Viewport Rendering Pipeline (The FBO Bridge)
The Core Engine does not render directly to the OS window. It renders the game world into a virtual VRAM texture (Frame Buffer Object). Because the Tauri WebKit frontend cannot access GPU VRAM directly, we utilize **Shared Memory (Memory-Mapped Files)** to bridge the pixels from the Java backend to the Rust frontend at 60 FPS with zero network overhead.

#### 4.1. The Backend Responsibility (Java/OpenGL)
At the end of every render pass, the engine extracts the FBO pixels using `glReadPixels` and writes the raw RGB byte array into a Memory-Mapped File (e.g., `/dev/shm/aengine_viewport` on Linux or a memory-mapped buffer on Windows). 

* Format: Raw RGB (3 bytes per pixel)
* Resolution: Dynamic (Matches the Editor's Viewport pane dimensions).
* Synchronization: The Java engine will flip a boolean flag at the start of the memory block when a new frame is fully written.

#### 4.2. The Frontend Responsibility (Rust + Tauri)
Do **NOT** attempt to send 60FPS video frames via Tauri events (`emit_all`) or Base64 strings. It will crash the WebKit DOM due to excessive memory allocation.

**The Implementation Protocol:**
1. **Rust Custom Protocol:** The Tauri backend must register a custom URI scheme (e.g., `aengine://viewport`).
2. **Memory Interception:** When the WebKit frontend requests an image from this scheme, the Rust handler reads the raw RGB bytes from the Memory-Mapped File, wraps them in a standard image format header (like BMP or uncompressed PNG in-memory), and returns the byte stream to WebKit.
3. **HTML Canvas / Image:** The frontend UI uses a standard HTML `<img>` tag or `<canvas>`. A Javascript `requestAnimationFrame` loop constantly updates the image source to fetch the latest frame.

**Example WebKit Implementation (JS/TS):**

```javascript
const viewportImg = document.getElementById("viewport-display");

function requestNextFrame() {
    // Appending a timestamp forces WebKit to bypass the browser cache
    viewportImg.src = `aengine://viewport/frame?ts=${performance.now()}`;
    requestAnimationFrame(requestNextFrame);
}

// Start the 60FPS render loop in the UI
requestAnimationFrame(requestNextFrame);
```

**Example Tauri Setup (Rust):**

```rust
tauri::Builder::default()
    .register_uri_scheme_protocol("aengine", move |app, request| {
        // 1. Read the Shared Memory block updated by Java
        // 2. Format as an image buffer
        // 3. Return tauri::http::ResponseBuilder with the bytes
    })
```

### 5. Design Philosophy & Constraints
- Do Not Pollute the Main Thread: Do not attempt to run heavy calculations on the frontend that require synchronized blocking calls to the Java engine.

- Decoupled State: The Java Core is the ultimate source of truth. If the frontend crashes, the game loop must continue running. If the Java Core crashes, the frontend should display a "Connection Lost" overlay.

- UI Layout: The dashboard should feature modular panes (e.g., a Profiler for FPS, an ECS Inspector, and a real-time Console). Design the components to accept rapid data updates (10 times a second) without causing excessive DOM reflows.

## Committing
- All **Tauri/WebKit** interface work must be done on the `features/ui-launcher` branch.
- **ImGui overlay** changes (debug panels, gizmos, custom overlays) must be done on the `features/imgui-overlay` branch and merged into `main` via PR. Do not mix ImGui and Tauri work in the same branch.
