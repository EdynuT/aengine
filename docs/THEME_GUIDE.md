# Writing a theme

A theme is one JSON file that sets the editor's colours and sizes: the fill of a button, the
corner radius of a panel, the colour of a focus ring. It changes how the editor **looks**,
never what it does or where things are. Where panels and widgets go is the layout's job,
described in [LAYOUT_GUIDE.md](LAYOUT_GUIDE.md).

This guide covers everything a theme can say. The design reasons behind the format are in
[UI_FRAMEWORK_ARCHITECTURE.md](UI_FRAMEWORK_ARCHITECTURE.md), §7.

---

## 1. Quick start

A complete, working theme:

```json
{
  "name": "Ocean",
  "description": "Dark blue panels with a teal accent.",
  "format": 1,

  "global": {
    "accent":         "#2EC4B6",
    "background":     "#0B1320",
    "surface":        "#13203A",
    "surface.raised": "#1C2E52",
    "surface.hover":  "#27406E",
    "button.radius":  4
  }
}
```

Save it as `ocean.json` in a `themes/` directory (see §2), then start the editor with it:

```
./gradlew :editor:run -PthemeFile=ocean -PthemeWatch
```

With `-PthemeWatch`, every save of the file is applied to the running editor at once.
**Ctrl+R** reapplies the theme by hand at any time.

Everything the file leaves out keeps its default. Most widgets take their colours from the
**palette** (§5), so the eight palette colours alone repaint almost the whole editor.

---

## 2. Where theme files live, and how one is chosen

Themes live in two places, and the factory theme is always there besides:

| Order | Place | Who writes it |
|---|---|---|
| 1 | The user's directory: `~/.local/share/AEngine/gui/themes/` on Linux, `%LOCALAPPDATA%\AEngine\gui\themes\` on Windows | The user: their own themes |
| 2 | The installation: `<install>/gui/themes/`. In the repository, `editor/src/dist/gui/themes/` | The designer: the themes that ship |
| 3 | The factory theme, compiled into the engine | Nobody. Used when nothing else is chosen, or the file chosen is gone or cannot be read |

**Choosing a theme: View › Theme.** It lists the user's themes under *Yours*, the
installation's under *Installed*, each by the `"name"` its file gives, and the factory theme
last; the one in use is ticked. Choosing one applies it at once, without restarting, and asks
*Keep this theme and layout?*: **Keep** writes the choice to `editor.json` in the user's
directory for the next start; **Revert**, Esc, or ten seconds with no answer bring the previous
theme back.

> **A theme can be valid and still unusable.** Sizes have no upper limit, so a padding of
> 100 000 px is accepted and pushes the whole interface out of the window. If that happens,
> wait ten seconds — the confirmation reverts on its own — or press **Ctrl+Shift+F12** at any
> time: the factory theme and layout come back, whatever the files did. A user's theme with the
same file name as an installed one is listed beside it, not instead of it. The list is read
again each time the View menu opens, so a file just saved into a `themes/` directory is there.

From the command line, for a development run:

| Command | Effect |
|---|---|
| `./gradlew :editor:run` | The theme chosen last in View › Theme; the factory theme if none |
| `./gradlew :editor:run -PthemeFile=dark` | `themes/dark.json` for this run: the user's if it exists, else the installation's |
| `... -PthemeWatch` | Saving the theme file applies it to the running editor |
| `... -PtestShell` | The user's directory becomes `editor/test-shell/`, a local folder not in the repository, so test themes never touch your real one |

**File names** are lower case, with hyphens between words: `dark.json`,
`high-contrast.json`.

---

## 3. The file

A theme file is one JSON object. It holds four kinds of entry:

```json
{
  "name": "Dark",                                    ← metadata
  "description": "The built-in dark theme.",
  "format": 1,

  "global": {                                        ← global: everywhere
    "accent":        "#5C9EF0",                      ←   a palette colour
    "button.fill":   "@global.surface.raised",       ←   every button
    "button.radius": 6
  },

  "danger": "#C04040",                               ← a variable: no dot

  "toolbar.toggle.fill":     "#1A1D26",              ← a rule: every toggle in the toolbar
  "toolbar.buttonStop.fill": "@danger"               ← a rule: one widget
}
```

| Entry | Written as | What it does |
|---|---|---|
| **Metadata** | `name`, `description`, `format` | What the theme list will show, and the format version the theme was written for. Write them first, in this order. |
| **Global** | Inside `"global": { ... }` | The palette, and how every widget of a kind looks, everywhere in the editor. |
| **Variable** | A key with **no dot**, outside `global` | Paints nothing by itself. It is a named value that other entries refer to as `@name`. |
| **Rule** | A key **with a dot**, outside `global` | Dresses the widgets of one panel, or one widget, differently from the global look. See §6. |

### Metadata

- **`name`**: the theme's name as people will see it. Text.
- **`description`**: what the theme is, in a sentence. Text. JSON has no comments, so
  anything you would write as a comment goes here.
- **`format`**: always `1` for now. It is a whole number, written without quotes. A theme
  without it still loads, with a note in the log. A theme declaring a newer format than the
  engine knows is warned about, since it may use names this engine has never heard of.

### General rules of the file

- **Strict JSON.** Keys and text in double quotes, no trailing commas. Comments are
  tolerated if you add one by accident, but the shipped themes have none: VS Code marks them
  as errors in a `.json` file.
- **UTF-8**, indented by **two spaces**.
- **A key written twice:** only the last one counts. The log points it out.
- **Order does not matter**, except that a key written twice keeps its last value.

---

## 4. Values

Every value is one of three things:

| Value | Written as | Example |
|---|---|---|
| **Colour** | Text: `#RRGGBB`, or `#RRGGBBAA` with transparency. Upper or lower case. | `"#5C9EF0"`, `"#00000059"` |
| **Size** | A JSON **number**, without quotes. Pixels, 0 or more; fractions allowed. | `6`, `1.5` |
| **Reference** | Text starting with `@` | `"@global.accent"`, `"@danger"` |

**Types are strict.** A size written as text, `"6"`, is refused, and so is a colour written
as a number. Either way the property keeps its default and the log says why. This catches
mistakes that would otherwise look like a theme "not working".

**Transparency.** The last two digits of `#RRGGBBAA` are the opacity: `FF` opaque, `00`
invisible, `80` about half. `"#00000000"` draws nothing: use it to remove a border or a
fill.

### References

A reference makes one value follow another, so a colour is chosen once and used in many
places:

- **`@global.<name>`** refers to an entry of `global`: `"@global.accent"`. If the theme does
  not set that entry, the reference follows the engine's default for it.
- **`@<name>`** refers to a variable of this file: `"@danger"`.

References can chain: a variable may refer to another variable, or to `@global.something`.
They are resolved once, when the theme is loaded. A reference to something that does not
exist, or a loop (`a` → `b` → `a`), is reported, and the property keeps its default.

### Variables

A key without a dot, outside `global`, is a variable:

```json
"danger":  "#C04040",
"warning": "#E0A030",

"toolbar.buttonStop.fill": "@danger"
```

A variable paints nothing until something refers to it. One that nothing refers to is
pointed out in the log. Use variables for colours that are not part of the palette but are
used in several rules. Since the dot is what tells a variable from a rule, a variable is
**one word**: `danger`, `warning`, `brandBlue`.

---

## 5. The palette

The palette is eight colours in `global` that no widget reads directly. Instead, almost every
widget's default refers to them. Set the palette and the whole editor follows, **including
widgets added to the engine after the theme was written**.

