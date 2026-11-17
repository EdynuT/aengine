package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Themes: global values, scoped rules, and the path a widget is dressed by. */
class AegisThemeTest {

    private final TestShell    shell   = new TestShell();
    private final AegisWidgets ae      = shell.ae;
    private final AegisScreens screens = shell.aegis.screens();

    @Test
    void theFactoryThemeIsTheStyleCodeStartsWith() {
        AegisStyle factory = AegisTheme.factory().globalStyle();
        AegisStyle fresh   = new AegisStyle();
        assertArrayEquals(fresh.buttonFill, factory.buttonFill, 0.005f);   // a hex colour is 1/255 fine
        assertEquals(fresh.buttonPaddingX, factory.buttonPaddingX);
        assertEquals(fresh.scrollbarWidth, factory.scrollbarWidth);
    }

    @Test
    void aScopedRuleDressesOnlyTheWidgetItNames() throws Exception {
        int viewport = screens.columnPanel("viewport");
        int play = ae.button(viewport, "buttonPlay", "Play");
        int stop = ae.button(viewport, "buttonStop", "Stop");
        shell.layout.attach(viewport, shell.root);

        ae.applyTheme(theme("""
            { "name": "Test", "format": 1,
              "global": { "button.padding.x": 20 },
              "danger": "#FF0000",
              "viewport.buttonStop.fill": "@danger"
            }
            """), shell.root);

        assertSame(ae.style(), ae.styleOf(play), "no rule reaches it: it shares the global style");
        assertNotSame(ae.style(), ae.styleOf(stop));
        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.0f, 1.0f }, ae.styleOf(stop).buttonFill, 0.001f);
        assertEquals(20.0f, ae.styleOf(stop).buttonPaddingX, "its own style still starts from the global values");
    }

    @Test
    void aWidgetKeepsItsDressingWhenALayoutMovesIt() throws Exception {
        int viewport = screens.columnPanel("viewport");
        int toolbar  = screens.rowPanel("toolbar");
        int stop = ae.button(viewport, "buttonStop", "Stop");
        shell.layout.attach(viewport, shell.root);
        shell.layout.attach(toolbar, shell.root);
        shell.layout.attach(stop, toolbar);

        ae.applyTheme(theme("""
            { "name": "Test", "format": 1,
              "viewport.buttonStop.fill": "#FF0000",
              "toolbar.button.fill": "#00FF00"
            }
            """), shell.root);

        assertArrayEquals(new float[] { 1.0f, 0.0f, 0.0f, 1.0f }, ae.styleOf(stop).buttonFill, 0.001f);
    }

    @Test
    void aThemeWithMorePaddingMakesAButtonLarger() throws Exception {
        int play = ae.button(shell.root, "buttonPlay", "Play");
        float before = shell.layout.requestedWidth(play);

        ae.applyTheme(theme("""
            { "name": "Test", "format": 1, "global": { "button.padding.x": 30 } }
            """), shell.root);

        assertEquals(before + (30.0f - new AegisStyle().buttonPaddingX) * 2.0f, shell.layout.requestedWidth(play));
    }

    @Test
    void whatIsNotJsonIsUnusable() {
        assertThrows(AegisTheme.Unusable.class, () -> theme("{ \"name\": "));
    }

    private static AegisTheme theme(String text) throws AegisTheme.Unusable {
        return AegisTheme.parse(new StringReader(text), "test");
    }
}
