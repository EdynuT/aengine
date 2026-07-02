# AEngine: 

## Frontend UI Integration Guide (Tauri / WebKit)

### 1. Architectural Overview
AEngine operates on a strictly decoupled architecture. The Core Engine (Java/OpenGL) handles high-performance rendering, memory management (ECS), and native hardware I/O. The Editor UI and Dashboard are entirely delegated to a Tauri (Rust + WebKit) frontend.

The two systems do not share memory. They communicate via a zero-allocation, local TCP Loopback connection (IPC). This ensures that the UI can crash, reload, or freeze without ever dropping a frame in the Core Engine's execution loop.

### 2. The Telemetry IPC Daemon (Read-Only Stream)
The Core Engine hosts an asynchronous, non-blocking TCP server. As soon as the engine initializes, it binds to the local loopback interface.

- Host: `127.0.0.1`

- Port: `8080`

- Protocol: Raw TCP (Not WebSockets)

- Tick Rate: 10 Hz (100ms intervals)

#### 2.1. The Data Contract (JSON)
Every 100ms, the Java backend dispatches a serialized JSON payload containing the engine's current state. The frontend must parse this JSON to update the Dashboard UI.

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

### 3. Viewport Rendering Pipeline (The FBO Bridge)
The Core Engine does not render directly to the OS window. It renders the game world into a virtual VRAM texture (Frame Buffer Object). Because the Tauri WebKit frontend cannot access GPU VRAM directly, we utilize **Shared Memory (Memory-Mapped Files)** to bridge the pixels from the Java backend to the Rust frontend at 60 FPS with zero network overhead.

#### 3.1. The Backend Responsibility (Java/OpenGL)
At the end of every render pass, the engine extracts the FBO pixels using `glReadPixels` and writes the raw RGB byte array into a Memory-Mapped File (e.g., `/dev/shm/aengine_viewport` on Linux or a memory-mapped buffer on Windows). 

* Format: Raw RGB (3 bytes per pixel)
* Resolution: Dynamic (Matches the Editor's Viewport pane dimensions).
* Synchronization: The Java engine will flip a boolean flag at the start of the memory block when a new frame is fully written.

#### 3.2. The Frontend Responsibility (Rust + Tauri)
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

### 4. Design Philosophy & Constraints
- Do Not Pollute the Main Thread: Do not attempt to run heavy calculations on the frontend that require synchronized blocking calls to the Java engine.

- Decoupled State: The Java Core is the ultimate source of truth. If the frontend crashes, the game loop must continue running. If the Java Core crashes, the frontend should display a "Connection Lost" overlay.

- UI Layout: The dashboard should feature modular panes (e.g., a Profiler for FPS, an ECS Inspector, and a real-time Console). Design the components to accept rapid data updates (10 times a second) without causing excessive DOM reflows.

## Committing
All interface update must be done on the `features/ui-launcher` branch.
