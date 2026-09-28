# Aegis — AEngine's UI Framework: Architecture & Migration Plan

**Status:** In progress. Phases 0–2 complete, Phase 3 started: step 3a (text from a glyph
atlas) is done, step 3b is complete (Latin-1, font metrics, kerning, measuring and
wrapping, and a layout cache), step 3c is complete (rows and columns that nest, grow, align
per axis and skip unchanged solves), and **step 3d is complete**: nodes can be hit-tested,
hovered, pressed, clicked and focused, with Tab walking focus in tree order. The framework
is named **Aegis**. Dear ImGui remains the
transitional editor layer, with Aegis drawing test scaffolding on top of it.
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
| Step 3b-2 | Ascent, descent and line height read from the font; text is placed by the top of its line |
| Aegis | The framework has a name, and a single entry-point object in front of the layers |
| Step 3b-3 | Per-pair kerning from the font's `kern` table, baked into a lookup at load |
| Step 3b-4 | Strings measure to the width they draw at; paragraphs wrap to fit a panel |
| Step 3b-5 | Wrapped layouts are remembered, so unchanged text is not laid out again |
| Step 3c-1 | `AegisLayout`: rows, columns and boxes as handles; children placed with gap and padding |
| Step 3c-2 | Grow: spare space shared in proportion; a row follows the window's width |
| Step 3c-3 | Nesting and alignment per screen axis — `setAlignX` / `setAlignY` with start, centre, end, stretch |
| Step 3c-4 | A solve is skipped when neither the tree nor the rectangle it is given has changed |
| Step 3d-1 | `nodeAt`: the innermost node under a point, the later sibling winning an overlap |
| Step 3d-2 | `AegisTree`: interactive nodes are hovered, pressed with capture, and clicked |
| Step 3d-3 | Focusable nodes; Tab and Shift+Tab walk them in tree order, a press moves focus |
| Step 3e-1 | `Input` event queue: characters, key press / repeat / release, mouse, scroll; own key repeat on Wayland |
| Step 3e-2 | Punctuation beyond Latin-1 packed into the atlas as a second range |
| Step 3e-3 | `AegisWidgets` and the button: sized to its label, activated by click, Enter or Space; colours in `AegisStyle` |
| Step 3e-4 | Checkbox: toggled by click, Enter or Space |
| Step 3e-5 | Slider: dragged with capture; arrows, Shift+arrows, Home/End and `+`/`-` when focused; value shown as a whole number |
| Step 3e-6 | Text field: typing, Backspace/Delete, arrows, Home/End, click to place the caret, scrolling, placeholder, Enter confirms |
| Step 3e-7 | Selection by Shift+keys and dragging, word jumps, Ctrl+A/C/X/V through the system clipboard; typed and pasted text filtered alike |
| Step 3e-8 | Undo and redo in the field being edited, in blocks: a word, a run of spaces, of Backspaces or of Deletes, a paste |
| Step 3e-9 | Text box: several lines wrapped to its width, Enter breaks and Ctrl+Enter confirms, Up/Down keep the column, scrolled by caret or wheel |

### The plan ahead

Each step is done one at a time, and each ends with something visible on screen, so it can
be checked before the next begins.

**Step 3b — text that reads like text** (L2). Five parts, in order:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Latin-1 coverage | Bake characters 32–255 instead of 32–126, so `á ç ã é õ` have glyphs | The test line shows `Olá, ação!` instead of `Ol?, a??o!` |
| 2 ✅ | Font metrics | Ascent, descent and line height from the font file, so text can be placed by its top and lines stack evenly | Two lines placed one line-height apart, touching neither |
| 3 ✅ | Kerning | Per-pair spacing corrections from the font | Pairs like `AV` and `To` visibly tighten |
| 4 ✅ | Measuring and wrapping | The width of a string; a paragraph broken at spaces to fit a width | A paragraph wrapping inside a panel |
| 5 ✅ | Layout cache | Laid-out text cached by content, so unchanged text costs nothing per frame | No visual change — a recompute counter that stops climbing |

**Step 3c — layout** (L3). Rows and columns that size their children with grow, gap and
padding. Four parts, in order — **all four done**:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Fixed placement | `AegisLayout`, reached through `aegis.layout()`: a tree built once, solved each frame; children placed one after another with gap and padding | A row of three unequal boxes with equal gaps and padding |
| 2 ✅ | Grow | Spare space shared among children in proportion to their grow factor | A row along the bottom of the window; A and D fixed, C always twice B while resizing |
| 3 ✅ | Nesting and alignment | Rows inside columns inside rows; children aligned per screen axis with `setAlignX` / `setAlignY` (start, centre, end, stretch) | An outline of the editor's frame — toolbar over hierarchy, viewport, inspector |
| 4 ✅ | Layout cache | A solve skipped when neither the tree nor the space it is given has changed | No visual change — a solve counter that stops climbing, as in 3b-5 |

**Step 3d — retained tree** (L4). Nodes that persist across frames, hit-testing, focus
order. Three parts, in order — **all three done**:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Hit-testing | `layout.nodeAt(root, x, y)`: the innermost node under a point | An outline that moves inward as the mouse crosses frame, pane and box |
| 2 ✅ | Interaction | `AegisTree`, reached through `aegis.tree()`: hover, press with capture, click | Boxes that lighten on hover, darken while pressed, and count their clicks |
| 3 ✅ | Focus order | Focusable nodes; Tab and Shift+Tab in tree order; a press moves focus | A focus ring stepping through nine boxes with Tab |