| Name | Default | Used for |
|---|---|---|
| `accent` | `#5C9EF0` | Focus rings, a ticked checkbox, a slider's fill, selections, highlighted menu items |
| `text` | `#E6EBF2` | Text everywhere |
| `text.dim` | `#808A9E` | Secondary text: placeholders, shortcuts, arrows, unselected tabs |
| `surface` | `#242936` | Sunken areas: inside fields, an empty slider track, a pressed button |
| `surface.raised` | `#333B4A` | Raised areas: a resting button, the selected tab |
| `surface.hover` | `#454F63` | Raised areas under the pointer |
| `background` | `#1B1E26` | Behind the panels: the window itself, an empty viewport |
| `border` | `#616B85` | Borders and lines |

**Start every theme from the palette.** Then adjust kinds of widget in `global`, then
panels with rules, and use one widget's rule only for an exception (§9).

---

## 6. Rules: dressing one panel, or one widget

A key with a dot, outside `global`, is a **rule**. It dresses part of the editor
differently from the global look. There are two kinds.

### Every widget of a kind, in a panel

```
<panel>.<kind>.<property>
```

```json
"toolbar.toggle.radius": 2,
"status.label.text":     "@global.text.dim",
"toolbar.focus.ring":    "#E0A030"
```

`toolbar.toggle.radius` dresses every toggle button in the toolbar, **including toggles added
to the toolbar later**; once the inspector has fields, `inspector.field.fill` will dress all
of them. `<kind>` is one of the kinds in §7 (`button`, `field`, `toggle`...),
or `focus` for the focus ring.

### One widget

```
<widget address>.<property>
```

```json
"toolbar.buttonStop.fill":         "#C04040",
"toolbar.togglePlay.fill.selected": "#2E7D32",
"menuFile.menuItemQuit.text":      "#E57373"
```

The **address** is the names above the widget, then its own: usually `<panel>.<widget>`. The
property is written **without** the kind: `toolbar.buttonStop.fill`, not
`toolbar.buttonStop.button.fill`. §10 lists every address in the editor today.

A kind is always one lower-case word (`button`) and a widget's name never is (`buttonStop`),
which is how the two kinds of rule are told apart.

### Which value wins

From strongest to weakest:

1. **A rule for the widget itself**: `toolbar.buttonStop.fill`.
2. **A rule for its kind in a panel**: `toolbar.button.fill`. When the widget sits in named
   containers inside one another, the nearest one wins.
3. **`global`**: `"global": { "button.fill": ... }`.
4. **The engine's default**, which usually points at the palette.

### What rules cannot reach yet

Some entries are drawn by things that belong to no panel, so **only `global` sets them**. A
rule naming them reaches nothing, and the log says so:

| Entries | Why |
|---|---|
| `overlay.*`, `tooltip.*` | Popups, menus and tooltips float above every panel |
| `scrollbar.*` | Drawn by the scrolling area, not by a widget |
| `panel.*`, `window.fill` | Panels and the window are not widgets |
| `icon.gap`, `disabled.opacity`, `dialog.default.border` | Shared by every widget that shows them |
| `tabs.*` of the **docks** | The tab bars of the layout's docks belong to no panel (they are made by the layout). A tabbed pane made **inside** a panel can be dressed by rules: `inspector.tabs.fill`. |

---

## 7. Every property

Each kind of widget lists what it has. A **state** (`hover`, `pressed`, `focused`,
`selected`, `disabled`) is part of the name and exists only where listed: `button.fill.hover`
exists, `button.text.hover` does not. A name the list does not have is reported and ignored.

Defaults written `@global.x` follow the theme's palette; the others are fixed values.

**Colours** are text (`"#RRGGBB"`); **sizes** are numbers of pixels unless the description
says otherwise.

### Focus ring
Drawn around the widget that has the keyboard: every focusable widget except text fields,
which show their focus with `field.border.focused`.

| Property | Type | Default | What it is |
|---|---|---|---|
| `focus.ring` | colour | `@global.accent` | The ring's colour |
| `focus.ring.width` | size | `2` | The ring's thickness |
| `focus.ring.gap` | size | `2` | Space between the widget and its ring |

