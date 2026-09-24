# AEngine UI Framework — Architecture & Migration Plan

**Status:** Design agreed. Phase 0 complete — the Tauri/WebKit frontend and its IPC bridge
have been removed; the engine now runs as a single process with Dear ImGui as the
transitional editor layer.
**Supersedes:** `FRONTEND_INTEGRATION.md` (Tauri/WebKit frontend), now deleted.

---

## 1. Why

The editor currently runs two UI stacks at once:

| Layer | Role today | Cost |
|---|---|---|
| Tauri + WebKitGTK | Window frame, editor panels, dashboard | ~200–300 MB resident; JSON serialized per frame over TCP `127.0.0.1:8080` |
| Dear ImGui (JNI) | Viewport compositing, debug panels, menus | Per-widget JNI crossing; `ImVec2`/`String` garbage per frame |

Neither is wrong in isolation. Together they mean two input systems, two layout models, two
render paths and an IPC hop for data the engine already holds in memory.

The decision is to replace both with a UI framework written inside the engine, in the same
spirit as Unreal's Slate and Unity's UI Toolkit: purpose-built, owned, and free to assume
the engine's renderer exists.

**Two hard requirements:**

1. **No allocation in the steady-state frame loop.** Not "no GC" — ZGC in Java 25 gives
   sub-millisecond pauses, which is irrelevant against a 16.6 ms budget. The target is
   allocation *rate*, which destroys cache locality and burns memory bandwidth.
2. **Visual quality above Dear ImGui.** Anti-aliased rounded geometry, shadows, crisp text
   at any scale, and the ability to animate state transitions.

---

## 2. What is actually hard

Worth stating plainly, because it determines where the effort goes.

Dear ImGui issues **zero** GPU calls. It produces a per-frame draw list — a vertex buffer,
an index buffer, and a command list of `(scissor rect, texture id, index offset, index
count)`. The OpenGL backend that consumes it is roughly 300 lines. **Matching ImGui's
"hardware efficiency" is the easy part of this project**, and AEngine already has most of
it: `Renderer2D` is a 1000-quad batcher with a pre-allocated vertex array and a 10-float
vertex layout (pos/uv/color/texIndex), sitting behind `RendererAPI`.

The real cost, in rough proportion:

| Concern | Share of effort | Notes |
|---|---|---|
| **Text** | 30–40% | Glyph atlas, kerning, wrapping, caret, selection, clipboard, Unicode, IME. The single largest item. |
| **Layout** | 15% | Constraint resolution. Conceptually simple, bug-dense in practice. |
| **Input & focus** | 15% | Hit-testing, focus order, mouse capture during drag, keyboard navigation. |
| **Anti-aliased geometry** | 15% | Rounded corners, shadows, gradients, crisp 1px borders. This is what separates "looks like ImGui" from "looks like a product". |
| **Draw list + GPU backend** | 10% | Mostly already built. |
| **Widget behaviours** | rest | ~25 widgets is enough for an engine editor. |

---

## 3. Repository and module layout

**Decision: one repository, multiple Gradle modules.** Not a separate repository.

The UI framework depends on the engine's render abstraction, window and input. A separate
repository turns that into a published Maven dependency, forcing a `publishToMavenLocal`
and a version bump for every change — during a period when both sides change weekly.

More decisively, the two will need **atomic commits crossing the boundary**. Two already
known:

- `RendererAPI.drawBatch(float[], int, int)` carries no scissor rect, no blend state and no
  per-command texture binding. A UI draw list requires all three.
- `Input` is poll-based (`isKeyPressed`, `getMouseX`). UI requires *edge-triggered events*:
  character input, scroll deltas, press/release, key repeat.

Each is a single change touching engine and UI together. Across two repositories, that
becomes two coordinated pull requests and a version dance.

A Gradle module also enforces the dependency direction **at compile time**, which a separate
repository only does at publish time — but only at module granularity. It stops `:ui`
importing `:editor`, because that module is simply not on its compile classpath.

It does **not** stop `:ui` importing `com.aengine.ecs`. Java outside JPMS has no
package-level visibility across a jar, so every package in `:core` is visible to
anything that depends on it. This was verified rather than assumed: a probe file importing
`com.aengine.ecs.Registry` from `:ui` compiled without complaint.