The declarative build pass of §5 — describing a tree and letting the framework reconcile it
against the existing one — is not part of 3d. It earns its complexity once real widgets
exist, so it is decided with them.

**Step 3e — first widgets.** Button, checkbox, slider, text field, with real behaviour.
Nine parts, in order — **all nine done**:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Input event queue | Characters, key press / repeat / release with modifiers, mouse buttons and scrolling, queued per frame in `:core` (§10) | Typed text, accents included, and a held key repeating evenly |
| 2 ✅ | Punctuation in the atlas | A second atlas range: `– — ‘ ’ “ ” … • € ™` | A line of pasted-style punctuation with no `?` |
| 3 ✅ | Widgets and the button | `AegisWidgets` through `aegis.widgets()`; a button sized to its label, activated by click, Enter or Space; colours from one `AegisStyle` | Play / Pause / Stop buttons that react to mouse and keyboard |
| 4 ✅ | Checkbox | Toggles on click, Enter or Space | Boxes that tick and untick |
| 5 ✅ | Slider | Drag with capture; arrows (Shift for ten steps), Home/End, `+`/`-` when focused; the value is a float, shown as a whole number | A value following the drag |
| 6 ✅ | Text field: editing | Caret, typing, Backspace/Delete, arrows, Home/End, click to place the caret, long text scrolling inside the field; a placeholder while empty; Enter confirms, Esc leaves | Typing and editing in a field |
| 7 ✅ | Text field: selection and clipboard | Shift+arrows and mouse-drag selection; Ctrl+arrows by word; Ctrl+A; copy, cut, paste through clipboard access in `Window` (§10); `textField(parent)` with no length limit | Selecting, copying and pasting text from outside |
| 8 ✅ | Text field: undo | Ctrl+Z, Ctrl+Y / Ctrl+Shift+Z, in blocks: typed characters up to a space, a run of spaces, of Backspaces, of Deletes, a paste or cut; a second's pause ends a block; up to 100 blocks | Undoing a sentence a word at a time |
| 9 ✅ | Text box | `ae.textBox(parent, lines)`: several lines wrapped at its width, sharing the field's text, caret, selection, clipboard and undo; Up/Down keeping the column; Home/End per line, with Ctrl per text; Enter breaks the line and Ctrl+Enter confirms; scrolled by the caret and by the wheel through `ae.scroll` | Writing and editing a paragraph |

The undo in part 8 belongs to the field being edited and is dropped when focus leaves it.
Undoing what was done to the scene — a renamed entity, a moved object — is the engine's own
undo, through its command system; that remains a step of its own later, and is what Ctrl+Z
reaches once no field has focus.

The text box is a method of its own rather than a flag on `textField`, as Swing has
`JTextField` and `JTextArea`: a `true` at the call site does not say what it means, and Enter
changes meaning — it breaks the line instead of confirming — which the name should make
plain.

**Naming convention for the examples and scaffolding.** The long form,
`aegis.widgets().button(...)`, is always right. When the widgets object is kept in a short
local variable, it is called **`ae`** — `AegisWidgets ae = aegis.widgets(); ae.button(...)` —
never a generic `ui`: a short name should still say where the thing comes from, which is the
same objection as to `using namespace std` in C++.

The punctuation range exists because pasting is exactly what a text field introduces. The
no-break space (`0xA0`) needed nothing: it is inside Latin-1 and was already baked. Packing a
second range meant moving from stb's one-call baker, which takes a single contiguous range,
to its packer; at 1x oversampling the packer rasterises identically, so existing text is
unchanged.

**Step 3f — theme file.** `theme.json` drives the colours over the property catalogue,
with validation and live reload (§7). *Visible:* with the development option on, saving a
colour in the file changes the running editor.

**It stays after 3e.** Bringing it forward to sit between 3b and 3c was considered and
declined. The token names would have been chosen against test scaffolding, and a theme file
freezes its names the moment someone writes one — renaming `panel.bg` later breaks every file
that uses it. Once layout and the first widgets exist, the names describe real things rather
than guesses. Until then, colours in scaffolding are kept grouped in one place rather than
scattered through drawing calls, so the migration is moving a block, not hunting literals.

**Its scope is settled.** Two built-in themes, dark and light, ship with the installation;
themes a user makes go in their data directory. A theme dresses the editor's
frame and never the content being edited, and it is **only ever read** — the arrangement half
of §7, `layout.json`, stays in Phase 4 where it belongs, so nothing is written back in this
step. Both decisions are recorded in §7. A theme changes while running by two separate
paths: the user choosing one in settings, and — behind a development option, off by
default — the author's saves applying live (§7, *Changing the theme*).

Four parts, in order:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 | Property catalogue | The closed list of what is themeable — name, type, default — which validation checks against and fallbacks read from | Nothing on screen; it is the definition the next three depend on |
| 2 | Load and resolve | `theme.json` parsed, `@name` references resolved once, a baked style table indexed by handle | The scaffolding draws in the file's colours instead of Java's literals |
| 3 | Validation | A file with three mistakes reports all three, naming file, line and what was expected; value errors fall back, structural errors refuse to start | A deliberately broken file produces three precise warnings and still starts |
| 4 | Reload | One in-place reload that invalidates everything resolved from the old theme; the development option's watcher drives it on save; a structural error keeps the last valid theme | With the option on, saving a colour changes the running editor; saving a broken file keeps the old theme and reports why |

