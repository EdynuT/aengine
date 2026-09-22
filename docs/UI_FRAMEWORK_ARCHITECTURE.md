# AEngine UI Framework — Architecture & Migration Plan

**Status:** In progress. Phases 0–2 complete, Phase 3 started: step 3a (text from a glyph
atlas) is done, and step 3b is under way — part 1 (Latin-1) has landed. Dear ImGui remains
the transitional editor layer, with the in-house framework drawing test scaffolding on top
of it.
**Supersedes:** `FRONTEND_INTEGRATION.md` (Tauri/WebKit frontend), now deleted.

### Where we stopped

| Done | What exists now |
|---|---|
| Phase 0 | Tauri frontend and IPC bridge removed; the editor is a single process |
| Phase 1 | First light: draw list → dynamic mesh → SDF shader → screen |
| Phase 2 | Command list, clip stack, textured quads, borders — L1 is complete |
| Vertex packing | Colours packed as RGBA8; the vertex went from 21 floats to 13 words |
| Step 3a | Printable ASCII drawn from a baked stb_truetype atlas; DejaVu Sans as placeholder |
| Step 3b-1 | The atlas covers Latin-1, so Portuguese, Spanish, French, German and Italian read correctly |

### The plan ahead

Each step is done one at a time, and each ends with something visible on screen, so it can
be checked before the next begins.

**Step 3b — text that reads like text** (L2). Five parts, in order:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Latin-1 coverage | Bake characters 32–255 instead of 32–126, so `á ç ã é õ` have glyphs | The test line shows `Olá, ação!` instead of `Ol?, a??o!` |
| 2 | Font metrics | Ascent, descent and line height from the font file, so text can be placed by its top and lines stack evenly | Two lines placed one line-height apart, touching neither |
| 3 | Kerning | Per-pair spacing corrections from the font | Pairs like `AV` and `To` visibly tighten |
| 4 | Measuring and wrapping | The width of a string; a paragraph broken at spaces to fit a width | A paragraph wrapping inside a panel |
| 5 | Layout cache | Laid-out text cached by content, so unchanged text costs nothing per frame | No visual change — checked by allocation profiling instead |

**Step 3c — layout** (L3). Rows and columns that size their children with grow, gap and
padding. *Visible:* a row of boxes that redistributes itself when the window is resized.

**Step 3d — retained tree** (L4). Nodes that persist across frames, hit-testing, focus
order. *Visible:* a box highlighting under the mouse.

**Step 3e — first widgets.** Button, checkbox, slider, text field, with real behaviour.
*Visible:* each one reacting to input.

**Step 3f — theme file.** `theme.json` drives the colours over the property catalogue,
with validation and live reload (§7). *Visible:* editing a colour in the file changes the
running editor.

**Step 3g — localisation.** Editor text comes from locale files instead of code (§8).
*Visible:* switching the language changes every label.

**Then Phase 4** migrates the real editor panels one at a time, and **Phase 5** deletes Dear
ImGui. Both are described in §11.

### Open decisions

None of these blocks step 3b.

- **Default font** — the friend designing the shell chooses; DejaVu Sans holds the place.
- **Where the shell and locale files live** — beside the install, per project, or per user.
- **Instancing** — deferred until step 3b-4 puts real paragraphs on screen, so the vertex
  upload can be measured instead of estimated.
- **Localisation** — the questions listed at the end of §8.
- **Project licence** — GPL v3 is the likely choice, not yet confirmed. The bundled fonts'
  licences (OFL, Bitstream Vera) are compatible with it.

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
this project exists to remove.

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

C++ offers true zero-GC. But the requirement in §9 is not zero GC, it is **zero allocation
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

- **Stage 1 — `stb_truetype`.** Bitmap atlas at one baked size, no kerning, no shaping.
  Good enough to build every other layer against. *Implemented in step 3a* as `UIFont`,
  extended to Latin-1 in step 3b-1. The range is baked contiguously, control codes and
  all, because stb bakes ranges: skipping the 33 unassigned codes inside it would cost a
  second range to manage in exchange for a few empty atlas cells. `placeGlyph` maps them
  to the replacement character so they read as missing rather than as a blank.
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

## 7. The shell package

**Decision: the editor's appearance and its panel arrangement both live in data files, not
in Java.** This reverses what an earlier draft of this document said in the out-of-scope
section. The reason is a workflow one rather than a technical one: it lets someone design
the editor without writing engine code, and see the result without recompiling.

