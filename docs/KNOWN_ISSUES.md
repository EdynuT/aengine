# Known Issues

Defects found while documenting the engine, recorded here so they are not lost while the
affected modules are still unfinished. A fixed one keeps its entry, marked as fixed, with the
test that now guards it, until the release it ships in.

Each entry names the code by class and method; line numbers drift, so they are given only
as a starting point. Effects marked *from reading* were worked out from the code and have
not been reproduced in the running editor.

| # | Severity | Area | Summary | Status |
|---|---|---|---|---|
| 1 | High | Editor / serialization | Leaving Play mode strips every sprite from the scene | **Fixed** |
| 2 | High (latent) | Physics | A large collider can freeze the physics thread and the editor | **Fixed** |
| 3 | Medium | Physics | OBB colliders read rotation as radians; everything else uses degrees | **Fixed** |
| 4 | Medium | Physics | The broad phase can miss spheres and rotated OBBs | **Fixed** |
| 5 | Low | Physics | Two bodies at exactly the same position never separate | **Fixed** |
| 6 | Low (latent) | ECS | Destroying an entity twice makes two new entities share an ID | **Fixed** |
| 7 | Low | Audio | Audio properties apply once; `isPlaying` never resets | **Fixed** |
| 8 | Low (latent) | Audio | `AudioDevice` does not guard against repeated `init`/`cleanup` | **Fixed** |
| 9 | Low (latent) | Physics | `PhysicsThread` cannot be restarted after `cleanup` | **Fixed** |
| 10 | Medium | Graphics / UI | The UI pass turns depth testing back on in 2D mode | **Fixed** |
| 11 | Low (latent) | Graphics | `Renderer2D.flush` redraws quads already drawn | **Fixed** |
| 12 | Low | Graphics | A texture still loading shows whatever was on its slot | **Fixed** |
| 13 | Low (latent) | Graphics | `OpenGLDynamicMesh.upload` binds its index buffer outside its VAO | **Fixed** |
| 14 | Low | Graphics | `OpenGLTexture` can leak decoded pixels | **Fixed** |
| 15 | Low | Graphics | The CPU name is read with `wmic`, missing on recent Windows | **Fixed** |
| 16 | Low | Graphics | Per-frame allocations on paths documented as allocation-free | **Fixed** |
| 17 | Medium | Assets | Texture hot reload misses files whose names have capitals | **Fixed** |
| 18 | Low | Tooling | `ProjectWizard` writes the project name into JSON unescaped | **Fixed** |
| 19 | Low (latent) | UI | A clip pushed past 32 levels unbalances the clip stack | **Fixed** |
| 20 | Low | UI | On Windows, AltGr shortcuts can fire Ctrl shortcuts in text fields | **Fixed** |

---

## 1. Leaving Play mode strips every sprite from the scene

**Where:** `SceneLoader.load(Registry, JsonObject)` in
`core/src/main/java/com/aengine/ecs/serialization/SceneLoader.java` (inline branch, around
line 115); `SceneSerializer` in `editor/src/main/java/com/aengine/editor/SceneSerializer.java`;
the F5 handler in `editor/src/main/java/com/aengine/Main.java` (around line 344).

**What happens:** entering Play (F5) snapshots the scene with
`SceneSerializer.serializeScene`, which writes each entity's `SpriteComponent`. Leaving Play
clears the registry and reloads the snapshot with `SceneLoader.load`. The inline branch of
the loader reads `TransformComponent`, `ScriptComponent`, `ColliderComponent` and
`RigidbodyComponent`, but not `SpriteComponent`. The block commented `// Read SpriteComponent`
actually reads the script component.

**Effect (from reading):** after one Play → Stop cycle every entity comes back without a
sprite and disappears from view. Because `SceneSerializer` only writes entities that have
both a transform and a sprite, the next Play or Save drops those entities for good. Scenes
saved to disk by the editor hit the same gap when loaded.

**Fix direction:** read `SpriteComponent` (texture path and colour) in the inline branch,
mirroring `PrefabLoader`, and fix the misleading comment.

**Fixed:** the inline branch reads the sprite with the same `PrefabLoader.readSprite` a prefab
uses. Reproduced first by `SceneRoundTripTest` (editor), which writes a scene, reads it back
twice as Play and Stop do, and fails without the fix.

## 2. A large collider can freeze the physics thread and the editor

**Where:** `SpatialHashGrid.getOrCreateCell` in
`core/src/main/java/com/aengine/physics/SpatialHashGrid.java` (the `while (true)` probe loop,
around line 308).

