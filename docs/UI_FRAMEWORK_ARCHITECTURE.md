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
| Step 3f-1 | The theme catalogue: every themeable property with its type and a default on the palette; names on layout nodes |
| Step 3f-2 | Themes read and resolved into a style per widget — global, local variables, panel-and-kind and one-widget rules — with the factory theme when no file is found |
| Step 3f-3 | A theme's mistakes reported in one block after it is applied, each with its line: problems as warnings, notes — repeated keys, unused variables, rules that change nothing — as information; strict types; the `format` checked against the engine's |
| Step 3f-4a | `AegisJsonLines` reads lines inside lists (`children[1].width`); `AegisLayout.attach` / `detach` move a node to another parent |
| Step 3f-4b | `layout.json` read and checked into a description per screen — five node types, strict types, a screen that cannot form a tree set aside alone — reported like a theme; nothing built from it yet |
| Step 3f-4c | Screens built from layouts: widgets named as they are made, panels registered by code (`aegis.screens()`), the chain user → installation → factory per screen in `ShellFiles`, layouts in `layouts/`; a widget can be moved to any panel; stricter than a theme — an error sets the screen aside, only what is declared and never used is a warning; rows and columns with no size take their content's |
| Step 3f-4d | A size code or a layout file sets on a widget is kept when a theme is applied |
| Step 3f-5a | The theme read again in a running editor with Ctrl+R, applied at the start of the next frame; a file that cannot be used keeps the theme on screen |
| Step 3f-5b | The theme author's development option, `-PthemeWatch` for now: saving the theme file applies it, through a small watcher of the editor's own |
| Step 3g-1 | Label and separator; the format stays 1 until release |
| Step 3f-4e | `"hidden": true` on any layout node: checked, but kept out of the tree — not drawn, no room, no focus |
| Step 3g-2a | Scrolling rows and columns: content cut off at the padding, the wheel, focus scrolled into view, a thin position bar |
| Tests | JUnit in `ui/src/test`, run by `./gradlew test` with no window (`Aegis.headless`); every step from here adds its own |
| Step 3g-3 | Spinner: a number typed or stepped, by its arrows or Up and Down; the marks widgets draw (▴ ▾ ▸ ◂ ✓) join the font atlas |
| Step 3g-4 | Tabbed pane: `ae.tabbedPane` and `addTab`; a layout's `tabs` builds one, a tab per panel, titled by `screens.setPanelTitle` |
| Step 3g-5 | Split pane: `ae.splitPane`, its divider dragged or moved by keys, a press on it leaving focus alone (`tree.setKeepsFocus`); in memory only |
| Step 3g-6 | Tree: `ae.treeView` and `addTreeItem`; pointer, double click, the arrow keys, type-ahead; sized to its open items, the selection kept in view |
| Step 3g-7 | The overlay layer: roots drawn above everything and given the pointer first; `ae.showPopup`, closed by a press outside or Esc; modal ones for dialogs |
| Step 3g-8 | Tooltips: `ae.setTooltip` on any node; after the pointer rests, at once moving on from one, Ctrl+F1 from the keyboard |
| Step 3g-9 | Combo box: `ae.comboBox`, its list dropped down on the overlay layer; chosen by pointer, press-and-release, the arrows, letters |
| Step 3g-10 | Menu bar and menus: `ae.menuBar`, `menu`, `menuItem`, `menuCheckItem`, `subMenu`; by pointer, press-and-release, and F10 with the arrows |
| Step 3g-11 | Dialog: `ae.dialog`, `openDialog`; modal over a dimmed window, Tab kept inside, dragged by its title, Esc or × to cancel, a default button for Enter |
| Step 3g-2b | The scroll bar dragged, a press beside it scrolling a view; PageUp/PageDown around the focused widget; a focusable pane scrolled by arrows, Home and End |
| Conventions | One naming scheme for every data file; widgets named by their kind first — `buttonStop`; theme entries `kind.part.property.state` |
| Step 3h | Localisation: English in code, other languages from `lang/<tag>.json` keyed by widget address; `-PlocaleCheck` lists what is left to translate |
| Step 4a | The editor's own screen, arranged as Godot's and Unity's; the scene in an Aegis viewport that owns the camera's keys; the scaffolding deleted |
| Step 4b | Icons by name from an icon font; toggle buttons and groups; disabled widgets; the toolbar's tools and Play, Pause, Stop |

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

**Its scope is settled.** A theme dresses the editor's frame and never the content being
edited, and it is **only ever read**. It is looked for in the user's data directory, then in
the installation, and when neither has one the property catalogue's defaults — the factory
theme, compiled in — are used (§7, *Two files*). A theme changes while running by two separate
paths: the user choosing one in settings, and — behind a development option, off by
default — the author's saves applying live (§7, *Changing the theme*).

**The shipped theme files are not ours to write.** The themes that come with the
installation are made by the shell's designer. This step builds the machinery and, last, a
guide that lets the designer work without reading the code; the files that test the loader
are test files, and do not ship.

Five parts, in order, with `layout.json` brought forward from Phase 4 — **all five are
done**:

| # | Part | What it delivers | Visible check |
|---|---|---|---|
| 1 ✅ | Property catalogue | The closed list of what is themeable — name, type, default pointing at the palette — which validation checks against and fallbacks read from; names on layout nodes | Nothing on screen; it is the definition the next parts depend on |
| 2 ✅ | Load and resolve | `theme.json` parsed; `global`, local variables and scoped rules (one widget, panel and kind) resolved once into a style per node — nodes without a rule of their own share the global one; no file found, the factory theme | A test theme changes the widgets' colours; a scoped rule turns the Stop button red and leaves Play and Pause alone |
| 3 ✅ | Validation | A file with three mistakes reports all three, naming file, line and what was expected; value errors fall back, structural errors set the file aside for the next in line | A deliberately broken file produces three precise warnings and the editor still opens |
| 4 ✅ | Layout | `layout.json` read by screens; panels and their widgets registered by name in code and placed by the file — panels in the screen, widgets inside their panel; the chain user → installation → factory per screen; the factory layout in code | The scaffolding's frame built from a layout file; changing a width in it moves a pane, and reordering a panel's widgets moves them |
| 5 ✅ | Reload | One in-place reload that invalidates everything resolved from the old theme; the development option's watcher drives it on save; a structural error keeps the last valid theme | With the option on, saving a colour changes the running editor; saving a broken file keeps the old theme and reports why |

Then **a round of tests against the factory interface** — test themes and layouts that are
missing, partial, broken, contradictory or out of date — to find the gaps before anyone else
does. Only after it, the **guide for the shell's designer**: the format, the whole catalogue
with what each entry changes on screen, the names there are to reach, and the advice to write
in `global` — describing only what those tests have shown to work.

Parts 1 and 2 go together, since a catalogue with nothing reading it shows nothing. Parts 3
and 5 are what turn externalised constants into something another person can edit without
fear. The settings page that lets a user pick a theme is built with the real settings panel
in Phase 4; it calls the same reload part 5 delivers.

**What part 3 settled.** Every finding carries the line its key is written on, found by a
short walk over the text (`AegisJsonLines`) since Gson keeps no positions; `layout.json` will
reuse it. A wrong value inside a variable is reported at the variable's line, saying which
rule led there. Findings are gathered while the theme is read and applied, and said once, in
one block, by `AegisTheme.report()` at the end of `applyTheme`: problems first as warnings,
then notes as information, each group in line order. A theme with no problem says so in one
line of information. Notes are things that change nothing but are worth fixing: a key
written twice (JSON keeps the last without a word), a variable nothing refers to, a rule
that gives every widget it reaches the value it would have had anyway, a `global` value equal
to its default — the palette excepted, since writing it out is how a theme states its
colours. A rule already reported as broken is never also called redundant.

**Types are strict.** A size is a JSON number and a colour or reference is a string; `"4"`
for a size, `5` for a colour, or `"1"` for `format` is a problem, not something converted
quietly. `format` is checked against the engine's: missing is a note (read as 1), not a
whole number is a problem (read as 1), newer than the engine is a problem — unknown names may
come from a later version — and older is a note listing the properties added since. The
older case cannot run until the catalogue reaches format 2, so it is untested.

The deliberate test file is `editor/test-shell/themes/broken.json` (does not ship): run with
`-PtestShell -PthemeFile=broken`, it reports eight problems and four notes, and the Stop button,
its one correct rule, turns red. On screen the result is only "nothing crashed"; the log is
the check. Showing problems to the user is not planned yet — later, a popup that points at
the log file (§10, `Logger`).