### Button (`button`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `button.fill` | colour | `@global.surface.raised` | Background at rest |
| `button.fill.hover` | colour | `@global.surface.hover` | Background under the pointer |
| `button.fill.pressed` | colour | `@global.surface` | Background while held down |
| `button.border` | colour | `@global.border` | Outline |
| `button.text` | colour | `@global.text` | Text, and its icon |
| `button.border.width` | size | `1` | Outline thickness |
| `button.radius` | size | `6` | Corner radius |
| `button.padding.x` | size | `14` | Space left and right of the text |
| `button.padding.y` | size | `6` | Space above and below the text |

### Toggle button (`toggle`)
A button that stays pressed: the toolbar's tools, Play and Pause.

| Property | Type | Default | What it is |
|---|---|---|---|
| `toggle.fill` | colour | `@global.surface` | Background, not pressed |
| `toggle.fill.hover` | colour | `@global.surface.hover` | Not pressed, under the pointer |
| `toggle.fill.selected` | colour | `@global.surface.raised` | Pressed |
| `toggle.fill.selected.hover` | colour | `@global.surface.hover` | Pressed, under the pointer |
| `toggle.border` | colour | `#00000000` (none) | Outline |
| `toggle.text` | colour | `@global.text.dim` | Text and icon, not pressed |
| `toggle.text.selected` | colour | `@global.text` | Text and icon, pressed |
| `toggle.radius` | size | `4` | Corner radius |
| `toggle.border.width` | size | `1` | Outline thickness |
| `toggle.padding.x` | size | `8` | Space left and right of the content |
| `toggle.padding.y` | size | `4` | Space above and below the content |

### Checkbox (`checkbox`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `checkbox.fill` | colour | `@global.surface` | The box |
| `checkbox.fill.hover` | colour | `@global.surface.raised` | The box under the pointer |
| `checkbox.border` | colour | `@global.border` | The box's outline |
| `checkbox.mark` | colour | `@global.accent` | The tick |
| `checkbox.text` | colour | `@global.text` | The label beside it |
| `checkbox.size` | size | `16` | The box's side |
| `checkbox.radius` | size | `3` | The box's corner radius |
| `checkbox.mark.inset` | size | `4` | Space between the box's edge and the tick |
| `checkbox.gap` | size | `8` | Space between the box and its label |
| `checkbox.padding.y` | size | `4` | Space above and below |

### Label (`label`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `label.text` | colour | `@global.text` | Text, and its icon |
| `label.padding.y` | size | `4` | Space above and below; the same as a checkbox's, so the two line up in a row |

### Separator (`separator`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `separator.line` | colour | `@global.border` | The line |
| `separator.line.width` | size | `1` | Its thickness |
| `separator.margin` | size | `4` | Clear space on each side of the line |

### Slider (`slider`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `slider.track` | colour | `@global.surface` | The empty part of the track |
| `slider.fill` | colour | `@global.accent` | The filled part, up to the thumb |
| `slider.thumb` | colour | `@global.text` | The handle |
| `slider.thumb.hover` | colour | `#FFFFFF` | The handle under the pointer |
| `slider.thumb.pressed` | colour | `#B3BDCC` | The handle while dragged |
| `slider.text` | colour | `@global.text` | The value shown beside it |
| `slider.width` | size | `200` | The whole slider's width |
| `slider.track.height` | size | `4` | The track's thickness |
| `slider.thumb.size` | size | `14` | The handle's diameter |
| `slider.value.width` | size | `44` | Room kept for the value |
| `slider.gap` | size | `8` | Space between the track and the value |
| `slider.padding.y` | size | `4` | Space above and below |

### Text field and text box (`field`)
One-line fields and multi-line boxes alike.

