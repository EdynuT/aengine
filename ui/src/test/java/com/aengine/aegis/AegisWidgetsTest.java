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
    void theScrollBarIsDraggedAndKeepsFocusWhereItWas() {
        int pane = scrollingPane();
        int first = layout.firstChild(pane);
        shell.tab();
        assertEquals(first, shell.tree.focused());

        // Take hold of the bar: in the right padding, near the top, where it rests unscrolled.
        float barX = layout.x(pane) + layout.width(pane) - 4.0f;
        float top  = layout.y(pane) + layout.padding(pane);
        shell.pointerAt(barX, top + 2.0f);
        shell.frame();
        shell.press();
        shell.frame();
        assertEquals(first, shell.tree.focused(), "a scroll bar is not something focus goes to");

        shell.pointerAt(barX + 300.0f, top + 2.0f + 1000.0f);   // far past the end, and off the pane
        shell.frame();
        assertEquals(layout.scrollRange(pane), layout.scrollOffset(pane), "it follows the pointer to the end");

        shell.pointerAt(barX, top - 500.0f);
        shell.frame();
        assertEquals(0.0f, layout.scrollOffset(pane));

        shell.release();
        shell.frame();
        shell.pointerAt(barX, top + 60.0f);
        shell.frame();
        assertEquals(0.0f, layout.scrollOffset(pane), "let go, it no longer follows");
        for (int child = first; child != AegisLayout.NONE; child = layout.nextSibling(child)) {
            assertFalse(ae.wasActivated(child));
        }
    }

    @Test
    void aPressBesideTheBarScrollsOneView() {
        int pane = scrollingPane();
        shell.frame();
        float barX = layout.x(pane) + layout.width(pane) - 4.0f;
        float view = layout.height(pane) - layout.padding(pane) * 2.0f;

        shell.pointerAt(barX, layout.y(pane) + layout.height(pane) - layout.padding(pane) - 2.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertEquals(view - shell.aegis.lineHeight(), layout.scrollOffset(pane), 0.001f);
    }

    @Test
    void pageKeysScrollThePaneAroundTheFocusedWidget() {
        int pane = scrollingPane();
        shell.tab();
        float page = layout.height(pane) - layout.padding(pane) * 2.0f - shell.aegis.lineHeight();

        shell.key(Keys.PAGE_DOWN);
        assertEquals(page, layout.scrollOffset(pane), 0.001f);
        shell.key(Keys.PAGE_UP);
        assertEquals(0.0f, layout.scrollOffset(pane));
    }

    @Test
    void aFocusedPaneWithNothingFocusableInsideScrollsByKeys() {
        int pane = layout.column(shell.root);
        layout.setSize(pane, 300.0f, 100.0f);
        layout.setScrollable(pane, true);
        shell.tree.setFocusable(pane, true);
        for (int i = 0; i < 20; i++) ae.label(pane, "line" + i, "Line " + i);

        shell.tab();
        assertEquals(pane, shell.tree.focused());
        shell.key(Keys.DOWN);
        assertEquals(shell.aegis.lineHeight(), layout.scrollOffset(pane), 0.001f);
        shell.key(Keys.END);
        assertEquals(layout.scrollRange(pane), layout.scrollOffset(pane));
        shell.key(Keys.HOME);
        assertEquals(0.0f, layout.scrollOffset(pane));
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void aSpinnerStepsByKeysWithinItsRange() {
        int count = ae.spinner(shell.root, "countSpinner", 0.0f, 10.0f, 5.0f, 1.0f);
        assertEquals("5", ae.text(count).toString());
        shell.tab();

        shell.key(Keys.UP);
        assertEquals(6.0f, ae.spinnerValue(count));
        assertTrue(ae.wasChanged(count));
        assertEquals("6", ae.text(count).toString());

        shell.key(Keys.UP, Keys.MOD_SHIFT);
        assertEquals(10.0f, ae.spinnerValue(count), "ten steps, stopped at the most it can be");
        shell.key(Keys.UP);
        assertFalse(ae.wasChanged(count));
        shell.key(Keys.DOWN);
        assertEquals(9.0f, ae.spinnerValue(count));
    }

    @Test
    void aSpinnerTakesATypedNumberOnEnterAndDropsWhatIsNotOne() {
        int size = ae.spinner(shell.root, "sizeSpinner", -100.0f, 100.0f, 1.0f, 0.25f);
        assertEquals("1.00", ae.text(size).toString(), "as many decimals as the step has");
        shell.tab();                       // arriving by Tab selects the number

        shell.type("12,5x");               // the comma is a decimal point; the x never gets in
        assertEquals("12,5", ae.text(size).toString());
        assertEquals(1.0f, ae.spinnerValue(size), "typing is not the value yet");
        assertFalse(ae.wasChanged(size));

        shell.key(Keys.ENTER);
        assertEquals(12.5f, ae.spinnerValue(size));
        assertEquals("12.50", ae.text(size).toString());
        assertTrue(ae.wasChanged(size));
        assertTrue(ae.wasActivated(size));

        shell.type("--");
        shell.key(Keys.ENTER);
        assertEquals(12.5f, ae.spinnerValue(size), "not a number: the last value is put back");
        assertEquals("12.50", ae.text(size).toString());

        shell.type("5000");
        shell.key(Keys.ENTER);
        assertEquals(100.0f, ae.spinnerValue(size), "out of range: brought to the nearest end");
    }

    @Test
    void aSpinnerTakesWhatWasTypedWhenFocusLeavesAndGivesItUpOnEsc() {
        int first  = ae.spinner(shell.root, "firstSpinner", 0.0f, 100.0f, 1.0f, 1.0f);
        int second = ae.spinner(shell.root, "secondSpinner", 0.0f, 100.0f, 2.0f, 1.0f);
        shell.tab();
        shell.type("42");
        shell.tab();
        assertEquals(42.0f, ae.spinnerValue(first));
        assertTrue(ae.wasChanged(first));

        shell.type("77");
        shell.key(Keys.ESCAPE);
        assertEquals(2.0f, ae.spinnerValue(second));
        assertEquals("2", ae.text(second).toString());
        assertEquals(AegisLayout.NONE, shell.tree.focused());
    }

    @Test
    void aSpinnersArrowsStepItAndSteppingByTenthsStaysExact() {
        int opacity = ae.spinner(shell.root, "opacitySpinner", 0.0f, 1.0f, 0.0f, 0.1f);
        shell.frame();
        float x = layout.x(opacity) + layout.width(opacity) - 5.0f;

        for (int i = 0; i < 10; i++) {
            shell.pointerAt(x, layout.y(opacity) + 3.0f);          // the upper arrow
            shell.frame();
            shell.press();
            shell.frame();
            assertTrue(ae.wasChanged(opacity));
            shell.release();
            shell.frame();
        }
        assertEquals(1.0f, ae.spinnerValue(opacity), "ten tenths are one, not 1.0000001");
        assertEquals("1.0", ae.text(opacity).toString());

        shell.pointerAt(x, layout.y(opacity) + layout.height(opacity) - 3.0f);   // the lower arrow
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertEquals(0.9f, ae.spinnerValue(opacity));
        assertEquals(opacity, shell.tree.focused());
    }

    @Test
    void everyKindDrawsWithoutAWindow() {
        ae.spinner(shell.root, "aSpinner", 0.0f, 10.0f, 5.0f, 0.5f);
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