**Part 4, decided.** Code creates every panel and every widget — a button stays wired to
what it does in Java, which a file could never express — and `layout.json` decides only where
each goes: which panels a screen shows and how big, and, inside a panel, the order of its
widgets and how they are grouped into rows and columns. A panel the file gives no
`"children"` keeps its widgets in the order code made them; a widget a file does not mention
— one an update added after the user wrote theirs — goes to the end of its panel, so an
update never hides a control. Writing the user's copy waits for Phase 4: nothing changes the
layout in memory until dividers can be dragged, and code with no caller would go untested.
`tabs` builds a tabbed pane (3g-4): a tab per panel listed, in that order, the first shown,
each titled by `screens.setPanelTitle(panel, title)` or, without one, by the panel's name.

Before 4c, what was called a node's *id* became its **name** — `setNodeName`,
`nodeName(node)` — since "id" suggested a number the engine generates, when it is a word
whoever writes the code chooses, and the one a theme or layout file writes. `setNodeName`
rather than `setName`, so the call says what it names.

In four steps, each reviewed — **part 4 is done**: **4a** ✅ — `AegisJsonLines` learns
arrays (`children[1].width`), and `AegisLayout` can move a node to another parent; **4b** ✅ —
the file read and validated into a description (`AegisLayoutFile`), with the same report and
strict types as a theme — the report's machinery now shared by both, in `AegisFindings` —
nothing on screen yet;
**4c** — screens built from it, in two parts: **4c-1** ✅ — every widget named as it is made
(`ae.button(viewport, "buttonStop", "Stop")`), panels registered by code
(`aegis.screens().columnPanel("inspector")`, `rowPanel`), and the editor screen built from the
factory layout (`FactoryLayout`, the format's own text, compiled into the editor) by
`screens.build("editor", files...)` — the screen looks as it did, but its outer column and
body row now come from the layout; **4c-2** ✅ — the chain user → installation → factory, per
screen, in `ShellFiles`, layouts kept in `layouts/` like themes, the stricter rule below,
widgets moved between panels, and rows and columns that take their content's size; **4d** ✅
— a size code or a layout file sets on a widget is kept when a theme is applied, which used
to replace it.

**What 4c-2 settled.** The test layouts showed what a lenient layout does: a wrong size
ignored left a panel at 0 and the screen unusable. So a layout is **stricter than a theme**,
since a wrong layout breaks a whole screen where a wrong theme spoils a colour:

| Finding | Level | Result |
|---|---|---|
| Anything wrong with the tree — unknown `"type"`, `children` not a list, a panel inside a panel, a widget outside one, a panel or widget placed twice, a panel code does not register — and any wrong value: a size or spacing that is not a number of 0 or more, an alignment not one of the four | **ERROR** | The screen is set aside and comes from the next layout in line; every error of the screen is listed, including the ones only building can find |
| Declared and never used — a property a node does not hold, a key written twice, a widget name no panel has | **WARN** | Ignored; the layout is used |
| A widget the file places nowhere | **WARN** | Added at the end of the panel code made it in; the layout is used |

The split is what lets a user's layout survive an engine update: a widget the update adds,
removes or moves to another panel is a warning, never a lost layout. The user's file is never
rewritten or deleted for an error: it is only not used that run, and the log says where and
why — and which layout comes instead, as it happens: `Screen "editor" in …/default.json has
errors, listed in its report; trying to apply <the installation's>`, or `applying factory
layout` when the factory is next. A name placed twice says both lines: `widget "fieldName" on
line 16 was already declared on line 15`.

**A widget can be placed in any panel**, not only the one code made it in — the Stop button in
the toolbar — by naming it in that panel's `"children"`. It is the same handle, so code that
asks `ae.wasActivated(buttonStop)` notices nothing; Tab follows its new place. Widget names are
therefore unique across panels, which `build` checks. A widget is **dressed by the panel code
made it in** — `viewport.buttonStop` wherever it stands — so moving it in a layout never
changes its look, and a theme and a layout stay independent.

**A row or column given no size takes its content's**: its children and the gaps between
along its direction, the largest across it, plus padding. A layout grouping two checkboxes in
a new row no longer has to know how tall a checkbox is. A size asked for still wins, and a
growing or stretching parent may still make it larger.

A panel's widgets, for the layout, are its direct children as code made them. Code sets a
panel's inside — padding, the gap between its widgets, their alignment — and the layout its
size and place; what the layout also writes on a panel replaces code's value. Building twice
would leave the first build's rows and columns behind, since the layout frees no nodes. Part 5
does not meet it, since a layout is not reloaded while running (*Changing the theme*, §7);
Phase 4 does, with *Restore default layout*, and settles it there.

**Part 5, in two steps** — **5a** ✅ — `ShellFiles.reloadTheme(name, current)` reads the theme
again, found as at startup; a file that cannot be used keeps the theme on screen, with a
warning, where startup would take the next in line, and a file gone falls to the factory
theme. The editor asks for a reload with **Ctrl+R**, a development key until the settings page
exists, and runs it at the start of the next frame, so no frame is solved or drawn with half
of each theme. Applying a theme again needs nothing else invalidated today: each widget's
style is resolved anew and each widget refitted, keeping sizes code or a layout set (4d); the
text cache depends on the font, which no theme sets yet. **5b** ✅ — the development option:
`ThemeWatcher`, in `:editor` and never `AssetWatcher`, watches the user's and the
installation's `themes/` directories for the theme in use, waits until a save has been quiet
for 200 ms — an editor saving through a temporary file raises several events — and only raises
a flag, which the editor takes at the start of a frame and reloads there, as Ctrl+R does. Off
unless `-PthemeWatch` turns it on, until the settings page carries the option; one save gives
one reload, and a save of another theme none.

**Step 3g — the widget set.** The widgets an editor needs that 3e did not make, measured
against Swing's list; each keyboard-operable like the rest. **Done: 3g-1 to 3g-11.**

| Group | Widgets |
|---|---|
| First, the editor needs them | Label, separator, scroll pane, **tabbed pane** — tab bar, click, Ctrl+Tab and Ctrl+PageUp/PageDown, arrows while the bar has focus, like Swing's `JTabbedPane` — split pane (the draggable dividers), spinner (a number with arrows, for the inspector), tree (the hierarchy) |
| Then, on an overlay layer | An **overlay layer** first — Swing's layered pane: drawn above everything, given the pointer first. Then tooltip, combo box (dropdown), menu bar and menus, dialog (modal; also the future "see the log" popup) |
| When a real panel asks | Radio button, list, table (asset browser), progress bar (asset baking), colour chooser, toolbar |
| Elsewhere | File chooser: the system's own dialog (tinyfd, bundled with LWJGL), not a reimplementation. Internal frames — floating windows inside the main one — come with docking, much later |
| Not planned | Applet, root pane (Swing internals or obsolete), password field (the editor asks for none), rich-text panes (only if a script editor is ever built in) |

The first two groups are step 3g, one widget a step, each reviewed: **3g-1** ✅ label and
separator; **3g-2** scroll pane, in two parts — **3g-2a** ✅ scrolling by wheel and focus,
**3g-2b** ✅ a draggable bar and PageUp/PageDown; **3g-3** ✅ spinner; **3g-4** ✅ tabbed pane, which the layout's
`tabs` then uses instead of showing its first panel; **3g-5** ✅ split pane, its dividers dragged
in memory only until Phase 4 writes them; **3g-6** ✅ tree; **3g-7** ✅ the overlay layer; **3g-8** ✅
tooltip; **3g-9** ✅ combo box; **3g-10** ✅ menu bar and menus; **3g-11** ✅ dialog. The third group
waits for the panels that ask for it, in Phase 4.

**The format stays 1 until release.** Theme and layout files keep `"format": 1` while the
framework is in development: the entries new widgets bring arrive in format 1, since nobody
yet has a theme or a layout they could break. The format is raised only after release, and
rarely, since each raise flags every custom theme or layout. The path that reports an older
format works — 3g-1 ran it once, briefly at format 2, and a format-1 theme was told which
entries came since — and waits for that day.

**3g-1, label and separator.** `ae.label(parent, name, text)` is text with nothing to act on,
like `JLabel`: neither clickable nor focusable, so Tab passes it by; `setLabel` shows other
text and sizes it again. Its `label.padding.y` defaults to a checkbox's, so the two line up in
a row. `ae.separator(parent, name)` is a line that follows its parent — flat in a column,
upright in a row — drawn across the whole parent from one inner edge to the other, so it spans
the panel whether or not the panel stretches its children; moved by a layout into a row, it
turns upright. Theme entries: `label.text`, `label.padding.y`, `separator.line`,
`separator.line.width`, `separator.margin`.

**3g-2a, scrolling.** Scrolling is the layout's, not a widget wrapped around content:
`layout.setScrollable(node, true)` makes a row or column scroll along the way it stacks — a
column up and down. Its children are then placed back by its offset and shown only inside its
padding, and it no longer asks for its content's length along that axis: it takes the room it
is given. The solve keeps the offset between 0 and how far the content reaches, so a larger
window never leaves it scrolled past the end; a click in the padding, where content is cut
off, is the container's own. The widgets turn input into an offset: the wheel scrolls a text
box under the pointer, or else the nearest scrolling row or column under it; **whatever gains
focus is scrolled into view**, so Tab never lands out of sight; a thin bar on the far edge
shows where the view is. Theme entries `scrollbar.thumb` and `scrollbar.width` — global only for
now, since panels are not dressed by rules. Anything drawn by hand rather than by
`ae.draw()` cuts itself off, as the scaffolding's plain box in the inspector does.

**3g-2b, the scroll bar and the keyboard.** The bar is dragged as a `JScrollBar` is: a press on
it takes hold and it follows the pointer until the button comes up, wherever the pointer goes;
a press beside it, in the same strip, scrolls one view towards the pointer. The strip is the
whole padding along the far edge, not the four pixels drawn. While dragged, the pane holds the
pointer's capture (`tree.capture`), so nothing under it lights up or is clicked, and **focus
stays where it was** — a scroll bar is not something focus goes to. From the keyboard,
**PageUp and PageDown** scroll the nearest scrolling pane around the focused widget, by a view
less one line. A pane with nothing focusable inside — a log of labels — is made focusable
itself (`tree.setFocusable(pane, true)`), and then also takes the arrows, a line at a time,
and Home and End, with a focus ring around it. New theme entry: `scrollbar.thumb.hover`.

**3g-3, spinner.** `ae.spinner(parent, name, min, max, value, step)` is a number in a field
with two arrows, working as `JSpinner` does: the arrows — clicked, or held to repeat — and Up
and Down step it, Shift making it ten steps; a number can be typed, and only what a number can
hold gets in (digits, a minus, a point or a comma). What is typed becomes the value on Enter,
on a step or when focus leaves; text that is not a number puts the last value back, a number
out of range goes to the nearest end, and Esc gives the typing up. Arriving by Tab selects the
number, so typing replaces it — the one departure from Swing, and what editors do. It is
shown and kept with the decimals its step has (`setSpinnerDecimals` to say otherwise), so ten
steps of 0.1 are exactly 1. `wasChanged` reports a new value, not each key typed. It edits
through the text field's code — caret, selection, clipboard, undo — and shares the field's
caret, selection, radius and padding, so the two line up; its own theme entries are
`spinner.fill`, `.fill.hover`, `.border`, `.border.focused`, `.text`, `.arrow`,
`.arrow.hover`, `.width` and `.button.width`. The arrows are glyphs: the draw list has
rectangles and text only, so the font atlas now carries the few marks widgets draw —
▴ ▾ ▸ ◂ ✓ (`AegisFont.ARROW_UP` and the rest).

**3g-4, tabbed pane.** `ae.tabbedPane(parent, name)` then `ae.addTab(pane, title, content)`,
working as `JTabbedPane` does: a press on a tab shows its panel — on the press, not the
release; the bar is one stop of Tab, and with focus Left and Right show the tab before and
after, going around at the ends, Home and End the first and last; from anywhere inside,
Ctrl+Tab and Ctrl+PageDown show the next, Ctrl+Shift+Tab and Ctrl+PageUp the one before.
The pane is a column — the bar, then the shown panel growing to fill it — and **the panels
not shown hang nowhere**, exactly as a node a layout hides: not drawn, not solved, no stop for
Tab. Focus inside a panel being put away goes to the bar. A theme applied dresses the panels
not shown too. `selectedTab`, `selectTab`, `tabCount`; `wasChanged(pane)` on the frame the
user switched. A layout file's `tabs` now builds one, with no name of its own: a theme
reaches it by panel and kind, or globally. A panel in tabs counts as on a screen even while
not shown, so naming it twice is still an error. Theme entries: `tabs.fill`, `.fill.hover`,
`.fill.selected`, `.text`, `.text.selected`, `.accent`, `.border`, `.padding.x`, `.padding.y`,
`.gap`, `.radius`, `.accent.height`. Tabs the bar has no room for are cut off; scrolling the
bar waits for a panel that needs it.

**3g-5, split pane.** `ae.splitPane(parent, name, sideBySide, first, second, location)`, as
`JSplitPane`: a row (side by side) or column of the first side, a divider, the second side.
The first keeps the size the divider gives it and the second grows into the rest, so a
larger window goes to the second side, as `JSplitPane`'s default resize weight does. The
divider is dragged — it does not jump under the pointer — or, being a stop of Tab, moved by
the arrows along its way, 8 pixels a press and Shift ten times that, and taken to either end
by Home and End. Neither side gets smaller than 24 pixels. Pressing the divider leaves focus
where it was: `tree.setKeepsFocus(node, true)` is new, for what is worked with the pointer
while the keyboard stays elsewhere. `dividerLocation`, `setDividerLocation`;
`wasChanged(split)` on each frame the user moved it. Where it is stays in memory; writing it
to the layout file is Phase 4's. Theme entries: `split.line`, `.line.hover`, `.divider.width` (the room
the divider takes, what the pointer takes hold of) and `.line.width`. The widgets' timings now
read a clock tests can set, so a delay is tested by moving time rather than waiting.