### The split

The governing rule is that **the editor declares what a thing is, never what it looks like
or where it goes.**

| Owned by the editor (code) | Owned by the shell package (data) |
|---|---|
| A panel named `hierarchy` exists and draws a tree | What a panel looks like |
| A button is a button and reacts to clicks | Which panels are shown, and where |
| Behaviour, state, interaction | Colours, radii, spacing, typography |

No line of editor code names a colour or a pixel position. A panel registers under a stable
id and declares a semantic role; the package maps roles to appearance and ids to positions.
This is the same cut as `<button class="primary">` against `.primary { … }`, and it is what
makes the package genuinely detachable rather than just externalised constants.

### Two files, read once

The shell is two files, because they have different authors and change for different
reasons:

| File | Holds | Written by the program |
|---|---|---|
| `theme.json` | Appearance: colours, radii, spacing, typography | Never |
| `layout.json` | Arrangement: which panels exist, where, at what size | On clean exit, if panels were moved |

Split, they swap independently — one person's theme with another person's arrangement,
without either file knowing about the other.

**The program reads both once at startup and never writes at startup.** Everything after
that works from what was parsed. When the user drags a divider or moves a panel, the change
lives in memory and is written back to `layout.json` when the editor closes cleanly.

A sudden shutdown loses the arrangement changes made in that session. That is accepted: the
alternative is writing during the session, and occasionally redoing a panel arrangement costs
less than a program that keeps rewriting its own configuration.

No temporary copy is needed. With writes happening only on clean exit, the in-memory state
already is the working copy, and a crash discards it either way.

**Both are ordinary editable files, not resources inside a jar.** Editing the interface is
meant to be open to anyone — that is the point of shipping it as data. The default shell is
simply the pair of files that ships with the engine, and changing them is customisation, not
tampering. They also cannot be jar resources for a practical reason: `layout.json` is written
back, and a classpath resource is read-only.

### Format

**JSON, parsed with Gson**, which the project already depends on and already uses for
`.scene` and `.entity`. No grammar to write, no parser to maintain, and one less format for
a contributor to learn. Gson's lenient mode accepts `//` comments, which a hand-maintained
file needs and strict JSON does not have.

**`theme.json`**

```json
{
  "accent":       "#3B82F6",
  "panel.bg":     "#1F2328",
  "panel.border": "@accent",
  "panel.radius": 6,
  "text.body":    "#C9D1D9",
  "spacing.md":   8
}
```

**`layout.json`**

```json
{
  "type": "row",
  "children": [
    { "panel": "hierarchy", "width": 240 },
    { "panel": "viewport",  "grow": 1    },
    { "panel": "inspector", "width": 300 }
  ]
}
```

A value may reference another with `@name`, resolved once at load. Cycles are an error
reported at parse time, not a hang.

**Docking is not a separate system.** A dock arrangement is a layout tree whose dividers can
be dragged, so the shell's `layout` feeds the same L3 solver as everything else rather than
getting a parallel implementation.

### Validation

One rule decides every case: **a structural error refuses to start; a value error falls back
to the default.** A structural error means nothing usable can be built from the file. A value
error means the structure is fine and one property is wrong.

| Situation | Kind | Outcome |
|---|---|---|
| File missing | structure | Refuse to start |
| JSON does not parse | structure | Refuse to start |
| `layout.json` does not form a tree — `"type": "diagonal"`, `children` not a list | structure | Refuse to start |
| Invalid value — `"bleu"` for a colour | value | Default, with a warning |
| Unknown property name — `panel.bgg` | value | Ignored, with a warning; the intended property falls to its default |
| `@reference` to a name that does not exist | value | Default, with a warning |
| `@reference` cycle | value | Default for every property in the cycle, with a warning |
| Invalid size in the layout — `"width": -40` | value | Automatic sizing, with a warning |
| `panel` id not registered in code | value | Slot skipped, with a warning; the rest still lays out |
| Registered panel absent from `layout.json` | — | Not shown — how a minimal layout is authored |
| Property omitted | — | Default, no warning — this is what allows a partial theme |

An unregistered `panel` falls back rather than refusing so that renaming a panel in code does
not stop the editor from starting for everyone with an older `layout.json`.

Default values come from the property catalogue described under *Scope discipline* below:
each supported property is declared with a name, a type and a default. That catalogue is not
a second theme — it is the definition of what is themeable, and falling back reads from it.

