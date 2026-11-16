package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locales — step 3h: texts by address, English from code where a file says nothing, the strict reading. */
class AegisLocaleTest {

    private final TestShell    shell   = new TestShell();
    private final AegisLayout  layout  = shell.layout;
    private final AegisWidgets ae      = shell.ae;
    private final AegisScreens screens = shell.aegis.screens();

    private final int viewport = screens.columnPanel("viewport");
    private final int stop     = ae.button(viewport, "buttonStop", "Stop");
    private final int play     = ae.button(viewport, "buttonPlay", "Play");

    @Test
    void aTextIsLookedUpByTheWidgetsAddress() throws Exception {
        ae.applyLocale(locale("""
            { "name": "Português", "format": 1, "viewport.buttonStop": "Parar" }
            """));

        assertEquals("Parar", ae.shownText(stop));
        assertEquals("Play", ae.shownText(play), "what the file does not say stays in English");
    }

    @Test
    void englishPutsEveryTextBack() throws Exception {
        ae.applyLocale(locale("""
            { "format": 1, "viewport.buttonStop": "Parar" }
            """));
        ae.applyLocale(AegisLocale.english());

        assertEquals("Stop", ae.shownText(stop));
    }

    @Test
    void aWidgetIsSizedAgainForItsNewText() throws Exception {
        float english = layout.requestedWidth(stop);
        ae.applyLocale(locale("""
            { "format": 1, "viewport.buttonStop": "Parar a cena inteira" }
            """));

        assertTrue(layout.requestedWidth(stop) > english);
    }

    @Test
    void everyKindOfTextHasItsKey() throws Exception {
        int field = ae.textField(viewport, "fieldName");
        ae.setPlaceholder(field, "Entity name");
        ae.setTooltip(stop, "Stops the scene.");

        int tabs = ae.tabbedPane(viewport, "tabsScene");
        ae.addTab(tabs, "tabEntities", "Entities", layout.column(AegisLayout.NONE));

        int combo = ae.comboBox(viewport, "comboShading");
        ae.addComboItem(combo, "lit", "Lit");
        ae.addComboItem(combo, "unlit", "Unlit");

        int bar = ae.menuBar(viewport, "menuBarMain");
        int file = ae.menu(bar, "menuFile", "File");
        int save = ae.menuItem(file, "menuItemSave", "Save", "Ctrl+S");

        int dialog = ae.dialog("dialogAbout", "About");
        int ok = ae.button(dialog, "buttonOk", "OK");

        AegisLocale locale = locale("""
            {
              "format": 1,
              "viewport.fieldName.placeholder":  "Nome da entidade",
              "viewport.buttonStop.tooltip":     "Para a cena.",
              "viewport.tabsScene.tabEntities":  "Entidades",
              "viewport.comboShading.lit":       "Iluminado",
              "viewport.menuBarMain.menuFile":   "Arquivo",
              "menuFile.menuItemSave":           "Salvar",
              "dialogAbout.title":               "Sobre",
              "dialogAbout.buttonOk":            "Certo"
            }
            """);
        ae.applyLocale(locale);

        assertEquals("Nome da entidade", ae.shownText(field));
        assertEquals("Para a cena.", ae.shownTooltip(stop));
        assertEquals("Entidades", ae.tabTitle(tabs, 0));
        assertEquals("Iluminado", ae.comboItem(combo, 0));
        assertEquals("Unlit", ae.comboItem(combo, 1));
        assertEquals("lit", ae.comboItemName(combo, 0), "code tells items by name, in any language");
        assertEquals("Arquivo", ae.shownText(layout.firstChild(bar)), "the title, on the bar");
        assertEquals("Salvar", ae.shownText(save));
        assertEquals("Sobre", ae.shownText(layout.firstChild(dialog)));
        assertEquals("Certo", ae.shownText(ok));
        assertTrue(locale.unused().isEmpty(), "every key reached a text: " + locale.unused());
    }

    @Test
    void aPanelInTabsIsTitledByItsTitleKey() throws Exception {
        screens.setPanelTitle("viewport", "Viewport");
        screens.build("editor", AegisLayoutFile.parse(new StringReader("""
            { "format": 1, "screens": { "editor": { "root": { "type": "tabs", "panels": ["viewport"] } } } }
            """), "test"));
        int pane = layout.parent(viewport);

        ae.applyLocale(locale("""
            { "format": 1, "viewport.title": "Cena" }
            """));

        assertEquals("Cena", ae.tabTitle(pane, 0));
    }