**3g-6, tree.** `ae.treeView(parent, name)` and `ae.addTreeItem(tree, parentItem, text)` — the
top level under `AegisWidgets.NO_ITEM` — as `JTree`: a press selects an item, a press on its
arrow or a double click opens or closes it; one stop of Tab, and with focus Up and Down move
through the items shown, Right opens the selected item or goes to its first child, Left
closes it or goes to its parent, Home and End go to the ends, a letter goes to the next item
starting with it, Enter activates. Items are numbers per tree, not layout nodes: a tree of a
thousand entities costs a thousand array slots, not a thousand nodes. The tree asks for the
room its open items take, so it belongs in a scrolling column, which keeps the selection in
view as it moves; rows out of sight are not drawn. Closing an item that holds the selection
moves it to the item. `selectedTreeItem`, `selectTreeItem` (opening what is above it),
`setTreeItemExpanded`, `isTreeItemExpanded`, `treeItemText`, `treeItemParent`; `wasChanged`
on a new selection, `wasActivated` on Enter or a double click. Theme entries: `tree.text`,
`.text.selected`, `.fill.selected`, `.fill.hover`, `.arrow`, `.indent`, `.padding.x`,
`.padding.y`. Dragging items to reorder them, and several selected at once, wait for the
hierarchy panel that needs them.

**3g-7, the overlay layer.** Swing's layered pane, made small: an overlay is any root — a
row, column or box built with `NONE` as its parent — shown above everything. The tree keeps
the open ones as layers, topmost last, and asks them first whenever it finds what is under
the pointer (`tree.nodeUnder`), so nothing beneath an overlay is hovered, pressed or scrolled;
a modal layer also keeps the pointer from everything below it, and Tab inside it. The widgets
open, place and draw them: each at the size its content asks for, moved in if it would leave
the window, on a background with a shadow — `overlay.fill`, `.border`, `.shadow`, `.scrim`
(over what is below a modal one), `.radius`, `.shadow.offset`, global only — drawn by
`ae.draw()` after the tree, so `ae.draw()` is the last thing the interface draws.
`ae.showPopup(root, x, y)` shows a popup: a press outside it, or Esc, closes it, and that
press still reaches what it landed on, as in Swing; opening one does not move focus, and its
background is a press that keeps focus where it was. `closeOverlay` closes one and everything
on top of it; `isOverlayOpen`. The tooltip, combo box, menus and dialog stand on this.

