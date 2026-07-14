package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaintPersistenceTest
{
    private static final Gson GSON = new Gson();

    @Test
    public void legacyDisabledDecorationsMigrateToTransparentAndStayThatWay()
    {
        String legacyJson = "{"
            + "\"strokes\":[],"
            + "\"shapes\":[],"
            + "\"texts\":[{"
            + "\"plane\":0,"
            + "\"worldX\":3200,"
            + "\"worldY\":3200,"
            + "\"offsetX\":64,"
            + "\"offsetY\":64,"
            + "\"colorArgb\":" + new Color(0x26FF00).getRGB() + ","
            + "\"fontSize\":16,"
            + "\"fontStyle\":\"RUNE_SCAPE\","
            + "\"backgroundEnabled\":false,"
            + "\"backgroundColorArgb\":" + new Color(0, 0, 0, 128).getRGB() + ","
            + "\"borderEnabled\":false,"
            + "\"borderColorArgb\":" + new Color(255, 255, 255, 255).getRGB() + ","
            + "\"text\":\"Legacy\""
            + "}]"
            + "}";

        PaintChunkData chunk = GSON.fromJson(legacyJson, PaintChunkData.class);
        chunk.normalizeLoadedState();

        PaintText text = chunk.texts.get(0);
        assertEquals(0, new Color(text.backgroundColorArgb, true).getAlpha());
        assertEquals(0, new Color(text.borderColorArgb, true).getAlpha());
        assertFalse(text.backgroundEnabled);
        assertFalse(text.borderEnabled);

        PaintChunkData reloaded = GSON.fromJson(GSON.toJson(chunk), PaintChunkData.class);
        reloaded.normalizeLoadedState();

        PaintText persisted = reloaded.texts.get(0);
        assertEquals(0, new Color(persisted.backgroundColorArgb, true).getAlpha());
        assertEquals(0, new Color(persisted.borderColorArgb, true).getAlpha());
        assertFalse(persisted.backgroundEnabled);
        assertFalse(persisted.borderEnabled);
    }

    @Test
    public void alphaBasedDecorationsPersistAcrossJsonRoundTrip()
    {
        PaintChunkData chunk = new PaintChunkData();
        chunk.texts.add(new PaintText(
            new PaintTarget(3200, 3200, 0, 64, 64),
            new Color(0x26FF00),
            16,
            PaintFontStyle.RUNE_SCAPE,
            new Color(0, 0, 0, 96),
            new Color(255, 255, 255, 180),
            "Hello"));

        PaintChunkData reloaded = GSON.fromJson(GSON.toJson(chunk), PaintChunkData.class);
        reloaded.normalizeLoadedState();

        PaintText text = reloaded.texts.get(0);
        assertEquals(96, new Color(text.backgroundColorArgb, true).getAlpha());
        assertEquals(180, new Color(text.borderColorArgb, true).getAlpha());
        assertTrue(text.backgroundEnabled);
        assertTrue(text.borderEnabled);
    }
}
