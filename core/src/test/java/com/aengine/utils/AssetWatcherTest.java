package com.aengine.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The path a re-baked texture is reloaded from. */
class AssetWatcherTest {

    @Test
    void theBakedPathKeepsTheFileNamesCase() {
        // KNOWN_ISSUES #17
        assertEquals("assets://baked/textures/Box.atex", AssetWatcher.bakedTexturePath("Box.png"));
        assertEquals("assets://baked/textures/HeroIdle.atex", AssetWatcher.bakedTexturePath("HeroIdle.JPG"));
        assertEquals("assets://baked/textures/a.b.atex", AssetWatcher.bakedTexturePath("a.b.jpeg"));
    }
}
