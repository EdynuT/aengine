package com.aengine.editor.gui;

import com.aengine.aegis.AegisLayoutFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The theme and layout selector's files: the lists, editor.json and the arrangements. */
class ShellFilesTest {

    @TempDir Path temp;
    private Path user, install;

    @BeforeEach
    void pointAtTemporaryDirectories() throws Exception {
        user = temp.resolve("user");
        install = temp.resolve("install");
        Files.createDirectories(install.resolve("gui"));
        System.setProperty("aengine.userdata", user.toString());
        System.setProperty("aengine.home", install.toString());
    }

    @AfterEach
    void putThemBack() {
        System.clearProperty("aengine.userdata");
        System.clearProperty("aengine.home");
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static final String LAYOUT = """
        { "name": "%s", "format": 1, "screens": { "editor": { "root": { "type": "panel", "panel": "viewport" } } } }
        """;

    @Test
    void theListsHoldTheUsersThenTheInstallationsThenTheFactorys() throws Exception {
        write(user.resolve("themes/dark.json"), "{ \"name\": \"My dark\", \"format\": 1 }");
        write(install.resolve("gui/themes/dark.json"), "{ \"name\": \"Dark\", \"format\": 1 }");
        write(install.resolve("gui/themes/light.json"), "not json at all");

        List<ShellFiles.Listed> themes = ShellFiles.listThemes();
        assertEquals(4, themes.size());
        assertEquals(new ShellFiles.Listed(ShellFiles.Choice.yours("dark"), "My dark"), themes.get(0));
        assertEquals(new ShellFiles.Listed(ShellFiles.Choice.installed("dark"), "Dark"), themes.get(1),
            "a copy of the same name does not hide the installed one");
        assertEquals(new ShellFiles.Listed(ShellFiles.Choice.installed("light"), "light"), themes.get(2),
            "a file that cannot be read is listed by its file's name");
        assertEquals(ShellFiles.Choice.FACTORY, themes.get(3).choice());
    }

    @Test
    void theChoicesAreKeptInEditorJsonWithTheOtherSettings() throws Exception {
        write(user.resolve("editor.json"), "{ \"locale\": \"pt-BR\" }");
        ShellFiles.saveChoices(ShellFiles.Choice.installed("dark"), ShellFiles.Choice.FACTORY);

        ShellFiles.Choice[] read = ShellFiles.readChoices();
        assertEquals(ShellFiles.Choice.installed("dark"), read[0]);
        assertEquals(ShellFiles.Choice.FACTORY, read[1]);
        JsonObject top = JsonParser.parseString(Files.readString(user.resolve("editor.json"))).getAsJsonObject();
        assertEquals("pt-BR", top.get("locale").getAsString(), "a setting the selector does not own is kept");
    }

    @Test
    void aChoiceWrittenWrongIsPassedOverAndTheOtherStillCounts() throws Exception {
        write(user.resolve("editor.json"),
            "{ \"theme\": { \"origin\": 3, \"file\": \"dark\" }, \"layout\": { \"origin\": \"yours\", \"file\": \"wide\" } }");
        ShellFiles.Choice[] read = ShellFiles.readChoices();
        assertNull(read[0], "an origin must be a string");
        assertEquals(ShellFiles.Choice.yours("wide"), read[1]);
    }

    @Test
    void anArrangementGoesToItsOwnFileAndComesFirstForItsLayout() throws Exception {
        write(install.resolve("gui/layouts/default.json"), LAYOUT.formatted("Default"));
        ShellFiles.Choice installed = ShellFiles.Choice.installed("default");
        String layoutBefore = Files.readString(install.resolve("gui/layouts/default.json"));

        JsonObject screen = JsonParser.parseString("{ \"root\": { \"type\": \"panel\", \"panel\": \"viewport\" } }").getAsJsonObject();
        ShellFiles.saveArrangement(installed, "Default", "editor", screen);

        Path arrangement = user.resolve("arrangements/installed/default.json");
        assertTrue(Files.exists(arrangement));
        assertEquals(layoutBefore, Files.readString(install.resolve("gui/layouts/default.json")), "the layout itself is never written");

        AegisLayoutFile[] inLine = ShellFiles.loadLayouts(installed);
        assertEquals(3, inLine.length);
        assertTrue(ShellFiles.isArrangement(inLine[0].source()));
        assertEquals("Default", inLine[1].name());
        assertEquals(FactoryLayout.SOURCE, inLine[2].source());

        ShellFiles.forgetArrangement(installed, "editor");
        assertFalse(Files.exists(arrangement), "with no screen left, the arrangement goes");
        assertEquals(2, ShellFiles.loadLayouts(installed).length);
    }

    @Test
    void withNothingChosenTheDefaultLayoutIsTheUsersThenTheInstallationsThenTheFactorys() throws Exception {
        assertEquals(ShellFiles.Choice.FACTORY, ShellFiles.defaultLayout("default"));
        write(install.resolve("gui/layouts/default.json"), LAYOUT.formatted("Default"));
        assertEquals(ShellFiles.Choice.installed("default"), ShellFiles.defaultLayout("default"));
        write(user.resolve("layouts/default.json"), LAYOUT.formatted("Mine"));
        assertEquals(ShellFiles.Choice.yours("default"), ShellFiles.defaultLayout("default"));
    }

    @Test
    void aChosenFileThatIsGoneGivesTheFactory() {
        assertEquals("factory", ShellFiles.loadTheme(ShellFiles.Choice.installed("missing")).source());
        AegisLayoutFile[] inLine = ShellFiles.loadLayouts(ShellFiles.Choice.yours("missing"));
        assertEquals(1, inLine.length);
        assertEquals(FactoryLayout.SOURCE, inLine[0].source());
    }
}
