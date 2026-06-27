# AEngine

Java graphics engine built on OpenGL 3.3 Core Profile.

The engine is structured as a reusable library. The abstraction layer between game code and the graphics backend (`RendererAPI`, `ShaderAPI`, `TextureAPI`, `BufferAPI`) is designed to allow a future migration from OpenGL to Vulkan without touching game logic.

---

## Stack

| | |
|---|---|
| Language | Java 25 |
| Build | Gradle 9.1+ (wrapper included) |
| Graphics | OpenGL 3.3 Core Profile via LWJGL 3.3.4 |
| Windowing / Input | GLFW via LWJGL |
| Math | JOML 1.10.5 |
| Texture loading | STB Image via LWJGL |

---

## Running the demo

- Windows
```powershell
.\gradlew.bat run
```

- Linux
```shell
./gradlew clean run
```

Opens a 1280×720 window with a blue quad centered on screen. Press **ESC** to close.

First run downloads Gradle (~130 MB). Subsequent runs start in seconds.

---

## Using AEngine as a library

### 1. Publish to Maven Local

- Windows
```powershell
.\gradlew publishToMavenLocal
```

- Linux
```shell
./gradlew publishToMavenLocal
```

This installs `com.aengine:AEngine:0.7` into `~/.m2/repository`.

### 2. Import in your game project

```groovy
// build.gradle
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation 'com.aengine:AEngine:0.7'

    // LWJGL natives — required, declare for your target platform
    runtimeOnly "org.lwjgl:lwjgl:3.3.4:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl-glfw:3.3.4:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl-opengl:3.3.4:natives-windows"
    runtimeOnly "org.lwjgl:lwjgl-stb:3.3.4:natives-windows"
}
```

### 3. Minimal game class

```java
public class MyGame extends Engine {

    public MyGame() { super("My Game", 1280, 720); }

    @Override protected void onInit()                  { Renderer2D.init(); }
    @Override protected void onUpdate(float deltaTime) { if (Input.isKeyPressed(Keys.ESCAPE)) stop(); }
    @Override protected void onRender()                { /* draw calls */ }
    @Override protected void onCleanup()               { Renderer2D.cleanup(); }

    public static void main(String[] args) { new MyGame().run(); }
}
```

---

## Roadmap

- [x] Window + OpenGL 3.3 context (GLFW)
- [x] Game loop with delta time
- [x] Input system (keyboard + mouse)
- [x] Shader compilation + uniform cache
- [x] VAO / VBO / EBO wrappers
- [x] Renderer2D — colored and textured quads
- [x] Texture loading (STB Image)
- [x] Camera — orthographic 2D and perspective 3D
- [x] Entity / Component / Transform system
- [x] Library publishing (Maven Local)
- [x] Logger with per-system log levels
- [x] Hot reload of game logic
- [x] Camera
- [x] WASD movement
- [x] Renderer3D — grid-based dungeon walls
- [ ] UI / HUD overlay
- [ ] Framebuffer + post-processing (scanlines, fog)
- [ ] Scene serialization
- [ ] ECS (Entity Component System) 
- [ ] Dear ImGui editor integration