**3g-8, tooltip.** `ae.setTooltip(node, text)`, as Swing's `setToolTipText`, on any node — a
widget, a panel, a plain box; a node without one shows the nearest one above it. It shows
below the pointer once the pointer has rested on the node for 750 ms — moving restarts the
wait, as Swing's does — and at once when moving on from another tooltip within half a second.
It goes when the pointer leaves the node, a button is pressed or a key is. From the keyboard,
**Ctrl+F1** shows the focused widget's tooltip below it, and hides it, as Swing's postTip
action does. Drawn above everything, even overlays; wrapped at `tooltip.width`, moved in to
stay in the window, above the pointer when there is no room below. Theme entries, global only:
`tooltip.fill`, `.border`, `.text`, `.padding.x`, `.padding.y`, `.radius`, `.width`. Swing's four-second
dismiss is left out: a note the user is reading should not vanish.

**3g-9, combo box.** `ae.comboBox(parent, name, items...)`, as a `JComboBox` that is not
editable: a box showing the chosen item and an arrow, sized to its longest item. A press opens
its list — a popup on the overlay layer, below the box, or above it when there is no room —
and another press closes it; pressing an item chooses it, and so does pressing the box and
letting go over an item, as in Swing. A press elsewhere closes the list; the press that closes
it from the box itself does not open it again. A stop of Tab: closed, Up and Down, Home and
End and a letter choose at once; Enter, Space, Alt+Down or F4 open the list, where the same
keys move through the items, Enter or Space choose, and Esc or Tab close it with the choice
unchanged. Pressing the list leaves focus on the box. `comboChosen`, `setComboChosen`,
`comboItem`, `comboItemCount`, `isComboOpen`; `wasChanged` on the user's choice. Theme entries:
`combo.fill`, `.fill.hover`, `.border`, `.text`, `.arrow`, `.item.fill.selected`, `.item.text.selected`,
`.padding.x`, `.padding.y`, `.radius`, `.arrow.width`. An editable combo — a text field with a
list — waits for a panel that asks for one.

**3g-10, menu bar and menus.** `ae.menuBar(parent, name)`; `ae.menu(bar, name, title)` puts a
title on it and returns its menu — a column hanging nowhere, shown as a popup — to which
`menuItem(menu, name, text[, shortcut])`, `menuCheckItem` (a `JCheckBoxMenuItem`: it ticks each
time it is chosen, read with `isChecked`), `subMenu` (an item opening a menu beside it, to the
left when there is no room on the right) and the ordinary `ae.separator` add. As `JMenuBar`: a
press on a title opens its menu and another closes it; with one open, moving over another
title opens that one; the pointer points at items, opening a submenu as it reaches one and
closing a deeper one as it leaves; a click, or letting go over an item after pressing a title,
activates it and closes the menus. From the keyboard **F10** opens the first bar's first menu —
the titles are not stops of Tab, as in Swing — then Up and Down move through the items, passing
separators and going around, Right opens a submenu or goes to the next menu, Left closes a
submenu or goes to the menu before, Enter or Space activate, Esc closes one level, F10 closes
all. While a menu is open the keys are the menus', whatever has focus, and focus is never
moved: the text field being typed into is still focused when the menu closes. The shortcut is
text only — binding it is the caller's, as with Swing's accelerator text. `wasActivated(item)`;
`isMenuOpen`. Theme entries: `menu.text`, `.shortcut.text`, `.fill.selected`, `.text.selected`,
`.title.fill.hover`, `.padding.x`, `.padding.y`, `.gap`, `.check.width`; a menu's items are reached
by the title's name — `menuFile.menuItemSave`. Mnemonics (Alt+F) wait for localisation, since the
underlined letter depends on the language. The factory layout gains a `menu` panel above the
toolbar, holding the scaffolding's bar.

**3g-11, dialog.** `ae.dialog(name, title)` makes a modal `JDialog`, closed and hanging
nowhere: a column with a title bar, to which its contents are added, stretched across — buttons
go in a row of their own. `openDialog` shows it centred, a little above the middle, over a
scrim; nothing outside it can be pointed at — a press there reaches nothing and leaves focus
inside — Tab goes around its widgets only, and F10 does not reach the menus behind it. Focus
goes to its first widget and back to where it was when it closes. Its title bar drags it, kept
in the window; the × at the bar's right and Esc close it as cancelled (`wasCancelled`, for one
frame); `closeDialog` is code's, for OK and Cancel, and is not a cancel.
`setDefaultButton(dialog, button)` is `JRootPane`'s: Enter from a text field, a spinner or a
slider in the dialog activates it, and it is drawn with an outline of its own. A theme now
dresses everything that hangs nowhere — dialogs and popups not open, panels not shown — so
what opens later is already dressed. Theme entries: `dialog.title.text`, `.line`, `.close.fill.hover`,
`.default.border`, `.title.padding.y`. Help > About in the scaffolding opens one.

**Tests.** `./gradlew test` runs JUnit tests in `ui/src/test` without a window:
`Aegis.headless()` makes an Aegis with no renderer whose font measures without uploading its
atlas, and `TestShell` wraps the frame loop's order of calls. Each widget step adds tests for
what it decides.

**3h, localisation.** ✅ English stays in code, where each widget is made; other languages
come from `<install>/lang/<tag>.json`, keyed by the widget's address, so a change to the
interface asks for no file to be edited (§8). `AegisLocale` reads a file strictly — a text
that is not a string, a key that is not an address or answers to no widget, a dropped
`{count}` are each reported with their line — and falls back from `pt-BR` to `pt` to English.
`ae.applyLocale` looks every text up and sizes widgets again; `AegisLocale.english()` puts the
English back. Tabs and combo items gain names for their keys: `addTab(pane, "tabEntities",
"Entities", content)`, `addComboItem(combo, "lit", "Lit")`, `comboItemName`. The editor
follows the system language, or `-Plocale=pt-BR`, and says in one line how much is
translated; `-PlocaleCheck` lists what is not, ready to copy into the file.

**Phase 4** builds the editor's own screen — 4a ✅ the factory arrangement and the viewport,
4b ✅ icons and the toolbar — and **Phase 5** deletes Dear ImGui. Both are described in §11.

### Open decisions

Step 3d is closed; none of these blocks step 3e, which follows it.

- **Default font** — the friend designing the shell chooses; DejaVu Sans holds the place.
- ~~**Where the shell, font and locale files live**~~ — **decided:** built-in dark and light
  themes, the default font and the locale files in the installation (locales in
  `<install>/lang/`); user themes, user fonts and layouts under `AEngine/ui/` in the
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
  licences (OFL, Bitstream Vera) are compatible with it, as is Lucide's (ISC), the provisional
  icon set.

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

**One generic call per kind, not one per use.** `ae.button(parent, "buttonPlay", "Play")`
creates a layout node, names it, marks it interactive and focusable, and records its kind and
label in the widgets' own arrays — the same handle again. The caller asks
`ae.wasActivated(buttonPlay)` each frame; there are no callbacks and no object per widget.

**Every widget is named as it is made.** The name — `"buttonPlay"` — is what a theme or a
layout file writes to reach it, and the label — `"Play"` — is what the user reads; the first
stays put, the second may be translated. Making the name an argument rather than a later
`setNodeName` means a widget cannot be left without one, so every widget can be dressed and
placed by a file.

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
name and declares a semantic role; the package maps roles to appearance and names to positions.
This is the same cut as `<button class="primary">` against `.primary { … }`, and it is what
makes the package genuinely detachable rather than just externalised constants.

### Two files

The shell is two files, because they have different authors and change for different
reasons:

| File | Holds | Written by the program |
|---|---|---|
| `theme.json` | Appearance: colours, radii, spacing, typography | Never |
| `layout.json` | Arrangement: which panels each screen shows, where, at what size | Only the user's copy, only the screens the user changed |

Split, they swap independently — one person's theme with another person's arrangement,
without either file knowing about the other.

**Each is looked for in three places, and the first that exists and is usable wins:**

1. **The user's** — in the user's data directory (*Where the files live*). The only place the
   program ever writes.
2. **The installation's** — the defaults the engine ships, made by the shell's designer. Read,
   never written, so an installation the user cannot write to is no obstacle, and an engine
   update brings new defaults to everyone who has not replaced them.
3. **The factory's** — written in Java, inside the executable, reached only when neither file
   exists or is usable. For the theme it is the property catalogue's defaults; for the layout,
   a description built in code. Nobody edits it and nobody needs to find it: it is what
   guarantees the editor always opens with something on screen.

**The program reads both at startup and never writes at startup.** Everything after that
works from what was parsed; the theme alone can be read again while running, as described
under *Changing the theme*.