| Property | Type | Default | What it is |
|---|---|---|---|
| `field.fill` | colour | `@global.surface` | Background |
| `field.fill.hover` | colour | `#2B3040` | Background under the pointer |
| `field.border` | colour | `@global.border` | Outline |
| `field.border.focused` | colour | `@global.accent` | Outline while typing in it; it stands for the focus ring |
| `field.text` | colour | `@global.text` | Text |
| `field.placeholder` | colour | `@global.text.dim` | The hint shown while empty |
| `field.caret` | colour | `@global.text` | The blinking cursor |
| `field.selection` | colour | `#5C9EF066` | Behind selected text; keep it translucent |
| `field.width` | size | `200` | Width, unless the layout sets one |
| `field.radius` | size | `4` | Corner radius |
| `field.border.width` | size | `1` | Outline thickness |
| `field.padding.x` | size | `8` | Space left and right of the text |
| `field.padding.y` | size | `6` | Space above and below the text |
| `field.caret.width` | size | `2` | The cursor's thickness |

### Spinner (`spinner`)
A number field with arrows. Its caret, selection, radius, border width and padding are the
text field's, so the two line up.

| Property | Type | Default | What it is |
|---|---|---|---|
| `spinner.fill` | colour | `@global.surface` | Background |
| `spinner.fill.hover` | colour | `#2B3040` | Background under the pointer |
| `spinner.border` | colour | `@global.border` | Outline |
| `spinner.border.focused` | colour | `@global.accent` | Outline while typing in it |
| `spinner.text` | colour | `@global.text` | The number |
| `spinner.arrow` | colour | `@global.text.dim` | The arrows |
| `spinner.arrow.hover` | colour | `@global.text` | An arrow under the pointer |
| `spinner.width` | size | `120` | Width, unless the layout sets one |
| `spinner.button.width` | size | `20` | The arrows' column |

### Combo box (`combo`)
A drop-down list. The `item` entries dress its open list.

| Property | Type | Default | What it is |
|---|---|---|---|
| `combo.fill` | colour | `@global.surface.raised` | The closed box |
| `combo.fill.hover` | colour | `@global.surface.hover` | The closed box under the pointer |
| `combo.border` | colour | `@global.border` | Outline |
| `combo.text` | colour | `@global.text` | The chosen item |
| `combo.arrow` | colour | `@global.text.dim` | The arrow |
| `combo.item.fill.selected` | colour | `@global.accent` | The item the open list points at |
| `combo.item.text.selected` | colour | `#FFFFFF` | Its text |
| `combo.padding.x` | size | `10` | Space left and right of the text |
| `combo.padding.y` | size | `6` | Space above and below |
| `combo.radius` | size | `4` | Corner radius |
| `combo.arrow.width` | size | `20` | Room for the arrow |

### Tree (`tree`)
A list with levels that open and close, as the hierarchy's will be.

| Property | Type | Default | What it is |
|---|---|---|---|
| `tree.text` | colour | `@global.text` | Item text |
| `tree.text.selected` | colour | `#FFFFFF` | The selected item's text |
| `tree.fill.selected` | colour | `#5C9EF066` | Behind the selected item |
| `tree.fill.hover` | colour | `#454F6399` | Behind the item under the pointer |
| `tree.arrow` | colour | `@global.text.dim` | The open/close arrows |
| `tree.indent` | size | `16` | Indent of each level; also the arrow's room |
| `tree.padding.x` | size | `4` | Space left of the items |
| `tree.padding.y` | size | `3` | Space above and below each item |

### Tabs (`tabs`)
The bar of a tabbed pane: the docks' title bars, and tabbed panes inside panels.