Fallbacks reintroduce the risk of a mistake nobody notices, so **the warning carries the
weight**. It names the file, the line and what was expected —
`theme.json:14: panel.bg — expected a colour like "#1F2328", got "bleu"; using default` —
and validation collects every problem in one pass, so a file with three mistakes reports
three at once. Warnings go to the log, and to the editor's own console once it exists.

### Resolution pipeline

Three stages, and which stage runs how often is the whole point:

| Stage | Runs | Produces |
|---|---|---|
| **Parse** | on load and on file change | token table and layout description |
| **Resolve** | when the tree or the tokens change | a baked `Style` struct per node, a built layout tree |
| **Draw** | every frame | reads the baked struct |

**A name is never looked up in the frame loop.** A widget holds an integer handle into the
resolved style table; drawing indexes an array. String hashing per widget per frame would
violate §9 on its own, and it is the mistake that makes most data-driven styling systems
slow.

### Scope discipline

The supported property list is fixed and documented, the way Unity bounds USS. Each entry is
a name, a type and a default value; the validator checks against it and fallbacks read from
it. The token set is bounded by what `UIDrawList` can actually draw: today fill colour, border colour, border
width and corner radius, with typography added when L2 lands. Shadows and gradients are not
tokens until the shader can render them — a file that accepts properties the renderer
silently ignores is worse than one that rejects them.

### Hot reload

Saving either shell file applies the change to the running editor without a recompile or a
restart, so designing is a feedback loop rather than a build cycle.

**This uses its own small watcher, not `AssetWatcher`.** `AssetWatcher` exists for the
projects built with the engine — baking and reloading their assets — and the editor's
interface is not one of those projects. Reusing it would tie two unrelated concerns to one
daemon. The shell watcher lives in `:editor`, which is where the file locations are known,
and runs on its own thread.

It is deliberately small:

- **Directory, not file.** Java's `WatchService` watches directories, so it watches the one
  holding the shell and filters for `theme.json` and `layout.json`.
- **Debounced.** Text editors often save by writing a temporary file and renaming it, which
  produces several events for one save. Events within a short window collapse into one
  reload.
- **It never touches interface state.** The watcher thread only raises a flag. The main loop
  checks it at the start of a frame and does the reload there, on the thread that owns the
  interface — the same discipline `PhysicsThread` follows with its sync lock.

Two rules keep it from fighting the program's own writes:

- An external edit to `layout.json` **replaces** the in-memory arrangement: the file on disk
  is the truth, and panel moves not yet written at exit are discarded.
- The watcher **stops before** the exit write-back, so the program never reloads the file it
  is in the middle of writing.

Reloading follows the same rule as startup with one difference: a structural error cannot
refuse to start an editor that is already open, so it keeps the last valid shell on screen
and reports the error. Value errors fall back to defaults exactly as at startup.

### Open: where the files live

They must be ordinary files on disk. Whether that means beside the engine install (one shell
for every project), inside each project (a per-project editor look) or in the user's config
directory (one per person) is not decided yet.

### Where the cost lands

Addressing panels by id from data requires every panel to be a registration rather than a
hardcoded call. That is not extra work bolted on: it is how panels have to be written anyway
as they move to the framework in Phase 4. The arrangement half of this feature is largely
absorbed by migration work already planned.

---

## 8. Localisation

**Decision: every piece of editor text comes from a locale file, never from code.** It is
the rule of §7 applied to words: the editor declares what a label *means*, and a file
supplies the words for it. The engine is not only for its author, so the editor must be
usable in other languages.

### What the files are

A locale is identified by a language and a region — `en_US`, `pt_BR`, `pt_PT`, `de_DE`,
`ja_JP` — the language as an ISO 639 code and the region as an ISO 3166 code. The region
matters because one language varies between countries.

Each locale is one JSON file mapping keys to text:

**`en_US.json`**

```json
{
  "menu.file":          "File",
  "menu.file.save":     "Save scene",
  "inspector.entities": "{count} entities"
}
```

**`pt_BR.json`**

```json
{
  "menu.file":          "Arquivo",
  "menu.file.save":     "Salvar cena",
  "inspector.entities": "{count} entidades"
}
```

Editor code refers to `menu.file`, never to `"File"`. `{count}` is a placeholder filled in at
display time.

**`en_US` is the reference locale.** It must contain every key, and it is the catalogue of
what can be translated — the same role the property catalogue plays in §7.

### Choosing the language

1. The language chosen in the editor's settings, if there is one.
2. Otherwise the operating system's language, which Java reports through
   `Locale.getDefault()`.
