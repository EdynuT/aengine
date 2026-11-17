package com.aengine.aegis;

import com.aengine.graphics.RenderContext;
import com.aengine.graphics.TextureAPI;
import com.aengine.graphics.TextureFormat;
import com.aengine.utils.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_PackBegin;
import static org.lwjgl.stb.STBTruetype.stbtt_PackEnd;
import static org.lwjgl.stb.STBTruetype.stbtt_PackFontRanges;
import static org.lwjgl.stb.STBTruetype.stbtt_PackSetOversampling;

/**
 * The icons the interface draws, by name — step 4b.
 *
 * <p>An icon set is an <b>icon font</b> and a <b>map</b> from the engine's names for its icons
 * to the font's code points. The map is a JSON file:</p>
 *
 * <pre>{@code
 * {
 *   "name":        "Lucide",
 *   "description": "A provisional set until the designer's.",
 *   "format":      1,
 *   "font":        "lucide.ttf",
 *   "icons": {
 *     "play":       "U+E140",
 *     "toolMove":   "U+E125"
 *   }
 * }
 * }</pre>
 *
 * <p>Only the icons the map names are baked, into an atlas of their own that holds shapes,
 * not colours — as the text atlas does — so an icon is drawn in whatever colour the widget
 * showing it draws its text in, and a theme recolours every icon with the text. The names
 * are the engine's and stay; the font and its code points are the designer's to replace:
 * any icon font works — those that IcoMoon or Fontello make from a folder of SVGs come with
 * the code points to write here.</p>
 *
 * <p>The set ships with the engine, so a mistake in its map is a bug in the engine and is
 * thrown, not reported. An icon code asks for that the map does not name is said once in
 * the log and drawn as nothing, so an icon set that lacks one never stops the editor.</p>
 */
public final class AegisIcons {

    /** The version of what a map holds. Stays 1 until the engine is released. */
    public static final int FORMAT = 1;

    /** An icon no map names: drawn as nothing. */
    public static final int NONE = -1;

    private static final int STRIDE = 4;   // x0, y0, x1, y1 in the atlas

    private final String name;
    private final float pixelHeight;
    private final int atlasSize;
    private final TextureAPI atlas;
    private final Map<String, Integer> byName = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private final float[] boxes;

    /**
     * Reads an icon set from the classpath and bakes it.
     *
     * @param mapResource classpath path of the map, e.g. {@code /icons/icons.json}; its
     *                    {@code "font"} is found beside it
     * @param pixelHeight the size the icons are drawn at, in pixels
     * @param upload      whether to send the atlas to the GPU; {@code false} for a headless Aegis
     * @throws IllegalStateException for a map or font that cannot be read — a bug in the engine
     */
    AegisIcons(String mapResource, float pixelHeight, boolean upload) {
        this.pixelHeight = pixelHeight;
        JsonObject map = readMap(mapResource);
        this.name = text(map, "name", mapResource);
        Integer format = map.has("format") ? AegisFindings.wholeNumber(map.get("format")) : null;
        if (format == null || format < 1 || format > FORMAT) {
            throw new IllegalStateException(mapResource + ": \"format\" must be a whole number from 1 to " + FORMAT);
        }
        String fontFile = text(map, "font", null);
        if (fontFile == null) throw new IllegalStateException(mapResource + ": \"font\" names no file");
        JsonElement icons = map.get("icons");
        if (icons == null || !icons.isJsonObject()) {
            throw new IllegalStateException(mapResource + ": \"icons\" must be an object of names and code points");
        }

        List<Integer> codepoints = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : icons.getAsJsonObject().entrySet()) {
            if (!AegisLayout.isValidNodeName(e.getKey())) {
                throw new IllegalStateException(mapResource + ": \"" + e.getKey() + "\" is not an icon name: letters and digits, in camelCase");
            }
            codepoints.add(codepoint(mapResource, e.getKey(), e.getValue()));
            byName.put(e.getKey(), names.size());
            names.add(e.getKey());
        }
        this.boxes = new float[names.size() * STRIDE];

        // Square, a power of two, with room for every icon and a pixel between them.
        int cell = (int) Math.ceil(pixelHeight) + 2;
        int perRow = (int) Math.ceil(Math.sqrt(Math.max(1, names.size())));
        int size = 64;
        while (size < perRow * cell) size *= 2;
        this.atlasSize = size;