Parts 1 and 2 go together, since a catalogue with nothing reading it shows nothing. Parts 3
and 4 are what turn externalised constants into something another person can edit without
fear. The settings page that lets a user pick a theme is built with the real settings panel
in Phase 4; it calls the same reload part 4 delivers.

**Step 3g — localisation.** Editor text comes from locale files instead of code (§8).
*Visible:* switching the language changes every label.

**Then Phase 4** migrates the real editor panels one at a time, and **Phase 5** deletes Dear
ImGui. Both are described in §11.

### Open decisions

Step 3d is closed; none of these blocks step 3e, which follows it.

- **Default font** — the friend designing the shell chooses; DejaVu Sans holds the place.
- ~~**Where the shell, font and locale files live**~~ — **decided:** built-in dark and light
  themes, the default font and the locale files in the installation (locales in
  `<install>/lang/`); user themes, user fonts and `layout.json` under `AEngine/ui/` in the
  user's data directory (`~/.local/share/AEngine/ui/`, `%LOCALAPPDATA%\AEngine\ui\`). Not per
  project. See §7 and §8.
- **Instancing** — 3b-4 has put real paragraphs on screen, so the size of the question is now
  known even though it has not been profiled. The scaffolding draws roughly 700 quads, of
  which about 320 are one wrapped paragraph. At 13 words a vertex that is ~146 KB of vertex
  data uploaded per frame, or ~31 MB/s at the ~210 fps the editor runs at. That is arithmetic
  from the quad count, not a measurement, and it is small enough that instancing stays
  deferred — but the number to beat is now written down rather than guessed at. Worth
  revisiting when a panel shows thousands of rows, which is what the asset browser will do.
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

So does **JPMS**: a `module-info.java` per module, with `:core` exporting only what the UI
may see, would make the boundary a compiler and runtime guarantee rather than a text scan.
Nothing blocks it — no package is split across modules. It is deferred because it
restructures the whole build to solve a problem `checkBoundary` already solves while Aegis
lives inside the engine. The moment to adopt it is when Aegis moves to its own repository
(*When to split* below), which is planned; the module boundary then becomes the published
API anyway.

### What the split exposed

Splitting the modules immediately surfaced a dependency that had been invisible in the
single-module build: `RenderSystem` and `CameraSystem` — engine ECS code — imported
`com.aengine.Main` to read `Main.getActiveRenderMode()`. The engine core depended on the
application host.

`RenderMode` was moved to `com.aengine.core` as engine state, which is what it always was:
the systems branching on it must not care which host started the engine. This is the kind
of drift a module boundary catches on the next build instead of at the next rewrite.

### The name

The framework is called **Aegis**, and its types carry that name: `AegisDrawList`,
`AegisRenderer`, `AegisFont`. It is the shield of Athena — a surface raised in front of
something — which is what an interface layer composited over the viewport is.

The point of naming it at all is the one Unreal makes with Slate: a framework with a name of
its own is a thing that can be talked about, documented and eventually extracted, whereas
"the UI code" is a folder. An acronym like `AEUI` would have been the folder with extra
steps, so the "AE" of the engine had to land inside a real word rather than be prefixed onto
one.

**The Gradle module stays `:ui` and the directory stays `ui/`.** The module says what the
code is *for*, the package says what it *is* — `com.aengine.aegis`. Renaming the module would
also move the published coordinate `aengine-ui`, which is not worth doing mid-flight. If
Aegis ever leaves for its own repository, as the section below describes, that is the moment
to reconsider.

`com.aengine.core.UILayer` and the editor's `ImGuiUILayer` keep their names deliberately:
they are the engine's generic hook for *any* interface layer, which today is Dear ImGui and
tomorrow is Aegis. Naming the hook after one of its implementations would be the mistake the
hook exists to avoid.

### Target layout

```
AEngine/
├── settings.gradle     include ':core', ':ui', ':editor'
├── core/               renderer, ECS, physics, audio, assets, scripting
├── ui/                 Aegis, the UI framework — depends on core, knows nothing above it
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

### The entry point

The four layers are the structure; `Aegis` is the door. It owns the renderer, the draw list
and the current font, so a caller creates one object and talks to it:

```java
Aegis aegis = new Aegis(512);
aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);