The package rule below is therefore enforced by a `checkBoundary` task in
`ui/build.gradle`, which scans the module's imports and fails the build on a
violation. It runs as part of `compileJava`, so the rule is checked on every build rather
than on review. The structurally pure alternative — splitting `:core` so the UI can
only see a rendering-and-windowing module — stays open if the rule ever needs to be
stronger than a build check.

### What the split exposed

Splitting the modules immediately surfaced a dependency that had been invisible in the
single-module build: `RenderSystem` and `CameraSystem` — engine ECS code — imported
`com.aengine.Main` to read `Main.getActiveRenderMode()`. The engine core depended on the
application host.

`RenderMode` was moved to `com.aengine.core` as engine state, which is what it always was:
the systems branching on it must not care which host started the engine. This is the kind
of drift a module boundary catches on the next build instead of at the next rewrite.

### Target layout

```
AEngine/
├── settings.gradle     include ':core', ':ui', ':editor'
├── core/               renderer, ECS, physics, audio, assets, scripting
├── ui/                 the UI framework — depends on core, knows nothing above it
└── editor/             editor panels built with :ui; depends on both
```

Directories are short; published artifacts are not. `archivesName` keeps the jars and Maven
coordinates as `aengine-core`, `aengine-ui` and `aengine-editor`, so the names stay
unambiguous outside this repository.

```groovy
// ui/build.gradle
dependencies {
    implementation project(':core')   // renderer + window + input only
    // NO dependency on :editor, and none on the ECS packages.
}
```

### The rule that matters

The `:ui` module may import **only**:

- `com.aengine.graphics.*` — the API interfaces, never `com.aengine.graphics.opengl.*`
- `com.aengine.core.Window`, `Input`, `Keys`
- `com.aengine.utils.Logger`, `FileSystem`

It must **never** import `com.aengine.ecs.*`, `com.aengine.editor.*`,
`com.aengine.physics.*` or `com.aengine.graphics.opengl.*`. The moment it does, the
framework is no longer extractable and this plan's exit route closes. `checkBoundary`
enforces exactly this list; widening it means editing that allowlist deliberately, which is
the point.

### When to split into its own repository

When `:ui` depends on nothing but "a GL context and a draw list sink" — that is, when
its only engine imports are the `graphics` interfaces. At that point it has earned
independence, and extracting a Gradle module into its own repository is an afternoon's work.
Merging two repositories back together is not.

---

## 4. Implementation language

**Decision: Java, in-process with the engine.** Not C++ behind a native boundary.

The reasoning is not that Java is adequate — it is that C++ reintroduces the exact defect
this project it is trying to remove.

### The defect is the boundary, not the technology

The problem with the Tauri frontend is not that it is Rust, or that the transport is TCP.
It is that **the UI lives outside the engine's address space and object graph**. Every
inspector field is a serialize/deserialize round trip against data the engine already holds.

A C++ UI framework driving a Java engine has the same shape. The transport gets faster —
shared memory instead of TCP — but the boundary remains, and every entity list, component
value, asset name and scene-graph node still has to be marshalled across it in both
directions. Building that means designing a reflection and mutation protocol over the
boundary, which is precisely the work Phase 0 deletes.

Writing the UI in Java does not make that boundary fast. It removes it.

### The strongest argument for C++ no longer holds

The serious case for C++ was the native text stack: FreeType, HarfBuzz and msdfgen are C/C++
libraries, and text is 30–40% of this project.

That case is closed. LWJGL 3.3.4 — the version already in `build.gradle` — ships bindings
for all three, with prebuilt natives for Linux, Windows, macOS (x64 and ARM64), FreeBSD and
several other architectures:

```groovy
implementation "org.lwjgl:lwjgl-freetype:${lwjglVersion}"
implementation "org.lwjgl:lwjgl-harfbuzz:${lwjglVersion}"
implementation "org.lwjgl:lwjgl-msdfgen:${lwjglVersion}"
runtimeOnly "org.lwjgl:lwjgl-freetype:${lwjglVersion}:${lwjglNatives}"
runtimeOnly "org.lwjgl:lwjgl-harfbuzz:${lwjglVersion}:${lwjglNatives}"
runtimeOnly "org.lwjgl:lwjgl-msdfgen:${lwjglVersion}:${lwjglNatives}"
```