| Property | Type | Default | What it is |
|---|---|---|---|
| `tabs.fill` | colour | `@global.surface` | Tabs not shown |
| `tabs.fill.hover` | colour | `@global.surface.hover` | A tab under the pointer |
| `tabs.fill.selected` | colour | `@global.surface.raised` | The tab shown |
| `tabs.text` | colour | `@global.text.dim` | Titles of tabs not shown |
| `tabs.text.selected` | colour | `@global.text` | Title of the tab shown |
| `tabs.accent` | colour | `@global.accent` | The line marking the tab shown |
| `tabs.border` | colour | `@global.border` | The line under the bar |
| `tabs.padding.x` | size | `14` | Space left and right of a title |
| `tabs.padding.y` | size | `6` | Space above and below a title |
| `tabs.gap` | size | `2` | Space between tabs |
| `tabs.radius` | size | `4` | A tab's top corners |
| `tabs.accent.height` | size | `2` | The marking line's thickness |

### Split pane (`split`)
Two areas with a divider between them. The **docks' edges**, which the user drags to resize
a dock, use `split.line.hover` while pointed at or dragged.

| Property | Type | Default | What it is |
|---|---|---|---|
| `split.line` | colour | `@global.border` | The divider |
| `split.line.hover` | colour | `@global.accent` | The divider under the pointer or dragged; also a dock's edge |
| `split.divider.width` | size | `8` | The room the divider takes, which is what the pointer can grab |
| `split.line.width` | size | `2` | The line drawn in that room |

### Menus (`menu`)
The menu bar's titles, the menus they open, submenus and context menus.

| Property | Type | Default | What it is |
|---|---|---|---|
| `menu.text` | colour | `@global.text` | Titles and items |
| `menu.shortcut.text` | colour | `@global.text.dim` | Shortcuts (`Ctrl+S`) and submenu arrows |
| `menu.fill.selected` | colour | `@global.accent` | The item pointed at, and the open menu's title |
| `menu.text.selected` | colour | `#FFFFFF` | Its text |
| `menu.title.fill.hover` | colour | `@global.surface.hover` | A title under the pointer, no menu open |
| `menu.padding.x` | size | `10` | Space left and right of a title or item |
| `menu.padding.y` | size | `4` | Space above and below |
| `menu.gap` | size | `28` | Space between an item's text and its shortcut |
| `menu.check.width` | size | `18` | Room for the tick of a check item |

### Dialog (`dialog`)

| Property | Type | Default | What it is |
|---|---|---|---|
| `dialog.title.text` | colour | `@global.text` | The title bar's text |
| `dialog.line` | colour | `@global.border` | The line under the title bar |
| `dialog.close.fill.hover` | colour | `#C04040` | The × under the pointer |
| `dialog.default.border` | colour | `@global.accent` | Outline of the default button, which Enter presses (global only) |
| `dialog.title.padding.y` | size | `6` | Space above and below the title |

The dialog itself is drawn as an overlay: its background is `overlay.fill`.

### Surface (`surface`)
An area the engine draws into, such as the viewport's scene.

| Property | Type | Default | What it is |
|---|---|---|---|
| `surface.fill` | colour | `@global.background` | Shown while it has nothing to show |
| `surface.border.focused` | colour | `@global.accent` | Outline while it has the keyboard |
| `surface.border.width` | size | `1` | That outline's thickness |

The scene itself is never coloured by the theme. A theme dresses the editor, never the
user's work: if a theme colour changes anything inside the viewport's scene, that is a bug.

### Global only

These are set in `global` only (§6, *What rules cannot reach yet*).

