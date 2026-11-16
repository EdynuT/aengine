package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The widgets as the user works them: pointer, keyboard, wheel — with no window. */
class AegisWidgetsTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    @Test
    void aButtonIsActivatedByAClickAndByEnter() {
        int play = ae.button(shell.root, "playButton", "Play");

        shell.click(play);
        assertTrue(ae.wasActivated(play));
        assertFalse(ae.activatedByKeyboard());

        shell.frame();
        assertFalse(ae.wasActivated(play), "an activation lasts one frame");

        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(play), "the click focused it, so Enter reaches it");
        assertTrue(ae.activatedByKeyboard());
    }

    @Test
    void aPressDraggedOffAButtonDoesNotActivateIt() {
        int play = ae.button(shell.root, "playButton", "Play");
        shell.frame();
        shell.pointerOn(play);
        shell.press();
        shell.frame();
        shell.pointerAt(700.0f, 500.0f);
        shell.release();
        shell.frame();
        assertFalse(ae.wasActivated(play));
    }

    @Test
    void aCheckboxFlipsBySpaceAndReportsTheChange() {
        int snap = ae.checkbox(shell.root, "snapCheckbox", "Snap to grid");
        shell.tab();
        shell.key(Keys.SPACE);
        assertTrue(ae.isChecked(snap));
        assertTrue(ae.wasChanged(snap));

        ae.setChecked(snap, false);
        shell.frame();
        assertFalse(ae.wasChanged(snap), "a change from code is not reported");
    }

    @Test
    void tabStepsThroughWidgetsPassingLabelsAndSeparators() {
        int first = ae.button(shell.root, "firstButton", "First");
        ae.label(shell.root, "nameLabel", "Name");
        ae.separator(shell.root, "aSeparator");
        int second = ae.button(shell.root, "secondButton", "Second");

        shell.tab();
        assertEquals(first, shell.tree.focused());
        shell.tab();
        assertEquals(second, shell.tree.focused());
        shell.tab();
        assertEquals(first, shell.tree.focused(), "past the last it wraps");
        shell.key(Keys.TAB, Keys.MOD_SHIFT);
        assertEquals(second, shell.tree.focused());
    }

    @Test
    void aSliderMovesByKeysAndStaysInRange() {
        int slider = ae.slider(shell.root, "opacitySlider", 0.0f, 100.0f, 50.0f);
        shell.tab();

        shell.key(Keys.RIGHT);
        assertEquals(51.0f, ae.sliderValue(slider), 0.001f);
        assertTrue(ae.wasChanged(slider));

        shell.key(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(41.0f, ae.sliderValue(slider), 0.001f);

        shell.key(Keys.END);
        assertEquals(100.0f, ae.sliderValue(slider), 0.001f);
        shell.key(Keys.RIGHT);
        assertEquals(100.0f, ae.sliderValue(slider), 0.001f);
        assertFalse(ae.wasChanged(slider));
    }

    @Test
    void aTextFieldTakesTypingKeepsItsLimitAndUndoes() {
        int field = ae.textField(shell.root, "nameField", 8);
        shell.tab();

        shell.type("Player");
        assertEquals("Player", ae.text(field).toString());
        assertTrue(ae.wasChanged(field));

        shell.type("12345");
        assertEquals("Player12", ae.text(field).toString(), "characters past the limit are not inserted");

        shell.key(Keys.Z, Keys.MOD_CONTROL);
        assertEquals("", ae.text(field).toString(), "typing with no pause is one block");
        shell.key(Keys.Y, Keys.MOD_CONTROL);
        assertEquals("Player12", ae.text(field).toString());

        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(field), "Enter confirms a field");
    }

    @Test
    void aTextFieldRefusesControlCharacters() {
        int field = ae.textField(shell.root, "nameField");
        shell.tab();
        ae.character(0x1B);
        ae.character(0x202E);
        ae.character('a');
        shell.frame();
        assertEquals("a", ae.text(field).toString());
    }

    @Test
    void aLabelIsSizedToItsTextAndAgainWhenItChanges() {
        int label = ae.label(shell.root, "countLabel", "1");
        float narrow = layout.requestedWidth(label);
        ae.setLabel(label, "One hundred");
        assertTrue(layout.requestedWidth(label) > narrow);
    }

    @Test
    void aSizeSetByCodeSurvivesBeingFittedAgain() {
        int stop = ae.button(shell.root, "stopButton", "Stop");
        float fittedHeight = layout.requestedHeight(stop);
        layout.setWidth(stop, 140.0f);

        ae.applyTheme(AegisTheme.factory(), shell.root);

        assertEquals(140.0f, layout.requestedWidth(stop));
        assertEquals(fittedHeight, layout.requestedHeight(stop));
    }

    @Test
    void aNameAThemeCouldNotWriteIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ae.button(shell.root, "stop.button", "Stop"));
        assertEquals(AegisLayout.NONE, layout.firstChild(shell.root), "nothing half-built is left behind");
    }

    @Test
    void theWheelScrollsTheColumnUnderThePointer() {
        int pane = scrollingPane();
        shell.frame();
        assertTrue(layout.scrollRange(pane) > 0.0f);

        shell.pointerOn(pane);
        shell.frame();
        ae.scroll(-1.0f);
        shell.frame();
        assertEquals(shell.aegis.lineHeight() * 3.0f, layout.scrollOffset(pane), 0.001f);

        ae.scroll(100.0f);
        shell.frame();
        assertEquals(0.0f, layout.scrollOffset(pane), "it stops at the top");
    }

    @Test
    void focusScrollsTheWidgetIntoView() {
        int pane = scrollingPane();
        int last = layout.lastChild(pane);
        shell.frame();
        assertTrue(layout.y(last) > layout.y(pane) + layout.height(pane), "the last button starts out of sight");

        shell.key(Keys.TAB, Keys.MOD_SHIFT);   // from nothing, Shift+Tab goes to the last
        assertEquals(last, shell.tree.focused());
        float bottom = layout.y(pane) + layout.height(pane) - layout.padding(pane);
        assertTrue(layout.y(last) + layout.height(last) <= bottom, "and focus brought it in");
    }

    @Test
    void everyKindDrawsWithoutAWindow() {
        ae.button(shell.root, "aButton", "Button");
        ae.checkbox(shell.root, "aCheckbox", "Checkbox");
        ae.slider(shell.root, "aSlider", 0.0f, 10.0f, 5.0f);
        ae.label(shell.root, "aLabel", "Label");
        ae.separator(shell.root, "aSeparator");
        int field = ae.textField(shell.root, "aField");
        int box   = ae.textBox(shell.root, "aBox", 3);
        ae.setText(field, "text");
        ae.setText(box, "first line\nsecond line");
        scrollingPane();

        shell.frame();
        assertDoesNotThrow(shell::draw);
        shell.tab();
        assertDoesNotThrow(shell::draw);
    }

    /** A column 120 tall holding ten buttons: far more than fits. */
    private int scrollingPane() {
        int pane = layout.column(shell.root);
        layout.setSize(pane, 300.0f, 120.0f);
        layout.setPadding(pane, 8.0f);
        layout.setGap(pane, 4.0f);
        layout.setScrollable(pane, true);
        for (int i = 0; i < 10; i++) ae.button(pane, "button" + i, "Button " + i);
        return pane;
    }
}