aegis.begin(width, height);
aegis.addRoundedRect(x, y, 220f, 36f, 8f, 0.16f, 0.18f, 0.23f, 1f);
aegis.addTextTop(x + 14f, y + 9f, "Save scene", 0.9f, 0.92f, 0.95f, 1f);
aegis.end();
```

Three things leave the call site: the renderer and draw list variables, and the font argument
that every text call previously carried. `end()` closes the list and presents it, because
every caller did both in that order every time; splitting them bought no freedom and only
offered the chance to forget the second.

The layers stay public and reachable through `drawList()`. The front door is a convenience,
not a wall — hiding L1 would contradict the claim above that each layer is independently
useful.

**The failure mode to watch.** Every drawing call is a forwarding method here, which is cheap
at a dozen and absurd at two hundred. When L4 lands, the widget tree must be *reached
through* `Aegis` — handed out as an object — rather than flattened into one method per
widget. Dear ImGui can afford `ImGui::Button` as a free function because it has no retained
tree to address; this framework does.

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

**Clipping stays on the scissor for now; clipping in the shader is recorded as the next
move.** A scissor change splits the frame into another draw command. Moving the clip into
the fragment shader — each vertex carrying its clip rectangle, the shader discarding what
falls outside — would let differently clipped panels share one draw. The draw-call saving
alone is not worth it: a few dozen draws a frame cost nothing on desktop OpenGL, and each
vertex would grow by two to four words. The argument that *is* worth it is shape: a scissor
only clips to straight rectangles, so a scrolling list inside a panel with rounded corners
leaks at the corners, while a clip evaluated as a distance field follows them. Decide when
the first scrolling panel is built, in 3e or Phase 4.

### L2 — Text

Staged deliberately, because this is where projects of this kind stall.

- **Stage 1 — `stb_truetype`.** Bitmap atlas at one baked size, no kerning, no shaping.
  Good enough to build every other layer against. *Implemented in step 3a* as `AegisFont`,
  extended to Latin-1 in step 3b-1. The range is baked contiguously, control codes and
  all, because stb bakes ranges: skipping the 33 unassigned codes inside it would cost a
  second range to manage in exchange for a few empty atlas cells. `placeGlyph` maps them
  to the replacement character so they read as missing rather than as a blank. Step 3b-2
  added the vertical metrics, so text is positioned by the top of its line box rather than
  by a baseline the caller had to guess.

  Two things about those metrics are worth recording, because both are easy to get wrong.
  The line height is **rounded to a whole pixel**: glyphs are snapped to whole pixels to
  keep the atlas sampled 1:1, so a fractional line height would put successive baselines at
  14.6, 29.2, 43.8 and those snap to gaps of 15, 14, 15 — invisible in two lines, obvious in
  a paragraph. And `stbtt_ScaleForPixelHeight` scales the font so that ascent plus descent
  equals the requested pixel height, so for a font whose line gap is zero — DejaVu Sans is
  one — the line height comes out equal to the baked size and looks like it added nothing.
  It has: the **ascent** is the number the pixel height cannot supply, and the line height
  does diverge as soon as a font asks for leading.

  Step 3b-3 added kerning. Two things about it are worth recording. It reads the **legacy
  `kern` table, which is all stb_truetype parses** — a font that keeps its kerning in `GPOS`
  yields nothing and needs HarfBuzz, so `AegisFont` warns rather than letting text stay
  quietly loose. DejaVu Sans was checked before the code was written and has 2,727 entries,
  of which 1,087 pairs fall inside the baked Latin-1 range. And the table is **baked dense at
  load**, 224×224 floats, which keeps the frame loop to an array index instead of a hash or a
  native call per character. Only about 2% of the pairs carry a correction, so the table is
  mostly zeros — 200 KB, which is nothing. **That choice is bounded by the alphabet:** it
  holds for Latin, Greek and Cyrillic, and the moment the atlas covers CJK the square grows
  past any sane allocation and this must become sparse.

  The sparse form, when it comes, is a list per first glyph: the second glyphs it pairs
  with, sorted, and their corrections. DejaVu averages about five pairs per first glyph, so
  a lookup is a handful of comparisons. Two shortcuts are rejected in advance: indexing
  `rowOffset[a] + b` only moves the dense square around, and storing corrections as
  `byte` loses the sub-pixel precision kerning exists for. The trigger is the atlas growing
  past Latin-1 — Latin Extended or Cyrillic for localisation — not the 200 KB, which is a
  necessary cost the engine can carry.

  Step 3b-4 added measuring and wrapping, and both turn on the same rule: **a paragraph is
  one string and a set of ranges into it, never a set of substrings.** `String.substring` per
  line per frame is precisely the allocation §9 forbids, so `measure` and `addText` both take
  a range, and `wrap` writes line boundaries into arrays the caller owns.

  `wrap` reports each line's **start and end, not just its end**, because the two are not
  adjacent: a line that breaks at a space ends before it and the next begins after it.
  Inferring the start from the previous end would put that space at the head of the next
  line, where it draws nothing but still advances the pen — an invisible indent with no
  visible cause. A word wider than the limit is broken mid-word, which is the only option
  that terminates; refusing to break loops forever and overflowing puts text outside the
  panel it was asked to fit.

  Measuring and drawing resolve a character through the same `glyphIndex`, so an unprintable
  character falls back to the replacement glyph identically in both. If they disagreed, a
  measured width and a drawn width would differ exactly on the strings where it is hardest to
  notice.

  Step 3b-5 closed the stage with a layout cache, and its shape is worth recording because
  the obvious implementation is the wrong one.

  It is **direct-mapped, not a hash map**: the slot is `hash & (CAPACITY-1)` and a colliding
  entry simply replaces the one there, the way a CPU cache works. No probing, no eviction
  policy, no growth — nothing to allocate, nothing to leak, no per-frame bookkeeping. Two
  paragraphs colliding on one slot evict each other every frame, which is a
  worse-than-nothing case rather than a common one at 128 slots.

  A 2- or 4-way set-associative cache was considered and deferred. Only wrapped paragraphs
  go through this cache — single-line labels, which are what a dense hierarchy or inspector
  is made of, never do — so a busy panel does not fill it. If collisions ever start costing,
  the on-screen recompute counter climbs in a still window, and that is the signal to make
  the change, which is small.

  **The key is verified, never trusted.** A hash alone would eventually serve one paragraph's
  line breaks for another — rare, silent and baffling — so each entry stores a copy of its own
  text and a hit is confirmed character by character. The hash is what makes the comparison
  rare; the comparison is what makes the answer right.

  **`maxWidth` and the kerning setting are part of the key.** The width obviously changes
  where lines break. Kerning does too, and the first attempt emptied the cache whenever it was
  toggled — which the scaffolding's kerned-versus-unkerned comparison does twice a frame,
  so the cache never once hit. Keyed instead, the two variants simply coexist.

  **Everything is bounded.** Text beyond 512 characters, or wrapping past 64 lines, is not
  cached at all and is laid out directly each time. Refusing to hold it beats growing a
  buffer inside the frame loop, which §9 forbids, and beats truncating the text silently.

  The cache has no visual effect, which is the difficulty in checking it. It therefore counts
  recomputes, and the scaffolding draws that count: it climbs while text or widths are new and
  **stops climbing** once they are not, ticking up once more on a resize. A steady-state frame
  that still increments it is a cache that is not working.
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

*Implemented* as `AegisLayout` (step 3c), handed out by `aegis.layout()` rather than
forwarded method by method, per the rule in *The entry point*. A few things about it are
worth recording.

**Building and solving are separate.** The tree — what contains what, what each node asks
for — is built once. The solve turns it into a rectangle per node every frame, and drawing
reads those rectangles back. The tree rarely changes and the space it is given often does, so
a window resize reruns only the solve.

**Children are a linked list threaded through the arrays**: each node records its first
child, last child and next sibling. Walking the children is two array reads per step, with no
list object or iterator to allocate, and appending is constant time because the last child is
kept.

**Grow adds to what a child asked for; it does not replace it.** The solve walks the children
twice — once to sum what they ask for plus the gaps, which tells how much is spare, and once to
place them, giving each grower its proportional share. A child that should be sized by its
share alone asks for 0. When the children already overflow, nothing grows and fixed children
run past the end: **shrink is not implemented**, and is added only when a real panel needs it.

**Edges are rounded to whole pixels, not sizes.** Sharing spare space makes widths fractional,
and a fractional edge is drawn half-covered — a soft edge on what should be a crisp box. The
cursor is kept exact and only each child's start and end are rounded, so the error never
accumulates and every gap stays exactly the gap asked for. The same reasoning as the rounded
line height in L2, applied to boxes.

**Alignment is named by screen axis, not by main and cross.** `setAlignX` and `setAlignY`
each take start, centre, end or stretch, and mean the same thing on a row as on a column:
`setAlignY(CENTER)` centres vertically either way. The solve translates them into main and
cross once, because that is what its walk needs. Along the axis the children follow each
other on, alignment moves the group as a whole within the spare space — which is how a
single box is centred in a pane, with no spacer nodes. Across the other axis it places each
child, and there stretch fills the pane edge to edge, which is what lets panels nest into a
frame without knowing its size. Stretch along the flow axis is refused when the tree is
built, since that is what grow does. A CSS-style `justify` property was considered and
declined in favour of this: one concept per axis rather than two properties with different
vocabularies, and distributing space between children can still be done with growing boxes
when a panel needs it.

**A solve that could not move anything is skipped.** Every real change to the tree bumps a
version number; each root remembers the version and the rectangle it was last solved with,
and `solve` returns at once when both match. Setters handed the value a node already has
change nothing, so a tree rebuilt with the same values every frame keeps the cache. The
granularity is the whole tree: any change re-solves all of it. At dozens of nodes a full
solve is cheap, and tracking dirty subtrees is worth its complexity only once widgets
change on their own — that is for L4 to decide. As with the text cache, the scaffolding
draws a solve counter that stops climbing when the window is still.

When the re-solve does need narrowing, the mechanism is **layout boundaries**: a subtree
whose size cannot change what its parent sees — a pane of fixed size, say — contains its
own changes, and a change inside it re-solves only that subtree. That belongs with L4, when
nodes start changing on their own.

**Rounding under display scaling — the rule for when content scale arrives.** Today every
coordinate is a physical pixel, so rounding to whole numbers is exactly right. Once
`Window` exposes content scale (§10) and layout works in logical pixels, rounding must
happen **in physical pixels** — `round(x * scale) / scale` — or at 150% an edge lands
between device pixels and jitters. Leaving positions fractional and trusting the SDF to
smooth them is the wrong fix: it brings back the soft edges rounding exists to prevent.

### L4 — Widget tree

Nodes are **integer handles into struct-of-arrays storage**, not object references. Java 25
has no value types — Valhalla is not stable — so flat primitive arrays (or `MemorySegment`
via the FFM API) are how a tree is represented without an object per node.

*Started* as `AegisTree` (step 3d), handed out by `aegis.tree()`. What is worth recording:

**One tree, not two.** An `AegisTree` node is the same `int` as the `AegisLayout` node; L4
only adds its own arrays of markings beside L3's. There is nothing to keep in sync, and the
layout's rectangles are directly what the pointer is tested against. Hit-testing itself
(`nodeAt`) lives in L3, since it is a purely geometric question about solved rectangles.

**Input is handed in, not read.** `update(root, mouseX, mouseY, buttonDown)` takes the
pointer as arguments. The caller decides what the interface may see — the editor hides the
pointer from Aegis while it is over a Dear ImGui window, which only the editor can know.

**Markings are separate.** A node is *interactive* (reacts to the pointer) and/or
*focusable* (takes the keyboard) independently: a text field takes focus without being a
button. The hit-test finds the innermost node, and the tree walks up to the nearest node
with the marking it needs, so pressing a label presses its button.

**A click is a press and a release on the same node.** The node a press lands on captures the
pointer until release: nothing else is hovered meanwhile, and it reads as hovered only while
the pointer is over it. Releasing elsewhere cancels, and a widget drawn pressed only while
also hovered shows the user that it would.

**Focus follows the tree.** Tab and Shift+Tab step through focusable nodes in tree order — a
node, its children in the order they were added, then its next sibling — which is reading
order for an interface built top to bottom and left to right, with no order maintained by
hand. The walk uses the layout's own parent, child and sibling links, with no stack. A press
moves focus to the focusable node under it, or clears it.

**Edges are found by comparing frames**, which is enough for a mouse button and for Tab but
not for typed characters or key repeat. The input event queue in §10 is therefore due before
the text field in 3e.

The pointer state is four integers — at most one node hovered, pressed, clicked and focused
at a time — so it allocates nothing.

*Widgets* are `AegisWidgets` (step 3e), handed out by `aegis.widgets()`. What is worth
recording:

**One generic call per kind, not one per use.** `ae.button(parent, "Play")` creates a layout
node, marks it interactive and focusable, and records its kind and label in the widgets' own
arrays — the same handle again. The caller asks `ae.wasActivated(playButton)` each frame;
there are no callbacks and no object per widget.

**Keyboard intent is handed in, like the pointer.** `ae.key(key, mods, repeat)` and
`ae.character(codepoint)` are fed from the `Input` event queue by the caller, which decides
whether the interface has the keyboard (the editor withholds it while Dear ImGui wants it).
`ae.update(root)` then applies what arrived: Tab and Shift+Tab move focus, a click or Enter
or Space *acts* on a widget — Enter on any kind, so the whole interface works from the
keyboard alone — and a focused slider takes arrows, Home/End and `+`/`-`.

**Acting is defined per kind in one place.** A button is activated, a checkbox flips and
reports a change. A slider follows the pointer while it holds the capture, clamped to its
range, and steps by a hundredth of it from the keyboard, or a tenth with Shift.

**A text field edits as the keys arrive.** The other widgets gather a frame's keys and apply
them in `update`; a focused text field applies each key and character the moment it is
handed in, because typing, deleting and typing again in one frame must come out in that
order. Its text lives in a `StringBuilder` sized to the field's limit when the field is
made, so editing allocates nothing, and `text()` hands that buffer out as a `CharSequence`
for reading. The caret is placed by walking advances and kerning exactly as drawing does, so
a click lands between the characters it looks like it lands between.

**Selection is an anchor and the caret**, one more `int` per field; there is a selection when
they differ. The clipboard is reached through `Window`, handed to the widgets once with
`ae.setClipboard(window)` like the pointer and keys are handed in. Copying gathers the
selection in a reused builder; pasting allocates only the `String` GLFW returns, once per
Ctrl+V.

**Undo is one history, for the field with focus.** Blocks are kept in arrays and one
`char[]` made once — each block a replacement: at a position, this text removed, that text
inserted — so undoing and redoing allocate nothing. The history is dropped when focus leaves
the field or code calls `setText`.

**A text box is a text field with lines.** It shares the field's state — text, caret,
anchor, scroll, now vertical — and its editing; what it adds is where lines are. They are
not stored: `AegisFont.wrap` breaks the text into ranges when the box is drawn or a key
needs them, into one pair of arrays reused by every box, and a line is drawn as a range of
the text, never a substring. The caret follows the text into view only when it moves, so a
box scrolled with the wheel stays where it was put. Up and Down aim at the place across the
line where a run of them began, so a short line on the way does not drag the caret left.

**Text from the user is data, never instructions.** Nothing in Aegis executes what a field
holds. What the field itself guards against is characters that act on whatever displays the
text: typed and pasted text pass the same filter, which refuses control characters — among
them ESC and CSI, which start the escape sequences a terminal obeys, so text reaching the
log cannot clear it, hide lines or retitle the window — and the bidirectional controls, which
make `file\u202Etxt.exe` display as `fileexe.txt`. Keeping the text clean does not make it safe to
*use*; that is checked where it is used:

- **Never build a shell command by joining strings.** Run processes with `ProcessBuilder` and
  a list of arguments, so no shell ever parses the text and `; rm -rf ~` is one literal
  argument, not a second command.
- **Normalise and check paths** before touching the file system: an asset named
  `../../.bashrc` must not reach outside the project.
- **Write text into files as data**: the scene serializer escapes quotes and backslashes, so
  a name cannot break the JSON around it.

**Colours and sizes live only in `AegisStyle`**, a class of public fields no drawing code
bypasses. It is what 3f turns into the theme's property catalogue, which is why the theme
waits for the widgets: the names are chosen against real ones.

The widget set an engine editor actually needs, which is finite:

- Text: label, text field, multi-line editor
- Numeric: drag-field, slider, vector2/3/4 field
- Choice: button, checkbox, radio, combo box, colour picker
- Structural: panel, collapsible header, tab bar, splitter, **dock host**
- Collections: tree view, **virtualized list** (the asset browser will hold thousands of
  entries — virtualization is not optional). The first one is built with **fixed-height
  rows**: which rows are visible is then one division, and most of the edge cases that make
  virtualized lists hard — rows measured after they scroll into view, a scroll position that
  shifts as they are — do not arise. Variable heights, and grids of asynchronously loaded
  thumbnails, come after, if a panel needs them.
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

### Two files

The shell is two files, because they have different authors and change for different
reasons:

| File | Holds | Written by the program |
|---|---|---|
| `theme.json` | Appearance: colours, radii, spacing, typography | Never |
| `layout.json` | Arrangement: which panels exist, where, at what size | On clean exit, if panels were moved |

Split, they swap independently — one person's theme with another person's arrangement,
without either file knowing about the other.

**The program reads both at startup and never writes at startup.** Everything after that
works from what was parsed; the theme alone can be read again while running, as described
under *Changing the theme*. When the user drags a divider or moves a panel, the change
lives in memory and is written back to `layout.json` when the editor closes cleanly.

A sudden shutdown loses the arrangement changes made in that session. That is accepted: the
alternative is writing during the session, and occasionally redoing a panel arrangement costs
less than a program that keeps rewriting its own configuration.

No temporary copy is needed. With writes happening only on clean exit, the in-memory state
already is the working copy, and a crash discards it either way.

**Both are ordinary editable files, not resources inside a jar.** Editing the interface is
meant to be open to anyone — that is the point of shipping it as data. Customising means
adding a theme of one's own beside the built-in ones, as described under *Where the files
live*. They also cannot be jar resources for a practical reason: `layout.json` is written
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
| **Parse** | at startup; for the theme, again on a theme switch or — with the development option on — a save | token table and layout description |
| **Resolve** | when the tree or the tokens change | a baked `Style` struct per node, a built layout tree |
| **Draw** | every frame | reads the baked struct |

**A name is never looked up in the frame loop.** A widget holds an integer handle into the
resolved style table; drawing indexes an array. String hashing per widget per frame would
violate §9 on its own, and it is the mistake that makes most data-driven styling systems
slow.

### Scope discipline

The supported property list is fixed and documented, the way Unity bounds USS. Each entry is
a name, a type and a default value; the validator checks against it and fallbacks read from
it. The token set is bounded by what `AegisDrawList` can actually draw: today fill colour, border colour, border
width and corner radius, with typography added when L2 lands. Shadows and gradients are not
tokens until the shader can render them — a file that accepts properties the renderer
silently ignores is worse than one that rejects them.

### Changing the theme — two paths

A theme changes in a running editor in one of two ways, and they are kept deliberately
apart.

**The user switches themes.** Settings has an *Interface and themes* page listing the
installed themes, built-in and the user's own. The user picks one and confirms; the editor
reloads the interface with it, in place, without restarting. Editing a theme file changes
nothing on this path — what the user sees only ever changes because they chose it.

**The theme author edits live.** Saving a theme file applies it to the running editor almost
at once, so designing is a feedback loop rather than a build-and-restart cycle. This is a
**development option**, off by default, in the same settings page, shown with a note that it
is recommended for theme development only. An end user never has their interface change
under them because a file was touched.

Both paths end in the same reload, and the reload is the hard part: switching a theme must
invalidate everything resolved from the old one — the baked style table, cached text
layouts, glyph atlases once typography is themeable — and leave none of it stale. That was
the reason hot reload was first dropped, in favour of a restart that gets it right by
construction. It came back because the theme author's loop matters, and because the same
reload also lets a user switch themes without restarting. It is done once, carefully, and
both paths share it.

A structural error during a reload cannot refuse to start an editor that is already open,
so it **keeps the last valid theme on screen** and reports the error; value errors fall back
to defaults exactly as at startup. A broken edit therefore never leaves the editor
unusable — the author sees the message, fixes the file, saves again.

**The watcher is small and the editor's own — never `AssetWatcher`.** `AssetWatcher` serves
the projects built with the engine, and the editor's interface is not one of them. The theme
watcher lives in `:editor`, where the file locations are known, runs only while the
development option is on, and:

- **watches directories, not files** — `WatchService` works that way — filtering for the
  theme in use;
- **is debounced**, since editors often save by writing a temporary file and renaming it,
  which raises several events for one save;
- **never touches interface state.** Its thread only raises a flag; the main loop checks it
  at the start of a frame and reloads there, on the thread that owns the interface — the
  discipline `PhysicsThread` follows with its sync lock.

It watches themes only. `layout.json` is written back by the program (Phase 4) and is not
live-reloaded, which keeps the watcher from ever reacting to the editor's own writes.

### Where the files live — decided

Two places, for two kinds of theme. "`theme.json`" elsewhere in this document stands for
any theme file.

**Built-in themes ship with the installation**, in `<install>/ui/themes/`. There are two, a
dark one and a light one. They are not meant to be edited — nothing stops a curious user
from opening them, but an update to the engine may replace them. Because they always come
with the engine, a fresh machine starts with a working theme and nothing has to be copied
anywhere on first start.

**Themes the user makes go in the user's data directory**, the place each operating system
reserves for an application's per-user files, under `AEngine/ui/`:

| System | Directory |
|---|---|
| Linux | `$XDG_DATA_HOME/AEngine/ui/`, which defaults to `~/.local/share/AEngine/ui/` |
| Windows | `%LOCALAPPDATA%\AEngine\ui\`, i.e. `C:\Users\<name>\AppData\Local\AEngine\ui\` |
| macOS | `~/Library/Application Support/AEngine/ui/` |

A new theme is a file placed in `themes/` inside that directory. The procedure — how to
start from a built-in theme and where to put the result — is for user documentation, not
for the editor to guide.

`layout.json`, which the program writes back in Phase 4, also lives under `AEngine/ui/` and
**never in the installation**, so an engine update cannot reset a user's arrangement. Not in
a cache directory either (`~/.cache`): the system and cleaning tools treat a cache as
disposable and empty it, which would reset the arrangement just the same.

**Fonts a user adds** go in `fonts/` under the same directory. The default font ships with
the engine. A user's font may lack glyphs a language needs — a Latin-only font with the
editor in Russian — so any character missing from it is drawn from the default font instead.
That is the glyph fallback §8 already calls for, and it needs the font set planned for L2.

Both locations are resolved in one place in `:editor`, so nothing else in the code knows
which system it is on.

The per-project option — each game project carrying its own editor appearance — is
**rejected**, and the test that rejects it is worth keeping: *is the file needed for the game
to exist, or only for the editor to look the way it looks?* A scene, its entities, its assets
and its scripts belong to the project. How the editor is painted belongs to whoever operates
the engine, and the editor should look the same whichever project is open. The same answer
applies to the locale files in §8 for the same reason.

Keeping everything beside the installation was the earlier choice, and it was split this
way. A directory the engine is installed into is often not writable by an ordinary user —
`/opt/aengine`, `C:\Program Files\AEngine` — which breaks two things: `layout.json`, which
the program writes back, and the claim that anyone can make a theme without administrator
rights. The user's data directory is always writable by its owner and survives reinstalling
or updating the engine; the installation keeps only what the engine itself guarantees. The
future theme list shows both kinds together.

### What the theme may and may not dress

**The theme dresses the editor's frame, never the content being edited.** A colour in
`theme.json` changes panels, tabs, buttons, text fields and labels. It must never reach the
viewport's grid, the transform gizmos, the colour of a sprite or anything else the project
owns — those belong to the engine and to the scene, and a theme that could repaint them would
be restyling the user's work rather than the tool.

This is checkable rather than aspirational: if editing a colour in the shell file changes
anything inside the viewport, that is a defect.

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
- ~~**Where the locale files live**~~ — **decided:** with the engine, in `<install>/lang/`.
  Not per project, since the language the editor speaks belongs to whoever operates the
  engine, and not in the user's data directory like themes: translation keys change with each
  engine version, so the files must be updated with the engine rather than kept as a user's
  copy.
- **Plurals.** "1 entity" and "2 entities" differ, and languages disagree on how many forms
  exist — Russian has three, Japanese has none. Simple placeholders now; proper plural rules
  later, if needed.
- **What gets translated.** Suggested: the editor interface only. Log messages stay in
  English, so they can be searched and pasted into bug reports by anyone.
- **Input methods (IME).** Typing Chinese, Japanese or Korean goes through the operating
  system's input method, which draws its own composition box and needs to be told where the
  caret is. That takes platform-specific hooks beyond what GLFW's character callback gives.
  Not a problem while CJK is not planned; it becomes one the day it is, alongside the
  growing atlas in the table above.

---

## 9. Zero-allocation rules

These are binding constraints on `aengine-ui`, not aspirations.

**They are about allocation rate, not memory footprint.** A structure of a few kilobytes or
megabytes that buys correct behaviour — a dense kerning table, a layout cache — is an
acceptable, necessary cost, allocated once. What this project exists to avoid is the other
kind: a runtime like the removed WebKit frontend, which cost hundreds of megabytes simply by
existing. Memory is not squeezed for its own sake; an optimisation that only saves memory
needs a concrete problem behind it.

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
- **`Input`** gained an **event queue** beside its polling API (step 3e-1): characters as
  code points, key press / repeat / release with modifier bits (`Keys.MOD_SHIFT`,
  `MOD_CONTROL`, `MOD_ALT`), mouse press and release, and scrolling. Parallel arrays sized
  once, emptied at the start of each `Input.poll()` — which the engine loop now calls instead
  of `glfwPollEvents` — so during a frame it holds that frame's events; overflow is dropped
  and counted, never grown. Polling is unchanged for gameplay.

  **Key repeat is our own on Wayland.** Wayland leaves repeating to the client, and the GLFW
  bundled with LWJGL 3.3.4 (3.5.0-dev) does not service its repeat timer every poll: repeats
  piled up and arrived in bursts of twenty, seconds late. On Wayland, GLFW's repeats and
  their characters are ignored and `Input` repeats the held key itself — after 500 ms, 20 a
  second, each repeat carrying the character the key typed. X11 and Windows keep the
  operating system's repeat. The cost is that on Wayland the user's own repeat settings are
  not honoured, since GLFW does not report them.

**Still to do:**

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
`AegisDrawList` and `AegisRenderer` were built directly, and the phase ended with one rounded
rectangle drawn through draw list, dynamic mesh and SDF shader, composited over ImGui.

**Phase 2 — Complete L1. ✅ Done.** Phase 1 drew one shape type in a single draw call —
enough to prove the pipeline, not enough to build an editor on. Phase 2 added:

1. **A command list.** `AegisRenderer` issues one draw for the whole frame. A real draw list
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
| **3b** ✅ | Latin-1, metrics, kerning, measuring, wrapping and a layout cache | L2 |
| **3c** ✅ | Rows and columns nest and lay themselves out with grow, gap, padding and alignment | L3 |
| **3d** ✅ | A retained tree survives frames; hit-testing and focus order work | L4 |
| **3e** ✅ | Button, checkbox, slider, text field and text box behave correctly | L4 |
| **3f** | `theme.json` drives the colours, a broken file stops startup with a precise error, and — with the development option on — saving it reloads live | §7 |
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

**The viewport owns the camera controls.** Today the fly camera reads the keyboard by
polling and acts wherever the pointer is, so Space and Ctrl typed into an Aegis widget also
raise and lower it, and the wheel over a text box also zooms it. When the viewport is native, the camera takes input only once the
viewport has been clicked — has focus — and a click anywhere else, or focus in any widget,
takes the camera's keys away. Until then it is left as it is.

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