**The layout in memory is the one in use.** When the user drags a divider, the new size goes
into the layout held in memory; leaving the editor screen for settings and coming back reads
no file and loses nothing. It is written to the user's `layout.json` **a second after the
divider is released, and when the editor closes** — only when something changed. Writing
only at exit would lose a session's arrangement to a crash or a power cut. Each write goes to
`layout.json.tmp` first and is then renamed over `layout.json`, so a crash in the middle of
writing leaves the previous file whole rather than half a file.

**Only the screens the user changed are written.** Someone who resized the editor's panels
gets an `editor` entry in their own file; every other screen keeps following the
installation's defaults, so an update that improves the settings screen still reaches them.
A screen the user did change stops following those updates — which is why settings is to
offer **Restore default layout**, deleting the user's copy, once it exists.

**The files are ordinary editable data; the factory defaults are code.** Editing the
interface is meant to be open to anyone — that is the point of shipping it as data — and
customising means adding a theme of one's own beside the built-in ones, as described under
*Where the files live*. The factory defaults are deliberately not a file: there is nothing
for a user to open, break or delete.

### Format

**JSON, parsed with Gson**, which the project already depends on and already uses for
`.scene` and `.entity`. No grammar to write, no parser to maintain, and one less format for
a contributor to learn.

**Theme files are plain `.json`, without comments.** Strict JSON has none, and an editor
such as VS Code marks every comment in a `.json` file as an error, so a theme a user opens to
edit must not arrive covered in red. What a comment would say about the theme goes in its
`"description"`. Gson still reads in lenient mode, so a user who adds a comment anyway gets a
theme that loads — tolerance, not a feature the shipped themes use.

**`theme.json`**

```json
{
  "name":        "Dark",
  "description": "The built-in dark theme.",
  "format":      1,

  "global": {
    "accent":         "#5C9EF0",
    "surface":        "#242936",
    "surface.raised": "#333B4A",
    "text":           "#E6EBF2",
    "button.fill":    "@global.surface.raised",
    "button.radius":  6
  },

  "danger": "#C04040",

  "inspector.field.fill":     "#1A1D26",
  "viewport.buttonStop.fill": "@danger"
}
```

A theme holds four kinds of entry:

| Entry | Written as | Means |
|---|---|---|
| Metadata | `name`, `description`, `format` | What the theme list shows, and which catalogue version the theme was written against. Not colours. |
| Global | inside `"global"` | Applies everywhere: the **palette** (`accent`, `surface`, `text`…) and the look of each **kind** of widget (`button.fill` — every button). |
| Local variable | a key **without** a dot, outside `global` | Paints nothing by itself; takes effect only where referenced as `@name`. |
| Scoped rule | a key **with** a dot, outside `global` | A path of names, then a property. `panel.kind.property` — `inspector.field.fill` — styles every widget of that kind inside that panel, **including ones added later**; `panel.widgetName.property` — `viewport.buttonStop.fill` — styles one widget. |

The dot is what tells a local variable from a scoped rule, which is why local variables are
one word (`danger`, `warning`). References say where they point: `@global.accent` reads the
global, `@danger` a local variable.

**Precedence:** one widget's own rule > panel and kind > global > the catalogue's default.

**Paths are made of names given in code** — `layout.setNodeName(buttonStop, "buttonStop")` —
never of handles, which shift whenever a widget is added before another. A name is chosen by
whoever writes the code, not generated, so it reads as what the thing is and stays the same
from one run to the next. Containers without a name are left out of the path, so wrapping
part of a panel in a new row does not break a theme. Names are thereby part of the contract
with theme authors: renaming one is a change to announce in the release notes, and a path
that no longer matches is reported, not silently ignored.

**How a custom theme survives an engine update.** An update that adds widgets must not leave
them looking foreign in someone's theme. Three layers see to it, strongest first:

1. **The catalogue's defaults point at the palette**, not at fixed colours — `button.fill`
   defaults to `@global.surface.raised`. A widget a theme has never heard of still wears the
   theme's palette.
2. **Panel-and-kind rules cover what is added later.** `inspector.field.fill` applies to a
   field an update adds to the inspector.
3. **An older theme is reported.** Catalogue entries record the format they arrived in; a
   theme declaring an older `format` gets a log line naming the entries it does not set.

The guidance for theme authors, for the user documentation: **the base look goes in
`global`, a panel's look in panel-and-kind rules, and one widget's own rule only for
exceptions.** A theme written that way survives updates; one written widget by widget is
warned about, not repaired — nobody can know how its author would have dressed a widget they
never saw.

References are resolved once at load. Cycles are an error reported at parse time, not a
hang.

**`layout.json`**

```json
{
  "name":        "Default",
  "description": "Toolbar on top; hierarchy, viewport and inspector below.",
  "format":      1,

  "screens": {
    "editor": {
      "root": { "type": "column", "alignX": "stretch", "children": [
        { "type": "panel", "panel": "toolbar", "height": 40 },
        { "type": "row", "grow": 1, "alignY": "stretch", "gap": 4, "children": [
          { "type": "panel", "panel": "hierarchy", "width": 240 },
          { "type": "panel", "panel": "viewport",  "grow": 1 },
          { "type": "panel", "panel": "inspector", "width": 300, "children": [
            { "type": "widget", "widget": "fieldName" },
            { "type": "row", "gap": 12, "children": [
              { "type": "widget", "widget": "checkboxShowGrid" },
              { "type": "widget", "widget": "checkboxSnap" }
            ]}
          ]}
        ]}
      ]}
    },
    "settings": {
      "root": { "type": "row", "alignY": "stretch", "children": [
        { "type": "panel", "panel": "settingsMenu", "width": 200 },
        { "type": "tabs", "grow": 1,
          "panels": ["settingsGeneral", "settingsInterface", "settingsInput"] }
      ]}
    }
  }
}
```

**The file says where each panel goes and how much room it gets** — not what is inside a
panel, which is the panel's code, nor what it looks like, which is the theme.

**It describes screens, not one window.** The editor's main screen is one entry of
`"screens"`; settings is another, and screens not yet decided are added the same way. A
screen is registered in code under a name, as a panel is. **Code decides what the user may
rearrange through the interface** — the editor's dividers can be dragged, the settings
screen's cannot — while **the file can rearrange any screen**, so the shell's designer can
lay out settings without end users moving it by accident.

Five kinds of node, mirroring `AegisLayout`:

| Node | Holds |
|---|---|
| `row` | Children side by side, left to right |
| `column` | Children stacked, top to bottom |
| `panel` | One panel's slot, by the name its code registered — `"panel": "inspector"`. Optionally `"children"`: how the panel's own widgets are ordered and grouped |
| `widget` | Inside a panel's `"children"` only: a widget, by its name — `"widget": "fieldName"` — one of that panel's, or one moved in from another panel |
| `tabs` | Several panels sharing a slot, one shown at a time, in the order listed |

**Inside a panel, the file arranges and code creates.** A panel's widgets are made in code,
each wired to what it does; the file only orders them and groups them into rows and columns,
by the same names a theme uses — and may move a widget into another panel, by naming it in
that panel's `"children"`. A panel without `"children"` shows its widgets as code laid them
out. A widget the file places nowhere is added at the end of the panel code made it in, in
code order, with a warning — an update's new control is never hidden by an older file. Rows
and columns inside a panel carry no name, like the screen's, so regrouping a panel changes no
theme path; and a widget is dressed by the panel code made it in, wherever the file moves it.

Sizes and spacing are the layout's own: `width`, `height`, `grow`, and on rows and columns
`gap`, `padding`, `alignX`, `alignY` (`start`, `center`, `end`, `stretch`). The `"type"` is
always written, so the four kinds read plainly.

**Any node can be hidden** with `"hidden": true` — `false`, the default, may be written or not.
A hidden node is checked like any other — its name must exist and be placed once, its values
must be right — but nothing of it is built: it is not drawn, takes no room, and neither the
pointer nor Tab reaches it. Hiding a row or a panel hides everything in it. A hidden widget
counts as placed, so it does not go back to the end of its panel; code holding its handle
notices nothing, except that it is never activated. It belongs to the layout, not the theme:
the theme says how something looks, the layout where and whether it appears. Hiding a panel
shows the same as not placing it, but keeps its arrangement written, ready to come back by
turning `true` to `false`. The root of a screen cannot be hidden.

**A node can be resizable** with `"resizable": true` — step 4c. The user then drags the
edge it shares with the growing sibling beside it, as a split pane's divider: dragged, the
node takes room from the growers, never going below 80 pixels and never leaving them less.
**F8** gives the keyboard to the edges in turn, as Swing's split pane does; the arrows move
the one that has it, ten pixels a step, fifty with Shift, and Esc, Enter or Tab give the
keyboard back. A resizable node must be a child of a row, with a `width`, or of a column,
with a `height`, beside a sibling with `grow`; one that is not is a warning and stays fixed.
The edge is drawn only while pointed at, dragged — `split.line.hover` — or held by the
keyboard — the focus ring's colour.

