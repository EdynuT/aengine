package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-11: the modal dialog. */
class AegisDialogTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int outside, dialog, titleBar, noteField, okButton, cancelButton;

    /** A button outside; a dialog with a field and OK / Cancel in a row, OK the default. */
    private void build() {
        outside = ae.button(shell.root, "outsideButton", "Outside");
        dialog = ae.dialog("noteDialog", "Leave a note");
        titleBar = layout.firstChild(dialog);
        noteField = ae.textField(dialog, "noteField");
        int buttons = layout.row(dialog);
        layout.setGap(buttons, 8.0f);
        okButton = ae.button(buttons, "okButton", "OK");
        cancelButton = ae.button(buttons, "cancelButton", "Cancel");
        ae.setDefaultButton(dialog, okButton);
        shell.frame();
        shell.tab();
        assertEquals(outside, shell.tree.focused());
    }

    @Test
    void openItIsCentredAndFocusGoesInside() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        assertTrue(ae.isDialogOpen(dialog));
        assertEquals(400.0f, layout.x(dialog) + layout.width(dialog) * 0.5f, 1.0f, "centred across");
        assertEquals(noteField, shell.tree.focused());
    }

    @Test
    void nothingOutsideCanBePointedAt() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        shell.click(outside);
        assertFalse(ae.wasActivated(outside));
        assertTrue(ae.isDialogOpen(dialog), "a press outside a dialog does not close it");
        assertEquals(noteField, shell.tree.focused(), "nor does it take focus");
    }

    @Test
    void tabGoesAroundInsideOnly() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        shell.tab();
        assertEquals(okButton, shell.tree.focused());
        shell.tab();
        assertEquals(cancelButton, shell.tree.focused());
        shell.tab();
        assertEquals(noteField, shell.tree.focused(), "around, never out to the window");
    }

    @Test
    void enterInTheFieldActivatesTheDefaultButton() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        shell.type("hello");
        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(okButton));
        ae.closeDialog(dialog);
        shell.frame();
        assertFalse(ae.isDialogOpen(dialog));
        assertFalse(ae.wasCancelled(dialog), "closed from code: not cancelled");
        assertEquals(outside, shell.tree.focused(), "focus goes back where it was");
    }

    @Test
    void escCancelsAndGivesFocusBack() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        shell.tab();                                      // on OK: Esc is not a field's here
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isDialogOpen(dialog));
        assertTrue(ae.wasCancelled(dialog));
        assertEquals(outside, shell.tree.focused());
        shell.frame();
        assertFalse(ae.wasCancelled(dialog), "for one frame");
    }

    @Test
    void theCloseMarkCancelsAndTheTitleBarDrags() {
        build();
        ae.openDialog(dialog);
        shell.frame();
        float x = layout.x(dialog), y = layout.y(dialog);

        shell.pointerAt(layout.x(titleBar) + 5.0f, layout.y(titleBar) + 5.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.pointerAt(layout.x(titleBar) + 45.0f, layout.y(titleBar) + 25.0f);
        shell.frame();
        shell.release();
        shell.frame();
        assertEquals(x + 40.0f, layout.x(dialog));
        assertEquals(y + 20.0f, layout.y(dialog));
        assertEquals(noteField, shell.tree.focused(), "dragging it keeps focus inside");

        shell.pointerAt(layout.x(titleBar) + layout.width(titleBar) - 4.0f, layout.y(titleBar) + 5.0f);
        shell.frame();
        shell.press();
        shell.frame();
        assertFalse(ae.isDialogOpen(dialog));
        assertTrue(ae.wasCancelled(dialog));
        shell.release();
        shell.frame();
    }

    @Test
    void f10DoesNotReachTheMenusBehindADialog() {
        build();
        int bar = ae.menuBar(shell.root, "mainMenu");
        int file = ae.menu(bar, "fileMenu", "File");
        ae.menuItem(file, "newItem", "New");
        shell.frame();
        ae.openDialog(dialog);
        shell.frame();
        shell.key(Keys.F10);
        assertFalse(ae.isMenuOpen(file));
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void aThemeDressesADialogThatIsNotOpen() throws Exception {
        build();
        ae.applyTheme(AegisTheme.parse(new StringReader("""
            { "name": "Test", "format": 1, "noteDialog.button.fill": "#FF0000" }
            """), "test"), shell.root);
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.0f, 1.0f }, ae.styleOf(okButton).buttonFill, 0.001f);
    }
}