The genuinely hard, genuinely native part of text is therefore already native and already
bound, with a coarse boundary — shape a string once, cache the result forever. Nothing is
gained by writing the layers above it in the same language as the layers below.

### The GC argument is weaker than it appears

C++ offers true zero-GC. But the requirement in §7 is not zero GC, it is **zero allocation
in the frame loop** — and the discipline that achieves it is identical in both languages.
Nobody calls `malloc` per frame in C++ either.

What C++ adds beyond that is value types and control over memory layout. Java 25 lacks value
types (Valhalla is not stable), which is a real cost — but it is a constant factor addressed
by struct-of-arrays and the FFM API, not an architectural difference.

### The permanent cost of adding a native module

Worth stating explicitly, because it is paid every week rather than once:

- A native toolchain per target platform, and a CI matrix to build them
- Shipping and loading `.so` / `.dll` / `.dylib` per platform and architecture
- Crashes become JVM crashes with `hs_err` logs instead of Java stack traces
- Debugging across the boundary: two debuggers, two symbol sets
- Profiling that no longer shows one continuous call tree

Today the build is a single Gradle invocation with no native toolchain requirement. On a
long-running project with one primary maintainer, that is worth more than it sounds.

---

## 5. Immediate mode or retained mode

**Decision: hybrid, in the shape of Slate.** A retained widget tree with a declarative
build API.

This is the decision that determines whether the framework can look good, so it is made up
front rather than discovered later. In pure immediate mode a widget holds no state between
frames, which makes animation, transition and stateful styling awkward *by construction* —
this is a substantial part of why Dear ImGui looks the way it does. Unity moved to retained
(UI Toolkit, USS/UXML). Unreal's Slate keeps a retained tree with a declarative C++ DSL and
separate layout and paint passes.

The model here:

- **Retained:** the widget tree persists across frames. Layout results are cached; only
  dirty subtrees are recomputed. Per-widget state (hover, focus, animation phase, scroll
  offset, text caret) lives in the tree, which is what makes transitions possible.
- **Declarative:** panel code reads like immediate mode. The build pass reconciles a
  described tree against the existing one and marks what changed, so authors are not
  hand-managing widget lifetimes.

---

## 6. Layer architecture

Four layers, bottom-up. Each is independently testable and independently useful, which is
what makes incremental delivery possible.

```
┌───────────────────────────────────────────────────────────────┐
│  L4  Widget tree      retained nodes, declarative build,      │
│                       behaviour, focus, animation             │
├───────────────────────────────────────────────────────────────┤
│  L3  Layout           constraint solve, dirty-subtree cache   │
├───────────────────────────────────────────────────────────────┤
│  L2  Text             glyph atlas, shaping, layout cache      │
├───────────────────────────────────────────────────────────────┤
│  L1  Draw list        vertex/index/command buffers,           │
│                       scissor stack, SDF shader               │
└───────────────────────────────────────────────────────────────┘
                              ↓
                  RendererAPI  (aengine-core)
```

### L1 — Draw list

The output contract. One frame produces:

- a vertex buffer (position, UV, colour, and SDF parameters)
- an index buffer
- a command list: `(scissor rect, texture id, index offset, index count)`

Anti-aliasing is done with **signed distance fields in the fragment shader**, not with
ImGui's vertex-fringe technique. Rounded rectangles, borders, and drop shadows become
shader parameters rather than tessellated geometry: fewer vertices, better quality, and
resolution independence. This is the layer that makes the framework *look* different from
ImGui, and it is also the cheapest layer to build.

### L2 — Text

Staged deliberately, because this is where projects of this kind stall.

- **Stage 1 — `stb_truetype`.** Already on the classpath via `lwjgl-stb`, already used by
  `AssetBaker`. Bitmap atlas, ASCII plus Latin-1, no shaping. Good enough to build every
  other layer against.
- **Stage 2 — MSDF atlas.** Multi-channel signed distance fields for crisp glyphs at any
  scale, which matters for editor zoom and high-DPI displays.
