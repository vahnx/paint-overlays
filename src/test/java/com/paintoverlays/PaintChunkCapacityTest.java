package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PaintChunkCapacityTest
{
    private static final Gson GSON = new Gson();

    @Test
    public void deserializeChunkDoesNotTrimStrokeOverflow() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        for (int i = 0; i < PaintOverlaysPlugin.MAX_STROKES_PER_CHUNK + 5; i++)
        {
            PaintStroke stroke = new PaintStroke(0, new Color(0x26FF00), 4);
            stroke.points.add(new PaintPoint(new PaintTarget(3200 + i, 3200, 0, 64, 64)));
            chunk.strokes.add(stroke);
        }

        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        Field gsonField = PaintOverlaysPlugin.class.getDeclaredField("gson");
        gsonField.setAccessible(true);
        gsonField.set(plugin, GSON);

        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);

        PaintChunkData deserialized = (PaintChunkData) deserializeChunk.invoke(plugin, GSON.toJson(chunk));
        assertEquals(PaintOverlaysPlugin.MAX_STROKES_PER_CHUNK + 5, deserialized.strokes.size());
    }

    @Test
    public void compressedChunkPayloadRoundTrips() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        for (int i = 0; i < 40; i++)
        {
            PaintStroke stroke = new PaintStroke(0, new Color(0x26FF00), 4);
            for (int j = 0; j < 60; j++)
            {
                stroke.points.add(new PaintPoint(new PaintTarget(3200 + i, 3200 + j, 0, 64, 64)));
            }
            chunk.strokes.add(stroke);
        }

        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        Field gsonField = PaintOverlaysPlugin.class.getDeclaredField("gson");
        gsonField.setAccessible(true);
        gsonField.set(plugin, GSON);

        Method encodeChunkPayload = PaintOverlaysPlugin.class.getDeclaredMethod("encodeChunkPayload", String.class);
        encodeChunkPayload.setAccessible(true);
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);

        String json = GSON.toJson(chunk);
        String encoded = (String) encodeChunkPayload.invoke(null, json);
        assertTrue(encoded.startsWith("gz:"));

        PaintChunkData decoded = (PaintChunkData) deserializeChunk.invoke(plugin, encoded);
        assertEquals(chunk.strokes.size(), decoded.strokes.size());
        assertEquals(chunk.strokes.get(0).points.size(), decoded.strokes.get(0).points.size());
    }
}