3. For each key, a fallback chain: the exact locale (`pt_BR`), then the language alone
   (`pt`), then `en_US`. A missing Portuguese translation shows English, not a blank.
4. A key missing even from `en_US` shows the key itself — `menu.file.save` — so the gap is
   visible instead of silent, consistent with §7.

### Validation

The same structure-or-value rule as §7:

| Situation | Outcome |
|---|---|
| `en_US.json` missing or does not parse | Refuse to start — it is the reference |
| A translation file missing or does not parse | That locale is unavailable, with a warning; English is used |
| A key missing from a translation | Falls through the chain, with a warning |
| A placeholder missing from a translation — `{count}` dropped | The translation is used as is, with a warning |

### Allocation

Text is looked up when the interface is resolved, not every frame. A label holds a handle to
its resolved string, the way a widget holds a style handle; switching language resolves
everything once. Placeholders are filled into a reused `StringBuilder`, which is why
`addText` takes a `CharSequence`.

The mechanism — loading, fallback, placeholders — lives in `:ui`, since it is not specific to
this editor. The locale files themselves are editor data.

### What translation demands from the text stack

This is where localisation stops being a data problem, because every language needs its
glyphs in the atlas:

| Languages | Script | Cost |
|---|---|---|
| English | ASCII | Done in step 3a |
| Portuguese, Spanish, French, German, Italian | Latin-1 | Done in step 3b-1 |
| Polish, Czech, Turkish, Vietnamese | Latin Extended | Cheap, a larger atlas |
| Russian, Ukrainian, Greek | Cyrillic, Greek | Cheap, a larger atlas |
| Chinese, Japanese, Korean | Thousands of glyphs | The atlas can no longer be baked up front: glyphs must be rasterised on demand into an atlas that grows |
| Arabic, Hebrew | Right-to-left, with shaping | Needs HarfBuzz — stage 3 of the text stack in §6 |

So **the set of supported languages decides how far the text stack has to go.** Latin,
Greek and Cyrillic scripts are close to free once step 3b is done. East Asian scripts change
how the atlas works. Right-to-left scripts need shaping.

Fonts follow the same line. DejaVu Sans covers Latin, Greek and Cyrillic but not Chinese,
Japanese or Korean, which would need a fallback font behind the main one.

### Open questions

- **Which languages ship first.** Suggested: `en_US` and `pt_BR`.
- **Which scripts to plan for.** Latin only for now, or East Asian and right-to-left as
  well — this decides the atlas design, so it is worth settling before step 3b-2.
- **Where the locale files live** — the same open question as the shell files in §7.
- **Plurals.** "1 entity" and "2 entities" differ, and languages disagree on how many forms
  exist — Russian has three, Japanese has none. Simple placeholders now; proper plural rules
  later, if needed.
- **What gets translated.** Suggested: the editor interface only. Log messages stay in
  English, so they can be searched and pasted into bug reports by anyone.

---

## 9. Zero-allocation rules

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

## 10. Required changes in `aengine-core`

Known cross-boundary work. All of it is additive: nothing existing callers use was changed.

**Done:**

- **`RendererAPI`** gained render state — `setDepthTest`, `setBlend`, `setScissor`,
  `disableScissor`, `setRenderTargetSize`, `bindTexture`. Scissor takes top-left
  coordinates and each backend converts, so a Vulkan backend needs no change in `:ui`.
- **`DynamicMeshAPI`**, a vertex and index buffer rewritten every frame from off-heap
  staging, described by a `VertexAttribute` layout (`FLOAT1`–`FLOAT4`, `RGBA8`).
- **`RenderContext.createTexture` from memory**, with `TextureFormat` (`R8`, `RGBA8`), for
  textures the engine generates itself such as the glyph atlas. A separate class from the
  asset-streaming `OpenGLTexture`, which is untouched.
- **`drawBatch`** no longer allocates a fresh array per call.

**Still to do:**

**`Input`** — poll-based today (`isKeyPressed`, `getMouseX`, `getMouseDeltaX`). UI requires
an **edge-triggered event queue**: character input (not key codes), scroll deltas, button
press and release as distinct events, and key repeat. The polling API stays for gameplay;
the event queue is added beside it and drained once per frame by the UI.

**`Window`** — needs to expose content scale (high-DPI), cursor shape control, and clipboard
access. All three are GLFW calls already available through the existing handle.

---

## 11. Migration path

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