- **Stage 3 — FreeType + HarfBuzz via the FFM API.** Proper shaping, kerning, and complex
  scripts. Only when it is actually needed.

Text layout results are **cached by content hash**, never recomputed per frame. Java
`String` handling is the single largest allocation source in UI code, so the public text
API accepts `CharSequence` and byte ranges rather than forcing `String` construction.

### L3 — Layout

A flex-style constraint model (direction, grow, shrink, align, gap, padding) — enough to
express every panel an engine editor needs, without a full CSS engine.

Layout is **cached and dirty-tracked**. A frame in which nothing changed performs no layout
work at all. Results are stored in parallel primitive arrays indexed by node handle, not as
objects per node.

### L4 — Widget tree

Nodes are **integer handles into struct-of-arrays storage**, not object references. Java 25
has no value types — Valhalla is not stable — so flat primitive arrays (or `MemorySegment`
via the FFM API) are how a tree is represented without an object per node.

The widget set an engine editor actually needs, which is finite:

- Text: label, text field, multi-line editor
- Numeric: drag-field, slider, vector2/3/4 field
- Choice: button, checkbox, radio, combo box, colour picker
- Structural: panel, collapsible header, tab bar, splitter, **dock host**
- Collections: tree view, **virtualized list** (the asset browser will hold thousands of
  entries — virtualization is not optional)
- Feedback: tooltip, modal, context menu, progress bar

---

## 7. Zero-allocation rules

These are binding constraints on `aengine-ui`, not aspirations.

**Forbidden in the frame loop:**

- `String.format`, string concatenation, `String.valueOf` — currently done per frame in
  `Main.java`'s stats panels, and a good measure of how easy this is to get wrong
- Autoboxing, and therefore `List<Integer>`, `Map<String, ?>` and friends on hot paths
- Lambdas or method references that capture per frame
- Iterator allocation — index-based loops over arrays only
- `new` of any kind after initialisation, including JOML temporaries

**Required patterns:**

- Struct-of-arrays in `float[]` / `int[]`, or `MemorySegment` through the FFM API
- Integer handles instead of object references for every node, style and layout record
- Number formatting into a reused `char[]` or `StringBuilder`, never through `String.format`
- Every buffer pre-allocated at init and reused, following `Renderer2D`'s existing pattern
- Ring buffers for transient per-frame data, sized at startup

**Verification** is part of the definition of done, not an afterthought:

- JFR allocation profiling on the editor's idle loop; the target is a flat allocation
  counter across an idle frame
- An automated test that runs N frames and asserts zero allocation growth, so regressions
  are caught by CI rather than by feel

---

## 8. Required changes in `aengine-core`

Known cross-boundary work, to be done before or alongside L1.

**`RendererAPI`** — the current surface is insufficient for a UI draw list:

```java
void drawBatch(float[] vertices, int vertexCount, int indexCount);   // today
```

It needs scissor rectangles, explicit blend state, and per-command texture binding. The
cleanest shape is a command-list submission rather than more parameters on `drawBatch`.

**`Input`** — poll-based today (`isKeyPressed`, `getMouseX`, `getMouseDeltaX`). UI requires
an **edge-triggered event queue**: character input (not key codes), scroll deltas, button
press and release as distinct events, and key repeat. The polling API stays for gameplay;
the event queue is added beside it and drained once per frame by the UI.

**`Window`** — needs to expose content scale (high-DPI), cursor shape control, and clipboard
access. All three are GLFW calls already available through the existing handle.

---

## 9. Migration path

Sequenced so that **the editor never stops working**. The failure mode to avoid is stopping
the engine for six months to write a framework on a branch.

**Phase 0 — Remove Tauri. ✅ Done.** Removed `src-tauri/`, `ui/`, the entire
`com.aengine.network` package (`TelemetryServer`, `SharedMemory`) and
`docs/FRONTEND_INTEGRATION.md`, and unwired them from `Main`, `Engine`, `Logger` and
`FrameBuffer`.

**Measured result: frame rate is unchanged.** Before and after, on the sandbox scene at
native resolution, the engine holds ~213 fps and ~211 fps respectively — the same number
within run-to-run noise. This is worth recording because it contradicts the expectation this
plan was written with.