| Property | Type | Default | What it is |
|---|---|---|---|
| `window.fill` | colour | `@global.background` | The window behind the panels |
| `panel.fill` | colour | `#2B303C` | Every panel's background |
| `panel.border` | colour | `#3A4150` | Every panel's outline |
| `panel.radius` | size | `4` | A panel's corner radius |
| `panel.border.width` | size | `1` | A panel's outline thickness |
| `overlay.fill` | colour | `#2B3040` | Background of popups, open lists, menus and dialogs |
| `overlay.border` | colour | `@global.border` | Their outline |
| `overlay.shadow` | colour | `#00000059` | Their shadow |
| `overlay.scrim` | colour | `#00000073` | Laid over the editor behind an open dialog |
| `overlay.radius` | size | `6` | Their corner radius |
| `overlay.shadow.offset` | size | `4` | How far the shadow falls |
| `tooltip.fill` | colour | `#1A1C26` | Tooltip background |
| `tooltip.border` | colour | `@global.border` | Tooltip outline |
| `tooltip.text` | colour | `@global.text` | Tooltip text |
| `tooltip.padding.x` | size | `6` | Space left and right of the text |
| `tooltip.padding.y` | size | `6` | Space above and below |
| `tooltip.radius` | size | `4` | Corner radius |
| `tooltip.width` | size | `320` | The widest a tooltip gets; longer text wraps |
| `scrollbar.thumb` | colour | `@global.border` | The scroll bar of any scrolling area |
| `scrollbar.thumb.hover` | colour | `@global.text.dim` | Under the pointer, or dragged |
| `scrollbar.width` | size | `4` | Its thickness |
| `icon.gap` | size | `6` | Space between an icon and its text |
| `disabled.opacity` | number | `0.4` | How visible a disabled widget is: `0` invisible, `1` not faded |

---

## 8. Reading the log

When the editor loads a theme it writes, in one block, everything worth knowing about the
file. Each line gives the **line number** in the file:

```
WARN  themes/ocean.json: 2 problems:
WARN    line 9: button.fil — button has no property fil; it is ignored
WARN    line 14: toolbar.buttonStop.fill — expected a colour like "#5C9EF0", got "red"; using the default
INFO  themes/ocean.json: 1 note:
INFO    line 6: global.button.radius sets the value it has by default; it can be removed
```

**Problems** are mistakes: the property they name keeps its default. **Notes** are things that
change nothing but are worth tidying.

