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

### 3. Asset Ingestion Pipeline (Action Commands)
While telemetry is a continuous stream from the engine, user actions in the Editor (like importing a new texture) must send commands to the engine.

#### 3.1. The Hermetic Project Rule
The engine relies on a strict zero-decode binary pipeline (`.atex` files). The UI must never reference files stored randomly on the user's OS. The project folder must remain completely self-contained.

The Workflow:

1. User clicks "Import Texture" in the UI.

2. Tauri opens a native OS File Dialog allowing the user to pick an image.

3. Crucial Step: The Tauri Rust backend must copy this file directly into the engine's raw asset directory: `assets/source/textures/map.png`.

4. The engine's Kernel-Level Watcher (or an explicit IPC command) will detect the new file, automatically trigger the `AssetBaker`, compile it to `assets/baked/textures/map.atex`, and hot-reload the VRAM.

#### 3.2. Future Command Contract
For actions that require direct state mutation (e.g., changing an entity's position or requesting an asset import via IPC), the frontend will eventually dispatch JSON payloads back to the Java server following this structure:

```json
{
  "command": "ASSET_IMPORT",
  "payload": {
    "sourcePath": "path/to/map.png",
    "targetCategory": "textures",
    "targetEntityId": 4
  }
}
```

### 4. Design Philosophy & Constraints
- Do Not Pollute the Main Thread: Do not attempt to run heavy calculations on the frontend that require synchronized blocking calls to the Java engine.

- Decoupled State: The Java Core is the ultimate source of truth. If the frontend crashes, the game loop must continue running. If the Java Core crashes, the frontend should display a "Connection Lost" overlay.

- UI Layout: The dashboard should feature modular panes (e.g., a Profiler for FPS, an ECS Inspector, and a real-time Console). Design the components to accept rapid data updates (10 times a second) without causing excessive DOM reflows.