The reason is that `FrameBuffer.dispatchToSharedMemory()` was implemented well: a
double-buffered PBO `glReadPixels` that reads frame *N* while mapping frame *N−1*, so the
GPU→CPU transfer overlapped with rendering instead of stalling on it. The cost was real but
it was not on the critical path.

What removing it actually buys is therefore not frame time:

- **Memory bandwidth.** A full-viewport readback plus a `memcpy` into `/dev/shm` every frame,
  which at 1080p is ~8.3 MB each way. It did not show up as frame time on an idle scene, but
  it is bandwidth competing with everything else, and it scales with resolution.
- **VRAM.** Two PBOs sized to the viewport, freed. `FrameBuffer` no longer touches
  `GL_PIXEL_PACK_BUFFER` at all.
- **Resident memory.** The WebKitGTK process, which was not running during either
  measurement — so its footprint is absent from these numbers rather than proven saved.
- **Complexity.** ~5,700 lines deleted, one less process, one less language toolchain, and
  no IPC protocol to keep in sync.

The honest summary: Phase 0 was a simplification, not a speedup.

Also removed: a `String.format` on **every** `Logger` call, which built a de-ANSI'd payload
for the telemetry queue regardless of whether a client was connected.

**Phase 1 — Custom render backend for Dear ImGui.** Replace `ImGuiImplGl3` with an
AEngine-owned backend consuming `ImDrawData` through the existing `RendererAPI`, with the
SDF shader and MSDF atlas from L1 and L2.

This phase is the leverage point. It delivers most of the visual improvement for a small
fraction of the total effort, and it builds and validates exactly the layers the framework
needs — while the editor stays fully functional throughout.

**Phase 2 — Complete L1.** *Revised.* The original plan routed through a custom Dear ImGui
backend in Phase 1 and extracted the draw list from it here. That detour was skipped:
Phase 1 built `UIDrawList` and `UIRenderer` directly, so there is no ImGui backend to
extract from and L1 already stands alone.

What remains is to finish it. Phase 1 draws one shape type in a single draw call, which is
enough to prove the pipeline and not enough to build an editor on:

1. **A command list.** `UIRenderer` issues one draw for the whole frame. A real draw list
   emits a sequence of `(clip rect, texture, index offset, index count)`, which is what lets
   clipping and texture changes happen partway through a frame.
2. **A clip stack.** Push and pop rectangles. This is how a scrolling list submits ten
   thousand rows and lets the hardware discard the ones outside the panel.
3. **Textured quads.** Required before L2, since text is exactly that: quads sampling a
   glyph atlas.

These three also give `setScissor`, `disableScissor` and `bindTexture` their first callers.

**Phase 3 — L2/L3/L4.** Text, layout, widget tree, in that order.

**Phase 4 — Panel-by-panel migration.** ImGui and the new framework coexist, both rendering
through the same backend. Panels move one at a time. Order: stats and physics debug panels
(read-only, trivial) → menu bar → inspector → hierarchy → asset browser → viewport docking.

**Phase 5 — Remove Dear ImGui.** Drop the three `io.github.spair` dependencies and the JNI
boundary with them.

### Realistic scope

Reaching "visually better than Dear ImGui, and equally reliable", solo and part-time:
**6 to 18 months**. Slate was built by a team. But the target here is roughly 25 widgets for
one editor, not a general-purpose UI toolkit — the scope is finite and the phases above are
individually shippable.

---

## 10. Out of scope

Stated so they are not rediscovered as surprises:

- **Accessibility APIs** (screen readers, AT-SPI / UIA). Not planned. Reconsider only if the
  editor ever needs to meet an accessibility requirement.
- **Full CSS or a styling language.** Styles are typed structs in code, not a parsed
  stylesheet.
- **Runtime game UI.** This framework targets the *editor*. Whether it later becomes the
  in-game UI system is a separate decision, deliberately deferred.
- **Multi-window / OS-level multi-viewport.** Docking within one window first; detachable
  OS windows much later, if at all.
- **Vulkan backend.** Inherited free when the engine migrates, since the UI only ever talks
  to `RendererAPI`.