**What happens:** the cell table has a fixed 4096 slots and is cleared each step. An entity
is inserted into every 2×2×2 cell its box overlaps. Once 4096 distinct cells are in use in
one step, the next new cell finds no empty slot and the probe loop never ends.

**Effect (from reading):** a floor collider of 200×200 units already covers about 10 000
cells. The physics thread hangs inside the step while holding the sync lock, so the render
thread blocks on the same lock and the editor freezes as soon as Play starts. A warning is
logged at 75 % occupancy, before the hang.

**Fix direction:** bound the probe loop and handle a full table (grow it, or fall back to
testing the oversized entity against everything), and consider keeping very large static
colliders out of the grid.

**Fixed:** the grid is hierarchical. Level 0 keeps cells of 2 units; each level above has
cells four times wider, and a box goes to the lowest level whose cell fits its largest side,
so it covers at most eight cells whatever its size (the 200×200 floor takes eight slots on
the level of 512-unit cells). Boxes on the same level pair inside shared cells; a box also
looks up the coarser levels in use, so a far-away wall is not a candidate for everything.
The cell table doubles past 70 % occupancy, so the probe always ends on a free slot; it
allocates only when a scene first reaches a new size. Coordinates are clamped to ±10⁹ units,
so infinite or NaN bounds cannot loop either. Covered by `SpatialHashGridTest` (core), run
only against the fixed grid.

## 3. OBB colliders read rotation as radians; everything else uses degrees

**Where:** `NarrowPhase.buildOBBAxes` in
`core/src/main/java/com/aengine/physics/NarrowPhase.java` (around line 314).

**What happens:** `TransformComponent.rotation` is in degrees: `Renderer2D` and `Renderer3D`
convert it with `Math.toRadians`. `buildOBBAxes` passes the same values straight to
`Matrix3f.rotationXYZ`, which expects radians.

**Effect:** a box rotated 90° is drawn at 90° but collides as if rotated 90 radians, about
116.6°. Only `ColliderType.OBB` is affected; AABBs ignore rotation and spheres do not use it.

**Fix direction:** convert with `Math.toRadians` in `buildOBBAxes`.

**Fixed:** `buildOBBAxes` converts each angle with `Math.toRadians`, in the XYZ order the
renderers use. `NarrowPhaseTest` (core) turns a plank 90° and checks it collides along the
axis it is drawn on, and no longer along the old one.

## 4. The broad phase can miss spheres and rotated OBBs

**Where:** the grid insertion in `PhysicsSystem.update`
(`core/src/main/java/com/aengine/ecs/systems/PhysicsSystem.java`, around line 177).

**What happens:** every collider is inserted as the box `centre ± size`, treating `size` as
half-extents for all shapes. For a sphere only `size.x` is the radius; `size.y` and `size.z`
keep whatever they hold (0.5 by default). For a rotated OBB the corners reach beyond the
unrotated half-extents.

**Effect (from reading):** the grid box can be smaller than the real shape, so two shapes
that touch may never share a cell and are never passed to the narrow phase.

**Fix direction:** insert a sphere as `centre ± radius` on all axes, and a rotated OBB with
its world-space bounding box.

**Fixed:** `NarrowPhase.boundsHalfExtents` gives the grid the box that encloses the shape:
`size` for an AABB, the radius on every axis for a sphere, and for an OBB the reach of the
rotated box along each world axis (`Σ |R[axis][k]| · size[k]`). It lives beside the OBB tests
so both read the rotation the same way. Covered by `NarrowPhaseTest`.

## 5. Two bodies at exactly the same position never separate

**Where:** `NarrowPhase.testAABB_AABB` and the "sphere centre inside the box" branch of
`NarrowPhase.testAABB_Sphere`.

**What happens:** the contact normal comes from `Math.signum` of the centre-to-centre
distance. When the centres coincide on the chosen axis, `signum(0)` is 0 and the normal is a
zero vector.

**Effect (from reading):** positional correction and impulse both scale by the normal, so
nothing pushes the bodies apart and they stay inside each other. `testSphere_Sphere` already
handles this case by picking +Y.

**Fix direction:** pick a fixed fallback axis when the distance is zero, as the
sphere-sphere test does.

**Fixed:** the box-box test and the "sphere inside the box" branch take a zero distance as
positive, so coinciding centres separate along + of the chosen axis. Covered by
`NarrowPhaseTest`, for both orders of the box-sphere pair.

## 6. Destroying an entity twice makes two new entities share an ID

