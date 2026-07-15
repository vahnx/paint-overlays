package com.paintoverlays;

import com.google.gson.Gson;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.callback.ClientThread;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PaintChunkCapacityTest
{
    private static final Gson GSON = new Gson();

    @Test
    public void deserializeChunkDoesNotTrimStrokeOverflow() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        for (int i = 0; i < PaintOverlaysPlugin.MAX_STROKES_PER_CHUNK + 5; i++)
        {
            PaintStroke stroke = new PaintStroke(0, i % 2 == 0 ? Color.GREEN : Color.BLUE, 4);
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
            PaintStroke stroke = new PaintStroke(0, i % 2 == 0 ? Color.GREEN : Color.BLUE, 4);
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
    public void consecutiveLegacyFragmentsCompactWithoutConnecting() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        chunk.strokes.add(stroke(Color.GREEN, 3200, 3201));
        chunk.strokes.add(stroke(Color.GREEN, 3210, 3211));
        chunk.strokes.add(stroke(Color.BLUE, 3220));
        chunk.strokes.add(stroke(Color.GREEN, 3230));

        PaintChunkData decoded = deserialize(chunk);

        assertEquals(3, decoded.strokes.size());
        assertEquals(4, decoded.strokes.get(0).points.size());
        assertTrue(decoded.strokes.get(0).points.get(2).startsNewSegment());
        assertEquals(3220, decoded.strokes.get(1).points.get(0).worldX);
        assertEquals(3230, decoded.strokes.get(2).points.get(0).worldX);
    }

    @Test
    public void legacySingleStrokeBeyondOldPointLimitLoadsAndRoundTrips() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        PaintStroke stroke = new PaintStroke(0, Color.GREEN, 4);
        for (int i = 0; i < 5_000; i++)
        {
            stroke.points.add(new PaintPoint(new PaintTarget(3200 + i, 3200, 0, 64, 64)));
        }
        chunk.strokes.add(stroke);

        PaintChunkData decoded = deserialize(chunk);
        assertEquals(5_000, decoded.strokes.get(0).points.size());
        assertEquals(8199, decoded.strokes.get(0).points.get(4_999).worldX);

        PaintOverlaysPlugin plugin = pluginWithGson();
        Method encodeChunkPayload = PaintOverlaysPlugin.class.getDeclaredMethod("encodeChunkPayload", String.class);
        encodeChunkPayload.setAccessible(true);
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);
        String encoded = (String) encodeChunkPayload.invoke(null, GSON.toJson(decoded));
        PaintChunkData roundTripped = (PaintChunkData) deserializeChunk.invoke(plugin, encoded);
        assertEquals(5_000, roundTripped.strokes.get(0).points.size());
    }

    @Test
    public void heavilyFragmentedLegacyChunkCompactsAfterSafetyValidation() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        for (int i = 0; i < 12_000; i++)
        {
            chunk.strokes.add(stroke(Color.GREEN, 3200 + i));
        }

        PaintChunkData decoded = deserialize(chunk);

        assertEquals(1, decoded.strokes.size());
        assertEquals(12_000, decoded.strokes.get(0).points.size());
        assertTrue(decoded.strokes.get(0).points.get(11_999).startsNewSegment());
    }

    @Test
    public void storageSplitsSegmentMarkersForOlderPluginVersions() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        PaintStroke stroke = stroke(Color.GREEN, 3200, 3201, 3210, 3211);
        stroke.points.get(2).startsNewSegment = true;
        chunk.strokes.add(stroke);
        PaintOverlaysPlugin plugin = pluginWithGson();

        Method serialize = PaintOverlaysPlugin.class.getDeclaredMethod("serializeChunkForStorage", PaintChunkData.class);
        serialize.setAccessible(true);
        String json = (String) serialize.invoke(plugin, chunk);
        PaintChunkData stored = GSON.fromJson(json, PaintChunkData.class);

        assertEquals(2, stored.strokes.size());
        assertEquals(2, stored.strokes.get(0).points.size());
        assertEquals(2, stored.strokes.get(1).points.size());
        assertTrue(!json.contains("startsNewSegment"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void failedStrokeSaveKeepsChunkQueuedForRetry() throws Exception
    {
        PaintOverlaysPlugin plugin = pluginWithGson();
        Map<String, PaintChunkData> cache = new HashMap<>();
        Set<String> keys = new HashSet<>();
        PaintStroke stroke = stroke(Color.GREEN, 3200);

        Method persist = PaintOverlaysPlugin.class.getDeclaredMethod(
            "persistStrokeSegment", Map.class, Set.class, String.class, PaintStroke.class);
        persist.setAccessible(true);
        assertEquals(true, persist.invoke(plugin, cache, keys, "scene.0.1", stroke));

        assertEquals(1, cache.get("scene.0.1").strokes.size());
        assertTrue(keys.contains("scene.0.1"));
        assertTrue(((Set<String>) getField(plugin, "pendingChunkPersistenceKeys")).contains("scene.0.1"));
        assertTrue(getField(plugin, "pendingUndoAction") != null);

        Method finalizeAction = PaintOverlaysPlugin.class.getDeclaredMethod("finalizePendingPaintAction");
        finalizeAction.setAccessible(true);
        assertEquals(false, finalizeAction.invoke(plugin));
        assertTrue(((Set<String>) getField(plugin, "pendingChunkPersistenceKeys")).contains("scene.0.1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void pendingPersistenceSnapshotUsesInMemoryChunk() throws Exception
    {
        PaintOverlaysPlugin plugin = pluginWithGson();
        String key = "scene.0.1";
        Map<String, PaintChunkData> cache = new HashMap<>();
        Set<String> keys = new HashSet<>();
        PaintChunkData chunk = new PaintChunkData();
        chunk.strokes.add(stroke(Color.GREEN, 3200, 3201));
        cache.put(key, chunk);
        keys.add(key);
        ((Set<String>) getField(plugin, "pendingChunkPersistenceKeys")).add(key);
        PaintUndoAction undoAction = new PaintUndoAction("profile");

        Method capture = PaintOverlaysPlugin.class.getDeclaredMethod(
            "captureUndoSnapshot", PaintUndoAction.class, Map.class, Set.class, String.class);
        capture.setAccessible(true);
        capture.invoke(plugin, undoAction, cache, keys, key);

        assertEquals(1, undoAction.snapshots.size());
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);
        PaintChunkData snapshot = (PaintChunkData) deserializeChunk.invoke(plugin, undoAction.snapshots.get(0).payload);
        assertEquals(2, snapshot.strokes.get(0).points.size());
        assertEquals(3201, snapshot.strokes.get(0).points.get(1).worldX);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void profileReloadFailurePreservesOldStateAndClearsGate() throws Exception
    {
        PaintOverlaysPlugin plugin = pluginWithGson();
        setField(plugin, "loadedRsProfileKey", "old-profile");
        Map<String, PaintChunkData> cache = (Map<String, PaintChunkData>) getField(plugin, "sceneChunks");
        Set<String> keys = (Set<String>) getField(plugin, "sceneChunkKeys");
        cache.put("scene.0.1", new PaintChunkData());
        keys.add("scene.0.1");

        Method reload = PaintOverlaysPlugin.class.getDeclaredMethod("reloadAllChunks");
        reload.setAccessible(true);
        reload.invoke(plugin);

        assertEquals("old-profile", getField(plugin, "loadedRsProfileKey"));
        assertTrue(cache.containsKey("scene.0.1"));
        assertTrue(keys.contains("scene.0.1"));
        assertEquals(false, getField(plugin, "profileReloadPending"));
        assertEquals(true, getField(plugin, "profileReloadRetryPending"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void staleAsyncChunkLoadDoesNotOverwriteNewerClientState() throws Exception
    {
        PaintOverlaysPlugin plugin = pluginWithGson();
        setField(plugin, "loadedRsProfileKey", "profile");
        setField(plugin, "clientThread", new ClientThread()
        {
            @Override
            public void invokeLater(Runnable runnable)
            {
                runnable.run();
            }
        });

        Map<String, PaintChunkData> cache = (Map<String, PaintChunkData>) getField(plugin, "sceneChunks");
        Set<String> keys = (Set<String>) getField(plugin, "sceneChunkKeys");
        Set<String> loading = (Set<String>) getField(plugin, "loadingChunkKeys");
        Set<String> pending = (Set<String>) getField(plugin, "pendingChunkPersistenceKeys");
        String editedKey = "scene.0.1";
        PaintChunkData edited = new PaintChunkData();
        edited.strokes.add(stroke(Color.GREEN, 4000));
        cache.put(editedKey, edited);
        keys.add(editedKey);
        loading.add(editedKey);

        Method complete = PaintOverlaysPlugin.class.getDeclaredMethod(
            "completeChunkLoadAsync",
            String.class,
            String.class,
            long.class,
            PaintChunkData.class,
            RuntimeException.class);
        complete.setAccessible(true);
        PaintChunkData stale = new PaintChunkData();
        stale.strokes.add(stroke(Color.BLUE, 3000));
        complete.invoke(plugin, editedKey, "profile", 0L, stale, null);

        assertTrue(cache.get(editedKey) == edited);
        assertTrue(!loading.contains(editedKey));

        String erasedKey = "scene.0.2";
        keys.add(erasedKey);
        loading.add(erasedKey);
        pending.add(erasedKey);
        complete.invoke(plugin, erasedKey, "profile", 0L, stale, null);

        assertTrue(!cache.containsKey(erasedKey));
        assertTrue(pending.contains(erasedKey));
        assertTrue(!loading.contains(erasedKey));
    }

    @Test
    public void strokeOverflowSplitKeepsExactCapAndCarryPoint() throws Exception
    {
        PaintStroke stroke = new PaintStroke(0, Color.WHITE, 4);
        for (int i = 0; i < PaintOverlaysPlugin.MAX_POINTS_PER_STROKE + 1; i++)
        {
            stroke.points.add(new PaintPoint(new PaintTarget(3200 + i, 3200, 0, 64, 64)));
        }

        Method firstSegmentMethod = PaintOverlaysPlugin.class.getDeclaredMethod("firstStrokeSegment", PaintStroke.class);
        firstSegmentMethod.setAccessible(true);
        Method remainingSegmentMethod = PaintOverlaysPlugin.class.getDeclaredMethod("remainingStrokeSegment", PaintStroke.class);
        remainingSegmentMethod.setAccessible(true);

        PaintStroke first = (PaintStroke) firstSegmentMethod.invoke(null, stroke);
        PaintStroke remaining = (PaintStroke) remainingSegmentMethod.invoke(null, stroke);

        assertEquals(PaintOverlaysPlugin.MAX_POINTS_PER_STROKE, first.points.size());
        assertEquals(2, remaining.points.size());
        assertEquals(first.points.get(first.points.size() - 1).worldX, remaining.points.get(0).worldX);
        assertEquals(stroke.points.get(stroke.points.size() - 1).worldX, remaining.points.get(1).worldX);
    }

    @Test
    public void unsafeSizesAndNullPointsAreNormalizedOnLoad() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        PaintStroke stroke = new PaintStroke(0, new Color(0x26FF00), -10);
        stroke.points.add(null);
        stroke.points.add(new PaintPoint(new PaintTarget(3200, 3200, 0, -5, 500)));
        chunk.strokes.add(stroke);

        PaintShape shape = new PaintShape(
            new PaintTarget(3200, 3200, 0, 64, 64),
            Color.WHITE,
            Integer.MAX_VALUE,
            PaintShapeType.RECTANGLE);
        chunk.shapes.add(shape);

        PaintText text = new PaintText(
            new PaintTarget(3200, 3200, 0, 64, 64),
            Color.WHITE,
            16,
            PaintFontStyle.RUNE_SCAPE,
            Color.BLACK,
            Color.WHITE,
            "Safe");
        text.fontSize = Integer.MAX_VALUE;
        chunk.texts.add(text);

        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "gson", GSON);
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);

        PaintChunkData decoded = (PaintChunkData) deserializeChunk.invoke(plugin, GSON.toJson(chunk));
        assertEquals(1, decoded.strokes.size());
        assertEquals(1, decoded.strokes.get(0).width);
        assertEquals(1, decoded.strokes.get(0).points.size());
        assertEquals(0, decoded.strokes.get(0).points.get(0).offsetX);
        assertEquals(127, decoded.strokes.get(0).points.get(0).offsetY);
        assertEquals(1000, decoded.shapes.get(0).size);
        assertEquals(5000, decoded.texts.get(0).fontSize);
    }

    @Test
    public void invalidEntityPlaneIsRejectedBeforeRendering() throws Exception
    {
        PaintChunkData chunk = new PaintChunkData();
        PaintStroke stroke = new PaintStroke(-1, Color.WHITE, 4);
        stroke.points.add(new PaintPoint(new PaintTarget(3200, 3200, -1, 64, 64)));
        chunk.strokes.add(stroke);

        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "gson", GSON);
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);

        try
        {
            deserializeChunk.invoke(plugin, GSON.toJson(chunk));
            fail("Expected an invalid plane to be rejected");
        }
        catch (InvocationTargetException ex)
        {
            assertTrue(ex.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void liveBrushSizeIsClamped()
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();

        plugin.setBrushSize(0);
        assertEquals(1, plugin.getBrushSize());

        plugin.setBrushSize(Integer.MAX_VALUE);
        assertEquals(200, plugin.getBrushSize());
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
        setField(plugin, "loadedRsProfileKey", "profile");

        assertTrue(plugin.isSceneInputAvailable());
    }

    @Test
    public void sceneInputRejectsLoggedInStateWithoutPlayer() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "client", clientWithState(GameState.LOGGED_IN, null));
        setField(plugin, "loadedRsProfileKey", "profile");

        assertTrue(!plugin.isSceneInputAvailable());
    }

    @Test
    public void sceneInputRequiresLoadedProfile() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        Player player = proxy(Player.class);
        setField(plugin, "client", clientWithState(GameState.LOGGED_IN, player));

        assertTrue(!plugin.isSceneInputAvailable());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception
    {
        Field field = PaintOverlaysPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception
    {
        Field field = PaintOverlaysPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static PaintOverlaysPlugin pluginWithGson() throws Exception
    {
        PaintOverlaysPlugin plugin = new PaintOverlaysPlugin();
        setField(plugin, "gson", GSON);
        return plugin;
    }

    private static PaintChunkData deserialize(PaintChunkData chunk) throws Exception
    {
        PaintOverlaysPlugin plugin = pluginWithGson();
        Method deserializeChunk = PaintOverlaysPlugin.class.getDeclaredMethod("deserializeChunk", String.class);
        deserializeChunk.setAccessible(true);
        return (PaintChunkData) deserializeChunk.invoke(plugin, GSON.toJson(chunk));
    }

    private static PaintStroke stroke(Color color, int... worldXs)
    {
        PaintStroke stroke = new PaintStroke(0, color, 4);
        for (int worldX : worldXs)
        {
            stroke.points.add(new PaintPoint(new PaintTarget(worldX, 3200, 0, 64, 64)));
        }
        return stroke;
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