**What the user arranges is written down**, a second after they let go: the screen as the
file described it, with the sizes the user left, into `layouts/<name>.json` in the user's
directory, under `"screens"`. Only that screen: the file's other screens are kept, and
screens it does not hold go on coming from the installation's layout, so an update that
improves them still reaches the user. `-PtestShell` never writes over its test layouts.

**Panel names are unique across the whole engine**, not per screen, so a theme path stays
`panel.widget` — `settingsGeneral.fontSize.border` — with no screen in it; a panel belongs to
one screen. **Widget names are unique across the engine too**, since a layout names a widget
without its panel; `build` refuses two panels with a widget of the same name. Rows, columns and tabs in the layout carry no name and never appear in a theme
path, so moving the inspector to the other side changes no theme.

Screens fall back **one by one**: the editor screen may come from the user's file while
settings comes from the installation's, and a screen an update adds is taken from the
installation or the factory rather than appearing empty.

Kept for later, without changing this format: draggable dividers writing back (Phase 4), a
minimum size so a divider cannot crush a panel, and dragging panels between slots.

**Docking is not a separate system.** A dock arrangement is a layout tree whose dividers can
be dragged, so the shell's `layout` feeds the same L3 solver as everything else rather than
getting a parallel implementation.

### Validation

One rule decides every case: **a structural error sets the file aside for the next one in
line; a value error falls back to the default for that one property.** A structural error
means nothing usable can be built from the file. A value error means the structure is fine
and one property is wrong.

The editor never refuses to start over a shell file. An earlier draft had it refuse, when a
broken file left nothing to show; with the factory defaults compiled in (*Two files*), there
is always something to fall back to, and refusing would only lock the user out of the very
settings that could fix the problem.

| Situation | Kind | Outcome |
|---|---|---|
| File missing | — | The next in line — installation, then factory — with no warning for the user's, which is normally absent |
| JSON does not parse | structure | The next in line, with a warning |
| A layout screen with an **error** — anything wrong with the tree (`"type": "diagonal"`, `children` not a list, a panel inside a panel, a widget outside one, a panel or widget placed twice, a `panel` name code does not register) or a wrong value (`"width": -40`, `"width": "240"`, `"alignY": "middle"`, `"hidden": "yes"`), or a hidden root | structure | That screen from the next in line, with an **error** listing every one found; the file's other screens still apply |
| Invalid value — `"bleu"` for a colour | value | Default, with a warning |
| Unknown property name — `button.fil` | value | Ignored, with a warning; the intended property falls to its default |
| A path that matches no widget — a name changed in code | value | Ignored, with a warning |
| `@reference` to a name that does not exist | value | Default, with a warning |
| `@reference` cycle | value | Default for every property in the cycle, with a warning |
| A layout property a node does not hold — `"colour"` on a panel | declared, unused | Ignored, with a warning |
| A `widget` name no panel has | declared, unused | Ignored, with a warning |
| A widget the layout places nowhere | — | Added at the end of the panel code made it in, with a warning |
| Registered panel absent from `layout.json` | — | Not shown — how a minimal layout is authored |
| Property omitted | — | Default, no warning — this is what allows a partial theme |

**A layout is stricter than a theme.** A theme value that falls back spoils one colour; a
layout value that falls back can leave a panel 0 wide and the screen unusable, which the
first test layouts showed. So in a layout everything wrong is an error that sets the screen
aside, and only what is declared and never used is a warning — the split that lets a user's
layout survive an update which adds, removes or moves widgets (*Part 4*, in the plan at the
top). A panel name code does not register is an error too: a panel an update adds has no
obvious place to go, and the next layout in line puts it somewhere.

Default values come from the property catalogue described under *Scope discipline* below:
each supported property is declared with a name, a type and a default. That catalogue is not
a second theme — it is the definition of what is themeable, and falling back reads from it.

Fallbacks reintroduce the risk of a mistake nobody notices, so **the warning carries the
weight**. It names the file, the line and what was expected —
`theme.json:14: panel.bg — expected a colour like "#1F2328", got "bleu"; using default` —
and validation collects every problem in one pass, so a file with three mistakes reports
three at once, in one block, after the notes on what is merely untidy (*What part 3
settled*, in the plan at the top). Values are typed strictly: a size written as `"4"` is a
problem, not a 4. Warnings go to the log; later a popup will point the user at the log file.

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
under them because a file was touched. Until the settings page exists (Phase 4), a
development run turns it on with `-PthemeWatch`.

Both paths end in the same reload, and the reload is the hard part: switching a theme must
invalidate everything resolved from the old one — the baked style table, cached text
layouts, glyph atlases once typography is themeable — and leave none of it stale. That was
the reason hot reload was first dropped, in favour of a restart that gets it right by
construction. It came back because the theme author's loop matters, and because the same
reload also lets a user switch themes without restarting. It is done once, carefully, and
both paths share it.

A structural error during a reload **keeps the last valid theme on screen** — rather than
dropping to the next file in line, which would repaint the editor under the author's hands
because of a typo — and reports the error; value errors fall back
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

**Built-in themes ship with the installation**, in `<install>/ui/themes/`, made by the
shell's designer — a dark one and a light one are planned. They are not meant to be edited — nothing stops a curious user
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

**Layouts live in `layouts/`, as themes live in `themes/`** — the one the editor reads is
`layouts/default.json`; elsewhere this document calls it `layout.json`, the format's name.
Keeping them in a folder of their own leaves room for several layouts to choose from, the
way themes are chosen. The installation carries a default layout in its `layouts/`, made by
the shell's designer and never written. **The user's layout**, which the program writes,
lives under `AEngine/ui/layouts/` and **never in the installation**, so an engine update cannot reset a
user's arrangement. Not in a cache directory either (`~/.cache`): the system and cleaning tools treat a cache as
disposable and empty it, which would reset the arrangement just the same.

**Fonts a user adds** go in `fonts/` under the same directory. The default font ships with
the engine. A user's font may lack glyphs a language needs — a Latin-only font with the
editor in Russian — so any character missing from it is drawn from the default font instead.
That is the glyph fallback §8 already calls for, and it needs the font set planned for L2.

Both locations are resolved in one place in `:editor` — `ShellFiles` — so nothing else in
the code knows which system it is on. Each can be moved with a system property, which is how
development runs work: `aengine.home` is the installation, and `./gradlew :editor:run` points
it at `editor/src/dist`, which is what the application plugin copies into an installation;
`aengine.userdata` is the user's directory, and `-PtestShell` points it at
`editor/test-shell`, where the test themes and layouts live, so trying one never touches the
real `~/.local/share/AEngine`. `-PthemeFile=<name>` picks a theme by name, and
`-PlayoutFile=<name>` a layout — not `-Playout`, since every Gradle project already has a
`layout` property of its own, which would be read instead; the theme's option is named the
same way for uniformity. Nothing in
`test-shell` ships.

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

### The files are data, never behaviour

**Decision: nothing a user can edit reaches what the engine does.** `theme.json` and
`layout.json` place and dress widgets; every behaviour is compiled Java, shipped inside the
installation. A user who edits the files can make the editor look wrong — which validation
and the factory fallbacks already contain — but cannot make it do anything it was not built
to do.

The split is the one NetBeans draws between a form and its code. `layout.json` plays the
`.form`, with one difference: it places widgets but does not declare them, and it is read
on every start rather than turned into code once. A panel's Java class creates its widgets
by name and handles their events, as an `ActionPerformed` method would; those handlers
call the editor's commands and services, which change the engine's model and can be undone.

