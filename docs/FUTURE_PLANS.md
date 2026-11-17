# Future Plans

Ideas the engine may take up later, with what is already known about each. Nothing here is
scheduled: an idea moves into a plan of its own — like `UI_FRAMEWORK_ARCHITECTURE.md` — when
work on it starts, and its entry here then points there.

Each entry says what the idea is, what has been decided or learned so far, and what is still
open.

| # | Idea | Status |
|---|---|---|
| 1 | Launcher | How it works decided; not started |
| 2 | Telemetry, with the user's consent | Idea |
| 3 | A backend server for the launcher and telemetry | Idea |

---

## 1. Launcher

**What:** a program of its own that runs before the editor, where the user picks the project
to work on — and, later, whatever else is worth having there: creating a project, checking
for engine updates, news.

**Decided — how it works.** Two executables in the same project, as games with a launcher
ship them:

1. The user opens the **launcher** and picks a project.
2. The launcher starts the **editor** with that project as its argument, and closes.
3. When the user closes the editor, it asks: **back to the launcher, or quit.** Back starts
   the launcher again.

Each program runs alone, never both at once, and they talk only through the command line: the
launcher says which project, nothing more. The editor keeps working without the launcher —
started by hand, from a terminal or a shortcut, with a project path.

**What is known:**

- **The editor already takes the project as its argument**: `Main` reads the first argument
  that is not an option as the project's path (`--2d` and `--3d` are options), and falls back
  to `~/AeternumSandbox` with a warning when there is none. The launcher's half of the
  conversation is therefore already understood by the editor.
- **It is a client, built like the rest of the engine** — in Java, its window drawn with
  Aegis, sharing the editor's theme and font. Spring Boot is a server framework and does not
  belong in it, and an embedded web view would bring back the browser runtime the engine
  removed in Phase 0 of the UI migration.
- **In the same repository**, as a Gradle module of its own (`:launcher`) beside `:core`, `:ui`
  and `:editor`, using `:ui` for its window. It needs neither the renderer's 3D side nor the
  editor.
- `ProjectWizard` already creates projects, and is what a "new project" button would call.
- Updating, when it comes, needs somewhere to download from — the server in §3, or simply
  release files on the repository's host, which needs no server at all.

**Open:**

- **How "back to the launcher" starts it:** the editor starts the launcher's executable as
  it exits. It has to find it — beside its own executable in the installation, the natural
  place — and the launcher must not need arguments to start.
- **Which projects the launcher lists:** recent ones remembered in the engine's user data
  (engine-wide, like the editor's other settings), plus "open a folder".
- **What happens to unsaved work** when the editor is closed: the question to the user comes
  after the usual "save changes?", never instead of it.
- How an update is checked and verified — a signature, so a download cannot be swapped.

## 2. Telemetry, with the user's consent

**What:** the engine sending anonymous data on how it runs — frame rate, graphics card and
driver, crashes and their logs — so problems on hardware the developers do not own can be
found and fixed.

**What is known:**

- **Off until the user says yes**, asked once, changeable in settings at any time, and saying
  no changes nothing else about the engine.
- **What is sent is shown before it is sent**, and nothing that identifies the user or their
  project goes in it — no names, paths or scene content. Paths in logs need masking first.
- It builds on the Logger step planned in `UI_FRAMEWORK_ARCHITECTURE.md` §10: the log file
  per run is what a crash report would attach, and the popup that points at the log on an
  error is where the user would be offered to send it.
- `HardwareCapabilities` already gathers the hardware facts at start.

**Open:** what exactly is collected; where it is stored and for how long; the privacy text
the user reads; whether only crash reports are sent, or performance data too.

## 3. A backend server

**What:** a service on the internet that the launcher and telemetry talk to — receiving
telemetry and crash reports, and, if ever needed, serving updates or user accounts. Spring
Boot fits here: it is made for exactly this kind of server.

**What is known — the rule that matters:** **the engine and the launcher never connect to a
database.** They send requests to the server's API (`POST /telemetry`, say); only the server
holds the database's address and password, in environment variables on the machine it runs
on, never in the repository. Anything in the engine's code is public — the repository is
open — so a password written there is everyone's password: anyone could read, delete or fill
the database, and run up the bill of whoever hosts it.

The server also has to:

- **Distrust everything it receives.** The engine is open source, so anyone can send it
  anything, shaped however they like: validate every field, limit sizes, and limit how often
  one sender may post.
- **Keep secrets out of the repository** even when its own code is public: configuration
  comes from the environment, and a file holding keys is never committed.

**The licence:** the engine is GPL v3. The licence covers the software, not servers or
credentials, so it neither protects a leaked password nor requires one to be published. A
server that is only run, not distributed, does not have to publish its code under the GPL;
the AGPL would require it, which is a choice to make when the server exists.

**Open:** where it is hosted and who pays for it; whether its code lives in this repository
or a separate one; whether it is needed at all before there are users to send data.

---

*Origin:* items 1–3 came from a conversation about Spring Boot. Its conclusions were kept;
where it suggested a Spring Boot or web-view launcher, this file records the engine's own
direction instead.
