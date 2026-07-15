package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
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

    @Test
    public void sceneInputUnavailableAtLoginScreen() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "client", clientWithState(GameState.LOGIN_SCREEN, null));

        assertTrue(!plugin.isSceneInputAvailable());
        assertTrue(!plugin.isWorldMapInputAvailable());
    }

    @Test
    public void sceneInputRequiresLoggedInPlayer() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        Player player = proxy(Player.class);
        setField(plugin, "client", clientWithState(GameState.LOGGED_IN, player));

        assertTrue(plugin.isSceneInputAvailable());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception
    {
        Field field = PaintOverlaysPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Client clientWithState(GameState gameState, Player localPlayer)
    {
        return (Client) Proxy.newProxyInstance(
            Client.class.getClassLoader(),
            new Class<?>[] {Client.class},
            (proxy, method, args) ->
            {
                switch (method.getName())
                {
                    case "getGameState":
                        return gameState;
                    case "getLocalPlayer":
                        return localPlayer;
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type)
    {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> returnType)
    {
        if (!returnType.isPrimitive())
        {
            return null;
        }

        if (returnType == boolean.class)
        {
            return false;
        }

        if (returnType == byte.class)
        {
            return (byte) 0;
        }

        if (returnType == short.class)
        {
            return (short) 0;
        }

        if (returnType == int.class)
        {
            return 0;
        }

        if (returnType == long.class)
        {
            return 0L;
        }

        if (returnType == float.class)
        {
            return 0f;
        }

        if (returnType == double.class)
        {
            return 0d;
        }

        if (returnType == char.class)
        {
            return '\0';
        }

        return null;
    }
}