So no field in either file is ever an expression, a script, a condition or a method name.
The most a file will name is an *action* — `"view.toggleGrid"` — and only one already
registered in Java (Phase 4, modelled on Swing's `Action`); an unknown name is a warning
and is ignored, like an unknown widget.

### What the theme may and may not dress

**The theme dresses the editor's frame, never the content being edited.** A colour in
`theme.json` changes panels, tabs, buttons, text fields and labels. It must never reach the
viewport's grid, the transform gizmos, the colour of a sprite or anything else the project
owns — those belong to the engine and to the scene, and a theme that could repaint them would
be restyling the user's work rather than the tool.

This is checkable rather than aspirational: if editing a colour in the shell file changes
anything inside the viewport, that is a defect.

### Where the cost lands

Addressing panels by name from data requires every panel to be a registration rather than a
hardcoded call. That is not extra work bolted on: it is how panels have to be written anyway
as they move to the framework in Phase 4. The arrangement half of this feature is largely
absorbed by migration work already planned.

### Conventions

The editor's data files — themes, layouts and, from step 3h, locales — share one set of
conventions, so a key read in one can be guessed in another. They hold for as long as the
format does; renaming anything after release is a change to announce.

**Files.** Every file opens with `name`, `description` and `format`, in that order. Strict
JSON — no comments, `description` says what a comment would — in UTF-8, indented by two
spaces in the files that ship. File names are lower case with hyphens between words:
`dark.json`, `default.json`, `high-contrast.json`. Locale files are the exception, named by
their BCP 47 tag, the form Java's `Locale.toLanguageTag()` gives: `en-US.json`, `pt-BR.json`.

**Names given in code.** ASCII letters and digits only, camelCase, starting with a letter;
the layout refuses anything else.

- A **panel** is named for what it shows, one word where one will do: `hierarchy`,
  `inspector`, `assetBrowser`.
- A **widget** is named by a prefix saying its kind, then what it is for: `buttonStop`,
  `spinnerGridSize`. The prefix comes first so that a panel's code lists its buttons together,
  its combos together; and it is the word the theme already knows the kind by, so the name
  says which entries dress it. The widgets refuse a name without their prefix, and a name
  that is the prefix alone.

| Kind | Prefix | Example | Dressed by |
|---|---|---|---|
| Button | `button` | `buttonStop` | `button.*` |
| Checkbox | `checkbox` | `checkboxSnap` | `checkbox.*` |
| Label | `label` | `labelName` | `label.*` |
| Separator | `separator` | `separatorToolbar` | `separator.*` |
| Slider | `slider` | `sliderOpacity` | `slider.*` |
| Spinner | `spinner` | `spinnerGridSize` | `spinner.*` |
| Text field, one line or several | `field` | `fieldName`, `fieldNotes` | `field.*` |
| Toggle button | `toggle` | `toggleMove` | `toggle.*` |
| Tabbed pane | `tabs` | `tabsScene` | `tabs.*` |
| A tab of one | `tab` | `tabEntities` | — |
| Split pane | `split` | `splitView` | `split.*` |
| Tree | `tree` | `treeScene` | `tree.*` |
| Combo box | `combo` | `comboShading` | `combo.*` |
| Menu bar | `menuBar` | `menuBarMain` | `menu.*` |
| Menu, or a submenu | `menu` | `menuFile`, `menuRecent` | `menu.*` |
| Menu item, or a check item | `menuItem` | `menuItemSave` | `menu.*` |
| Dialog | `dialog` | `dialogAbout` | `dialog.*` |
| Surface | `surface` | `surfaceScene` | `surface.*` |

A plain box the code draws itself, not a widget, is named the same way with `box` —
`boxStretch` — though nothing checks it.

A kind is one lower-case word and a widget's name never is, so a scoped rule can tell them
apart: `inspector.button.fill` dresses every button in the inspector, `inspector.buttonSave.fill`
one of them.

**One address in every file: `panel.widget.part`.** The theme writes
`inspector.buttonSave.fill.hover`; the layout writes `"panel": "inspector"` and
`"widget": "buttonSave"`; a locale file, from step 3h, writes `inspector.buttonSave` for the
widget's text, `inspector.buttonSave.tooltip` for its tooltip and `inspector.title` for the
panel's own title. A dialog hangs in no panel, so its address starts at the dialog:
`dialogAbout.title`, `dialogAbout.buttonOk`.

**Theme entries: `kind.part.property.state`.**

- *Parts* — `fill`, `border`, `text`, `line`, `mark`, `arrow`, `thumb`, `track`, `caret`,
  `selection`, `accent`, and the sub-parts a widget has, such as a combo's `item` or a
  dialog's `title`.
- *Measures* — `width`, `height`, `size` (the side of something square, as a checkbox's
  box), `radius`, `gap`, `padding.x`, `padding.y`. A line's thickness is always its
  `.width`, as `border.width` is.
- *States* — `hover`, `pressed`, `focused`, `selected`, `disabled`, always last:
  `button.fill.hover`, `tree.text.selected`. What a list or menu points at — by pointer or
  by arrow keys alike — is `selected`, as a tree's row is: `combo.item.fill.selected`,
  `menu.fill.selected`.

**Layout entries.** Keys in camelCase (`alignX`), fixed values in lower case (`stretch`,
`column`), sizes as plain numbers of pixels.

**Values.** Colours as `#RRGGBB` or `#RRGGBBAA`; a reference starts with `@`; a number is a
JSON number and a word is a JSON string, never one written as the other (§7, *Validation*).

---

## 8. Localisation

**Decision: English is written in code, where each widget is made; every other language
comes from a locale file, keyed by the widget's address.** The engine is not only for its
author, so the editor must be usable in other languages — but a key catalogue kept by hand
beside the code would have to be edited at every change to the interface, and the interface
changes all the time until it is finished. So the code that makes a widget also says its
English, as it always has, and the key is not invented: it is the address the theme already
reaches the widget by (§7, *Conventions*).

```java
int stop = ae.button(viewport, "buttonStop", "Stop");   // key viewport.buttonStop, English "Stop"
ae.setTooltip(stop, "Stops the scene and goes back to the saved state.");
```

Adding, moving or renaming a widget therefore asks for no file to be edited. Until a
translation says otherwise the widget shows its English, and the editor lists what each
language has left to translate.

### What the files are

A locale is identified by a language and a region — `pt-BR`, `pt-PT`, `de-DE`, `ja-JP` —
written as a BCP 47 tag, the form Java's `Locale.toLanguageTag()` gives. The region matters
because one language varies between countries.

Each locale is one JSON file in `<install>/lang/`, named by its tag, holding the texts that
differ from English:

**`pt-BR.json`**

```json
{
  "name":        "Português (Brasil)",
  "description": "The editor in Brazilian Portuguese.",
  "format":      1,

  "viewport.buttonStop":              "Parar",
  "viewport.buttonStop.tooltip":      "Para a cena e volta ao estado salvo.",
  "inspector.fieldName.placeholder":  "Nome da entidade",
  "inspector.comboShading.lit":       "Iluminado",
  "hierarchy.tabsScene.tabEntities":  "Entidades",
  "menuFile.menuItemSave":            "Salvar",
  "dialogAbout.title":                "Sobre o Aegis",
  "inspector.title":                  "Inspetor"
}
```

The key is the widget's address, and a suffix names a text beside its main one:

| Text | Key |
|---|---|
| A button's, a checkbox's, a label's; a menu's title or an item's | the address — `viewport.buttonStop`, `menuFile.menuItemSave` |
| A text field's placeholder | `.placeholder` — `inspector.fieldName.placeholder` |
| A tooltip | `.tooltip` — `viewport.buttonStop.tooltip` |
| A dialog's title | `.title` — `dialogAbout.title` |
| A panel's title, as a tab where a layout puts it in `"tabs"` | `.title` — `inspector.title` |
| A tab of a tabbed pane | the tab's name — `hierarchy.tabsScene.tabEntities` |
| A combo box's item | the item's name — `inspector.comboShading.lit` |

Tabs and combo items have names for this reason: `ae.addTab(pane, "tabEntities", "Entities",
content)`, `ae.addComboItem(combo, "lit", "Lit")`. Code tells an item by its name —
`comboItemName` — since its text depends on the language.

Not translated: text code composes and sets with `setLabel`, which code is responsible for; a
tree's items, which are the user's data; a menu item's shortcut text. `{count}` in an English
text is a placeholder, and a translation must keep it.

### Choosing the language

1. The language chosen in the editor's settings, once they exist (Phase 4); until then,
   `-Plocale=pt-BR` when running from Gradle.
2. Otherwise the operating system's language, which Java reports through
   `Locale.getDefault()`.
3. For each text, a fallback chain: the exact locale (`pt-BR.json`), then the language alone
   (`pt.json`), then the English in code. A missing translation shows English, never a blank
   or a key.

English needs no file: it is what the code says.

### Validation

The same structure-or-value rule as §7:

| Situation | Outcome |
|---|---|
| The chosen locale has no file | English, with a line of information |
| A locale file does not parse, or is not an object | That locale is unusable, with a warning; English is used |
| A text that is not a string, or a key that is not an address | That text is ignored, with a warning naming its line |
| A key no widget answers to — a widget renamed or removed | Ignored, with a warning |
| A placeholder missing from a translation — `{count}` dropped | The translation is used as written, with a warning |
| A text the file does not translate | English, without a warning: counted in one line of information |

**What is left to translate** is listed by `./gradlew :editor:run -PlocaleCheck`: every
text the chosen locale lacks, each line written as the file would hold it —
`"viewport.buttonPlay": "Play",` — ready to be copied in and translated.

### Allocation

Text is looked up when the locale is applied — `ae.applyLocale(locale)`, once the tree is
built and again to switch language — never per frame. A widget keeps the English it was
made with beside the text it shows, so switching back to English, or to another language,
needs nothing from code.

The mechanism — `AegisLocale`, reading, fallback, placeholders — lives in `:ui`, since it is
not specific to this editor. The locale files themselves are editor data.

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

- **Which languages ship first.** Suggested: English, from code, and `pt-BR`.
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

**`Logger`** — a step of its own, after 3f, not mixed into a UI step. Two gaps:

- *More levels.* Five today (TRACE, DEBUG, INFO, WARN, ERROR). The owner wants a range like
  `java.util.logging`'s seven (SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST): either
  modelled on it, or JUL itself behind our formatter if that brings no problems. The call
  site — `Logger.warn(System.UI, ...)`, used in 37 files — stays as it is; only what sits
  behind it changes.
- *A log file.* Today everything goes to the terminal only, so someone who starts the editor
  with a double click sees nothing. The planned "something went wrong, see the log" popup
  needs a file to point at. Planned: one file per run, under `$XDG_STATE_HOME/AEngine/logs/`
  (`~/.local/state/AEngine/logs/` when unset) on Linux and `%LOCALAPPDATA%\AEngine\logs\` on
  Windows; at startup the oldest is deleted so only the last five remain. Not `/tmp`, which
  is RAM on many distributions and would lose the log of a run that froze the machine; not
  `/var/log`, which a user program cannot write to.

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
| **3f** | `theme.json` drives the colours and `layout.json` the arrangement, each falling back to the factory defaults in code; a broken file is reported precisely and never stops the editor; with the development option on, saving the theme reloads it live | §7 |
| **3g** | The widget set: label, separator, scroll, tabbed and split panes, spinner, tree, the overlay layer and what sits on it | §6 |
| **3h** ✅ | Editor text in English from code, other languages from locale files keyed by address, following the system language by default | §8 |

The breakdown of 3b into its five parts is in *The plan ahead* at the top of this document.

The arrangement half of the shell package — panels placed from data rather than from code —
was first put in Phase 4, since it needs panels that register by name. It moved into 3f: the
scaffolding's panes are enough to register, and settling the format with the theme's let
both be designed together.

Step 3a is the real start of the mountain. Everything before it in this document is the 10%
the effort table in §2 assigns to the draw list and GPU backend.

**Phase 4 — The editor's own screen.** Revised at its start: the panels Dear ImGui draws
today — Engine Stats, Physics Debug, Scene Hierarchy, Properties — are **not** migrated.
The panels are designed anew by the owner and a colleague, and each is added when it is
decided; the ImGui windows stay as they are until Phase 5 removes them. The viewport alone
moves at once, since the screen is built around it.

**The factory model is the reference.** Development works against the factory theme — the
catalogue's defaults — and `FactoryLayout`, both compiled in. The theme and layout files the
engine ships are another developer's, as the themes already were (§7).

**The arrangement follows Godot's and Unity's**, the common ground of the three editors
studied (Godot, Unity, Unreal): the menu bar and the toolbar across the top, the status bar
across the bottom; the hierarchy over the assets on the left, the viewport over the console
and output in the middle, the inspector the whole height on the right. Every dock is a
`"tabs"`, so it shows its title and can take more panels.

**4a ✅ — the screen and the viewport.** The scaffolding is deleted; `EditorShell` registers
the editor's panels, builds the `editor` screen, applies the factory theme — or
`-PthemeFile` — and the language, and runs each frame. The panels are empty but for their
titles, except:

- the **menu bar**, with File › Save scene (Ctrl+S) and Quit;
- the **viewport**, a surface — `ae.surface(parent, "surfaceScene")`, Swing's `Canvas` —
  showing the scene's framebuffer, which renders at the surface's size. A press on it picks
  an entity and starts a drag, as the ImGui viewport did, and focuses it.

**The viewport owns the camera controls.** The camera takes the keys only while the
viewport has focus — a left or right press on it, or Tab — and gives them up as soon as
anything else is focused, so Space typed into a field no longer raises it. With the right
button held, W A S D fly the camera, as in Unity, Unreal and Godot. The wheel zooms only over
the viewport. Esc leaves the editor only while the viewport has focus and nothing
is open over it.

Panels and the screen are now drawn by `ae.draw()`: a registered panel as its background,
the screen's root as the window's. Theme entries, global only: `window.fill`, `panel.fill`,
`.border`, `.radius`, `.border.width`; `surface.fill`, `.border.focused`, `.border.width`;
and the palette's `background`.

While ImGui still draws windows, it draws **on top**: over one of them the pointer is
ImGui's, and while one of its fields is typed into the keys are. The ImGui viewport's
right-click menu (Add Entity, Save Scene) is gone with it; the right button now looks around.

**4b ✅ — icons and the toolbar.** An icon set is an **icon font and a map** from the engine's
names for its icons to the font's code points — `/icons/icons.json`:

```json
{ "name": "Lucide (provisional)", "format": 1, "font": "lucide/lucide.ttf",
  "icons": { "play": "U+E140", "toolMove": "U+E125" } }
```

`aegis.loadIcons(map, size)` bakes only the icons the map names into an atlas of shapes, as
the text atlas is, so an icon is drawn in the colour of the text beside it and a theme
recolours both at once — neutral by default. The names are the engine's and stay; the font
and code points are the designer's to replace, with any icon font, such as those IcoMoon or
Fontello make from SVGs. Lucide (ISC) stands in until then. The set ships with the engine, so
a mistake in its map is thrown; an icon code asks for that the map lacks is said once and
shows nothing.

- `ae.setIcon(node, "play")` on a button, a toggle button or a label — Swing's `setIcon`. With
  no text the widget is a square of the icon, and wants a tooltip; an empty text asks for no
  translation.
- `ae.toggleButton(parent, "toggleMove", text)` — Swing's `JToggleButton`: stays pressed,
  `isChecked`, `wasChanged`. `ae.groupToggles(...)` — Swing's `ButtonGroup`: one pressed at a
  time, the pressed one staying so when pressed again.
- `ae.setEnabled(node, false)` — Swing's `setEnabled`: the widget and all it holds drawn
  faded by `disabled.opacity`, reached by neither the pointer nor Tab; focus on it moves off.

Theme entries: `toggle.fill`, `.fill.hover`, `.fill.selected`, `.fill.selected.hover`,
`.border`, `.text`, `.text.selected`, `.radius`, `.border.width`, `.padding.x`, `.padding.y`;
global `icon.gap` and `disabled.opacity`.

The toolbar has the **tools** — select, move, rotate, scale, a group — on the left, and
**Play, Pause and Stop** in the middle, as Unity's and Godot's. Play starts the scene and,
pressed again, stops it; Pause freezes physics, scripts and audio while it plays; Stop puts
the scene back as it was. F5 still plays and stops. Pause and Stop are usable only while the
scene plays. The tools change nothing yet: there are no gizmos to use them.

**2D and 3D are not on the toolbar**: `RenderMode` is chosen at startup and read by the
physics thread, and says it must not change underneath the systems. Switching it while the
editor runs is engine work to plan first.

**4c-1 ✅ — docks the user resizes.** `"resizable": true` in the layout (§7): the user
drags a dock's edge, or moves it with F8 and the arrows, and the arrangement is written to
their `layout.json` a second later. The factory layout makes the left column, the assets,
the console and output, and the inspector resizable. `screens.describe(screen)` gives a
built screen as a file would write it; `ae.wasLayoutChanged()` says when to.

**Next**, in an order to be agreed:

- **4c-2, panels closed and reopened** from a View menu, and *Restore default layout*.
- **What any panel will need:** nodes removed and reused, for panels whose content changes; a
  label updated without allocating; a context menu; undo and redo.
- **The panels themselves**, as they are decided, with what each asks of the framework.
- **Settings**: `editor.json` in the user's directory, the language, the theme list with the
  development option, *Restore default layout*.

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