**Where:** `Registry.destroyEntity` in `core/src/main/java/com/aengine/ecs/Registry.java`.

**What happens:** the ID is appended to the free list without checking whether it is already
free.

**Effect:** after a double destroy, two later `createEntity()` calls return the same ID and
the two "entities" share components. The editor deletes one entity at a time and resets its
pending-delete marker, so this is not expected to trigger today.

**Fix direction:** ignore IDs that are not active, e.g. by checking `activeEntities` or
keeping a per-ID alive flag.

**Fixed:** `destroyEntity` returns at once for an ID that is not in `activeEntities`, so a
repeated destroy (or one of an ID never created) queues nothing. Covered by `RegistryTest`
(core).

## 7. Audio properties apply once; `isPlaying` never resets

**Where:** `AudioSystem.update` in `core/src/main/java/com/aengine/ecs/systems/AudioSystem.java`.

**What happens:** `gain`, `pitch`, `loop`, `referenceDistance` and `maxDistance` are sent to
OpenAL only when the source is created. `isPlaying` is set to `true` when `playOnAwake`
starts the sound and nothing sets it back.

**Effect:** changing those fields later, from the inspector or a script, has no audible
effect, and `isPlaying` stays `true` after a one-shot sound ends.

**Fix direction:** push changed properties to the source each frame (or on change), and read
`AL_SOURCE_STATE` to keep `isPlaying` accurate.

**Fixed:** `AudioSystem` sends the five properties to each source every frame, beside its
position, and reads `AL_SOURCE_STATE` back into `isPlaying`, which now turns false when a
one-shot sound ends. Five OpenAL calls per source per frame. No automated test: it needs an
OpenAL device.

## 8. `AudioDevice` does not guard against repeated `init`/`cleanup`

**Where:** `core/src/main/java/com/aengine/audio/AudioDevice.java`.

**What happens:** `init()` opens a new device and context without checking for existing
ones; `cleanup()` does not reset the handles after closing them. Missing OpenAL 1.0 support
is logged but initialisation continues.

**Effect:** a second `init()` leaks the first device; a second `cleanup()` destroys freed
handles again. Neither happens in the current startup and shutdown path.

**Fix direction:** make both calls idempotent by checking and clearing the handles.

**Fixed:** `init` returns when the device is already open, and closes the device again if
the context cannot be created; `cleanup` resets both handles, so a second call does nothing
and a later `init` opens the device afresh. No automated test: it needs an OpenAL device.

## 9. `PhysicsThread` cannot be restarted after `cleanup`

**Where:** `PhysicsThread.init` in `core/src/main/java/com/aengine/physics/PhysicsThread.java`.

**What happens:** `PhysicsThread` extends `Thread`, and a Java thread can be started only
once. After `cleanup()` resets the running flag, `init()` calls `start()` again and throws
`IllegalThreadStateException`.

**Effect:** stopping and restarting physics requires a new `PhysicsThread`. The editor does
not do this today; it pauses with `setPaused` instead.

**Fix direction:** document it (already done in the Javadoc) or wrap the loop in a
restartable owner instead of extending `Thread`.

**Fixed:** `PhysicsThread` owns its Java thread instead of extending `Thread`: `init` starts
a new one each time, `cleanup` stops it, and `isRunning` tells which. A loop runs only while
it is still the current worker, so a thread that outlives the 2-second wait in `cleanup`
stops at its next check even if `init` has started another. Covered by `PhysicsThreadTest`
(core).

## 10. The UI pass turns depth testing back on in 2D mode

**Where:** `AegisRenderer.end()` in `ui/src/main/java/com/aengine/aegis/AegisRenderer.java`;
the render-mode setup in `editor/src/main/java/com/aengine/Main.java` (around line 277).

**What happens:** in 2D mode the editor disables depth testing once, at startup, so quads
overlap in submission order. The Aegis pass disables depth for itself and, when done,
restores it by enabling it unconditionally, not by returning to what it found.

**Effect (from reading):** from the second frame on, 2D quads are depth-tested. With
`GL_LESS`, of two overlapping quads at the same Z the one drawn *first* now wins, the
reverse of the first frame.

**Fix direction:** have the scene pass set the depth state it needs every frame, or have
the UI pass restore the previous state instead of a fixed one.

**Fixed:** the editor's `onRender` sets the depth test its render mode needs at the start of
every frame — off in 2D, on in 3D — instead of once at startup, and `AegisRenderer` documents
the state it leaves. Checked by running the editor with `--2d`; no automated test, since it
needs a GL context.

## 11. `Renderer2D.flush` redraws quads already drawn

