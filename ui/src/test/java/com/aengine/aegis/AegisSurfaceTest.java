package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4a: the surface the viewport shows the scene on, and panels and screens drawn as backgrounds. */
class AegisSurfaceTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisTree    tree   = shell.tree;
    private final AegisWidgets ae     = shell.ae;

    @Test
    void aSurfaceIsNamedByItsKind() {
        assertThrows(IllegalArgumentException.class, () -> ae.surface(shell.root, "sceneSurface"));
        ae.surface(shell.root, "surfaceScene");
    }

    @Test
    void aPressFocusesItAndAnythingElseTakesFocusAway() {
        int surface = ae.surface(shell.root, "surfaceScene");
        layout.setSize(surface, 400.0f, 300.0f);
        int button = ae.button(shell.root, "buttonSave", "Save");

        shell.click(surface);
        assertEquals(surface, tree.focused(), "pressed, it takes the keys");

        shell.click(button);
        assertEquals(button, tree.focused(), "so the keys are the button's, and no longer the scene's");
    }

    @Test
    void tabReachesItAndLeavesIt() {
        int button  = ae.button(shell.root, "buttonSave", "Save");
        int surface = ae.surface(shell.root, "surfaceScene");
        layout.setSize(surface, 400.0f, 300.0f);
        shell.frame();

        shell.tab();
        assertEquals(button, tree.focused());
        shell.tab();
        assertEquals(surface, tree.focused());
        shell.tab();
        assertEquals(button, tree.focused(), "Tab is never kept by what the surface shows");
    }

    @Test
    void itTakesNoRoomOfItsOwnButGrows() {
        int surface = ae.surface(shell.root, "surfaceScene");
        shell.frame();
        assertEquals(0.0f, layout.height(surface));

        layout.setGrow(surface, 1.0f);
        shell.frame();
        assertEquals(600.0f, layout.height(surface), "grown, it fills what its parent leaves");
    }

    @Test
    void itDrawsWithATextureWithoutOneAndFocused() {
        int surface = ae.surface(shell.root, "surfaceScene");
        layout.setGrow(surface, 1.0f);
        shell.frame();
        assertDoesNotThrow(shell::draw, "nothing to show: its fill");

        ae.setSurfaceTexture(surface, 7, true);
        tree.focus(surface);
        assertDoesNotThrow(shell::draw, "a framebuffer's texture, with its focused edge");
    }

    @Test
    void panelsAndTheScreenAreDrawnButAreNotWidgets() throws Exception {
        AegisScreens screens = shell.aegis.screens();
        int viewport = screens.columnPanel("viewport");
        int surface = ae.surface(viewport, "surfaceScene");
        int root = screens.build("editor", AegisLayoutFile.parse(new StringReader("""
            { "format": 1, "screens": { "editor": { "root": { "type": "column", "children": [
              { "type": "panel", "panel": "viewport", "grow": 1 } ] } } } }
            """), "test"));

        assertFalse(ae.isWidget(viewport), "a panel is drawn as its background, but takes nothing");
        assertFalse(ae.isWidget(root));
        assertTrue(ae.isWidget(surface));

        layout.solve(root, 0.0f, 0.0f, 800.0f, 600.0f);
        shell.aegis.begin(800, 600);
        assertDoesNotThrow(() -> ae.draw(root));
        shell.aegis.end();
    }

    @Test
    void anOpenOverlayIsSaidToBeOpen() {
        int popup = layout.column(AegisLayout.NONE);
        assertFalse(ae.isAnyOverlayOpen());
        ae.showPopup(popup, 10.0f, 10.0f);
        assertTrue(ae.isAnyOverlayOpen(), "Esc is the overlay's while it is open");
        ae.closeOverlay(popup);
        assertFalse(ae.isAnyOverlayOpen());
    }
}
