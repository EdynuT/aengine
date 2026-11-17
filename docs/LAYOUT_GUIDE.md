# Writing a layout

A layout is one JSON file that says **where** things go in the editor: which panels each
screen shows, in which rows, columns and tabs, at what size, and in what order a panel's
widgets stand. It never says what a widget looks like (that is the theme, see
[THEME_GUIDE.md](THEME_GUIDE.md)) or what it does (that is the engine's code).

This guide covers everything a layout can say. The design reasons behind the format are in
[UI_FRAMEWORK_ARCHITECTURE.md](UI_FRAMEWORK_ARCHITECTURE.md), §7.

---

## 1. The idea in one paragraph

The engine's code **makes** the panels and their widgets, each with a name and wired to
what it does. The layout only **places** those names. It puts the `hierarchy` panel 260
pixels wide on the left, makes the `viewport` take the room that is left, and so on. It can
reorder a panel's widgets, group them in rows and columns, move a widget into another panel,
or hide one. It cannot create a widget, give it a text or an action: the file is data, never
behaviour.

---

## 2. Quick start

The smallest useful layout: the toolbar on top, the viewport filling the rest.

```json
{
  "name": "Minimal",
  "description": "Toolbar on top, viewport below. Nothing else.",
  "format": 1,

  "screens": {
    "editor": {
      "root": { "type": "column", "alignX": "stretch", "children": [
        { "type": "panel", "panel": "toolbar", "height": 40 },
        { "type": "panel", "panel": "viewport", "grow": 1 }
      ]}
    }
  }
}
```

Every panel the file does not place (here, everything else) is simply not shown.

Save it as `minimal.json` in a `layouts/` directory (see §3) and start the editor with it:

```
./gradlew :editor:run -PtestShell -PlayoutFile=minimal
```

`-PtestShell` reads `editor/test-shell/layouts/minimal.json` and never writes over it. The
`test-shell` folder is your own sandbox: it is not in the repository, so create it, with
`layouts/` and `themes/` inside, the first time.

---

## 3. Where layout files live, and how one is chosen

The editor reads `layouts/<name>.json`, where the name is `default` unless
`-PlayoutFile=<name>` says otherwise. It looks in three places, **screen by screen**:

| Order | Place | Who writes it |
|---|---|---|
| 1 | The user's directory: `~/.local/share/AEngine/gui/layouts/` on Linux, `%LOCALAPPDATA%\AEngine\gui\layouts\` on Windows | **The editor**, when the user resizes or closes a dock |
| 2 | The installation: `<install>/gui/layouts/`. In the repository, `editor/src/dist/gui/layouts/` | The designer: the layout that ships |
| 3 | The factory layout, compiled into the engine | Nobody. Used when no file describes the screen usably |

For each screen (today only `editor`), the first file that describes it **without errors**
is used. A screen with an error in the user's file comes from the installation's, and so on.

> **Watch out while designing.** Once you resize or close a dock in the running editor, the
> editor writes your arrangement to the user's file, which then **comes first**: changes you
> make to the installation's file seem to do nothing. Use **View › Restore default layout**,
> which deletes the user's copy of the screen, or run with `-PtestShell`, which never writes.

| Command | Effect |
|---|---|
| `./gradlew :editor:run` | `layouts/default.json`, looked for as above |
| `... -PlayoutFile=moved` | `layouts/moved.json` instead |
| `... -PtestShell` | The user's directory becomes `editor/test-shell/` (a local folder, not in the repository), and nothing is written there |

The layout is read once, when the editor starts: restart it to see a change. **File names**
are lower case, with hyphens between words.

---

## 4. The file

```json
{
  "name": "Default",
  "description": "What it arranges, in a sentence.",
  "format": 1,

  "screens": {
    "editor": {
      "root": { ...one node... }
    }
  }
}
```

| Key | What it is |
|---|---|
| `name` | The layout's name as people will see it. Text. |
| `description` | What it arranges, in a sentence. Text. JSON has no comments, so anything you would write as a comment goes here. |
| `format` | Always `1` for now: a whole number, without quotes. |
| `screens` | **Required.** An object with one entry per screen, each holding exactly one key, `root`: the node at the top of that screen. |

A file with anything else at the top level gets a warning for that key. A file with no
`screens` object cannot be used at all.

**Screens.** A screen is a whole window's content. Today the editor has one, `editor`; the
settings screen will be another. A file may describe only some screens: the others come
from the next file in line.

**Strict JSON**, UTF-8, two-space indentation, as for themes.

---

## 5. Nodes

A screen is a tree of nodes. Every node is an object with a `"type"`, always written:

| Type | What it is | Holds |
|---|---|---|
| `row` | Children side by side, left to right | `children` |
| `column` | Children stacked, top to bottom | `children` |
| `panel` | One panel, by the name code gave it: `"panel": "inspector"` | Optionally `children`: its widgets, arranged (§7) |
| `tabs` | Several panels sharing one place, one shown at a time, with a tab for each | `panels`: a list of panel names |
| `widget` | One widget, by its name: `"widget": "buttonStop"`. Only inside a panel's `children`. | Nothing |

**Where each may stand:**

- At the top and between panels: `row`, `column`, `panel`, `tabs`.
- Inside a panel's `children`: `widget`, `row`, `column`. Never another `panel` or `tabs`.
- A `widget` only inside a panel's `children`, at any depth of rows and columns there.
- The `root` of a screen cannot be a `widget`, and cannot be hidden.

**Each panel and each widget has one place in the whole file.** Naming one twice, even on
two different screens, is an error.

---

## 6. Properties

### Which node takes which

| Property | `row` | `column` | `panel` | `tabs` | `widget` |
|---|:-:|:-:|:-:|:-:|:-:|
| `width`, `height`, `grow` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `gap`, `padding`, `alignX`, `alignY` | ✓ | ✓ | ✓ | | |
| `children` | ✓ | ✓ | ✓ | | |
| `hidden` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `resizable` | ✓ | ✓ | ✓ | ✓ | |
| `panels`, `closed` | | | | ✓ | |

A property a node does not take is a warning, and is ignored.

### Size

All sizes are numbers of pixels, written without quotes, 0 or more.

| Property | What it does |
|---|---|
| `width` | The width the node asks for. |
| `height` | The height the node asks for. |
| `grow` | A share of the room left over **along the parent's direction**: across for a child of a `row`, down for a child of a `column`. Room left over is shared among the children with `grow` above 0, in proportion: `2` gets twice what `1` gets. `0`, or no `grow`, keeps the node at the size it asks for. |

A node that sets no `width` (or `height`) takes what its content needs: a row is as wide as
its children and the gaps between them, a widget as wide as its text. A panel's size from
code is kept unless the layout sets one.

**`grow` works along the parent's direction only.** To make a child fill the other way (a
panel in a row filling the row's height), use the parent's `alignY: "stretch"` (below).

### Spacing and alignment (`row`, `column`, `panel`)

| Property | Values | What it does |
|---|---|---|
| `padding` | number | Space kept clear inside the node's four edges |
| `gap` | number | Space between one child and the next |
| `alignX` | `"start"`, `"center"`, `"end"`, `"stretch"` | Where the children stand horizontally |
| `alignY` | the same | Where the children stand vertically |

The four alignments:

| Value | Meaning |
|---|---|
| `"start"` | Against the left edge (X) or the top edge (Y). **The default.** |
| `"center"` | In the middle |
| `"end"` | Against the right edge (X) or the bottom edge (Y) |
| `"stretch"` | Across the whole width (X) or height (Y), ignoring the size the child asks for |

What an alignment does depends on the direction the node lays out its children:

| Node | `alignX` | `alignY` |
|---|---|---|
| `row` (children left to right) | Moves the whole group of children left, centre or right, when they leave room spare. A child with `grow` takes that room and nothing moves. **`"stretch"` is an error**: use `grow` to widen children in a row. | Each child up, centred, down, or **stretched to the row's full height** |
| `column` (children top to bottom) | Each child left, centred, right, or **stretched to the column's full width** | Moves the whole group up, centre or down. **`"stretch"` is an error**: use `grow` to heighten children in a column. |

For a `panel`, it depends on whether its code made it a row or a column (§10). The editor
says which in the error if you get it wrong.

> **The most common surprise:** alignment starts at `"start"`, so a column's children keep
> the width they ask for. A column of panels that should fill the window's width needs
> `"alignX": "stretch"`, and a row of panels that should fill its height needs
> `"alignY": "stretch"`. The factory layout (§11) sets both wherever it matters.

### `hidden`

`"hidden": true` hides a node, with everything in it: it is not drawn, takes no room, and
neither the pointer nor the keyboard reaches it. It is still **checked**: its names must
exist and its values must be right. A hidden widget counts as placed, so it does not move
to the end of its panel (§7). `false`, the default, may be written or left out.

Hiding keeps the arrangement written down, ready to come back by turning `true` to `false`,
where removing it would lose it. The root of a screen cannot be hidden.

### `resizable`: docks the user drags

`"resizable": true` lets the user resize the node by dragging the edge it shares with a
growing neighbour, as in Godot or Unity. The rules:

- The node must be a child of a `row` and have a `width`, or a child of a `column` and have
  a `height`. That size is what the user changes.
- A sibling with `grow` must stand beside it, to give and take the room. The edge dragged is
  the one facing the nearest growing sibling.
- The node never gets smaller than 80 pixels, and never leaves its growing neighbour less.

A resizable node that breaks a rule gets a warning and stays fixed. From the keyboard,
**F8** gives each resizable edge the keyboard in turn: the arrow keys move it 10 pixels a
step, 50 with Shift, and Esc, Enter or Tab give the keyboard back.

The edge is drawn only while pointed at, dragged (the theme's `split.line.hover`) or held by
the keyboard.

### `tabs`: `panels` and `closed`

```json
{ "type": "tabs", "height": 200, "resizable": true, "panels": ["console", "output"] }
```

- **`panels`** (required): the panels, in tab order. The first is the one shown at start.
  Each tab's title comes from code, translated by the language files; the layout never
  writes a title.
- **`closed`**: panels of `panels` the user closed, which are not on the tab bar:
  `"closed": ["output"]`. The editor writes this when a dock is closed from the View menu or
  its tab's context menu. With every panel closed, the dock takes no room. A name that is
  not one of its `panels` is a warning, and ignored.

Only panels shown in `tabs` can be closed by the user. A panel placed with a `panel` node
stays where it is: use that for the menu bar, the toolbar and the status bar.

---

## 7. Arranging a panel's own widgets

A `panel` without `children` shows its widgets as code laid them out. With `children`, the
file arranges them:

```json
{ "type": "panel", "panel": "toolbar", "height": 40, "children": [
  { "type": "widget", "widget": "toggleSelect" },
  { "type": "widget", "widget": "toggleMove" },
  { "type": "row", "gap": 12, "children": [
    { "type": "widget", "widget": "togglePlay" },
    { "type": "widget", "widget": "togglePause" }
  ]},
  { "type": "widget", "widget": "buttonStop", "width": 80 }
]}
```

- **Order and groups.** Widgets stand in the order written, grouped in any rows and columns
  you like. These rows and columns have no name, so regrouping a panel never breaks a theme.
- **Moving a widget to another panel.** Name it in the other panel's `children`. A widget
  keeps the theme of the panel code made it in, wherever it is moved. For example, the recipe
  in §12 moves the menu bar into the toolbar.
- **A widget left out goes to the end** of the panel code made it in, with a warning. A
  control an engine update adds is never lost because an older layout did not know it. To
  leave a widget out on purpose, place it with `"hidden": true`.
- **A widget name no panel has** is a warning, and ignored: it was renamed or removed.
- A widget node takes only `width`, `height`, `grow` and `hidden`.

**Spacers.** Code puts named boxes in some panels, `boxSpaceBefore` and `boxSpaceAfter` in
the toolbar, that grow to push widgets apart. They are placed like widgets. A layout can
also make its own: an empty row that grows, `{ "type": "row", "grow": 1 }`.

---

## 8. What the editor writes back

When the user resizes a dock or closes one, the editor writes the `editor` screen a second
later, with the sizes the user left and the panels they closed, into the **user's**
`layouts/<name>.json`, under `"screens"`. Only that screen: the file's other screens are
kept, and screens it does not hold go on coming from the installation. The write goes
through a temporary file, so a crash never leaves half a file.

**View › Restore default layout** removes the screen from the user's file (deleting the
file if nothing is left in it) and builds it again from the installation's layout.

The installation's file is **never** written by the editor.

---

## 9. Reading the log

When the editor loads a layout it writes, in one block, everything it found. Each line gives
the **line number** in the file:

```
ERROR layouts/default.json: screen "editor" has errors and is not used; it comes from the next layout in line. Its errors:
ERROR   line 12: panel "inspector": width is a number, written without quotes, like 240; got "300"
WARN  layouts/default.json: 1 problem:
WARN    line 20: widget "buttonRecord" is not a widget of any panel the code registers; it is ignored
```

**A layout is stricter than a theme.** A wrong theme colour spoils one colour; a wrong layout
value can leave a panel 0 pixels wide and the editor unusable. So:

**Errors set the whole screen aside**, and it comes from the next file in line:

| Error | Example |
|---|---|
| A node that is not an object | `"children": [ "toolbar" ]` |
| No `"type"`, or an unknown one | `"type": "grid"` |
| A `panel` or `widget` node without its name | `{ "type": "panel" }` |
| A panel name code does not register | `"panel": "console2"` |
| A panel or widget placed twice in the file | `toolbar` in two places |
| A `panel` or `tabs` inside a panel's `children` | |
| A `widget` outside a panel's `children` | |
| `children` that is not a list | `"children": {}` |
| `tabs` without `panels`, with an empty list, or with a name that is not text | |
| A size or spacing that is not a number of 0 or more | `"width": "260"`, `"gap": -4` |
| An alignment that is not one of the four, or `"stretch"` along a row or column's direction | `"alignY": "middle"`; `"alignX": "stretch"` on a row |
| `hidden` or `resizable` that is not `true` or `false` | `"hidden": "yes"` |
| A root that is hidden, or a widget | |

Every error of a screen is listed, not only the first.

**Warnings** keep the screen, and drop what they name:

| Warning | What happens |
|---|---|
| A property the node does not take (`"color"` on a panel, `"gap"` on a tabs) | Ignored |
| A key written twice | The last counts |
| A widget name no panel has | Ignored |
| A widget of a rearranged panel that the file does not place | Added at the end of its panel |
| `resizable` on a node that cannot be resized (§6) | It stays fixed |
| A `closed` name that is not one of the tabs' `panels`, or not text | Ignored |
| Unknown keys at the top level, or in a screen | Ignored |
| No `format` | Read as format 1 (a note) |

**Not an error:** a registered panel the file does not place is simply not shown. That is
how a small layout is written.

A wrong layout never stops the editor from starting: the factory layout is always there.

---

## 10. The editor's panels and widgets today

The panels the editor registers, and their widgets, by the names a layout uses. The panels
are still being designed: this list will grow, and the log always says when a name no longer
exists.

| Panel | Code lays it out as | Widgets |
|---|---|---|
| `menu` | row | `menuBarMain`: the menu bar with File, Edit and View |
| `toolbar` | row | `toggleSelect`, `toggleMove`, `toggleRotate`, `toggleScale`, `boxSpaceBefore`, `togglePlay`, `togglePause`, `buttonStop`, `boxSpaceAfter` |
| `hierarchy` | column | None yet |
| `assets` | column | None yet |
| `viewport` | column | `surfaceScene`: the scene |
| `console` | column | None yet |
| `output` | column | None yet |
| `inspector` | column | None yet |
| `status` | row | `labelEditing`, `labelPlaying`, `labelPaused`, `separatorState`, `labelFps`, `labelFpsValue`, `separatorFps`, `labelEntities`, `labelEntitiesValue`, `boxStatusSpace`, `labelProject` |

Of `labelEditing`, `labelPlaying` and `labelPaused`, code shows one at a time. Hiding one
from the layout hides it for good.

The View menu can close and reopen `hierarchy`, `assets`, `inspector`, `console` and
`output`, **when the layout puts them in `tabs`**. Put in a `panel` node instead, a dock
cannot be closed and its View item is disabled.

---

## 11. A complete example: the factory layout

The layout the engine falls back on, in the Godot and Unity arrangement: menu bar and
toolbar on top, status bar at the bottom; between them the hierarchy over the assets on the
left, the viewport over the console and output in the middle, and the inspector on the
right.

```json
{
  "name": "Factory",
  "description": "Menu bar and toolbar on top, status bar below; hierarchy over assets on the left, viewport over console and output in the middle, inspector on the right.",
  "format": 1,

  "screens": {
    "editor": {
      "root": { "type": "column", "padding": 6, "gap": 6, "alignX": "stretch", "children": [
        { "type": "panel", "panel": "menu",    "height": 28 },
        { "type": "panel", "panel": "toolbar", "height": 40 },
        { "type": "row", "grow": 1, "gap": 6, "alignY": "stretch", "children": [
          { "type": "column", "width": 260, "resizable": true, "gap": 6, "alignX": "stretch", "children": [
            { "type": "tabs", "grow": 1, "panels": ["hierarchy"] },
            { "type": "tabs", "height": 280, "resizable": true, "panels": ["assets"] }
          ]},
          { "type": "column", "grow": 1, "gap": 6, "alignX": "stretch", "children": [
            { "type": "tabs", "grow": 1, "panels": ["viewport"] },
            { "type": "tabs", "height": 200, "resizable": true, "panels": ["console", "output"] }
          ]},
          { "type": "tabs", "width": 320, "resizable": true, "panels": ["inspector"] }
        ]},
        { "type": "panel", "panel": "status", "height": 24 }
      ]}
    }
  }
}
```

Reading it from the top:

- The root `column` stacks four things and stretches each across the window (`alignX`).
  `padding` keeps 6 pixels clear around the window's edge, `gap` 6 between rows.
- The menu bar, toolbar and status bar have fixed heights. The middle `row` has `grow: 1`,
  so it takes all the height they leave, and stretches its three children to its full
  height (`alignY`).
- In the middle row, the left column (260 wide) and the inspector (320 wide) are fixed and
  `resizable`; the middle column has `grow: 1`, so it takes the width left over, and gives
  and takes room as the user drags the side docks' edges.
- In each column, the top `tabs` grows and the bottom one is fixed and resizable: dragging
  the edge between them moves room from one to the other.
- Every dock is a `tabs`, so each shows its title on a tab, can be closed from the View
  menu, and could take more panels: `"panels": ["console", "output"]` puts two in one dock.

---

## 12. Recipes

**Arranged as Unreal's.** Viewport in the middle, inspector and hierarchy side by side on the
right, the assets with the console and output at the bottom:

```json
{ "type": "row", "grow": 1, "gap": 6, "alignY": "stretch", "children": [
  { "type": "tabs", "grow": 1, "panels": ["viewport"] },
  { "type": "tabs", "width": 300, "resizable": true, "panels": ["inspector"] },
  { "type": "tabs", "width": 260, "resizable": true, "panels": ["hierarchy"] }
]},
{ "type": "tabs", "height": 220, "resizable": true, "panels": ["assets", "console", "output"] }
```

These two go inside the root column, in place of the factory's middle row. Here, the bottom
dock is resizable because the middle row above it grows.

**The menu bar inside the toolbar**, giving its row back to the scene:

```json
{ "type": "panel", "panel": "menu", "hidden": true },
{ "type": "panel", "panel": "toolbar", "height": 40, "children": [
  { "type": "widget", "widget": "menuBarMain" },
  { "type": "widget", "widget": "toggleSelect" },
  { "type": "widget", "widget": "toggleMove" },
  { "type": "widget", "widget": "toggleRotate" },
  { "type": "widget", "widget": "toggleScale" },
  { "type": "widget", "widget": "boxSpaceBefore" },
  { "type": "widget", "widget": "togglePlay" },
  { "type": "widget", "widget": "togglePause" },
  { "type": "widget", "widget": "buttonStop" },
  { "type": "widget", "widget": "boxSpaceAfter" }
]}
```

`menu` is hidden rather than left out, so it is ready to come back.

**Play, Pause and Stop on the right** instead of in the middle: place `boxSpaceBefore`
before them and hide `boxSpaceAfter`.

**No status bar:** leave the `status` panel out, or give it `"hidden": true`.

**Two docks sharing the left side, as tabs:** `{ "type": "tabs", "grow": 1, "panels":
["hierarchy", "assets"] }` in place of the left column.