**Where:** `Renderer2D.flush` in `core/src/main/java/com/aengine/graphics/Renderer2D.java`.

**What happens:** `flush` draws the batch but does not empty it. `Renderer3D.drawPlane` and
`drawCube` call it before each mesh, so every call redraws all quads queued since
`beginScene`, and `endScene` draws them once more.

**Effect:** wasted draws that grow with the number of meshes; translucent quads would blend
more than once when depth testing is off. The editor never mixes quads and meshes in one
frame today (2D mode draws only quads, 3D mode only meshes), so nothing shows yet.

**Fix direction:** empty the batch after a flush (as `nextBatch` does), keeping the texture
slots consistent.

**Fixed:** `flush` empties the batch (vertices, indices and texture slots) after drawing, so
`nextBatch` is now just a flush. No automated test: it needs a GL context.

## 12. A texture still loading shows whatever was on its slot

**Where:** `OpenGLTexture.bind` in
`core/src/main/java/com/aengine/graphics/opengl/OpenGLTexture.java`.

**What happens:** until the background decode finishes, `bind` returns without binding
anything, and the texture reports a size of 1×1.

**Effect (from reading):** for the first frames after a texture is requested, or after a
hot reload, a sprite samples whatever texture was last bound to that unit, possibly another
sprite's image, instead of a neutral placeholder.

**Fix direction:** bind a 1×1 white (or checkerboard) placeholder while loading or failed.

**Fixed:** until a texture's first upload, `bind` binds a shared 1×1 white texture, so a
sprite shows its own colour; during a hot reload the previous image stays bound instead.
Checked by running the editor in 2D; no automated test, since it needs a GL context.

## 13. `OpenGLDynamicMesh.upload` binds its index buffer outside its VAO

**Where:** `OpenGLDynamicMesh.upload` in
`core/src/main/java/com/aengine/graphics/opengl/OpenGLDynamicMesh.java`.

**What happens:** the index-buffer binding is part of vertex array state. `upload` binds its
EBO without first binding its own VAO, so the binding lands on whichever VAO is current.

**Effect:** if another VAO is bound when the UI uploads, that VAO's index buffer is silently
replaced by the UI's. The scene renderers unbind their VAOs after each draw, so no VAO
should be bound when the UI uploads, and this is not expected to show today.

**Fix direction:** bind the mesh's VAO before binding the EBO in `upload` (and unbind after).

**Fixed:** `upload` binds the mesh's own VAO around the EBO update and unbinds it after.
Checked by running the editor (the interface draws whole); no automated test.

## 14. `OpenGLTexture` can leak decoded pixels

**Where:** `OpenGLTexture.cleanup`, `OpenGLTexture.reload`.

**What happens:** `cleanup` frees pixels that have already been decoded, but a decode still
running on a worker thread finishes afterwards and stores new native memory that nothing
frees. `reload` on a texture that is decoded but not yet uploaded starts a second decode
that overwrites the first buffer without freeing it.

**Effect:** native memory leaks in those two timing windows. Small in practice.

**Fix direction:** have the worker check a "disposed" flag before publishing, and free any
pending pixels at the start of `reload`.

**Fixed:** pending pixels change hands under the texture's lock. A decode that finishes
after `cleanup` sees the `disposed` flag and frees its own pixels; `reload` and a new decode
free pixels that never reached the GPU before replacing them. No automated test: the timing
windows need the GL thread and the decode pool.

## 15. The CPU name is read with `wmic`, missing on recent Windows

**Where:** `HardwareCapabilities.resolveCpuSpecifications` in
`core/src/main/java/com/aengine/graphics/HardwareCapabilities.java`.

**What happens:** on Windows the CPU name comes from running `wmic cpu get name`. Microsoft
has deprecated `wmic` and recent Windows 11 releases no longer install it by default.

**Effect:** the startup log shows "Unknown CPU" and a warning on those machines. Nothing
else depends on the value.

**Fix direction:** read the `ProcessorNameString` registry value, or query through
PowerShell's `Get-CimInstance Win32_Processor`.

**Fixed:** on Windows the name is read with `reg query` from the registry value
`ProcessorNameString` (`reg.exe` ships with every Windows), falling back to the
`PROCESSOR_IDENTIFIER` variable. The line parsing is covered by `HardwareCapabilitiesTest`
(core); the query itself was not run here, as this machine is Linux.

## 16. Per-frame allocations on paths documented as allocation-free

**Where:** `core/src/main/java/com/aengine/graphics/Renderer2D.java` and `Renderer3D.java`.

