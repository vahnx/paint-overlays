package com.paintoverlays;

import java.awt.Color;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PaintOverlaysPluginStatusTest
{
    @Test
    public void unloadedKnownChunkDoesNotPretendToHaveZeroUsage()
    {
        assertEquals(
            "Chunk scene.0.1234 | Stored paint",
            PaintOverlaysPlugin.formatChunkUsageStatus("Chunk", "scene.0.1234", null, true));
    }

    @Test
    public void loadedChunkShowsCurrentUsageCounts()
    {
        PaintChunkData chunk = new PaintChunkData();
        PaintStroke stroke = new PaintStroke(0, new Color(0x26FF00), 4);
        stroke.points.add(new PaintPoint(new PaintTarget(3200, 3200, 0, 64, 64)));
        chunk.strokes.add(stroke);
        chunk.shapes.add(new PaintShape(
            new PaintTarget(3201, 3201, 0, 64, 64),
            new Color(0xFF0000),
            48,
            PaintShapeType.STAR));
        chunk.texts.add(new PaintText(
            new PaintTarget(3202, 3202, 0, 64, 64),
            new Color(0x26FF00),
            16,
            PaintFontStyle.RUNE_SCAPE,
            new Color(0, 0, 0, 96),
            new Color(255, 255, 255, 180),
            "Hello"));

        assertEquals(
            "Chunk scene.0.1234 | Brush 1/300 | Shapes 1/100 | Text 1/100",
            PaintOverlaysPlugin.formatChunkUsageStatus("Chunk", "scene.0.1234", chunk, true));
    }
}
