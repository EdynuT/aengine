package com.aengine.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The project manifest the wizard writes. */
class ProjectWizardTest {

    @Test
    void aNameWithQuotesAndBackslashesStillMakesValidJson() {
        // KNOWN_ISSUES #18
        String name = "My \"Big\" Game \\ v2 <ç>";
        JsonObject manifest = JsonParser.parseString(ProjectWizard.manifestJson(name)).getAsJsonObject();
        assertEquals(name, manifest.get("projectName").getAsString());
        assertEquals("OPENGL", manifest.get("targetAPI").getAsString());
    }
}
