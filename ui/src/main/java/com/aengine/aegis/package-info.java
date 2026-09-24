/**
 * Aegis — AEngine's in-house UI framework.
 *
 * <p>The architecture — a four-layer stack of draw list, text, layout and retained widget
 * tree, under a zero-allocation frame-loop budget — and the phased migration away from Dear
 * ImGui are described in {@code docs/UI_FRAMEWORK_ARCHITECTURE.md}.</p>
 *
 * <p>This package may depend only on the engine's graphics API interfaces, its window and
 * input layer, and its logging and filesystem utilities. Reaching into the ECS, the
 * editor, physics or the OpenGL backend package breaks the boundary that keeps this
 * framework extractable.</p>
 */
package com.aengine.aegis;