    @Test
    void textCodeSetsIsShownAsGiven() throws Exception {
        int count = ae.label(viewport, "labelCount", "No entities");
        ae.setLabel(count, "3 entities");

        ae.applyLocale(locale("""
            { "format": 1, "viewport.labelCount": "Nenhuma entidade" }
            """));

        assertEquals("3 entities", ae.shownText(count));
    }

    @Test
    void missingListsWhatIsLeftToTranslateWithItsEnglish() throws Exception {
        AegisLocale locale = locale("""
            { "format": 1, "viewport.buttonStop": "Parar", "viewport.buttonRecord": "Gravar" }
            """);
        ae.applyLocale(locale);

        assertEquals(Map.of("viewport.buttonPlay", "Play"), locale.missing());
        assertEquals(Set.of("viewport.buttonRecord"), locale.unused(), "a widget renamed or removed");
        assertTrue(AegisLocale.english().missing().isEmpty());
    }

    @Test
    void aRegionFallsBackOnItsLanguageThenOnEnglish() throws Exception {
        AegisLocale pt = locale("""
            { "format": 1, "viewport.buttonStop": "Parar", "viewport.buttonPlay": "Jogar" }
            """);
        AegisLocale ptBr = locale("""
            { "format": 1, "viewport.buttonPlay": "Executar" }
            """).withFallback(pt);
        int pause = ae.button(viewport, "buttonPause", "Pause");

        ae.applyLocale(ptBr);

        assertEquals("Executar", ae.shownText(play), "the region's own text first");
        assertEquals("Parar", ae.shownText(stop), "then the language's");
        assertEquals("Pause", ae.shownText(pause), "then English");
        assertTrue(pt.unused().isEmpty(), "a text the region overrides is still one the editor shows");
    }

    @Test
    void aTextThatIsNotAStringOrAKeyThatIsNotAnAddressIsIgnored() throws Exception {
        AegisLocale locale = locale("""
            { "format": 1, "viewport.buttonStop": 12, "viewport.button-play": "Executar",
              "viewport.buttonPlay": "Executar" }
            """);
        ae.applyLocale(locale);

        assertEquals("Stop", ae.shownText(stop));
        assertEquals("Executar", ae.shownText(play));
        assertEquals(1, locale.size());
    }

    @Test
    void aTranslationThatDropsAPlaceholderIsShownAsWritten() throws Exception {
        int count = ae.label(viewport, "labelCount", "{count} entities");
        ae.applyLocale(locale("""
            { "format": 1, "viewport.labelCount": "entidades" }
            """));

        assertEquals("entidades", ae.shownText(count));
    }

    @Test
    void aFileThatIsNotAnObjectCannotBeUsed() {
        assertThrows(AegisLocale.Unusable.class, () -> locale("[ \"Parar\" ]"));
        assertThrows(AegisLocale.Unusable.class, () -> locale("{ \"viewport.buttonStop\": "));
    }

    @Test
    void tabsAndComboItemsAreNamedStrictly() {
        int tabs = ae.tabbedPane(viewport, "tabsScene");
        ae.addTab(tabs, "tabEntities", "Entities", layout.column(AegisLayout.NONE));
        assertThrows(IllegalArgumentException.class, () -> ae.addTab(tabs, "entities", "Entities", layout.column(AegisLayout.NONE)));
        assertThrows(IllegalArgumentException.class, () -> ae.addTab(tabs, "tabEntities", "Again", layout.column(AegisLayout.NONE)));

        int combo = ae.comboBox(viewport, "comboShading");
        ae.addComboItem(combo, "lit", "Lit");
        assertThrows(IllegalArgumentException.class, () -> ae.addComboItem(combo, "lit", "Lit again"));
        assertThrows(IllegalArgumentException.class, () -> ae.addComboItem(combo, "tooltip", "Tooltip"));
        assertThrows(IllegalArgumentException.class, () -> ae.addComboItem(combo, "flat_shaded", "Flat"));
    }

    private static AegisLocale locale(String json) throws AegisLocale.Unusable {
        return AegisLocale.parse(new StringReader(json), "test");
    }
}
