# Building and Running

Every command the project's build offers, in one place. Run them from the repository's root.
On Windows, write `.\gradlew.bat` where this page writes `./gradlew`.

The build needs a JDK 25. Gradle finds it through its toolchain; `gradlew.bat` on Windows
starts from `JAVA_HOME` (see the README's troubleshooting section).

---

## 1. Running the editor

| Command | What it does |
|---|---|
| `./gradlew :editor:run` | Starts the editor, in 3D, on the fallback project (`~/AeternumSandbox`) |
| `./gradlew :editor:run --args="--2d"` | Starts it in 2D, which loads the project's `level_01.scene` |
| `./gradlew :editor:run --args="--3d"` | Starts it in 3D, explicitly |
| `./gradlew :editor:run --args="/path/to/project --2d"` | Opens a given project |

### Development options

Add any of these to `./gradlew :editor:run`. They exist for development only; an installed
editor has none of them.

| Option | What it does |
|---|---|
| `-PtestShell` | Uses `editor/test-shell/` as the user's directory, for test themes and layouts. Nothing the editor writes there touches your real settings, and the user's arrangements are not written at all |
| `-PthemeFile=<name>` | Uses `themes/<name>.json` for this run, ignoring what View › Theme chose |
| `-PthemeWatch` | Applies the theme in use each time its file is saved |
| `-PlayoutFile=<name>` | Uses `layouts/<name>.json` for this run, ignoring what View › Layout chose |
| `-Plocale=<tag>` | Shows the editor in that language, e.g. `-Plocale=pt-BR`, instead of the system's |
| `-PlocaleCheck` | Writes the texts left to translate to `editor/build/locale-check/<tag>.json` |
| `-PwindowSize=<w>x<h>` | Opens the window at that size instead of maximized, e.g. `-PwindowSize=1280x720` |

Examples:

```
./gradlew :editor:run -PtestShell -PthemeFile=dark -PthemeWatch
./gradlew :editor:run -PtestShell -PwindowSize=1024x600 --args="--2d"
./gradlew :editor:run -Plocale=pt-BR -PlocaleCheck
```

`editor/test-shell/` is a local folder, not in the repository: create it, with `themes/` and
`layouts/` inside, the first time.

### Keys worth remembering in the running editor

| Key | What it does |
|---|---|
| **Ctrl+Shift+F12** | Back to the factory theme and layout, whatever the files did |
| Ctrl+R | Reads the theme file in use again |
| F5 | Play / Stop |
| Ctrl+S | Saves the scene |
| Ctrl+Z, Ctrl+Y | Undo, redo |

---

## 2. Tests

| Command | What it does |
|---|---|
| `./gradlew test` | Every module's tests: `core`, `ui` and `editor`. None needs a window |
| `./gradlew :ui:test` | One module's tests only |
| `./gradlew :ui:test --tests '*AegisMenuTest'` | One test class |

The results, test by test, are in `<module>/build/reports/tests/test/index.html`.

---

## 3. Packaging

The editor ships as a native application with a Java runtime of its own: whoever runs it does
not need Java installed. `jpackage` builds for the system it runs on, so a Windows package is
built on Windows and a Linux one on Linux.

| Command | What it builds | Where |
|---|---|---|
| `./gradlew :editor:packageApp` | The application folder: launcher, runtime, jars, `gui/` and `lang/` | `editor/build/package/AEngine/` |
| `./gradlew :editor:packageTarGz` | That folder as a `.tar.gz` (Linux) | `editor/build/distributions/AEngine-<version>-linux-x86_64.tar.gz` |
| `./gradlew :editor:packageAppImage` | A single-file AppImage (Linux) | `editor/build/distributions/AEngine-<version>-x86_64.AppImage` |

- **Running the folder:** `editor/build/package/AEngine/bin/AEngine` (on Windows,
  `AEngine.exe`).
- **The `.tar.gz`:** unpack it anywhere and run `AEngine/bin/AEngine`.
- **The AppImage** needs `appimagetool`: install it (on Arch and CachyOS, from the AUR), or
  point to it with `-PappImageTool=/path/to/appimagetool`. Without it, the build stops and says
  so. Make the AppImage executable (`chmod +x`) and run it.

What the package holds and how it is built is in `editor/packaging.gradle`. Not done yet: an
installer for Windows (`.exe` or `.msi`, which needs WiX on the machine that builds it), an AUR
package, and the engine's own icon, which comes with the designer's work.

---

## 4. Other tasks

| Command | What it does |
|---|---|
| `./gradlew build` | Compiles, checks and tests everything |
| `./gradlew clean` | Deletes every module's `build/` folder |
| `./gradlew javadoc` | Generates one API documentation site for all modules, in `build/docs/javadoc/` |
| `./gradlew tasks --all` | Lists every task the build offers |