| What you wrote | What happens |
|---|---|
| A file that is not JSON, or not one `{ ... }` object | The whole file is skipped for the next in line (§2), with a warning. While editing with `-PthemeWatch`, the theme on screen stays instead. |
| A name that is not a property (`button.fil`) | Ignored, warned |
| A value of the wrong type (`"6"` for a size, `6` for a colour) | That property keeps its default, warned |
| A colour that does not parse (`"red"`, `"#12345"`) | That property keeps its default, warned |
| A reference to nothing (`@dangr`) | That property keeps its default, warned |
| A reference loop | Every property in the loop keeps its default, warned |
| A rule that reaches no widget (a name changed, a typo, a global-only entry) | Ignored, warned |
| A key written twice | Only the last counts, noted |
| A variable nothing refers to | Noted |
| A rule that changes nothing (the widget already looks that way) | Noted: it can be removed |
| A `global` entry set to its default | Noted: it can be removed (except palette colours: writing them out says what the theme's colours are) |
| No `format` | Read as format 1, noted |
| A property left out | Its default, silently. This is how a theme sets only what it changes. |

A wrong theme never stops the editor from starting.

---

## 9. Writing a theme that survives engine updates

The engine will gain widgets and panels after your theme is written. To have them fit in
without touching the theme:

1. **Base look in the palette.** Every new widget's defaults point at the palette, so it
   wears your colours automatically.
2. **Kinds of widget in `global`**: `button.radius`, `field.fill`. New buttons follow.
3. **A panel's look as panel-and-kind rules**: `inspector.field.fill` also dresses fields an
   update adds to the inspector.
4. **One widget's rule only for an exception**: Stop in red, Quit in a warning colour.

A theme written widget by widget works today but leaves every new widget at the default
look. Names given in code are part of the contract: when one changes, the release notes say
so, and the log points at every rule that no longer reaches anything.

---

## 10. The editor's addresses today

The panels and widgets of the editor as it stands, with the address a rule uses. The
editor's panels are still being designed: this list will grow, and the log always says when
a rule reaches nothing.

**Panels:** `menu`, `toolbar`, `hierarchy`, `assets`, `viewport`, `console`, `output`,
`inspector`, `status`.

| Where | Widget | Kind | Rule address | Example rule |
|---|---|---|---|---|
| Menu bar title | File, Edit, View | `menu` | `menu.menuBarMain.menuFile` | `"menu.menuBarMain.menuFile.text"` |
| File menu | Save scene, Quit | `menu` | `menuFile.menuItemSaveScene`, `menuFile.menuItemQuit` | `"menuFile.menuItemQuit.text"` |
| Edit menu | Undo, Redo | `menu` | `menuEdit.menuItemUndo`, `menuEdit.menuItemRedo` | |
| View menu | Hierarchy … Output, Undo my changes to this layout, Factory theme and layout | `menu` | `menuView.menuItemHierarchy`, `…Assets`, `…Inspector`, `…Console`, `…Output`, `menuView.menuItemRestoreLayout`, `menuView.menuItemFactoryLook` | |
| View › Theme, View › Layout | The submenus' items, and the lists in them | `menu` | `menuView.menuTheme`, `menuView.menuLayout`; inside, `menuTheme.menuItemThemesYours`, `…Installed` (the headings) and `menuTheme.menuItemTheme1`, `…2`, … in list order; the same under `menuLayout` | |
| Dock tabs' context menu | Close | `menu` | `menuDock.menuItemClose` | |
| Toolbar | Select, Move, Rotate, Scale | `toggle` | `toolbar.toggleSelect`, `…toggleMove`, `…toggleRotate`, `…toggleScale` | `"toolbar.toggle.radius": 2` |
| Toolbar | Play, Pause | `toggle` | `toolbar.togglePlay`, `toolbar.togglePause` | `"toolbar.togglePlay.fill.selected": "#2E7D32"` |
| Toolbar | Stop | `button` | `toolbar.buttonStop` | `"toolbar.buttonStop.text": "#E57373"` |
| Viewport | The scene | `surface` | `viewport.surfaceScene` | `"viewport.surfaceScene.border.focused": "#FFFFFF"` |
| Status bar | Editing, Playing, Paused | `label` | `status.labelEditing`, `…labelPlaying`, `…labelPaused` | `"status.labelPlaying.text": "@global.accent"` |
| Status bar | FPS and its number, Entities and its number, the project | `label` | `status.labelFps`, `status.labelFpsValue`, `status.labelEntities`, `status.labelEntitiesValue`, `status.labelProject` | `"status.label.text": "@global.text.dim"` |
| Status bar | Two separators | `separator` | `status.separatorState`, `status.separatorFps` | |

The menus' rules use the menu's name, not the panel: a menu opens above everything, outside
any panel. To dress every item of every menu, use `global` (`"menu.text"`); every item of
one menu, `menuFile.menu.text`; the titles on the bar, `menu.menu.text` (panel `menu`, kind
`menu`).

---

## 11. A complete example

```json
{
  "name": "Ember",
  "description": "Warm dark theme: charcoal panels, orange accent, red Stop.",
  "format": 1,

  "global": {
    "accent":         "#F28C28",
    "text":           "#EDE6DF",
    "text.dim":       "#9A8F85",
    "surface":        "#211C19",
    "surface.raised": "#2F2824",
    "surface.hover":  "#40362F",
    "background":     "#161210",
    "border":         "#5A4E45",

    "panel.fill":     "#1E1916",
    "panel.border":   "#2F2824",
    "button.radius":  3,
    "tabs.radius":    3,
    "field.selection": "#F28C2855",
    "tree.fill.selected": "#F28C2855"
  },

  "danger": "#D9483B",

  "toolbar.toggle.radius":            3,
  "toolbar.togglePlay.fill.selected": "#2E7D32",
  "toolbar.buttonStop.text":          "@danger",
  "menuFile.menuItemQuit.text":       "@danger",
  "status.label.text":                "@global.text.dim"
}
```

Notice that `field.selection` and `tree.fill.selected` are set: their defaults are fixed,
translucent blues rather than palette references, so a theme with a different accent sets
them to match.
