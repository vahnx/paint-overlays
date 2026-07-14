package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PaintUndoTest
{
    private static final Gson GSON = new Gson();

    @Test
    public void undoActionDeduplicatesSnapshotsPerChunk()
    {
        PaintUndoAction undoAction = new PaintUndoAction("profile");

        undoAction.addSnapshot("scene.0.1234", "{\"texts\":[]}");
        undoAction.addSnapshot("scene.0.1234", "{\"texts\":[1]}");
        undoAction.addSnapshot("map.5678", null);

        assertEquals(2, undoAction.snapshots.size());
        assertTrue(undoAction.hasSnapshot("scene.0.1234"));
        assertTrue(undoAction.hasSnapshot("map.5678"));
    }

    @Test
    public void snapshotJsonRestoresPreMutationChunkState()
    {
        PaintChunkData original = new PaintChunkData();
        original.texts.add(new PaintText(
            new PaintTarget(3200, 3200, 0, 64, 64),
            new Color(0x26FF00),
            16,
            PaintFontStyle.RUNE_SCAPE,
            new Color(0, 0, 0, 96),
            new Color(255, 255, 255, 180),
            "Before"));

        String snapshotJson = GSON.toJson(original);

        PaintChunkData mutated = GSON.fromJson(snapshotJson, PaintChunkData.class);
        assertNotNull(mutated);
        mutated.texts.clear();
        mutated.shapes.add(new PaintShape(
            new PaintTarget(3210, 3210, 0, 64, 64),
            new Color(0xFF0000),
            48,
            PaintShapeType.STAR));

        PaintChunkData restored = GSON.fromJson(snapshotJson, PaintChunkData.class);
        restored.normalizeLoadedState();

        assertEquals(1, restored.texts.size());
        assertEquals(0, restored.shapes.size());
        assertEquals("Before", restored.texts.get(0).text);
        assertEquals(96, new Color(restored.texts.get(0).backgroundColorArgb, true).getAlpha());
        assertFalse(restored.isEmpty());
    }
}