**What happens:** the comments promise zero allocation, but `Renderer2D.drawQuad` creates a
`Vector4f` per vertex (and `drawQuad(pos, size, texture)` one more for the tint),
`Renderer2D.beginScene` builds a uniform-name string per texture slot every frame, and
`Renderer3D.drawPlane`/`drawCube` each create a `Matrix4f`. `drawEntityQuad`, the path the
editor uses for sprites, is allocation-free.

**Effect:** garbage-collector pressure that grows with draw calls; no incorrect output.

**Fix direction:** reuse scratch objects as `drawEntityQuad` does, and precompute the
`u_Textures[i]` names once in `init`.

**Fixed:** `drawQuad` reuses the scratch vector `drawEntityQuad` uses and a shared white
tint, `beginScene` reads the uniform names built once in `init`, and `drawPlane`/`drawCube`
reuse one scratch matrix.

## 17. Texture hot reload misses files whose names have capitals

**Where:** `AssetWatcher.start` in `core/src/main/java/com/aengine/utils/AssetWatcher.java`.

**What happens:** when a source image changes, the watcher re-bakes it and then asks
`AssetManager.hotReloadTexture` to reload `assets://baked/textures/<name>.atex`. It builds
that name from the file name *lowercased*, but `AssetBaker` keeps the original case when it
writes the baked file, and the texture cache is keyed by the path the scene used.

**Effect (from reading):** saving `Box.png` re-bakes `Box.atex`, but the reload is requested
for `box.atex`, misses the cache, and logs "Hot-Reload skipped". The new image appears only
after a restart. File names in lower case work. On Windows the file system ignores case, but
the cache lookup still does not.

**Fix direction:** lowercase only for the extension check and build the virtual path from
the original name.

**Fixed:** the watcher lowercases only for the extension test and builds the virtual path
from the name as it is (`AssetWatcher.bakedTexturePath`). Covered by `AssetWatcherTest`
(core).

## 18. `ProjectWizard` writes the project name into JSON unescaped

**Where:** `ProjectWizard.generateProjectManifest` in
`core/src/main/java/com/aengine/utils/ProjectWizard.java`.

**What happens:** the manifest is assembled by string concatenation, with the project name
inserted as is.

**Effect:** a name containing `"` or `\` produces an invalid `config/project.json`. The
editor currently creates only a fixed name, so this does not trigger today.

**Fix direction:** write the manifest with Gson, which the engine already uses.

**Fixed:** the manifest is built as a Gson `JsonObject` and written in UTF-8. Covered by
`ProjectWizardTest` (core), with a name holding quotes, a backslash and an accent.

## 19. A clip pushed past 32 levels unbalances the clip stack

**Where:** `AegisDrawList.pushClipRect` / `popClipRect` in
`ui/src/main/java/com/aengine/aegis/AegisDrawList.java`.

**What happens:** clips nest up to 32 levels. A push beyond that is ignored, but the matching
pop is not: it removes a level that the ignored push never added.

**Effect:** after a 33rd nested clip is pushed and popped, everything drawn until the next
pop uses the clip of one level further out, so it can draw outside the panel it belongs to.
Nothing nests that deep today.

**Fix direction:** count ignored pushes and let the matching pops consume them first.

**Fixed:** `AegisDrawList` counts the ignored pushes (reset by `begin`) and their pops
consume them first. Covered by `AegisDrawListTest` (ui).

## 20. On Windows, AltGr shortcuts can fire Ctrl shortcuts in text fields

**Where:** `AegisWidgets.editKey` in `ui/src/main/java/com/aengine/aegis/AegisWidgets.java`.

**What happens:** Windows reports AltGr as Control plus Alt, and GLFW passes both on as
modifier bits. The text-field shortcuts check only for Control, so a key pressed with AltGr
also matches Ctrl+A, C, V, X, Y or Z.

**Effect (from reading):** on a Brazilian ABNT2 keyboard, AltGr+C types "₢" and would also
copy the selection. Characters typed with AltGr on other letters of that set behave the same
way on other layouts. Linux is not affected.

**Fix direction:** treat a key as a Ctrl shortcut only when Alt is not also held.

**Fixed:** `Keys.isCtrlShortcut(mods)` — Control held, Alt not — is what the text fields and
the editor's own keys (Ctrl+S, Ctrl+R, Ctrl+Z, Ctrl+Y) test now, so AltGr+R typing ® no longer
reloads the theme either. Guarded by `AegisWidgetsTest.altGrIsNotCtrlInATextField`.