**Phase 1 — First light. ✅ Done.** The original plan was a custom render backend for Dear
ImGui, reusing its draw lists as a test bench for the new renderer. That detour was skipped:
`UIDrawList` and `UIRenderer` were built directly, and the phase ended with one rounded
rectangle drawn through draw list, dynamic mesh and SDF shader, composited over ImGui.

**Phase 2 — Complete L1. ✅ Done.** Phase 1 drew one shape type in a single draw call —
enough to prove the pipeline, not enough to build an editor on. Phase 2 added:

1. **A command list.** `UIRenderer` issues one draw for the whole frame. A real draw list
   emits a sequence of `(clip rect, texture, index offset, index count)`, which is what lets
   clipping and texture changes happen partway through a frame.
2. **A clip stack.** Push and pop rectangles. This is how a scrolling list submits ten
   thousand rows and lets the hardware discard the ones outside the panel.
3. **Textured quads.** Required before L2, since text is exactly that: quads sampling a
   glyph atlas.
4. **Borders**, drawn by reading the same distance field twice rather than with extra
   geometry, so a 1px border stays exact at any corner radius.

Together these gave `setScissor`, `disableScissor` and `bindTexture` their first callers.
The vertex was then cut from 21 floats to 13 words by packing both colours as RGBA8,
before text multiplied the vertex count.

**Phase 3 — L2/L3/L4.** Text, layout, widget tree, in that order. This phase is around
**70% of the whole project** — the numbering in this list is not a measure of size, and
every other phase together is smaller than this one. It is broken into steps that each end
with something visible, because a milestone that takes months without one is a milestone
nobody can review.

| Step | Ends when | Layer |
|---|---|---|
| **3a** ✅ | A string of ASCII renders from a glyph atlas | L2 |
| **3b** | Latin-1 ✅, then metrics, kerning and wrapping; laid-out text is cached | L2 |
| **3c** | A row of boxes lays itself out with grow, gap and padding | L3 |
| **3d** | A retained tree survives frames; hit-testing and focus order work | L4 |
| **3e** | Button, checkbox, slider and text field behave correctly | L4 |
| **3f** | `theme.json` drives the colours, a broken file stops startup with a precise error, and saving it reloads live | §7 |
| **3g** | Editor text comes from locale files, following the system language by default | §8 |

The breakdown of 3b into its five parts is in *The plan ahead* at the top of this document.

The arrangement half of the shell package — panels placed from data rather than from code —
lands in Phase 4 instead, because it needs panels that register by id, which is what
migrating them produces.

Step 3a is the real start of the mountain. Everything before it in this document is the 10%
the effort table in §2 assigns to the draw list and GPU backend.

**Phase 4 — Panel-by-panel migration.** ImGui and the new framework coexist, both rendering
through the same backend. Panels move one at a time. Order: stats and physics debug panels
(read-only, trivial) → menu bar → inspector → hierarchy → asset browser → viewport docking.

Each panel arrives as a **registration under a stable id** rather than a hardcoded call, so
the shell package's `layout` can place it. That is the arrangement half of §7, and it costs
almost nothing extra here because a panel being migrated has to be rewritten anyway. The
phase ends when the shipped `layout.json` places every panel, with no arrangement left in
code.

**Phase 5 — Remove Dear ImGui.** Drop the three `io.github.spair` dependencies and the JNI
boundary with them.

### Realistic scope

Reaching "visually better than Dear ImGui, and equally reliable", solo and part-time:
**6 to 18 months**. Slate was built by a team. But the target here is roughly 25 widgets for
one editor, not a general-purpose UI toolkit — the scope is finite and the phases above are
individually shippable.

---

## 12. Out of scope

Stated so they are not rediscovered as surprises:

- **Accessibility APIs** (screen readers, AT-SPI / UIA). Not planned. Reconsider only if the
  editor ever needs to meet an accessibility requirement.
- **A full CSS implementation.** Superseded by §7: appearance comes from a JSON token file.
  A selector-and-cascade rule layer is deferred until L4, and even then it would be a
  bounded dialect with a documented property list, never the CSS specification.
- **Runtime game UI.** This framework targets the *editor*. Whether it later becomes the
  in-game UI system is a separate decision, deliberately deferred.
- **Multi-window / OS-level multi-viewport.** Docking within one window first; detachable
  OS windows much later, if at all.
- **Vulkan backend.** Inherited free when the engine migrates, since the UI only ever talks
  to `RendererAPI`.