        String fontResource = mapResource.substring(0, mapResource.lastIndexOf('/') + 1) + fontFile;
        ByteBuffer ttf = readResource(fontResource);
        ByteBuffer bitmap = MemoryUtil.memCalloc(size * size);
        STBTTFontinfo info = STBTTFontinfo.malloc();
        STBTTPackContext packer = STBTTPackContext.malloc();
        STBTTPackRange.Buffer range = STBTTPackRange.calloc(1);
        STBTTPackedchar.Buffer chars = STBTTPackedchar.malloc(Math.max(1, names.size()));
        IntBuffer points = MemoryUtil.memAllocInt(Math.max(1, names.size()));
        try {
            if (!stbtt_InitFont(info, ttf)) throw new IllegalStateException("Not a font stb_truetype can read: " + fontResource);
            for (int i = 0; i < codepoints.size(); i++) {
                if (stbtt_FindGlyphIndex(info, codepoints.get(i)) == 0) {
                    throw new IllegalStateException(String.format("%s: icon \"%s\" is U+%04X, which %s has no glyph for",
                        mapResource, names.get(i), codepoints.get(i), fontFile));
                }
                points.put(codepoints.get(i));
            }
            points.flip();
            if (!names.isEmpty()) {
                range.get(0).font_size(pixelHeight).first_unicode_codepoint_in_range(0)
                     .array_of_unicode_codepoints(points).chardata_for_range(chars);
                range.get(0).num_chars(names.size());
                if (!stbtt_PackBegin(packer, bitmap, size, size, 0, 1, MemoryUtil.NULL)) {
                    throw new IllegalStateException("Could not start packing the icon atlas for " + fontResource);
                }
                stbtt_PackSetOversampling(packer, 1, 1);
                boolean packed = stbtt_PackFontRanges(packer, ttf, 0, range);
                stbtt_PackEnd(packer);
                if (!packed) throw new IllegalStateException("Icon atlas too small for " + names.size() + " icons in " + size + "x" + size);
                for (int i = 0; i < names.size(); i++) {
                    STBTTPackedchar c = chars.get(i);
                    boxes[i * STRIDE]     = c.x0();
                    boxes[i * STRIDE + 1] = c.y0();
                    boxes[i * STRIDE + 2] = c.x1();
                    boxes[i * STRIDE + 3] = c.y1();
                }
            }
            this.atlas = upload ? RenderContext.createTexture(size, size, TextureFormat.R8, bitmap) : null;
            Logger.info(Logger.System.UI, "Icons \"%s\" baked: %d icons at %.0fpx from %s, atlas %dx%d.",
                name, names.size(), pixelHeight, fontResource, size, size);
        } finally {
            info.free();
            packer.free();
            range.free();
            chars.free();
            MemoryUtil.memFree(points);
            MemoryUtil.memFree(bitmap);
            MemoryUtil.memFree(ttf);
        }
    }

    private static JsonObject readMap(String resource) {
        try (InputStream in = AegisIcons.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Icon map not found on the classpath: " + resource);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IllegalStateException(resource + " must hold one JSON object");
            return root.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Could not read the icon map " + resource + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonObject map, String key, String otherwise) {
        JsonElement e = map.get(key);
        if (e == null) return otherwise;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("\"" + key + "\" is text, written in quotes; got " + e);
        }
        return e.getAsString();
    }

    /** {@code "U+E140"}: a code point, strictly written so. */
    private static int codepoint(String source, String icon, JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String s = value.getAsString();
            if (s.matches("U\\+[0-9A-F]{4,6}")) return Integer.parseInt(s.substring(2), 16);
        }
        throw new IllegalStateException(source + ": icon \"" + icon + "\" must be a code point written \"U+E140\"; got " + value);
    }

    private static ByteBuffer readResource(String path) {
        try (InputStream in = AegisIcons.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Icon font not found on the classpath: " + path);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the icon font " + path, e);
        }
    }

    // -----------------------------------------------------------------------------------
    // Asking for icons
    // -----------------------------------------------------------------------------------

    /**
     * An icon by name, looked up once — when a widget is given it — so drawing reads an index.
     *
     * @param icon the engine's name for it — {@code "play"}
     * @return its index, or {@link #NONE} if the set has no icon by that name
     */
    public int find(String icon) {
        Integer index = byName.get(icon);
        return index != null ? index : NONE;
    }

    /** @return the size the icons are drawn at, in pixels: the side of the square each sits in */
    public float size() { return pixelHeight; }

    /** @return the set's name, as its map declares it */
    public String name() { return name; }

    /** @return how many icons the set has */
    public int count() { return names.size(); }

    /**
     * Draws an icon centred in a square of {@link #size} at {@code x, y}.
     *
     * @param list  where to draw
     * @param index an index {@link #find} gave; {@link #NONE} draws nothing
     * @param x     the square's left edge
     * @param y     the square's top edge
     * @param rgba  the colour
     */
    void draw(AegisDrawList list, int index, float x, float y, float[] rgba) {
        if (index < 0 || atlas == null) return;
        int m = index * STRIDE;
        float w = boxes[m + 2] - boxes[m], h = boxes[m + 3] - boxes[m + 1];
        float left = Math.round(x + (pixelHeight - w) * 0.5f);
        float top  = Math.round(y + (pixelHeight - h) * 0.5f);
        list.addCoverageQuad(atlas.getID(), left, top, left + w, top + h,
            boxes[m] / atlasSize, boxes[m + 1] / atlasSize, boxes[m + 2] / atlasSize, boxes[m + 3] / atlasSize,
            rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /** Releases the atlas. */
    void cleanup() {
        if (atlas != null) atlas.cleanup();
    }
}
