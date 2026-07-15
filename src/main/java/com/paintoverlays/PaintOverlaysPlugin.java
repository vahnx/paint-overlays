package com.paintoverlays;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.AWTException;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
    name = "Paint Overlays",
    description = "Manually paint the scene and world map with persistent strokes and text notes",
    tags = {"paint", "overlay", "map", "notes", "drawing", "marker"}
)
public class PaintOverlaysPlugin extends Plugin
{
    static final int MAX_STROKES_PER_CHUNK = 300;
    static final int MAX_SHAPES_PER_CHUNK = 100;
    static final int MAX_POINTS_PER_STROKE = 512;
    static final int MAX_TEXTS_PER_CHUNK = 100;
    static final int MAX_UNDO_ACTIONS = 3;
    private static final String COMPRESSED_CHUNK_PREFIX = "gz:";
    private static final String SCENE_PREFIX = "scene.";
    private static final String MAP_PREFIX = "map.";
    private static final String DEBUG_TOOLS_PROPERTY = "paintoverlays.debugTools";
    private static final int REGION_SIZE = 64;
    private static final int DEFAULT_BRUSH_SIZE = 4;
    private static final int MIN_BRUSH_SIZE = 1;
    private static final int MAX_BRUSH_SIZE = 200;
    private static final int DEFAULT_TEXT_SIZE = 16;
    private static final int MIN_TEXT_SIZE = 1;
    private static final int MAX_TEXT_SIZE = 5000;
    private static final int DEFAULT_SHAPE_SIZE = 48;
    private static final int MIN_SHAPE_SIZE = 4;
    private static final int MAX_SHAPE_SIZE = 1000;
    private static final int MAX_DECODED_CHUNK_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENCODED_CHUNK_CHARS = 48 * 1024 * 1024;
    private static final int MAX_LOADED_STROKES_PER_CHUNK = MAX_STROKES_PER_CHUNK * MAX_POINTS_PER_STROKE * 2;
    private static final int MAX_LOADED_SHAPES_PER_CHUNK = 1_000;
    private static final int MAX_LOADED_TEXTS_PER_CHUNK = 1_000;
    private static final int MAX_LOADED_TOTAL_POINTS_PER_CHUNK = MAX_STROKES_PER_CHUNK * MAX_POINTS_PER_STROKE * 2;
    private static final int MAX_LOADED_POINTS_PER_STROKE = MAX_LOADED_TOTAL_POINTS_PER_CHUNK;
    private static final int MAX_LOADED_TEXT_LENGTH = 4096;
    private static final int MAX_INTERPOLATION_STEPS_PER_APPEND = 128;
    private static final int MAX_PENDING_DRAG_POINTS_PER_GESTURE = 8;
    private static final long PROFILE_RELOAD_RETRY_BASE_NANOS = 1_000_000_000L;
    private static final long PROFILE_RELOAD_RETRY_MAX_NANOS = 30_000_000_000L;
    private static final Color DEFAULT_COLOR = new Color(0x26FF00);
    private static final Color DEFAULT_TEXT_BACKGROUND_COLOR = new Color(0, 0, 0, 128);
    private static final Color DEFAULT_TEXT_BORDER_COLOR = new Color(255, 255, 255, 255);
    private static final Color DEFAULT_FRAME_COLOR = new Color(38, 255, 0, 180);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private Gson gson;

    @Inject
    private ColorPickerManager colorPickerManager;

    @Inject
    private PaintOverlaysConfig config;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private PaintOverlaysOverlay sceneOverlay;

    @Inject
    private PaintOverlaysWorldMapOverlay worldMapOverlay;

    @Inject
    private PaintOverlaysInputFrameOverlay inputFrameOverlay;

    private final Map<String, PaintChunkData> sceneChunks = new HashMap<>();
    private final Map<String, PaintChunkData> mapChunks = new HashMap<>();
    private final Set<String> sceneChunkKeys = new HashSet<>();
    private final Set<String> mapChunkKeys = new HashSet<>();
    private final Set<String> pendingChunkPersistenceKeys = new HashSet<>();
    private final Set<String> corruptChunkKeys = new HashSet<>();
    private final Set<String> loadingChunkKeys = new HashSet<>();
    private final Deque<PaintUndoAction> undoHistory = new ArrayDeque<>();
    private final AtomicReference<PaintPanelState> pendingPanelState = new AtomicReference<>();
    private final AtomicBoolean panelRefreshScheduled = new AtomicBoolean();
    private final Object pendingMouseDragLock = new Object();
    private final List<PendingMouseDrag> pendingMouseDrags = new ArrayList<>(MAX_PENDING_DRAG_POINTS_PER_GESTURE);
    private final AtomicBoolean mouseDragScheduled = new AtomicBoolean();
    private final AtomicLong inputGestureSequence = new AtomicLong();
    private final AtomicLong inputContextGeneration = new AtomicLong();
    private final AtomicLong panelToolRequestSequence = new AtomicLong();
    private final AtomicBoolean clientPanelSnapshotScheduled = new AtomicBoolean();

    private volatile PaintOverlaysPanel panel;
    private long lastHandledPanelToolRequestId;
    private NavigationButton navigationButton;
    private PaintOverlaysMouseListener mouseListener;
    private KeyEventDispatcher keyEventDispatcher;
    private BufferedImage iconImage;
    private Future<?> debugExportFuture;

    private volatile PaintTool tool;
    private volatile PaintShapeType shapeType = PaintShapeType.RECTANGLE;
    private volatile PaintFontStyle fontStyle = PaintFontStyle.RUNE_SCAPE;
    private volatile PaintFrameStyle frameStyle = PaintFrameStyle.SOLID;
    private volatile Color color = DEFAULT_COLOR;
    private volatile Color textBackgroundColor = DEFAULT_TEXT_BACKGROUND_COLOR;
    private volatile Color textBorderColor = DEFAULT_TEXT_BORDER_COLOR;
    private volatile Color frameColor = DEFAULT_FRAME_COLOR;
    private volatile int brushSize = DEFAULT_BRUSH_SIZE;
    private volatile int shapeSize = DEFAULT_SHAPE_SIZE;
    private volatile int textSize = DEFAULT_TEXT_SIZE;
    private volatile String pendingText = "This is a sample message.";
    private volatile boolean frameRainbowEnabled = true;

    private PaintStroke activeSceneStroke;
    private PaintStroke activeMapStroke;
    private String activeSceneChunkKey;
    private String activeMapChunkKey;
    private volatile String loadedRsProfileKey;
    private volatile String observedRsProfileKey;
    private volatile String cachedSceneStatusChunkKey;
    private volatile String cachedMapStatusChunkKey;
    private volatile boolean inputCaptureActive;
    private volatile long awtInputGestureId;
    private volatile long cancelledInputGestureId;
    private boolean clientInputCaptureActive;
    private long clientInputGestureId;
    private long clientInputContextGeneration;
    private java.awt.Point lastClientDragPoint;
    private boolean lastClientDragMapPointUsable;
    private volatile Point previewMouseCanvasPosition;
    private volatile boolean worldMapOpen;
    private volatile boolean clearPreviewActive;
    private PaintTarget lastSceneSearchTarget;
    private PaintUndoAction pendingUndoAction;
    private volatile boolean cachedUndoAvailable;
    private volatile boolean profileReloadPending;
    private boolean profileReloadRetryPending;
    private int profileReloadRetryAttempts;
    private long nextProfileReloadRetryNanos;

    private boolean sceneTargetCacheValid;
    private WorldView sceneTargetCacheWorldView;
    private PaintTarget sceneTargetCacheResult;
    private int sceneTargetCacheMouseX;
    private int sceneTargetCacheMouseY;
    private int sceneTargetCacheCameraX;
    private int sceneTargetCacheCameraY;
    private int sceneTargetCacheCameraZ;
    private int sceneTargetCacheCameraPitch;
    private int sceneTargetCacheCameraYaw;
    private int sceneTargetCacheBaseX;
    private int sceneTargetCacheBaseY;
    private int sceneTargetCachePlane;
    private int sceneTargetCacheViewportX;
    private int sceneTargetCacheViewportY;
    private int sceneTargetCacheViewportWidth;
    private int sceneTargetCacheViewportHeight;
    private int sceneTargetCacheCanvasWidth;
    private int sceneTargetCacheCanvasHeight;
    private int sceneTargetCacheTick;
    private long chunkLoadGeneration;

    @Provides
    PaintOverlaysConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(PaintOverlaysConfig.class);
    }

    @Override
    protected void startUp()
    {
        mouseDragScheduled.set(false);
        clearPendingMouseDrags();
        panel = new PaintOverlaysPanel(this, colorPickerManager, createPanelState());
        iconImage = createIcon();
        navigationButton = NavigationButton.builder()
            .tooltip("Paint Overlays")
            .icon(iconImage)
            .priority(8)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navigationButton);

        overlayManager.add(sceneOverlay);
        overlayManager.add(worldMapOverlay);
        overlayManager.add(inputFrameOverlay);

        mouseListener = new PaintOverlaysMouseListener(this);
        mouseManager.registerMouseListener(mouseListener);
        keyEventDispatcher = event -> event != null && event.getID() == KeyEvent.KEY_PRESSED && handleKeyPressed(event);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher);

        clientThread.invokeLater(() ->
        {
            updateContextState();
            reloadAllChunks();
        });
    }

    @Override
    protected void shutDown()
    {
        try
        {
            if (!finalizePendingPaintAction())
            {
                log.warn("Some paint changes remain only in memory while the plugin shuts down");
            }
        }
        catch (RuntimeException ex)
        {
            log.warn("Failed to finalize paint while shutting down", ex);
            abortActiveStrokesAfterFailure();
        }
        flushPersistedChangesAsync();
        inputCaptureActive = false;
        awtInputGestureId = 0L;
        cancelledInputGestureId = inputGestureSequence.get();
        inputContextGeneration.incrementAndGet();
        clientInputCaptureActive = false;
        clientInputGestureId = 0L;
        clientInputContextGeneration = 0L;
        clearPendingMouseDrags();
        mouseDragScheduled.set(false);
        lastClientDragPoint = null;
        lastClientDragMapPointUsable = false;
        if (debugExportFuture != null)
        {
            debugExportFuture.cancel(true);
            debugExportFuture = null;
        }
        mouseManager.unregisterMouseListener(mouseListener);
        if (keyEventDispatcher != null)
        {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher);
        }
        overlayManager.remove(inputFrameOverlay);
        overlayManager.remove(worldMapOverlay);
        overlayManager.remove(sceneOverlay);
        clientToolbar.removeNavigation(navigationButton);
        if (panel != null)
        {
            panel.disposePanel();
        }

        sceneChunks.clear();
        mapChunks.clear();
        sceneChunkKeys.clear();
        mapChunkKeys.clear();
        pendingChunkPersistenceKeys.clear();
        corruptChunkKeys.clear();
        loadingChunkKeys.clear();
        chunkLoadGeneration++;

        activeSceneStroke = null;
        activeMapStroke = null;
        activeSceneChunkKey = null;
        activeMapChunkKey = null;
        lastSceneSearchTarget = null;
        invalidateSceneTargetCache();
        worldMapOpen = false;
        tool = null;
        loadedRsProfileKey = null;
        observedRsProfileKey = null;
        cachedSceneStatusChunkKey = null;
        cachedMapStatusChunkKey = null;
        previewMouseCanvasPosition = null;
        pendingUndoAction = null;
        undoHistory.clear();
        cachedUndoAvailable = false;
        pendingPanelState.set(null);
        profileReloadPending = false;
        profileReloadRetryPending = false;
        profileReloadRetryAttempts = 0;
        nextProfileReloadRetryNanos = 0L;
        worldMapOverlay.resetViewState();

        panel = null;
        navigationButton = null;
        mouseListener = null;
        keyEventDispatcher = null;
        iconImage = null;
    }

    private void flushPersistedChangesAsync()
    {
        if (executor == null || configManager == null)
        {
            return;
        }

        try
        {
            executor.execute(() ->
            {
                try
                {
                    configManager.sendConfig();
                }
                catch (RuntimeException ex)
                {
                    log.warn("Failed to flush paint configuration during shutdown", ex);
                }
            });
        }
        catch (RuntimeException ex)
        {
            log.debug("Paint configuration flush could not be scheduled during shutdown", ex);
        }
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged event)
    {
        profileReloadPending = true;
        inputCaptureActive = false;
        awtInputGestureId = 0L;
        cancelledInputGestureId = inputGestureSequence.get();
        inputContextGeneration.incrementAndGet();
        clientThread.invoke(this::reloadAllChunks);
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        profileReloadPending = true;
        inputCaptureActive = false;
        awtInputGestureId = 0L;
        cancelledInputGestureId = inputGestureSequence.get();
        inputContextGeneration.incrementAndGet();
        clientThread.invoke(this::reloadAllChunks);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            profileReloadPending = true;
            reloadAllChunks();
            return;
        }

        inputCaptureActive = false;
        awtInputGestureId = 0L;
        cancelledInputGestureId = inputGestureSequence.get();
        inputContextGeneration.incrementAndGet();
        clientInputCaptureActive = false;
        clientInputGestureId = 0L;
        clearPendingMouseDrags();
        lastClientDragPoint = null;
        boolean persisted = finalizePendingPaintAction();
        tool = null;
        lastSceneSearchTarget = null;
        invalidateSceneTargetCache();
        if (persisted)
        {
            loadedRsProfileKey = null;
        }
        else
        {
            log.warn("Keeping unsaved paint in memory for a later persistence retry");
        }
        observedRsProfileKey = null;
        profileReloadRetryPending = false;
        profileReloadRetryAttempts = 0;
        nextProfileReloadRetryNanos = 0L;
        cachedSceneStatusChunkKey = null;
        cachedMapStatusChunkKey = null;
        clearPreviewActive = false;
        previewMouseCanvasPosition = null;
        refreshPanel();
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        if (profileReloadRetryPending
            && !profileReloadPending
            && System.nanoTime() >= nextProfileReloadRetryNanos)
        {
            reloadAllChunks();
            return;
        }
        updateContextState();
    }

    PaintInputMode getInputMode()
    {
        if (tool == null)
        {
            return PaintInputMode.NONE;
        }

        return worldMapOpen ? PaintInputMode.WORLD_MAP : PaintInputMode.SCENE;
    }

    PaintTool getTool()
    {
        return tool;
    }

    PaintFontStyle getFontStyle()
    {
        return fontStyle;
    }

    PaintShapeType getShapeType()
    {
        return shapeType;
    }

    Color getColor()
    {
        return color;
    }

    Color getTextBackgroundColor()
    {
        return textBackgroundColor;
    }

    Color getTextBorderColor()
    {
        return textBorderColor;
    }

    Color getFrameColor()
    {
        return frameColor;
    }

    int getBrushSize()
    {
        return brushSize;
    }

    int getShapeSize()
    {
        return shapeSize;
    }

    int getTextSize()
    {
        return textSize;
    }

    String getPendingText()
    {
        return pendingText;
    }

    PaintOverlaysConfig getPluginConfig()
    {
        return config;
    }

    void undoLastAction()
    {
        clientThread.invoke(this::undoLastActionOnClientThread);
    }

    void setTool(PaintTool tool)
    {
        if (invokeOnClientThread(() -> setTool(tool)))
        {
            return;
        }

        if (tool != null && !isEditingAvailable())
        {
            return;
        }

        this.tool = tool;
        inputCaptureActive = false;
        awtInputGestureId = 0L;
        cancelledInputGestureId = inputGestureSequence.get();
        inputContextGeneration.incrementAndGet();
        clientInputCaptureActive = false;
        clientInputGestureId = 0L;
        clearPendingMouseDrags();
        lastClientDragPoint = null;
        lastSceneSearchTarget = null;
        invalidateSceneTargetCache();
        finalizePendingPaintAction();
        refreshPanel();
    }

    long requestPanelToolChange(PaintTool requestedTool)
    {
        long requestId = panelToolRequestSequence.incrementAndGet();
        clientThread.invoke(() ->
        {
            if (requestId < lastHandledPanelToolRequestId)
            {
                return;
            }

            lastHandledPanelToolRequestId = requestId;
            setTool(requestedTool);
            refreshPanel();
        });
        return requestId;
    }

    void requestPanelRefresh()
    {
        refreshPanel();
    }

    void setFontStyle(PaintFontStyle fontStyle)
    {
        if (invokeOnClientThread(() -> setFontStyle(fontStyle)))
        {
            return;
        }

        this.fontStyle = fontStyle;
        refreshPanel();
    }

    void setShapeType(PaintShapeType shapeType)
    {
        if (invokeOnClientThread(() -> setShapeType(shapeType)))
        {
            return;
        }

        this.shapeType = shapeType == null ? PaintShapeType.RECTANGLE : shapeType;
        refreshPanel();
    }

    void setFrameStyle(PaintFrameStyle frameStyle)
    {
        if (invokeOnClientThread(() -> setFrameStyle(frameStyle)))
        {
            return;
        }

        this.frameStyle = frameStyle == null ? PaintFrameStyle.SOLID : frameStyle;
        refreshPanel();
    }

    void setColor(Color color)
    {
        if (invokeOnClientThread(() -> setColor(color)))
        {
            return;
        }

        if (color == null)
        {
            return;
        }

        this.color = color;
        refreshPanel();
    }

    void setTextBackgroundColor(Color textBackgroundColor)
    {
        if (invokeOnClientThread(() -> setTextBackgroundColor(textBackgroundColor)))
        {
            return;
        }

        if (textBackgroundColor == null)
        {
            return;
        }

        this.textBackgroundColor = textBackgroundColor;
        refreshPanel();
    }

    void setTextBorderColor(Color textBorderColor)
    {
        if (invokeOnClientThread(() -> setTextBorderColor(textBorderColor)))
        {
            return;
        }

        if (textBorderColor == null)
        {
            return;
        }

        this.textBorderColor = textBorderColor;
        refreshPanel();
    }

    void setFrameColor(Color frameColor)
    {
        if (invokeOnClientThread(() -> setFrameColor(frameColor)))
        {
            return;
        }

        if (frameColor == null)
        {
            return;
        }

        this.frameColor = frameColor;
        refreshPanel();
    }

    void setBrushSize(int brushSize)
    {
        if (invokeOnClientThread(() -> setBrushSize(brushSize)))
        {
            return;
        }

        this.brushSize = clampBrushSize(brushSize);
        refreshPanel();
    }

    void setShapeSize(int shapeSize)
    {
        if (invokeOnClientThread(() -> setShapeSize(shapeSize)))
        {
            return;
        }

        this.shapeSize = clampShapeSize(shapeSize);
        refreshPanel();
    }

    void setTextSize(int textSize)
    {
        if (invokeOnClientThread(() -> setTextSize(textSize)))
        {
            return;
        }

        this.textSize = clampTextSize(textSize);
        refreshPanel();
    }

    void setPendingText(String pendingText)
    {
        if (invokeOnClientThread(() -> setPendingText(pendingText)))
        {
            return;
        }

        String sanitized = PaintMath.sanitizePendingText(pendingText);
        if (!sanitized.equals(this.pendingText))
        {
            this.pendingText = sanitized;
            refreshPanel();
        }
    }

    void setFrameRainbowEnabled(boolean frameRainbowEnabled)
    {
        if (invokeOnClientThread(() -> setFrameRainbowEnabled(frameRainbowEnabled)))
        {
            return;
        }

        this.frameRainbowEnabled = frameRainbowEnabled;
        refreshPanel();
    }

    private boolean invokeOnClientThread(Runnable action)
    {
        if (client == null || clientThread == null || client.isClientThread())
        {
            return false;
        }

        clientThread.invoke(action);
        return true;
    }

    boolean isWorldMapOpen()
    {
        return worldMapOpen;
    }

    boolean isSceneInputAvailable()
    {
        return isEditingAvailable() && !worldMapOpen;
    }

    boolean isWorldMapInputAvailable()
    {
        return isEditingAvailable() && worldMapOpen;
    }

    boolean isEditingAvailable()
    {
        if (!isProfileDataCurrent()
            || !isInLoggedInGame()
            || client.getLocalPlayer() == null)
        {
            return false;
        }

        return true;
    }

    private boolean isProfileDataCurrent()
    {
        return !profileReloadPending
            && loadedRsProfileKey != null
            && (configManager == null || profileKeysEqual(loadedRsProfileKey, observedRsProfileKey));
    }

    boolean areDebugToolsEnabled()
    {
        return Boolean.getBoolean(DEBUG_TOOLS_PROPERTY);
    }

    void clearCurrentSurfaceChunk()
    {
        clientThread.invoke(this::clearCurrentSurfaceChunkOnClientThread);
    }

    void beginClearPreview()
    {
        clearPreviewActive = true;
    }

    void endClearPreview()
    {
        clearPreviewActive = false;
    }

    SceneClearPreview getSceneClearPreview()
    {
        if (!clearPreviewActive || worldMapOpen || client.getLocalPlayer() == null)
        {
            return null;
        }

        WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
        if (playerLocation == null)
        {
            return null;
        }

        int plane = playerLocation.getPlane();
        int currentRegionId = playerLocation.getRegionID();
        List<Integer> nearbyRegionIds = new ArrayList<>(9);
        int regionX = playerLocation.getX() >> 6;
        int regionY = playerLocation.getY() >> 6;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int previewRegionX = regionX + dx;
                int previewRegionY = regionY + dy;
                if (previewRegionX >= 0 && previewRegionY >= 0)
                {
                    nearbyRegionIds.add((previewRegionX << 8) | previewRegionY);
                }
            }
        }

        return new SceneClearPreview(plane, currentRegionId, nearbyRegionIds);
    }

    MapClearPreview getMapClearPreview()
    {
        if (!clearPreviewActive || !worldMapOpen)
        {
            return null;
        }

        int currentRegionId = resolveCurrentMapPreviewRegionId();
        Collection<Integer> visibleRegionIds = worldMapOverlay.getVisibleRegionIds();
        if ((visibleRegionIds == null || visibleRegionIds.isEmpty()) && currentRegionId < 0)
        {
            return null;
        }

        return new MapClearPreview(
            currentRegionId,
            visibleRegionIds == null ? Collections.emptyList() : new ArrayList<>(visibleRegionIds));
    }

    void clearSecondarySurfaceSelection()
    {
        clientThread.invoke(this::clearSecondarySurfaceSelectionOnClientThread);
    }

    String getClearActionText()
    {
        return worldMapOpen ? "Clear Map Paint" : "Clear Paint";
    }

    void generateDrawingTest()
    {
        if (!areDebugToolsEnabled())
        {
            return;
        }

        clientThread.invoke(this::generateDrawingTestOnClientThread);
    }

    void exportDebugSnapshot()
    {
        if (!areDebugToolsEnabled())
        {
            return;
        }

        clientThread.invoke(this::exportDebugSnapshotOnClientThread);
    }

    String getUndoActionText()
    {
        return "Undo Last";
    }

    void clearNearbySceneChunks()
    {
        finalizePendingPaintAction();
        PaintUndoAction undoAction = beginUndoAction();
        for (String key : getVisibleSceneChunkKeys())
        {
            if (sceneChunkKeys.contains(key))
            {
                captureUndoSnapshot(undoAction, sceneChunks, sceneChunkKeys, key);
                removeChunk(sceneChunks, sceneChunkKeys, key);
            }
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void clearCurrentSurfaceChunkOnClientThread()
    {
        updateContextState();
        if (worldMapOpen)
        {
            clearCurrentMapChunk();
            return;
        }

        clearCurrentSceneChunk();
    }

    private void clearSecondarySurfaceSelectionOnClientThread()
    {
        updateContextState();
        if (worldMapOpen)
        {
            clearVisibleMapRegions();
            return;
        }

        clearNearbySceneChunks();
    }

    void clearCurrentSceneChunk()
    {
        finalizePendingPaintAction();
        String key = getCurrentSceneChunkKey();
        if (key == null || !sceneChunkKeys.contains(key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, sceneChunks, sceneChunkKeys, key);
        removeChunk(sceneChunks, sceneChunkKeys, key);
        commitUndoAction(undoAction);
        refreshPanel();
    }

    void clearCurrentMapChunk()
    {
        finalizePendingPaintAction();
        String key = getCurrentMapChunkKey();
        if (key == null || !mapChunkKeys.contains(key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, mapChunks, mapChunkKeys, key);
        removeChunk(mapChunks, mapChunkKeys, key);
        commitUndoAction(undoAction);
        refreshPanel();
    }

    void clearVisibleMapRegions()
    {
        finalizePendingPaintAction();
        PaintUndoAction undoAction = beginUndoAction();
        for (Integer regionId : worldMapOverlay.getVisibleRegionIds())
        {
            String key = getMapChunkKey(regionId);
            if (mapChunkKeys.contains(key))
            {
                captureUndoSnapshot(undoAction, mapChunks, mapChunkKeys, key);
                removeChunk(mapChunks, mapChunkKeys, key);
            }
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void generateDrawingTestOnClientThread()
    {
        if (worldMapOpen || client.getLocalPlayer() == null)
        {
            refreshPanel();
            return;
        }

        finalizePendingPaintAction();
        clearNearbySceneChunks();

        WorldPoint location = client.getLocalPlayer().getWorldLocation();
        if (location == null)
        {
            refreshPanel();
            return;
        }

        int centerRegionX = location.getX() >> 6;
        int centerRegionY = location.getY() >> 6;
        int plane = location.getPlane();

        String[] labels = {"SW", "S", "SE", "W", "C", "E", "NW", "N", "NE"};
        Color[] colors = {
            new Color(0xF94144), new Color(0xF3722C), new Color(0xF8961E),
            new Color(0x90BE6D), new Color(0x43AA8B), new Color(0x577590),
            new Color(0x277DA1), new Color(0x9B5DE5), new Color(0xF15BB5)
        };
        PaintShapeType[] shapeTypes = {
            PaintShapeType.RECTANGLE, PaintShapeType.CIRCLE, PaintShapeType.DIAMOND,
            PaintShapeType.TRIANGLE, PaintShapeType.TARGET, PaintShapeType.PLUS,
            PaintShapeType.STAR, PaintShapeType.X, PaintShapeType.PRAYER_STAR
        };

        int labelIndex = 0;
        for (int dy = -1; dy <= 1; dy++)
        {
            for (int dx = -1; dx <= 1; dx++)
            {
                int regionX = centerRegionX + dx;
                int regionY = centerRegionY + dy;
                if (regionX < 0 || regionY < 0)
                {
                    continue;
                }

                int baseX = regionX << 6;
                int baseY = regionY << 6;
                String key = getSceneChunkKey(plane, (regionX << 8) | regionY);
                PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, true);
                chunk.strokes.clear();
                chunk.shapes.clear();
                chunk.texts.clear();

                Color color = colors[labelIndex % colors.length];
                String label = labels[labelIndex % labels.length];
                chunk.texts.add(new PaintText(
                    new PaintTarget(baseX + 8, baseY + 10, plane, 64, 64),
                    color,
                    18,
                    PaintFontStyle.RUNE_SCAPE_BOLD,
                    new Color(0, 0, 0, 140),
                    new Color(255, 255, 255, 190),
                    "TEST " + label));
                chunk.texts.add(new PaintText(
                    new PaintTarget(baseX + 8, baseY + 18, plane, 64, 64),
                    Color.WHITE,
                    14,
                    PaintFontStyle.RUNE_SCAPE_SMALL,
                    new Color(0, 0, 0, 110),
                    new Color(255, 255, 255, 150),
                    "R" + regionX + "," + regionY));

                chunk.shapes.add(new PaintShape(
                    new PaintTarget(baseX + 18, baseY + 18, plane, 64, 64),
                    color,
                    26,
                    shapeTypes[labelIndex % shapeTypes.length]));
                chunk.shapes.add(new PaintShape(
                    new PaintTarget(baseX + 46, baseY + 46, plane, 64, 64),
                    color.brighter(),
                    32,
                    PaintShapeType.RECTANGLE));

                addDebugFrameStroke(chunk, plane, color, baseX + 6, baseY + 6, 52, 52, 4);
                addDebugDiagonalStroke(chunk, plane, color, baseX + 8, baseY + 52, baseX + 52, baseY + 8, 3);
                addDebugWaveStroke(chunk, plane, color.brighter(), baseX + 6, baseY + 30, 52, 5 + labelIndex, 3);

                if (dx == 0 && dy == 0)
                {
                    addCenterStressPattern(chunk, plane, baseX, baseY);
                }

                saveChunk(sceneChunks, sceneChunkKeys, key);
                labelIndex++;
            }
        }

        refreshPanel();
    }

    private void exportDebugSnapshotOnClientThread()
    {
        finalizePendingPaintAction();
        refreshPanel();
        if (executor == null || (debugExportFuture != null && !debugExportFuture.isDone()))
        {
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        StringWriter report = new StringWriter();
        try (PrintWriter writer = new PrintWriter(report))
        {
            writer.println("timestamp=" + timestamp);
            writer.println("profileKey=" + loadedRsProfileKey);
            writer.println("worldMapOpen=" + worldMapOpen);
            writer.println("sceneChunkCount=" + sceneChunkKeys.size());
            writer.println("mapChunkCount=" + mapChunkKeys.size());
            writer.println("currentSceneChunk=" + getCurrentSceneChunkKey());
            writer.println("currentMapChunk=" + getCurrentMapChunkKey());
            writer.println();
            writer.println("[scene]");
            writeChunkDiagnostics(writer, sceneChunks, sceneChunkKeys);
            writer.println();
            writer.println("[map]");
            writeChunkDiagnostics(writer, mapChunks, mapChunkKeys);
        }

        String reportText = report.toString();
        debugExportFuture = executor.submit(() -> writeDebugExport(timestamp, reportText));
    }

    private void writeDebugExport(String timestamp, String reportText)
    {
        File debugDir = new File(RuneLite.RUNELITE_DIR, "paint-overlays-debug");
        if (!debugDir.exists() && !debugDir.mkdirs())
        {
            log.warn("Failed to create debug export directory {}", debugDir);
            return;
        }

        File textFile = new File(debugDir, "paint-overlays-debug-" + timestamp + ".txt");
        File imageFile = new File(debugDir, "paint-overlays-debug-" + timestamp + ".png");

        try (PrintWriter writer = new PrintWriter(textFile, StandardCharsets.UTF_8.name()))
        {
            writer.print(reportText);
        }
        catch (IOException ex)
        {
            log.warn("Failed to write paint debug report", ex);
        }

        try
        {
            captureCanvasSnapshot(imageFile);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            log.debug("Paint debug snapshot capture was interrupted", ex);
        }
        catch (IOException | InvocationTargetException ex)
        {
            log.warn("Failed to capture paint debug snapshot", ex);
        }
    }

    boolean hasSecondarySurfacePaint()
    {
        if (worldMapOpen)
        {
            for (Integer regionId : worldMapOverlay.getVisibleRegionIds())
            {
                if (mapChunkKeys.contains(getMapChunkKey(regionId)))
                {
                    return true;
                }
            }
            return false;
        }

        for (String key : getVisibleSceneChunkKeys())
        {
            if (sceneChunkKeys.contains(key))
            {
                return true;
            }
        }
        return false;
    }

    boolean handleMousePressed(MouseEvent event)
    {
        if (!isCanvasEvent(event))
        {
            long gestureId = awtInputGestureId;
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            clientThread.invoke(() -> endClientInputCapture(gestureId, null));
            return false;
        }

        PaintInputMode inputMode = getInputMode();
        if (inputMode == PaintInputMode.NONE || SwingUtilities.isMiddleMouseButton(event) || !isInputModeAvailable(inputMode))
        {
            return false;
        }

        if (!SwingUtilities.isLeftMouseButton(event))
        {
            long gestureId = awtInputGestureId;
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            clientThread.invoke(() -> endClientInputCapture(gestureId, null));
            return false;
        }

        long contextGeneration = inputContextGeneration.get();
        long gestureId = inputGestureSequence.incrementAndGet();
        PaintTool acceptedTool = tool;
        inputCaptureActive = true;
        awtInputGestureId = gestureId;
        if (contextGeneration != inputContextGeneration.get())
        {
            if (awtInputGestureId == gestureId)
            {
                inputCaptureActive = false;
                awtInputGestureId = 0L;
            }
            return false;
        }

        java.awt.Point point = event.getPoint();
        clientThread.invoke(() -> processMousePressed(
            gestureId,
            contextGeneration,
            inputMode,
            acceptedTool,
            point));
        return true;
    }

    boolean handleMouseDragged(MouseEvent event)
    {
        if (!isCanvasEvent(event))
        {
            return false;
        }

        PaintInputMode inputMode = getInputMode();
        if (inputMode == PaintInputMode.NONE || !inputCaptureActive || !isLeftButtonDown(event) || !isInputModeAvailable(inputMode))
        {
            return false;
        }

        long gestureId = awtInputGestureId;
        if (gestureId == 0L)
        {
            return false;
        }

        java.awt.Point point = event.getPoint();
        enqueuePendingMouseDrag(new PendingMouseDrag(
            gestureId,
            point,
            inputMode != PaintInputMode.WORLD_MAP || worldMapOverlay.containsCanvasPoint(point.x, point.y)));
        scheduleMouseDragProcessing();
        return true;
    }

    private void scheduleMouseDragProcessing()
    {
        if (mouseDragScheduled.compareAndSet(false, true))
        {
            clientThread.invokeLater(this::processPendingMouseDrag);
        }
    }

    private void processPendingMouseDrag()
    {
        List<PendingMouseDrag> futureGestures = new ArrayList<>();
        try
        {
            for (PendingMouseDrag pending : drainPendingMouseDrags())
            {
                if (pending.gestureId <= cancelledInputGestureId)
                {
                    continue;
                }

                long activeGestureId = clientInputGestureId;
                if (pending.gestureId > activeGestureId)
                {
                    futureGestures.add(pending);
                    continue;
                }

                if (pending.gestureId < activeGestureId || !clientInputCaptureActive)
                {
                    continue;
                }

                try
                {
                    processMouseDragged(pending);
                }
                catch (RuntimeException ex)
                {
                    log.warn("Failed to process paint drag input", ex);
                    abortActiveStrokesAfterFailure();
                    rejectInputGesture(pending.gestureId);
                }
            }
        }
        finally
        {
            prependPendingMouseDrags(futureGestures);
            mouseDragScheduled.set(false);
            if (hasPendingMouseDrags(clientInputGestureId))
            {
                scheduleMouseDragProcessing();
            }
        }
    }

    private void enqueuePendingMouseDrag(PendingMouseDrag pending)
    {
        if (pending == null)
        {
            return;
        }

        synchronized (pendingMouseDragLock)
        {
            if (!pendingMouseDrags.isEmpty())
            {
                PendingMouseDrag last = pendingMouseDrags.get(pendingMouseDrags.size() - 1);
                if (last.gestureId == pending.gestureId
                    && last.point.equals(pending.point)
                    && last.mapPointUsable == pending.mapPointUsable)
                {
                    return;
                }

                if (last.gestureId == pending.gestureId
                    && (!last.mapPointUsable || !pending.mapPointUsable))
                {
                    pending.breakBefore = true;
                }
            }

            pendingMouseDrags.add(pending);
            simplifyPendingMouseDrags();
        }
    }

    private void simplifyPendingMouseDrags()
    {
        boolean simplified;
        do
        {
            simplified = false;
            for (int runStart = 0; runStart < pendingMouseDrags.size();)
            {
                long gestureId = pendingMouseDrags.get(runStart).gestureId;
                int runEnd = runStart + 1;
                while (runEnd < pendingMouseDrags.size()
                    && pendingMouseDrags.get(runEnd).gestureId == gestureId)
                {
                    runEnd++;
                }

                if (runEnd - runStart <= MAX_PENDING_DRAG_POINTS_PER_GESTURE)
                {
                    runStart = runEnd;
                    continue;
                }

                int removalIndex = runStart + 1;
                double smallestArea = Double.MAX_VALUE;
                for (int i = runStart + 1; i < runEnd - 1; i++)
                {
                    PendingMouseDrag previous = pendingMouseDrags.get(i - 1);
                    PendingMouseDrag current = pendingMouseDrags.get(i);
                    PendingMouseDrag next = pendingMouseDrags.get(i + 1);
                    double area = Math.abs(
                        (current.point.x - previous.point.x) * (double) (next.point.y - previous.point.y)
                            - (current.point.y - previous.point.y) * (double) (next.point.x - previous.point.x));
                    if (area < smallestArea)
                    {
                        smallestArea = area;
                        removalIndex = i;
                    }
                }

                PendingMouseDrag removed = pendingMouseDrags.get(removalIndex);
                if (removed.breakBefore && removalIndex + 1 < runEnd)
                {
                    pendingMouseDrags.get(removalIndex + 1).breakBefore = true;
                }
                pendingMouseDrags.remove(removalIndex);
                simplified = true;
                break;
            }
        }
        while (simplified);
    }

    private List<PendingMouseDrag> drainPendingMouseDrags()
    {
        synchronized (pendingMouseDragLock)
        {
            if (pendingMouseDrags.isEmpty())
            {
                return Collections.emptyList();
            }

            List<PendingMouseDrag> drained = new ArrayList<>(pendingMouseDrags);
            pendingMouseDrags.clear();
            return drained;
        }
    }

    private List<PendingMouseDrag> drainPendingMouseDrags(long gestureId)
    {
        synchronized (pendingMouseDragLock)
        {
            if (pendingMouseDrags.isEmpty())
            {
                return Collections.emptyList();
            }

            List<PendingMouseDrag> drained = new ArrayList<>();
            pendingMouseDrags.removeIf(pending ->
            {
                if (pending.gestureId != gestureId)
                {
                    return false;
                }
                drained.add(pending);
                return true;
            });
            return drained;
        }
    }

    private void prependPendingMouseDrags(List<PendingMouseDrag> pending)
    {
        if (pending == null || pending.isEmpty())
        {
            return;
        }

        synchronized (pendingMouseDragLock)
        {
            pendingMouseDrags.addAll(0, pending);
            simplifyPendingMouseDrags();
        }
    }

    private boolean hasPendingMouseDrags(long gestureId)
    {
        synchronized (pendingMouseDragLock)
        {
            return pendingMouseDrags.stream().anyMatch(pending -> pending.gestureId == gestureId);
        }
    }

    private void discardPendingMouseDrags(long gestureId)
    {
        synchronized (pendingMouseDragLock)
        {
            pendingMouseDrags.removeIf(pending -> pending.gestureId == gestureId);
        }
    }

    private void rejectInputGesture(long gestureId)
    {
        cancelledInputGestureId = Math.max(cancelledInputGestureId, gestureId);
        discardPendingMouseDrags(gestureId);
        if (awtInputGestureId == gestureId)
        {
            inputCaptureActive = false;
            awtInputGestureId = 0L;
        }
        if (clientInputGestureId == gestureId)
        {
            clientInputCaptureActive = false;
            clientInputGestureId = 0L;
            clientInputContextGeneration = 0L;
            lastClientDragPoint = null;
            lastClientDragMapPointUsable = false;
        }
    }

    private void abortActiveStrokesAfterFailure()
    {
        activeSceneStroke = null;
        activeSceneChunkKey = null;
        activeMapStroke = null;
        activeMapChunkKey = null;
        commitUndoAction(pendingUndoAction);
    }

    private void clearPendingMouseDrags()
    {
        synchronized (pendingMouseDragLock)
        {
            pendingMouseDrags.clear();
        }
    }

    boolean handleMouseReleased(MouseEvent event)
    {
        if (!isCanvasEvent(event))
        {
            long gestureId = awtInputGestureId;
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            clientThread.invoke(() -> endClientInputCapture(gestureId, null));
            return false;
        }

        if (getInputMode() == PaintInputMode.NONE || SwingUtilities.isMiddleMouseButton(event))
        {
            long gestureId = awtInputGestureId;
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            clientThread.invoke(() -> endClientInputCapture(gestureId, null));
            return false;
        }

        boolean consumed = inputCaptureActive && (event.getButton() == MouseEvent.BUTTON1 || SwingUtilities.isLeftMouseButton(event));
        long gestureId = awtInputGestureId;
        java.awt.Point releasePoint = consumed ? event.getPoint() : null;
        inputCaptureActive = false;
        awtInputGestureId = 0L;
        clientThread.invoke(() -> endClientInputCapture(gestureId, releasePoint));
        return consumed;
    }

    boolean handleKeyPressed(KeyEvent event)
    {
        if (event == null)
        {
            return false;
        }

        if (event.getKeyCode() == KeyEvent.VK_Z
            && event.isControlDown()
            && !event.isAltDown()
            && !event.isMetaDown()
            && tool != null
            && !(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)
            && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == client.getCanvas())
        {
            undoLastAction();
            return true;
        }

        if (event.getKeyCode() != KeyEvent.VK_ESCAPE)
        {
            return false;
        }

        if (getInputMode() == PaintInputMode.NONE)
        {
            return false;
        }

        exitEditingMode();
        return true;
    }

    private void processMousePressed(
        long gestureId,
        long contextGeneration,
        PaintInputMode acceptedInputMode,
        PaintTool acceptedTool,
        java.awt.Point point)
    {
        if (gestureId <= cancelledInputGestureId
            || contextGeneration != inputContextGeneration.get()
            || acceptedInputMode != getInputMode()
            || acceptedTool == null
            || acceptedTool != tool)
        {
            rejectInputGesture(gestureId);
            return;
        }

        PaintInputMode inputMode = acceptedInputMode;
        if (inputMode == PaintInputMode.NONE || !isInputModeAvailable(inputMode))
        {
            rejectInputGesture(gestureId);
            return;
        }

        if (clientInputCaptureActive && clientInputGestureId != gestureId)
        {
            endClientInputCapture(clientInputGestureId, null);
        }

        clientInputCaptureActive = true;
        clientInputGestureId = gestureId;
        clientInputContextGeneration = contextGeneration;
        lastClientDragPoint = new java.awt.Point(point);
        lastClientDragMapPointUsable = inputMode != PaintInputMode.WORLD_MAP
            || worldMapOverlay.containsCanvasPoint(point.x, point.y);

        if (inputMode == PaintInputMode.SCENE)
        {
            try
            {
                handleScenePress(point);
            }
            catch (RuntimeException ex)
            {
                log.warn("Failed to process paint press input", ex);
                abortActiveStrokesAfterFailure();
                rejectInputGesture(gestureId);
                refreshPanel();
                return;
            }
            if (hasPendingMouseDrags(gestureId))
            {
                scheduleMouseDragProcessing();
            }
            return;
        }

        try
        {
            handleMapPress(point);
        }
        catch (RuntimeException ex)
        {
            log.warn("Failed to process world-map paint press input", ex);
            abortActiveStrokesAfterFailure();
            rejectInputGesture(gestureId);
            refreshPanel();
            return;
        }
        if (hasPendingMouseDrags(gestureId))
        {
            scheduleMouseDragProcessing();
        }
    }

    private boolean processMouseDragged(long gestureId, java.awt.Point point)
    {
        PaintInputMode inputMode = getInputMode();
        if (!isCurrentClientInputGesture(gestureId, inputMode))
        {
            return false;
        }

        java.awt.Point start = lastClientDragPoint;
        lastClientDragPoint = new java.awt.Point(point);
        if (tool == PaintTool.ERASER)
        {
            processMouseEraseSweep(inputMode, start == null ? point : start, point);
            return true;
        }

        if (tool == PaintTool.BRUSH)
        {
            if (inputMode == PaintInputMode.WORLD_MAP
                && start != null
                && !worldMapOverlay.canSweepBetweenCanvasPoints(start.x, start.y, point.x, point.y))
            {
                finishMapStroke();
            }
            processMouseDragPoint(inputMode, point);
        }
        return true;
    }

    private boolean processMouseDragged(PendingMouseDrag pending)
    {
        if (pending == null)
        {
            return false;
        }

        PaintInputMode inputMode = getInputMode();
        if (inputMode == PaintInputMode.WORLD_MAP && !pending.mapPointUsable)
        {
            if (!isCurrentClientInputGesture(pending.gestureId, inputMode))
            {
                return false;
            }

            lastClientDragPoint = new java.awt.Point(pending.point);
            lastClientDragMapPointUsable = false;
            if (tool == PaintTool.BRUSH)
            {
                finishMapStroke();
            }
            return true;
        }

        if (inputMode == PaintInputMode.WORLD_MAP
            && (pending.breakBefore || !lastClientDragMapPointUsable))
        {
            if (!isCurrentClientInputGesture(pending.gestureId, inputMode))
            {
                return false;
            }

            lastClientDragPoint = new java.awt.Point(pending.point);
            lastClientDragMapPointUsable = true;
            if (tool == PaintTool.BRUSH)
            {
                finishMapStroke();
                processMouseDragPoint(inputMode, pending.point);
            }
            else if (tool == PaintTool.ERASER)
            {
                processMouseEraseSweep(inputMode, pending.point, pending.point);
            }
            return true;
        }

        boolean processed = processMouseDragged(pending.gestureId, pending.point);
        if (processed && inputMode == PaintInputMode.WORLD_MAP)
        {
            lastClientDragMapPointUsable = true;
        }
        return processed;
    }

    private boolean isCurrentClientInputGesture(long gestureId, PaintInputMode inputMode)
    {
        return clientInputCaptureActive
            && clientInputGestureId == gestureId
            && clientInputContextGeneration == inputContextGeneration.get()
            && inputMode != PaintInputMode.NONE
            && isInputModeAvailable(inputMode);
    }

    private void processMouseEraseSweep(PaintInputMode inputMode, java.awt.Point start, java.awt.Point end)
    {
        if (inputMode == PaintInputMode.SCENE)
        {
            eraseVisibleScene(start, end);
            return;
        }

        if (!worldMapOverlay.containsCanvasPoint(end.x, end.y))
        {
            return;
        }

        int radius = PaintMath.cursorRadius(brushSize);
        if (!worldMapOverlay.canEraseBetweenCanvasPoints(end.x, end.y, end.x, end.y, radius))
        {
            return;
        }

        eraseVisibleMap(
            worldMapOverlay.canEraseBetweenCanvasPoints(start.x, start.y, end.x, end.y, radius) ? start : end,
            end);
    }

    private void processMouseDragPoint(PaintInputMode inputMode, java.awt.Point point)
    {
        if (inputMode == PaintInputMode.SCENE)
        {
            handleSceneDrag(point);
            return;
        }

        handleMapDrag(point);
    }

    private void endClientInputCapture(long gestureId, java.awt.Point releasePoint)
    {
        if (clientInputGestureId != gestureId)
        {
            return;
        }

        try
        {
            if (clientInputContextGeneration != inputContextGeneration.get())
            {
                return;
            }

            if (clientInputCaptureActive)
            {
                for (PendingMouseDrag pending : drainPendingMouseDrags(gestureId))
                {
                    processMouseDragged(pending);
                }
                if (releasePoint != null)
                {
                    PaintInputMode inputMode = getInputMode();
                    boolean releasePointUsable = inputMode != PaintInputMode.WORLD_MAP
                        || worldMapOverlay.containsCanvasPoint(releasePoint.x, releasePoint.y);
                    if (lastClientDragPoint == null
                        || !lastClientDragPoint.equals(releasePoint)
                        || (inputMode == PaintInputMode.WORLD_MAP
                            && releasePointUsable != lastClientDragMapPointUsable))
                    {
                        processMouseDragged(new PendingMouseDrag(gestureId, releasePoint, releasePointUsable));
                    }
                }
            }

            if (!finalizePendingPaintAction())
            {
                log.warn("Paint input was retained in memory for a later persistence retry");
            }
        }
        catch (RuntimeException ex)
        {
            log.warn("Failed to finalize paint input", ex);
            abortActiveStrokesAfterFailure();
        }
        finally
        {
            discardPendingMouseDrags(gestureId);
            clientInputCaptureActive = false;
            clientInputGestureId = 0L;
            clientInputContextGeneration = 0L;
            lastClientDragPoint = null;
            lastClientDragMapPointUsable = false;
            refreshPanel();
        }
    }

    Collection<PaintChunkData> getVisibleSceneChunks()
    {
        List<PaintChunkData> visible = new ArrayList<>();
        if (!isProfileDataCurrent() || client.getLocalPlayer() == null)
        {
            return visible;
        }

        WorldPoint location = client.getLocalPlayer().getWorldLocation();
        if (location == null)
        {
            return visible;
        }

        int regionX = location.getX() >> 6;
        int regionY = location.getY() >> 6;
        int plane = location.getPlane();

        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int rx = regionX + dx;
                int ry = regionY + dy;
                if (rx < 0 || ry < 0)
                {
                    continue;
                }

                PaintChunkData chunk = getCachedChunkForRender(
                    sceneChunks,
                    sceneChunkKeys,
                    getSceneChunkKey(plane, (rx << 8) | ry));
                if (chunk != null && !chunk.isEmpty())
                {
                    visible.add(chunk);
                }
            }
        }

        return visible;
    }

    Collection<PaintChunkData> getVisibleMapChunks(Collection<Integer> visibleRegionIds)
    {
        List<PaintChunkData> visible = new ArrayList<>();
        if (!isProfileDataCurrent())
        {
            return visible;
        }

        for (String key : getVisibleMapChunkKeys(visibleRegionIds))
        {
            PaintChunkData chunk = getCachedChunkForRender(mapChunks, mapChunkKeys, key);
            if (chunk != null && !chunk.isEmpty())
            {
                visible.add(chunk);
            }
        }
        return visible;
    }

    PaintStroke getActiveSceneStroke()
    {
        return isProfileDataCurrent() ? activeSceneStroke : null;
    }

    PaintStroke getActiveMapStroke()
    {
        return isProfileDataCurrent() ? activeMapStroke : null;
    }

    PaintTarget getScenePreviewTarget()
    {
        Point mouse = getMouseCanvasPosition();
        if (mouse == null)
        {
            return null;
        }
        return findSceneTarget(mouse.getX(), mouse.getY());
    }

    PaintTarget getMapPreviewTarget()
    {
        Point mouse = getMouseCanvasPosition();
        if (mouse == null)
        {
            return null;
        }
        return findMapTarget(mouse.getX(), mouse.getY());
    }

    Point getMouseCanvasPosition()
    {
        Point mouse = previewMouseCanvasPosition;
        return mouse != null ? mouse : client.getMouseCanvasPosition();
    }

    void updatePreviewMouseCanvasPosition(java.awt.Point point)
    {
        if (point == null)
        {
            return;
        }

        previewMouseCanvasPosition = new Point(point.x, point.y);
    }

    private boolean handleScenePress(java.awt.Point point)
    {
        if (tool == PaintTool.ERASER)
        {
            eraseVisibleScene(point);
            return true;
        }

        PaintTarget target = findSceneTarget(point.x, point.y);
        if (target == null)
        {
            if (tool == PaintTool.BRUSH)
            {
                finishSceneStroke();
            }
            return true;
        }

        if (tool == PaintTool.SHAPE)
        {
            placeSceneShape(target);
            return true;
        }

        if (tool == PaintTool.TEXT)
        {
            placeSceneText(target);
            return true;
        }

        beginSceneStroke(target);
        return true;
    }

    private boolean handleSceneDrag(java.awt.Point point)
    {
        if (tool == PaintTool.ERASER)
        {
            eraseVisibleScene(point);
            return true;
        }

        PaintTarget target = findSceneTarget(point.x, point.y);
        if (target == null)
        {
            if (tool == PaintTool.BRUSH)
            {
                finishSceneStroke();
            }
            return true;
        }

        if (tool == PaintTool.BRUSH)
        {
            appendSceneStroke(target);
            return true;
        }

        if (tool == PaintTool.SHAPE)
        {
            return true;
        }

        return false;
    }

    private boolean handleMapPress(java.awt.Point point)
    {
        if (tool == PaintTool.ERASER)
        {
            if (worldMapOverlay.containsCanvasPoint(point.x, point.y))
            {
                eraseVisibleMap(point);
            }
            return true;
        }

        PaintTarget target = findMapTarget(point.x, point.y);
        if (target == null)
        {
            return true;
        }

        if (tool == PaintTool.SHAPE)
        {
            placeMapShape(target);
            return true;
        }

        if (tool == PaintTool.TEXT)
        {
            placeMapText(target);
            return true;
        }

        beginMapStroke(target);
        return true;
    }

    private boolean handleMapDrag(java.awt.Point point)
    {
        if (tool == PaintTool.ERASER)
        {
            if (worldMapOverlay.containsCanvasPoint(point.x, point.y))
            {
                eraseVisibleMap(point);
            }
            return true;
        }

        PaintTarget target = findMapTarget(point.x, point.y);
        if (target == null)
        {
            return true;
        }

        if (tool == PaintTool.BRUSH)
        {
            appendMapStroke(target);
            return true;
        }

        if (tool == PaintTool.SHAPE)
        {
            return true;
        }

        return false;
    }

    private boolean beginSceneStroke(PaintTarget target)
    {
        String chunkKey = getSceneChunkKey(target.plane, target.getRegionId());
        if (!hasStrokeCapacity(sceneChunks, sceneChunkKeys, chunkKey))
        {
            activeSceneStroke = null;
            activeSceneChunkKey = null;
            inputCaptureActive = false;
            clientInputCaptureActive = false;
            refreshPanel();
            return false;
        }

        beginUndoAction();
        activeSceneChunkKey = chunkKey;
        activeSceneStroke = new PaintStroke(target.plane, color, brushSize);
        appendPoint(activeSceneStroke, target);
        return true;
    }

    private void appendSceneStroke(PaintTarget target)
    {
        String chunkKey = getSceneChunkKey(target.plane, target.getRegionId());
        if (activeSceneStroke == null || !chunkKey.equals(activeSceneChunkKey))
        {
            finishSceneStroke();
            beginSceneStroke(target);
            return;
        }

        appendPoint(activeSceneStroke, target);
        rollSceneStrokeSegmentIfNeeded();
    }

    private boolean beginMapStroke(PaintTarget target)
    {
        String chunkKey = getMapChunkKey(target.getRegionId());
        if (!hasStrokeCapacity(mapChunks, mapChunkKeys, chunkKey))
        {
            activeMapStroke = null;
            activeMapChunkKey = null;
            inputCaptureActive = false;
            clientInputCaptureActive = false;
            refreshPanel();
            return false;
        }

        beginUndoAction();
        activeMapChunkKey = chunkKey;
        activeMapStroke = new PaintStroke(0, color, brushSize);
        appendPoint(activeMapStroke, target);
        return true;
    }

    private void appendMapStroke(PaintTarget target)
    {
        String chunkKey = getMapChunkKey(target.getRegionId());
        if (activeMapStroke == null || !chunkKey.equals(activeMapChunkKey))
        {
            finishMapStroke();
            beginMapStroke(target);
            return;
        }

        appendPoint(activeMapStroke, target);
        rollMapStrokeSegmentIfNeeded();
    }

    private void placeSceneText(PaintTarget target)
    {
        String text = PaintMath.sanitizePendingText(pendingText).trim();
        if (text.isEmpty())
        {
            return;
        }

        String key = getSceneChunkKey(target.plane, target.getRegionId());
        if (!hasTextCapacity(sceneChunks, sceneChunkKeys, key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, sceneChunks, sceneChunkKeys, key);
        boolean chunkWasKnown = sceneChunkKeys.contains(key);
        PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, true);
        if (chunk == null)
        {
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            refreshPanel();
            return;
        }
        PaintText paintText = new PaintText(target, color, textSize, fontStyle,
            textBackgroundColor, textBorderColor,
            text);
        chunk.texts.add(paintText);
        try
        {
            saveChunk(sceneChunks, sceneChunkKeys, key);
        }
        catch (RuntimeException ex)
        {
            chunk.texts.remove(paintText);
            removeEmptyUnsavedChunk(sceneChunks, sceneChunkKeys, key, chunk, chunkWasKnown);
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            throw ex;
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void placeSceneShape(PaintTarget target)
    {
        String key = getSceneChunkKey(target.plane, target.getRegionId());
        if (!hasShapeCapacity(sceneChunks, sceneChunkKeys, key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, sceneChunks, sceneChunkKeys, key);
        boolean chunkWasKnown = sceneChunkKeys.contains(key);
        PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, true);
        if (chunk == null)
        {
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            refreshPanel();
            return;
        }
        PaintShape paintShape = new PaintShape(target, color, shapeSize, shapeType);
        chunk.shapes.add(paintShape);
        try
        {
            saveChunk(sceneChunks, sceneChunkKeys, key);
        }
        catch (RuntimeException ex)
        {
            chunk.shapes.remove(paintShape);
            removeEmptyUnsavedChunk(sceneChunks, sceneChunkKeys, key, chunk, chunkWasKnown);
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            throw ex;
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void placeMapText(PaintTarget target)
    {
        String text = PaintMath.sanitizePendingText(pendingText).trim();
        if (text.isEmpty())
        {
            return;
        }

        String key = getMapChunkKey(target.getRegionId());
        if (!hasTextCapacity(mapChunks, mapChunkKeys, key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, mapChunks, mapChunkKeys, key);
        boolean chunkWasKnown = mapChunkKeys.contains(key);
        PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, true);
        if (chunk == null)
        {
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            refreshPanel();
            return;
        }
        PaintText paintText = new PaintText(target, color, textSize, fontStyle,
            textBackgroundColor, textBorderColor,
            text);
        chunk.texts.add(paintText);
        try
        {
            saveChunk(mapChunks, mapChunkKeys, key);
        }
        catch (RuntimeException ex)
        {
            chunk.texts.remove(paintText);
            removeEmptyUnsavedChunk(mapChunks, mapChunkKeys, key, chunk, chunkWasKnown);
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            throw ex;
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void placeMapShape(PaintTarget target)
    {
        String key = getMapChunkKey(target.getRegionId());
        if (!hasShapeCapacity(mapChunks, mapChunkKeys, key))
        {
            refreshPanel();
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        captureUndoSnapshot(undoAction, mapChunks, mapChunkKeys, key);
        boolean chunkWasKnown = mapChunkKeys.contains(key);
        PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, true);
        if (chunk == null)
        {
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            refreshPanel();
            return;
        }
        PaintShape paintShape = new PaintShape(target, color, shapeSize, shapeType);
        chunk.shapes.add(paintShape);
        try
        {
            saveChunk(mapChunks, mapChunkKeys, key);
        }
        catch (RuntimeException ex)
        {
            chunk.shapes.remove(paintShape);
            removeEmptyUnsavedChunk(mapChunks, mapChunkKeys, key, chunk, chunkWasKnown);
            if (pendingUndoAction == undoAction)
            {
                pendingUndoAction = null;
            }
            throw ex;
        }
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void eraseVisibleScene(java.awt.Point mousePoint)
    {
        eraseVisibleScene(mousePoint, mousePoint);
    }

    private void eraseVisibleScene(java.awt.Point cursorStart, java.awt.Point cursorEnd)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (client.getLocalPlayer() == null || worldView == null)
        {
            return;
        }

        PaintUndoAction undoAction = beginUndoAction();
        for (String key : getVisibleSceneChunkKeys())
        {
            PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, false);
            if (chunk == null)
            {
                continue;
            }

            int plane = extractSceneChunkPlane(key);
            if (!canEraseSceneFromChunk(chunk, plane, cursorStart, cursorEnd, worldView))
            {
                continue;
            }

            captureUndoSnapshot(undoAction, sceneChunks, sceneChunkKeys, key);
            if (eraseSceneFromChunk(chunk, plane, cursorStart, cursorEnd, worldView))
            {
                queueChunkPersistence(sceneChunks, sceneChunkKeys, key);
            }
        }
    }

    private void eraseVisibleMap(java.awt.Point mousePoint)
    {
        eraseVisibleMap(mousePoint, mousePoint);
    }

    private void eraseVisibleMap(java.awt.Point cursorStart, java.awt.Point cursorEnd)
    {
        PaintUndoAction undoAction = beginUndoAction();
        for (String key : getVisibleMapChunkKeys(worldMapOverlay.getVisibleRegionIds()))
        {
            PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, false);
            if (chunk == null)
            {
                continue;
            }

            if (!canEraseMapFromChunk(chunk, cursorStart, cursorEnd))
            {
                continue;
            }

            captureUndoSnapshot(undoAction, mapChunks, mapChunkKeys, key);
            if (eraseMapFromChunk(chunk, cursorStart, cursorEnd))
            {
                queueChunkPersistence(mapChunks, mapChunkKeys, key);
            }
        }
    }

    private boolean canEraseSceneFromChunk(
        PaintChunkData chunk,
        int plane,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        WorldView worldView)
    {
        int radius = PaintMath.cursorRadius(brushSize);
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null || stroke.points == null || stroke.points.isEmpty())
            {
                return true;
            }

            if (stroke.plane == plane && strokeIntersectsCursorSweep(
                stroke,
                cursorStart,
                cursorEnd,
                radius + Math.max(1, stroke.width / 2),
                point -> toSceneCanvasPoint(worldView, stroke.plane, point.worldX, point.worldY, point.offsetX, point.offsetY)))
            {
                return true;
            }
        }

        for (PaintShape shape : chunk.shapes)
        {
            if (shape != null
                && shape.plane == plane
                && boundsWithinCursorSweep(sceneShapeBounds(shape, worldView), cursorStart, cursorEnd, radius))
            {
                return true;
            }
        }

        for (PaintText text : chunk.texts)
        {
            if (text != null
                && text.plane == plane
                && textWithinCursorSweep(
                    toSceneCanvasPoint(worldView, text.plane, text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text,
                    cursorStart,
                    cursorEnd,
                    radius))
            {
                return true;
            }
        }

        return false;
    }

    private boolean canEraseMapFromChunk(
        PaintChunkData chunk,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd)
    {
        int radius = PaintMath.cursorRadius(brushSize);
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null || stroke.points == null || stroke.points.isEmpty())
            {
                return true;
            }

            if (strokeIntersectsCursorSweep(
                stroke,
                cursorStart,
                cursorEnd,
                radius + Math.max(1, stroke.width / 2),
                point -> worldMapOverlay.toCanvasPoint(point.worldX, point.worldY, point.offsetX, point.offsetY)))
            {
                return true;
            }
        }

        for (PaintShape shape : chunk.shapes)
        {
            if (shape != null && boundsWithinCursorSweep(mapShapeBounds(shape), cursorStart, cursorEnd, radius))
            {
                return true;
            }
        }

        for (PaintText text : chunk.texts)
        {
            if (text != null
                && mapTextWithinCursorSweep(
                    worldMapOverlay.toCanvasPoint(text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text,
                    cursorStart,
                    cursorEnd,
                    radius))
            {
                return true;
            }
        }

        return false;
    }

    private boolean eraseSceneFromChunk(
        PaintChunkData chunk,
        int plane,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        WorldView worldView)
    {
        int radius = PaintMath.cursorRadius(brushSize);
        boolean changed = false;
        List<PaintStroke> updatedStrokes = new ArrayList<>(chunk.strokes.size());
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null || stroke.points == null || stroke.points.isEmpty())
            {
                changed = true;
                continue;
            }

            if (stroke.plane != plane)
            {
                updatedStrokes.add(stroke);
                continue;
            }

            EraseStrokeResult result = eraseStroke(stroke, cursorStart, cursorEnd,
                radius + Math.max(1, stroke.width / 2),
                point -> toSceneCanvasPoint(worldView, stroke.plane, point.worldX, point.worldY, point.offsetX, point.offsetY));
            changed |= result.changed;
            updatedStrokes.addAll(result.strokes);
        }
        if (changed)
        {
            chunk.strokes.clear();
            chunk.strokes.addAll(updatedStrokes);
        }

        boolean shapeChanged = chunk.shapes.removeIf(shape ->
            shape != null
                && shape.plane == plane
                && boundsWithinCursorSweep(sceneShapeBounds(shape, worldView), cursorStart, cursorEnd, radius));

        boolean textChanged = chunk.texts.removeIf(text ->
            text != null
                && text.plane == plane
                && textWithinCursorSweep(
                    toSceneCanvasPoint(worldView, text.plane, text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text,
                    cursorStart,
                    cursorEnd,
                    radius));

        return changed || shapeChanged || textChanged;
    }

    private boolean eraseMapFromChunk(
        PaintChunkData chunk,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd)
    {
        int radius = PaintMath.cursorRadius(brushSize);
        boolean changed = false;
        List<PaintStroke> updatedStrokes = new ArrayList<>(chunk.strokes.size());
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null || stroke.points == null || stroke.points.isEmpty())
            {
                changed = true;
                continue;
            }

            EraseStrokeResult result = eraseStroke(stroke, cursorStart, cursorEnd,
                radius + Math.max(1, stroke.width / 2),
                point -> worldMapOverlay.toCanvasPoint(point.worldX, point.worldY, point.offsetX, point.offsetY));
            changed |= result.changed;
            updatedStrokes.addAll(result.strokes);
        }
        if (changed)
        {
            chunk.strokes.clear();
            chunk.strokes.addAll(updatedStrokes);
        }

        boolean shapeChanged = chunk.shapes.removeIf(shape ->
            shape != null && boundsWithinCursorSweep(mapShapeBounds(shape), cursorStart, cursorEnd, radius));

        boolean textChanged = chunk.texts.removeIf(text ->
            text != null
                && mapTextWithinCursorSweep(
                    worldMapOverlay.toCanvasPoint(text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text,
                    cursorStart,
                    cursorEnd,
                    radius));

        return changed || shapeChanged || textChanged;
    }

    static EraseStrokeResult eraseStroke(
        PaintStroke stroke,
        int mouseX,
        int mouseY,
        int radius,
        CanvasPointResolver pointResolver)
    {
        java.awt.Point cursor = new java.awt.Point(mouseX, mouseY);
        return eraseStroke(stroke, cursor, cursor, radius, pointResolver);
    }

    static EraseStrokeResult eraseStroke(
        PaintStroke stroke,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        int radius,
        CanvasPointResolver pointResolver)
    {
        if (!strokeIntersectsCursorSweep(stroke, cursorStart, cursorEnd, radius, pointResolver))
        {
            return new EraseStrokeResult(false, Collections.singletonList(stroke));
        }

        PaintStroke remaining = copyStrokeMetadata(stroke);
        Point previousCanvasPoint = null;
        boolean changed = false;
        boolean gapBeforeNextPoint = false;

        for (PaintPoint point : stroke.points)
        {
            if (point == null)
            {
                previousCanvasPoint = null;
                gapBeforeNextPoint = true;
                changed = true;
                continue;
            }

            if (point.startsNewSegment())
            {
                previousCanvasPoint = null;
            }

            Point canvasPoint = pointResolver.resolve(point);
            boolean pointHit = PaintMath.pointWithinCanvasSweep(
                canvasPoint,
                cursorStart.x,
                cursorStart.y,
                cursorEnd.x,
                cursorEnd.y,
                radius);
            boolean segmentHit = previousCanvasPoint != null
                && canvasPoint != null
                && PaintMath.segmentsWithinCanvasRadius(
                    previousCanvasPoint,
                    canvasPoint,
                    cursorStart.x,
                    cursorStart.y,
                    cursorEnd.x,
                    cursorEnd.y,
                    radius);

            if (pointHit)
            {
                changed = true;
                gapBeforeNextPoint = true;
            }
            else
            {
                if (segmentHit)
                {
                    changed = true;
                    gapBeforeNextPoint = true;
                }

                boolean startsNewSegment = (gapBeforeNextPoint || point.startsNewSegment())
                    && !remaining.points.isEmpty();
                addCopiedPoint(remaining, point, startsNewSegment);
                gapBeforeNextPoint = false;
            }

            previousCanvasPoint = canvasPoint;
        }

        if (!changed)
        {
            return new EraseStrokeResult(false, Collections.singletonList(stroke));
        }

        return new EraseStrokeResult(
            true,
            remaining.points.isEmpty() ? Collections.emptyList() : Collections.singletonList(remaining));
    }

    private static boolean strokeIntersectsCursorSweep(
        PaintStroke stroke,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        int radius,
        CanvasPointResolver pointResolver)
    {
        Point previousCanvasPoint = null;
        for (PaintPoint point : stroke.points)
        {
            if (point == null)
            {
                return true;
            }

            if (point.startsNewSegment())
            {
                previousCanvasPoint = null;
            }

            Point canvasPoint = pointResolver.resolve(point);
            if (PaintMath.pointWithinCanvasSweep(
                canvasPoint,
                cursorStart.x,
                cursorStart.y,
                cursorEnd.x,
                cursorEnd.y,
                radius)
                || (previousCanvasPoint != null
                    && canvasPoint != null
                    && PaintMath.segmentsWithinCanvasRadius(
                        previousCanvasPoint,
                        canvasPoint,
                        cursorStart.x,
                        cursorStart.y,
                        cursorEnd.x,
                        cursorEnd.y,
                        radius)))
            {
                return true;
            }

            previousCanvasPoint = canvasPoint;
        }

        return false;
    }

    private static boolean boundsWithinCursorSweep(
        Rectangle2D bounds,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        int radius)
    {
        return PaintMath.rectangleWithinCanvasSweep(
            bounds,
            cursorStart.x,
            cursorStart.y,
            cursorEnd.x,
            cursorEnd.y,
            radius);
    }

    private static boolean textWithinCursorSweep(
        Point baselinePoint,
        PaintText text,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        int radius)
    {
        return PaintMath.textWithinCanvasSweep(
            baselinePoint,
            text.fontStyle.createFont(text.fontSize),
            text.text,
            cursorStart.x,
            cursorStart.y,
            cursorEnd.x,
            cursorEnd.y,
            radius);
    }

    private boolean mapTextWithinCursorSweep(
        Point baselinePoint,
        PaintText text,
        java.awt.Point cursorStart,
        java.awt.Point cursorEnd,
        int radius)
    {
        Font renderedFont = worldMapOverlay.getRenderedTextFont(text);
        return PaintMath.textWithinCanvasSweep(
            baselinePoint,
            renderedFont,
            text.text,
            cursorStart.x,
            cursorStart.y,
            cursorEnd.x,
            cursorEnd.y,
            radius);
    }

    private static PaintStroke copyStrokeMetadata(PaintStroke stroke)
    {
        PaintStroke copy = new PaintStroke();
        copy.plane = stroke.plane;
        copy.colorArgb = stroke.colorArgb;
        copy.width = stroke.width;
        return copy;
    }

    private static void addCopiedPoint(PaintStroke stroke, PaintPoint source)
    {
        addCopiedPoint(stroke, source, source.startsNewSegment());
    }

    private static void addCopiedPoint(PaintStroke stroke, PaintPoint source, boolean startsNewSegment)
    {
        PaintPoint point = new PaintPoint();
        point.worldX = source.worldX;
        point.worldY = source.worldY;
        point.offsetX = source.offsetX;
        point.offsetY = source.offsetY;
        point.startsNewSegment = startsNewSegment ? Boolean.TRUE : null;

        if (!stroke.points.isEmpty())
        {
            PaintPoint previous = stroke.points.get(stroke.points.size() - 1);
            if (previous.worldX == point.worldX
                && previous.worldY == point.worldY
                && previous.offsetX == point.offsetX
                && previous.offsetY == point.offsetY
                && !startsNewSegment)
            {
                return;
            }
        }

        stroke.points.add(point);
    }

    private void appendPoint(PaintStroke stroke, PaintTarget target)
    {
        if (stroke.points.isEmpty())
        {
            stroke.points.add(new PaintPoint(target));
            return;
        }

        PaintPoint lastPoint = stroke.points.get(stroke.points.size() - 1);
        if (lastPoint.worldX == target.worldX
            && lastPoint.worldY == target.worldY
            && Math.abs(lastPoint.offsetX - target.offsetX) <= 1
            && Math.abs(lastPoint.offsetY - target.offsetY) <= 1)
        {
            return;
        }

        double startX = lastPoint.getContinuousX();
        double startY = lastPoint.getContinuousY();
        double endX = target.getContinuousX();
        double endY = target.getContinuousY();
        double distance = Math.hypot(endX - startX, endY - startY);
        int interpolationSteps = Math.max(
            1,
            Math.min(MAX_INTERPOLATION_STEPS_PER_APPEND, (int) Math.ceil(distance / 0.15)));
        for (int i = 1; i < interpolationSteps; i++)
        {
            double t = i / (double) interpolationSteps;
            stroke.points.add(interpolatePoint(startX, startY, endX, endY, t));
        }

        stroke.points.add(new PaintPoint(target));
    }

    private void rollSceneStrokeSegmentIfNeeded()
    {
        while (activeSceneStroke != null
            && activeSceneChunkKey != null
            && activeSceneStroke.points.size() > MAX_POINTS_PER_STROKE)
        {
            PaintStroke segment = firstStrokeSegment(activeSceneStroke);
            PaintStroke continuation = remainingStrokeSegment(activeSceneStroke);
            if (!persistStrokeSegment(sceneChunks, sceneChunkKeys, activeSceneChunkKey, segment)
                || !hasStrokeCapacity(sceneChunks, sceneChunkKeys, activeSceneChunkKey))
            {
                activeSceneStroke = null;
                activeSceneChunkKey = null;
                inputCaptureActive = false;
                clientInputCaptureActive = false;
                refreshPanel();
                return;
            }

            activeSceneStroke = continuation;
        }
    }

    private void rollMapStrokeSegmentIfNeeded()
    {
        while (activeMapStroke != null
            && activeMapChunkKey != null
            && activeMapStroke.points.size() > MAX_POINTS_PER_STROKE)
        {
            PaintStroke segment = firstStrokeSegment(activeMapStroke);
            PaintStroke continuation = remainingStrokeSegment(activeMapStroke);
            if (!persistStrokeSegment(mapChunks, mapChunkKeys, activeMapChunkKey, segment)
                || !hasStrokeCapacity(mapChunks, mapChunkKeys, activeMapChunkKey))
            {
                activeMapStroke = null;
                activeMapChunkKey = null;
                inputCaptureActive = false;
                clientInputCaptureActive = false;
                refreshPanel();
                return;
            }

            activeMapStroke = continuation;
        }
    }

    private static PaintStroke firstStrokeSegment(PaintStroke stroke)
    {
        PaintStroke segment = copyStrokeMetadata(stroke);
        segment.points.addAll(stroke.points.subList(0, MAX_POINTS_PER_STROKE));
        return segment;
    }

    private static PaintStroke remainingStrokeSegment(PaintStroke stroke)
    {
        PaintStroke continuation = copyStrokeMetadata(stroke);
        continuation.points.add(stroke.points.get(MAX_POINTS_PER_STROKE - 1));
        continuation.points.addAll(stroke.points.subList(MAX_POINTS_PER_STROKE, stroke.points.size()));
        return continuation;
    }

    private void finishActiveStrokes()
    {
        finishSceneStroke();
        finishMapStroke();
    }

    private boolean finalizePendingPaintAction()
    {
        try
        {
            finishActiveStrokes();
            boolean persisted = flushQueuedChunkPersistence();
            commitUndoAction(pendingUndoAction);
            return persisted;
        }
        catch (RuntimeException ex)
        {
            log.warn("Failed to persist the active paint action", ex);
            abortActiveStrokesAfterFailure();
            return false;
        }
    }

    private void finishSceneStroke()
    {
        if (activeSceneStroke == null || activeSceneChunkKey == null || activeSceneStroke.points.isEmpty())
        {
            activeSceneStroke = null;
            activeSceneChunkKey = null;
            return;
        }

        persistStrokeSegment(sceneChunks, sceneChunkKeys, activeSceneChunkKey, activeSceneStroke);
        activeSceneStroke = null;
        activeSceneChunkKey = null;
    }

    private void finishMapStroke()
    {
        if (activeMapStroke == null || activeMapChunkKey == null || activeMapStroke.points.isEmpty())
        {
            activeMapStroke = null;
            activeMapChunkKey = null;
            return;
        }

        persistStrokeSegment(mapChunks, mapChunkKeys, activeMapChunkKey, activeMapStroke);
        activeMapStroke = null;
        activeMapChunkKey = null;
    }

    private boolean persistStrokeSegment(Map<String, PaintChunkData> cache, Set<String> knownKeys, String chunkKey, PaintStroke stroke)
    {
        if (stroke == null || chunkKey == null || stroke.points == null || stroke.points.isEmpty())
        {
            return false;
        }

        if (!hasStrokeCapacity(cache, knownKeys, chunkKey, stroke.points.size()))
        {
            return false;
        }

        PaintUndoAction undoAction = beginUndoAction();
        boolean undoAlreadyCaptured = undoAction.hasSnapshot(chunkKey);
        boolean chunkWasKnown = knownKeys.contains(chunkKey);
        PaintChunkData chunk = null;
        boolean strokeAdded = false;
        try
        {
            captureUndoSnapshot(undoAction, cache, knownKeys, chunkKey);
            chunk = getOrCreateChunk(cache, knownKeys, chunkKey, true);
            if (chunk == null)
            {
                discardFailedUndoSnapshot(undoAction, chunkKey, undoAlreadyCaptured);
                return false;
            }
            chunk.strokes.add(stroke);
            strokeAdded = true;
            saveChunk(cache, knownKeys, chunkKey);
            return true;
        }
        catch (RuntimeException ex)
        {
            if (strokeAdded)
            {
                pendingChunkPersistenceKeys.add(chunkKey);
                log.warn("Deferring persistence for paint stroke chunk {}", chunkKey, ex);
                return true;
            }
            removeEmptyUnsavedChunk(cache, knownKeys, chunkKey, chunk, chunkWasKnown);
            discardFailedUndoSnapshot(undoAction, chunkKey, undoAlreadyCaptured);
            throw ex;
        }
    }

    private void discardFailedUndoSnapshot(PaintUndoAction undoAction, String chunkKey, boolean undoAlreadyCaptured)
    {
        if (undoAction == null || undoAlreadyCaptured)
        {
            return;
        }

        undoAction.removeSnapshot(chunkKey);
        if (undoAction.isEmpty() && pendingUndoAction == undoAction)
        {
            pendingUndoAction = null;
        }
    }

    private static void removeEmptyUnsavedChunk(
        Map<String, PaintChunkData> cache,
        Set<String> knownKeys,
        String key,
        PaintChunkData chunk,
        boolean chunkWasKnown)
    {
        if (!chunkWasKnown && chunk != null && chunk.isEmpty())
        {
            cache.remove(key);
            knownKeys.remove(key);
        }
    }

    private void queueChunkPersistence(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        if (key == null)
        {
            return;
        }

        PaintChunkData chunk = cache.get(key);
        cleanChunk(chunk);
        if (chunk == null || chunk.isEmpty())
        {
            cache.remove(key);
            knownKeys.remove(key);
        }
        else
        {
            knownKeys.add(key);
        }

        pendingChunkPersistenceKeys.add(key);
    }

    private boolean flushQueuedChunkPersistence()
    {
        if (pendingChunkPersistenceKeys.isEmpty())
        {
            return true;
        }

        boolean allPersisted = true;
        List<String> pendingKeys = new ArrayList<>(pendingChunkPersistenceKeys);
        for (String key : pendingKeys)
        {
            Map<String, PaintChunkData> cache = key.startsWith(SCENE_PREFIX) ? sceneChunks : mapChunks;
            Set<String> knownKeys = key.startsWith(SCENE_PREFIX) ? sceneChunkKeys : mapChunkKeys;
            try
            {
                saveChunk(cache, knownKeys, key);
                pendingChunkPersistenceKeys.remove(key);
            }
            catch (RuntimeException ex)
            {
                allPersisted = false;
                log.warn("Failed to persist queued paint chunk {}", key, ex);
            }
        }
        return allPersisted;
    }

    private void cleanChunk(PaintChunkData chunk)
    {
        if (chunk == null)
        {
            return;
        }

        chunk.strokes.removeIf(stroke -> stroke == null || stroke.points == null || stroke.points.isEmpty());
        chunk.shapes.removeIf(shape -> shape == null || shape.shapeType == null || shape.size < MIN_SHAPE_SIZE);
        chunk.texts.removeIf(text -> text == null || text.text == null || text.text.trim().isEmpty() || text.fontStyle == null);
    }

    private boolean hasStrokeCapacity(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        return hasStrokeCapacity(cache, knownKeys, key, 1);
    }

    private boolean hasStrokeCapacity(
        Map<String, PaintChunkData> cache,
        Set<String> knownKeys,
        String key,
        int additionalPointCount)
    {
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }

        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }
        cleanChunk(chunk);
        if (chunk == null)
        {
            return true;
        }

        if (chunk.strokes.size() >= MAX_STROKES_PER_CHUNK)
        {
            return false;
        }

        long totalPointCount = 0L;
        for (PaintStroke stroke : chunk.strokes)
        {
            totalPointCount += stroke.points.size();
        }
        return totalPointCount + Math.max(0, additionalPointCount) <= MAX_LOADED_TOTAL_POINTS_PER_CHUNK;
    }

    private boolean hasShapeCapacity(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }

        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }
        cleanChunk(chunk);
        return chunk == null || chunk.shapes.size() < MAX_SHAPES_PER_CHUNK;
    }

    private boolean hasTextCapacity(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }

        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        if (corruptChunkKeys.contains(key))
        {
            return false;
        }
        cleanChunk(chunk);
        return chunk == null || chunk.texts.size() < MAX_TEXTS_PER_CHUNK;
    }

    private PaintUndoAction beginUndoAction()
    {
        if (pendingUndoAction == null)
        {
            pendingUndoAction = new PaintUndoAction(loadedRsProfileKey);
        }

        return pendingUndoAction;
    }

    private void captureUndoSnapshot(PaintUndoAction undoAction, Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        if (undoAction == null || key == null || undoAction.hasSnapshot(key))
        {
            return;
        }

        if (corruptChunkKeys.contains(key))
        {
            undoAction.addSnapshot(key, getStoredChunkPayload(key), true);
            return;
        }

        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        if (corruptChunkKeys.contains(key))
        {
            undoAction.addSnapshot(key, getStoredChunkPayload(key), true);
            return;
        }

        if (chunk == null)
        {
            undoAction.addSnapshot(key, null);
            return;
        }

        String storedPayload = pendingChunkPersistenceKeys.contains(key) ? null : getStoredChunkPayload(key);
        undoAction.addSnapshot(
            key,
            storedPayload != null ? storedPayload : encodeChunkPayload(serializeChunkForStorage(chunk)));
    }

    private void commitUndoAction(PaintUndoAction undoAction)
    {
        if (undoAction == null)
        {
            pendingUndoAction = null;
            return;
        }

        if (!undoAction.isEmpty())
        {
            undoHistory.addFirst(undoAction);
            while (undoHistory.size() > MAX_UNDO_ACTIONS)
            {
                undoHistory.removeLast();
            }
        }

        if (pendingUndoAction == undoAction)
        {
            pendingUndoAction = null;
        }
    }

    private void undoLastActionOnClientThread()
    {
        finalizePendingPaintAction();
        PaintUndoAction action = undoHistory.pollFirst();
        if (action == null)
        {
            refreshPanel();
            return;
        }

        if (!profileKeysEqual(action.rsProfileKey, loadedRsProfileKey))
        {
            undoHistory.clear();
            refreshPanel();
            return;
        }

        for (PaintUndoChunkSnapshot snapshot : action.snapshots)
        {
            restoreUndoSnapshot(snapshot);
        }
        refreshPanel();
    }

    private void restoreUndoSnapshot(PaintUndoChunkSnapshot snapshot)
    {
        if (snapshot == null || snapshot.key == null)
        {
            return;
        }

        Map<String, PaintChunkData> cache = snapshot.key.startsWith(SCENE_PREFIX) ? sceneChunks : mapChunks;
        Set<String> knownKeys = snapshot.key.startsWith(SCENE_PREFIX) ? sceneChunkKeys : mapChunkKeys;

        if (snapshot.payload == null || snapshot.payload.trim().isEmpty())
        {
            removeChunk(cache, knownKeys, snapshot.key);
            return;
        }

        if (snapshot.rawPayload)
        {
            cache.remove(snapshot.key);
            knownKeys.add(snapshot.key);
            corruptChunkKeys.add(snapshot.key);
            pendingChunkPersistenceKeys.remove(snapshot.key);
            if (loadedRsProfileKey != null)
            {
                configManager.setConfiguration(
                    PaintOverlaysConfig.GROUP,
                    loadedRsProfileKey,
                    snapshot.key,
                    snapshot.payload);
            }
            return;
        }

        PaintChunkData chunk = deserializeChunk(snapshot.payload);
        if (chunk == null || chunk.isEmpty())
        {
            removeChunk(cache, knownKeys, snapshot.key);
            return;
        }

        cache.put(snapshot.key, chunk);
        knownKeys.add(snapshot.key);
        corruptChunkKeys.remove(snapshot.key);
        saveChunk(cache, knownKeys, snapshot.key);
    }

    private String getStoredChunkPayload(String key)
    {
        if (loadedRsProfileKey == null || key == null)
        {
            return null;
        }

        return configManager.getConfiguration(PaintOverlaysConfig.GROUP, loadedRsProfileKey, key);
    }

    private void reloadAllChunks()
    {
        profileReloadPending = true;
        chunkLoadGeneration++;
        loadingChunkKeys.clear();
        String nextRsProfileKey = null;
        try
        {
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            cancelledInputGestureId = inputGestureSequence.get();
            inputContextGeneration.incrementAndGet();
            clientInputCaptureActive = false;
            clientInputGestureId = 0L;
            clientInputContextGeneration = 0L;
            clearPendingMouseDrags();
            lastClientDragPoint = null;
            lastClientDragMapPointUsable = false;
            nextRsProfileKey = configManager.getRSProfileKey();
            observedRsProfileKey = nextRsProfileKey;
            if (!finalizePendingPaintAction())
            {
                log.warn("Deferring profile reload until pending paint can be persisted");
                scheduleProfileReloadRetry();
                return;
            }

            Set<String> nextSceneChunkKeys = new HashSet<>();
            Set<String> nextMapChunkKeys = new HashSet<>();
            if (nextRsProfileKey != null)
            {
                nextSceneChunkKeys.addAll(configManager.getRSProfileConfigurationKeys(
                    PaintOverlaysConfig.GROUP, nextRsProfileKey, SCENE_PREFIX));
                nextMapChunkKeys.addAll(configManager.getRSProfileConfigurationKeys(
                    PaintOverlaysConfig.GROUP, nextRsProfileKey, MAP_PREFIX));
            }

            sceneChunks.clear();
            mapChunks.clear();
            sceneChunkKeys.clear();
            sceneChunkKeys.addAll(nextSceneChunkKeys);
            mapChunkKeys.clear();
            mapChunkKeys.addAll(nextMapChunkKeys);
            pendingChunkPersistenceKeys.clear();
            corruptChunkKeys.clear();
            pendingUndoAction = null;
            undoHistory.clear();
            cachedUndoAvailable = false;
            invalidateSceneTargetCache();
            loadedRsProfileKey = nextRsProfileKey;
            profileReloadRetryPending = false;
            profileReloadRetryAttempts = 0;
            nextProfileReloadRetryNanos = 0L;
            updateCachedStatusChunkKeys(worldMapOpen);
        }
        catch (RuntimeException ex)
        {
            observedRsProfileKey = nextRsProfileKey;
            scheduleProfileReloadRetry();
            log.warn("Failed to reload paint chunks for the active profile", ex);
        }
        finally
        {
            profileReloadPending = false;
            refreshPanel();
        }
    }

    private void scheduleProfileReloadRetry()
    {
        profileReloadRetryAttempts = Math.min(profileReloadRetryAttempts + 1, 30);
        int shift = Math.min(profileReloadRetryAttempts - 1, 5);
        long delayNanos = Math.min(
            PROFILE_RELOAD_RETRY_MAX_NANOS,
            PROFILE_RELOAD_RETRY_BASE_NANOS << shift);
        profileReloadRetryPending = true;
        nextProfileReloadRetryNanos = System.nanoTime() + delayNanos;
    }

    private PaintChunkData getCachedChunkForRender(
        Map<String, PaintChunkData> cache,
        Set<String> knownKeys,
        String key)
    {
        if (key == null || corruptChunkKeys.contains(key))
        {
            return null;
        }

        PaintChunkData chunk = cache.get(key);
        if (chunk == null && knownKeys.contains(key))
        {
            requestChunkLoadAsync(key);
        }
        return chunk;
    }

    private void requestChunkLoadAsync(String key)
    {
        if (key == null
            || executor == null
            || clientThread == null
            || configManager == null
            || loadedRsProfileKey == null
            || corruptChunkKeys.contains(key)
            || !loadingChunkKeys.add(key))
        {
            return;
        }

        String profileKey = loadedRsProfileKey;
        long generation = chunkLoadGeneration;
        try
        {
            executor.execute(() ->
            {
                try
                {
                    String payload = configManager.getConfiguration(
                        PaintOverlaysConfig.GROUP,
                        profileKey,
                        key);
                    PaintChunkData chunk = null;
                    if (payload != null && !payload.trim().isEmpty())
                    {
                        chunk = deserializeChunk(payload);
                        if (chunk == null)
                        {
                            throw new IllegalArgumentException("Paint chunk decoded to null");
                        }
                    }
                    completeChunkLoadAsync(key, profileKey, generation, chunk, null);
                }
                catch (RuntimeException ex)
                {
                    completeChunkLoadAsync(key, profileKey, generation, null, ex);
                }
            });
        }
        catch (RuntimeException ex)
        {
            loadingChunkKeys.remove(key);
            log.debug("Paint chunk load could not be scheduled for {}", key, ex);
        }
    }

    private void completeChunkLoadAsync(
        String key,
        String profileKey,
        long generation,
        PaintChunkData chunk,
        RuntimeException failure)
    {
        clientThread.invokeLater(() ->
        {
            if (generation != chunkLoadGeneration || !profileKeysEqual(profileKey, loadedRsProfileKey))
            {
                return;
            }

            loadingChunkKeys.remove(key);
            Map<String, PaintChunkData> cache = key.startsWith(SCENE_PREFIX) ? sceneChunks : mapChunks;
            Set<String> knownKeys = key.startsWith(SCENE_PREFIX) ? sceneChunkKeys : mapChunkKeys;
            if (!knownKeys.contains(key))
            {
                return;
            }
            if (cache.containsKey(key)
                || pendingChunkPersistenceKeys.contains(key)
                || corruptChunkKeys.contains(key))
            {
                // A synchronous edit, clear, or undo won the race with this background read.
                // Never let the older payload replace newer client-thread state.
                return;
            }

            if (failure != null)
            {
                corruptChunkKeys.add(key);
                cache.remove(key);
                log.warn("Failed to load paint chunk {}", key, failure);
            }
            else if (chunk == null)
            {
                cache.remove(key);
                knownKeys.remove(key);
            }
            else
            {
                cache.put(key, chunk);
                corruptChunkKeys.remove(key);
            }
            refreshPanel();
        });
    }

    private PaintChunkData getOrCreateChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key, boolean create)
    {
        if (key == null || corruptChunkKeys.contains(key))
        {
            return null;
        }

        PaintChunkData cached = cache.get(key);
        if (cached != null)
        {
            return cached;
        }

        if (!knownKeys.contains(key) && !create)
        {
            return null;
        }

        PaintChunkData chunk = loadChunk(key);
        if (chunk == null)
        {
            if (corruptChunkKeys.contains(key))
            {
                return null;
            }

            if (!create)
            {
                return null;
            }

            chunk = new PaintChunkData();
        }

        cache.put(key, chunk);
        if (create)
        {
            knownKeys.add(key);
        }
        return chunk;
    }

    private PaintChunkData loadChunk(String key)
    {
        if (loadedRsProfileKey == null)
        {
            return null;
        }

        String json = configManager.getConfiguration(PaintOverlaysConfig.GROUP, loadedRsProfileKey, key);
        if (json == null || json.trim().isEmpty())
        {
            return null;
        }

        try
        {
            PaintChunkData chunk = deserializeChunk(json);
            if (chunk == null)
            {
                throw new IllegalArgumentException("Paint chunk decoded to null");
            }
            return chunk;
        }
        catch (RuntimeException ex)
        {
            corruptChunkKeys.add(key);
            log.warn("Failed to load paint chunk {}", key, ex);
            return null;
        }
    }

    private PaintChunkData deserializeChunk(String json)
    {
        String decodedJson = decodeChunkPayload(json);
        PaintChunkData chunk = gson.fromJson(decodedJson, PaintChunkData.class);
        if (chunk == null)
        {
            return null;
        }

        if (chunk.strokes == null)
        {
            chunk.strokes = new ArrayList<>();
        }
        if (chunk.shapes == null)
        {
            chunk.shapes = new ArrayList<>();
        }
        if (chunk.texts == null)
        {
            chunk.texts = new ArrayList<>();
        }
        validateChunkSafety(chunk);
        normalizeChunkValues(chunk);
        chunk.normalizeLoadedState();
        cleanChunk(chunk);
        return chunk;
    }

    private static void validateChunkSafety(PaintChunkData chunk)
    {
        if (chunk.strokes.size() > MAX_LOADED_STROKES_PER_CHUNK
            || chunk.shapes.size() > MAX_LOADED_SHAPES_PER_CHUNK
            || chunk.texts.size() > MAX_LOADED_TEXTS_PER_CHUNK)
        {
            throw new IllegalArgumentException("Paint chunk exceeds safe collection limits");
        }

        int totalPoints = 0;
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null)
            {
                continue;
            }
            validatePlane(stroke.plane);
            if (stroke.points != null)
            {
                if (stroke.points.size() > MAX_LOADED_POINTS_PER_STROKE)
                {
                    throw new IllegalArgumentException("Paint stroke exceeds safe point limit");
                }
                totalPoints += stroke.points.size();
                if (totalPoints > MAX_LOADED_TOTAL_POINTS_PER_CHUNK)
                {
                    throw new IllegalArgumentException("Paint chunk exceeds safe total point limit");
                }
            }
        }

        for (PaintShape shape : chunk.shapes)
        {
            if (shape != null)
            {
                validatePlane(shape.plane);
                if (shape.shapeType == null)
                {
                    throw new IllegalArgumentException("Paint shape has an unsupported type");
                }
            }
        }

        for (PaintText text : chunk.texts)
        {
            if (text == null)
            {
                continue;
            }
            validatePlane(text.plane);
            if (text.fontStyle == null)
            {
                throw new IllegalArgumentException("Paint text has an unsupported font style");
            }
            if (text.text != null && text.text.length() > MAX_LOADED_TEXT_LENGTH)
            {
                throw new IllegalArgumentException("Paint text exceeds safe length limit");
            }
        }
    }

    private static void validatePlane(int plane)
    {
        if (plane < 0 || plane >= Constants.MAX_Z)
        {
            throw new IllegalArgumentException("Paint entity has an invalid plane");
        }
    }

    private static void normalizeChunkValues(PaintChunkData chunk)
    {
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke == null || stroke.points == null)
            {
                continue;
            }

            stroke.points.removeIf(Objects::isNull);
            stroke.width = clampBrushSize(stroke.width);
            for (PaintPoint point : stroke.points)
            {
                point.offsetX = clampOffset(point.offsetX);
                point.offsetY = clampOffset(point.offsetY);
            }
        }

        for (PaintShape shape : chunk.shapes)
        {
            if (shape == null)
            {
                continue;
            }

            shape.size = clampShapeSize(shape.size);
            shape.offsetX = clampOffset(shape.offsetX);
            shape.offsetY = clampOffset(shape.offsetY);
        }

        for (PaintText text : chunk.texts)
        {
            if (text == null)
            {
                continue;
            }

            text.fontSize = clampTextSize(text.fontSize);
            text.offsetX = clampOffset(text.offsetX);
            text.offsetY = clampOffset(text.offsetY);
        }
    }

    private void saveChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        if (key == null || corruptChunkKeys.contains(key))
        {
            throw new IllegalStateException("Cannot save an invalid or corrupt paint chunk");
        }
        if (loadedRsProfileKey == null)
        {
            throw new IllegalStateException("Cannot save paint without an active RuneScape profile");
        }

        PaintChunkData chunk = cache.get(key);
        cleanChunk(chunk);
        if (chunk == null || chunk.isEmpty())
        {
            removeChunk(cache, knownKeys, key);
            return;
        }

        knownKeys.add(key);
        configManager.setConfiguration(
            PaintOverlaysConfig.GROUP,
            loadedRsProfileKey,
            key,
            encodeChunkPayload(serializeChunkForStorage(chunk)));
    }

    private String serializeChunkForStorage(PaintChunkData chunk)
    {
        PaintChunkData persisted = new PaintChunkData();
        for (PaintStroke stroke : chunk.strokes)
        {
            PaintStroke fragment = null;
            for (PaintPoint point : stroke.points)
            {
                if (fragment == null || (point.startsNewSegment() && !fragment.points.isEmpty()))
                {
                    fragment = copyStrokeMetadata(stroke);
                    persisted.strokes.add(fragment);
                }
                addCopiedPoint(fragment, point, false);
            }
        }
        persisted.shapes.addAll(chunk.shapes);
        persisted.texts.addAll(chunk.texts);
        return gson.toJson(persisted);
    }

    private void removeChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        cache.remove(key);
        knownKeys.remove(key);
        corruptChunkKeys.remove(key);
        if (loadedRsProfileKey == null)
        {
            return;
        }

        configManager.unsetConfiguration(PaintOverlaysConfig.GROUP, loadedRsProfileKey, key);
    }

    private String getSceneChunkKey(int plane, int regionId)
    {
        return SCENE_PREFIX + plane + "." + regionId;
    }

    private String getMapChunkKey(int regionId)
    {
        return MAP_PREFIX + regionId;
    }

    private List<String> getVisibleMapChunkKeys(Collection<Integer> visibleRegionIds)
    {
        if (visibleRegionIds == null || visibleRegionIds.isEmpty() || mapChunkKeys.isEmpty())
        {
            return Collections.emptyList();
        }

        List<String> visibleKeys = new ArrayList<>(Math.min(visibleRegionIds.size(), mapChunkKeys.size()));
        for (Integer regionId : visibleRegionIds)
        {
            if (regionId == null)
            {
                continue;
            }

            String key = getMapChunkKey(regionId);
            if (mapChunkKeys.contains(key))
            {
                visibleKeys.add(key);
            }
        }
        return visibleKeys;
    }

    PaintTarget findSceneTarget(int mouseX, int mouseY)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

        if (isSceneTargetCacheMatch(worldView, mouseX, mouseY))
        {
            return sceneTargetCacheResult;
        }

        PaintTarget target = resolveSceneTarget(worldView, mouseX, mouseY);
        cacheSceneTarget(worldView, mouseX, mouseY, target);
        if (target != null)
        {
            lastSceneSearchTarget = target;
            cachedSceneStatusChunkKey = getSceneChunkKey(target.plane, target.getRegionId());
        }
        return target;
    }

    private PaintTarget resolveSceneTarget(WorldView worldView, int mouseX, int mouseY)
    {

        Tile hitTile = findSceneTileAtMouse(worldView, mouseX, mouseY);
        if (hitTile == null)
        {
            return null;
        }

        LocalPoint localLocation = hitTile.getLocalLocation();
        if (localLocation == null)
        {
            return null;
        }

        Polygon tilePoly = Perspective.getCanvasTilePoly(client, localLocation);
        if (tilePoly == null || tilePoly.npoints != 4 || !tilePoly.contains(mouseX, mouseY))
        {
            return null;
        }

        double[] uv = inverseQuad(tilePoly, mouseX, mouseY);
        if (uv == null)
        {
            return null;
        }

        WorldPoint worldLocation = hitTile.getWorldLocation();
        if (worldLocation == null)
        {
            worldLocation = WorldPoint.fromLocalInstance(client, localLocation, hitTile.getPlane());
        }
        if (worldLocation == null)
        {
            return null;
        }

        return new PaintTarget(
            worldLocation.getX(),
            worldLocation.getY(),
            worldLocation.getPlane(),
            clampOffset((int) Math.round(uv[1] * 128.0)),
            clampOffset((int) Math.round(uv[0] * 128.0)));
    }

    PaintTarget findMapTarget(int mouseX, int mouseY)
    {
        PaintTarget target = worldMapOverlay.getTarget(mouseX, mouseY);
        if (target != null)
        {
            cachedMapStatusChunkKey = getMapChunkKey(target.getRegionId());
        }
        return target;
    }

    private boolean isSceneTargetCacheMatch(WorldView worldView, int mouseX, int mouseY)
    {
        return sceneTargetCacheValid
            && (sceneTargetCacheResult != null || sceneTargetCacheTick == client.getTickCount())
            && sceneTargetCacheWorldView == worldView
            && sceneTargetCacheMouseX == mouseX
            && sceneTargetCacheMouseY == mouseY
            && sceneTargetCacheCameraX == client.getCameraX()
            && sceneTargetCacheCameraY == client.getCameraY()
            && sceneTargetCacheCameraZ == client.getCameraZ()
            && sceneTargetCacheCameraPitch == client.getCameraPitch()
            && sceneTargetCacheCameraYaw == client.getCameraYaw()
            && sceneTargetCacheBaseX == worldView.getBaseX()
            && sceneTargetCacheBaseY == worldView.getBaseY()
            && sceneTargetCachePlane == worldView.getPlane()
            && sceneTargetCacheViewportX == client.getViewportXOffset()
            && sceneTargetCacheViewportY == client.getViewportYOffset()
            && sceneTargetCacheViewportWidth == client.getViewportWidth()
            && sceneTargetCacheViewportHeight == client.getViewportHeight()
            && sceneTargetCacheCanvasWidth == client.getCanvasWidth()
            && sceneTargetCacheCanvasHeight == client.getCanvasHeight();
    }

    private void cacheSceneTarget(WorldView worldView, int mouseX, int mouseY, PaintTarget result)
    {
        sceneTargetCacheValid = true;
        sceneTargetCacheWorldView = worldView;
        sceneTargetCacheResult = result;
        sceneTargetCacheTick = client.getTickCount();
        sceneTargetCacheMouseX = mouseX;
        sceneTargetCacheMouseY = mouseY;
        sceneTargetCacheCameraX = client.getCameraX();
        sceneTargetCacheCameraY = client.getCameraY();
        sceneTargetCacheCameraZ = client.getCameraZ();
        sceneTargetCacheCameraPitch = client.getCameraPitch();
        sceneTargetCacheCameraYaw = client.getCameraYaw();
        sceneTargetCacheBaseX = worldView.getBaseX();
        sceneTargetCacheBaseY = worldView.getBaseY();
        sceneTargetCachePlane = worldView.getPlane();
        sceneTargetCacheViewportX = client.getViewportXOffset();
        sceneTargetCacheViewportY = client.getViewportYOffset();
        sceneTargetCacheViewportWidth = client.getViewportWidth();
        sceneTargetCacheViewportHeight = client.getViewportHeight();
        sceneTargetCacheCanvasWidth = client.getCanvasWidth();
        sceneTargetCacheCanvasHeight = client.getCanvasHeight();
    }

    private void invalidateSceneTargetCache()
    {
        sceneTargetCacheValid = false;
        sceneTargetCacheWorldView = null;
        sceneTargetCacheResult = null;
    }

    private static double[] inverseQuad(java.awt.Polygon polygon, int mouseX, int mouseY)
    {
        double ax = polygon.xpoints[0];
        double ay = polygon.ypoints[0];
        double bx = polygon.xpoints[1];
        double by = polygon.ypoints[1];
        double cx = polygon.xpoints[2];
        double cy = polygon.ypoints[2];
        double dx = polygon.xpoints[3];
        double dy = polygon.ypoints[3];

        double u = 0.5;
        double v = 0.5;

        for (int i = 0; i < 8; i++)
        {
            double px = bilerp(ax, bx, cx, dx, u, v, true);
            double py = bilerp(ay, by, cy, dy, u, v, false);

            double dux = (dx - ax) * (1.0 - v) + (cx - bx) * v;
            double duy = (dy - ay) * (1.0 - v) + (cy - by) * v;
            double dvx = (bx - ax) * (1.0 - u) + (cx - dx) * u;
            double dvy = (by - ay) * (1.0 - u) + (cy - dy) * u;

            double rx = px - mouseX;
            double ry = py - mouseY;
            double det = dux * dvy - duy * dvx;
            if (Math.abs(det) < 0.0001)
            {
                break;
            }

            double deltaU = (rx * dvy - ry * dvx) / det;
            double deltaV = (ry * dux - rx * duy) / det;

            u -= deltaU;
            v -= deltaV;
        }

        if (u < -0.15 || u > 1.15 || v < -0.15 || v > 1.15)
        {
            return null;
        }

        return new double[]
        {
            clamp01(u),
            clamp01(v)
        };
    }

    private static double bilerp(double a, double b, double c, double d, double u, double v, boolean xAxis)
    {
        double west = a * (1.0 - v) + b * v;
        double east = d * (1.0 - v) + c * v;
        return west * (1.0 - u) + east * u;
    }

    static int clampOffset(int value)
    {
        if (value < 0)
        {
            return 0;
        }
        if (value > 127)
        {
            return 127;
        }
        return value;
    }

    private static double clamp01(double value)
    {
        if (value < 0.0)
        {
            return 0.0;
        }
        if (value > 1.0)
        {
            return 1.0;
        }
        return value;
    }

    private BufferedImage createIcon()
    {
        try (InputStream inputStream = PaintOverlaysPlugin.class.getResourceAsStream("paint_overlays_icon.png"))
        {
            if (inputStream != null)
            {
                BufferedImage resourceImage = ImageIO.read(inputStream);
                if (resourceImage != null)
                {
                    return resourceImage;
                }
            }
        }
        catch (IOException ex)
        {
            log.warn("Failed to load icon resource", ex);
        }

        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(24, 24, 24, 220));
            graphics.fillRoundRect(0, 0, 16, 16, 5, 5);
            graphics.setColor(new Color(38, 255, 0));
            graphics.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawLine(3, 11, 7, 6);
            graphics.drawLine(7, 6, 12, 4);
            graphics.fillOval(10, 9, 3, 3);
        }
        finally
        {
            graphics.dispose();
        }
        return image;
    }

    private void refreshPanel()
    {
        if (panel == null)
        {
            return;
        }

        if (client != null && clientThread != null && !client.isClientThread())
        {
            clientThread.invokeLater(this::refreshPanel);
            return;
        }

        if (clientThread == null)
        {
            snapshotPanelState();
            return;
        }

        if (clientPanelSnapshotScheduled.compareAndSet(false, true))
        {
            clientThread.invokeLater(this::snapshotPanelState);
        }
    }

    private void snapshotPanelState()
    {
        try
        {
            PaintOverlaysPanel currentPanel = panel;
            if (currentPanel == null)
            {
                return;
            }

            pendingPanelState.set(createPanelState());
            schedulePanelRefresh(currentPanel);
        }
        finally
        {
            clientPanelSnapshotScheduled.set(false);
        }
    }

    PaintPanelState createPanelState()
    {
        PaintInputMode inputMode = getInputMode();
        boolean editingAvailable = isEditingAvailable();
        boolean sceneInputAvailable = editingAvailable && !worldMapOpen;
        boolean worldMapInputAvailable = editingAvailable && worldMapOpen;
        PaintUndoAction undoAction = undoHistory.peekFirst();
        boolean undoAvailable = undoAction != null && profileKeysEqual(undoAction.rsProfileKey, loadedRsProfileKey);
        cachedUndoAvailable = undoAvailable;
        boolean debugToolsEnabled = areDebugToolsEnabled();

        return new PaintPanelState(
            lastHandledPanelToolRequestId,
            tool,
            inputMode,
            shapeType,
            fontStyle,
            frameStyle,
            color,
            textBackgroundColor,
            textBorderColor,
            frameColor,
            brushSize,
            shapeSize,
            textSize,
            pendingText,
            frameRainbowEnabled,
            editingAvailable,
            sceneInputAvailable,
            worldMapInputAvailable,
            undoAvailable,
            editingAvailable && hasSecondarySurfacePaint(),
            debugToolsEnabled && editingAvailable && !worldMapOpen,
            debugToolsEnabled,
            getClearActionText(),
            getInputStatusText());
    }

    private void schedulePanelRefresh(PaintOverlaysPanel expectedPanel)
    {
        if (!panelRefreshScheduled.compareAndSet(false, true))
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
        {
            try
            {
                if (panel == expectedPanel)
                {
                    PaintPanelState state = pendingPanelState.getAndSet(null);
                    if (state != null)
                    {
                        expectedPanel.refreshState(state);
                    }
                }
            }
            finally
            {
                panelRefreshScheduled.set(false);
                PaintOverlaysPanel currentPanel = panel;
                if (currentPanel != null && pendingPanelState.get() != null)
                {
                    schedulePanelRefresh(currentPanel);
                }
            }
        });
    }

    String getInputStatusText()
    {
        PaintInputMode inputMode = getInputMode();
        String chunkUsage = areDebugToolsEnabled() ? getCurrentChunkUsageStatus() : null;
        if (inputMode == PaintInputMode.NONE)
        {
            return chunkUsage == null ? "Off" : "Off | " + chunkUsage;
        }

        if (!isInputModeAvailable(inputMode))
        {
            if (inputMode == PaintInputMode.WORLD_MAP)
            {
                if (!isInLoggedInGame())
                {
                    return chunkUsage == null
                        ? "World map mode selected | available after logging in"
                        : "World map mode selected | available after logging in | " + chunkUsage;
                }

                return chunkUsage == null
                    ? "World map mode selected | open the world map to paint"
                    : "World map mode selected | open the world map to paint | " + chunkUsage;
            }

            if (!isInLoggedInGame())
            {
                return chunkUsage == null
                    ? "In-Game mode selected | available after logging in"
                    : "In-Game mode selected | available after logging in | " + chunkUsage;
            }

            return chunkUsage == null
                ? "In-Game mode selected | close the world map to paint"
                : "In-Game mode selected | close the world map to paint | " + chunkUsage;
        }

        return chunkUsage == null
            ? inputMode + " | " + tool + " active"
            : inputMode + " | " + tool + " active | " + chunkUsage;
    }

    BasicStroke createFrameStroke(int strokeWidth)
    {
        switch (frameStyle)
        {
            case DASHED:
                return new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                    new float[] {Math.max(18f, strokeWidth * 4.5f), Math.max(16f, strokeWidth * 4.0f)}, 0f);
            case DOTTED:
                return new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {2f, Math.max(6f, strokeWidth * 1.5f)}, 0f);
            case SOLID:
            default:
                return new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
    }

    Color getActiveFrameColor()
    {
        if (!frameRainbowEnabled)
        {
            return frameColor;
        }

        float hue = (System.currentTimeMillis() % 1500L) / 1500.0f;
        Color animated = Color.getHSBColor(hue, 0.85f, 1.0f);
        return new Color(animated.getRed(), animated.getGreen(), animated.getBlue(), frameColor.getAlpha());
    }

    void exitEditingMode()
    {
        clientThread.invoke(() -> setTool(null));
    }

    private Rectangle2D sceneShapeBounds(PaintShape shape, WorldView worldView)
    {
        return shapeBounds(toSceneCanvasPoint(worldView, shape.plane, shape.worldX, shape.worldY, shape.offsetX, shape.offsetY), shape.size);
    }

    private Rectangle2D mapShapeBounds(PaintShape shape)
    {
        return shapeBounds(worldMapOverlay.toCanvasPoint(shape.worldX, shape.worldY, shape.offsetX, shape.offsetY), shape.size);
    }

    private static Rectangle2D shapeBounds(Point center, int size)
    {
        if (center == null)
        {
            return null;
        }

        double half = size / 2.0;
        return new Rectangle2D.Double(center.getX() - half, center.getY() - half, size, size);
    }

    private void updateContextState()
    {
        boolean currentWorldMapOpen = isWorldMapWidgetVisible();
        boolean statusChunkChanged = areDebugToolsEnabled() && updateCachedStatusChunkKeys(currentWorldMapOpen);
        if (!isEditingAvailable() && tool != null)
        {
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            cancelledInputGestureId = inputGestureSequence.get();
            inputContextGeneration.incrementAndGet();
            clientInputCaptureActive = false;
            clientInputGestureId = 0L;
            clearPendingMouseDrags();
            lastClientDragPoint = null;
            setTool(null);
            return;
        }

        if (worldMapOpen != currentWorldMapOpen)
        {
            inputCaptureActive = false;
            awtInputGestureId = 0L;
            cancelledInputGestureId = inputGestureSequence.get();
            inputContextGeneration.incrementAndGet();
            clientInputCaptureActive = false;
            clientInputGestureId = 0L;
            clearPendingMouseDrags();
            lastClientDragPoint = null;
            finalizePendingPaintAction();
            worldMapOverlay.resetViewState();
            worldMapOpen = currentWorldMapOpen;
            refreshPanel();
            return;
        }

        worldMapOpen = currentWorldMapOpen;
        if (statusChunkChanged)
        {
            refreshPanel();
        }
    }

    private boolean updateCachedStatusChunkKeys(boolean currentWorldMapOpen)
    {
        boolean changed = false;
        String sceneChunkKey = null;
        if (client.getLocalPlayer() != null)
        {
            WorldPoint location = client.getLocalPlayer().getWorldLocation();
            if (location != null)
            {
                sceneChunkKey = getSceneChunkKey(location.getPlane(), location.getRegionID());
            }
        }
        if (!Objects.equals(sceneChunkKey, cachedSceneStatusChunkKey))
        {
            cachedSceneStatusChunkKey = sceneChunkKey;
            changed = true;
        }

        String mapChunkKey = null;
        if (currentWorldMapOpen)
        {
            int centerRegionId = worldMapOverlay.getCenterRegionId();
            if (centerRegionId >= 0)
            {
                mapChunkKey = getMapChunkKey(centerRegionId);
            }
        }
        if (!Objects.equals(mapChunkKey, cachedMapStatusChunkKey))
        {
            cachedMapStatusChunkKey = mapChunkKey;
            changed = true;
        }
        return changed;
    }

    private boolean isWorldMapWidgetVisible()
    {
        net.runelite.api.widgets.Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
        return mapWidget != null && !mapWidget.isHidden();
    }

    private boolean isInputModeAvailable(PaintInputMode mode)
    {
        if (mode == PaintInputMode.WORLD_MAP)
        {
            return isWorldMapInputAvailable();
        }

        if (mode == PaintInputMode.SCENE)
        {
            return isSceneInputAvailable();
        }

        return false;
    }

    private boolean isInLoggedInGame()
    {
        return client.getGameState() == GameState.LOGGED_IN;
    }

    private static int clampTextSize(int textSize)
    {
        if (textSize < MIN_TEXT_SIZE)
        {
            return MIN_TEXT_SIZE;
        }

        return Math.min(textSize, MAX_TEXT_SIZE);
    }

    private static int clampShapeSize(int shapeSize)
    {
        if (shapeSize < MIN_SHAPE_SIZE)
        {
            return MIN_SHAPE_SIZE;
        }

        return Math.min(shapeSize, MAX_SHAPE_SIZE);
    }

    private String getCurrentChunkUsageStatus()
    {
        String chunkKey = worldMapOpen ? getStatusMapChunkKey() : getStatusSceneChunkKey();
        String scopeLabel = worldMapOpen ? "Map chunk" : "Chunk";
        String displayChunkKey = chunkKey == null ? "n/a" : chunkKey;
        if (chunkKey == null)
        {
            return formatChunkUsageStatus(scopeLabel, displayChunkKey, null, false);
        }
        if (corruptChunkKeys.contains(chunkKey))
        {
            return scopeLabel + " " + displayChunkKey + " | Stored paint could not be loaded; clear this chunk to reset it";
        }

        Map<String, PaintChunkData> cache = worldMapOpen ? mapChunks : sceneChunks;
        Set<String> knownKeys = worldMapOpen ? mapChunkKeys : sceneChunkKeys;
        PaintChunkData chunk = getCachedChunkForRender(cache, knownKeys, chunkKey);
        if (corruptChunkKeys.contains(chunkKey))
        {
            return scopeLabel + " " + displayChunkKey + " | Stored paint could not be loaded; clear this chunk to reset it";
        }
        return formatChunkUsageStatus(scopeLabel, displayChunkKey, chunk, knownKeys.contains(chunkKey));
    }

    static String formatChunkUsageStatus(String scopeLabel, String chunkKey, PaintChunkData chunk, boolean knownChunk)
    {
        if (chunk == null)
        {
            if (knownChunk)
            {
                return scopeLabel + " " + chunkKey + " | Stored paint";
            }

            return scopeLabel + " " + chunkKey
                + " | Brush 0/" + MAX_STROKES_PER_CHUNK
                + " | Shapes 0/" + MAX_SHAPES_PER_CHUNK
                + " | Text 0/" + MAX_TEXTS_PER_CHUNK;
        }

        int strokes = countValidStrokes(chunk);
        int shapes = countValidShapes(chunk);
        int texts = countValidTexts(chunk);
        return scopeLabel + " " + chunkKey
            + " | Brush " + formatUsage(strokes, MAX_STROKES_PER_CHUNK)
            + " | Shapes " + formatUsage(shapes, MAX_SHAPES_PER_CHUNK)
            + " | Text " + formatUsage(texts, MAX_TEXTS_PER_CHUNK);
    }

    private static String formatUsage(int count, int limit)
    {
        return count + "/" + limit + (count >= limit ? " full" : "");
    }

    private static int countValidStrokes(PaintChunkData chunk)
    {
        if (chunk == null || chunk.strokes == null)
        {
            return 0;
        }

        int count = 0;
        for (PaintStroke stroke : chunk.strokes)
        {
            if (stroke != null && stroke.points != null && !stroke.points.isEmpty())
            {
                count++;
            }
        }
        return count;
    }

    private static int countValidShapes(PaintChunkData chunk)
    {
        if (chunk == null || chunk.shapes == null)
        {
            return 0;
        }

        int count = 0;
        for (PaintShape shape : chunk.shapes)
        {
            if (shape != null && shape.shapeType != null && shape.size >= MIN_SHAPE_SIZE)
            {
                count++;
            }
        }
        return count;
    }

    private static int countValidTexts(PaintChunkData chunk)
    {
        if (chunk == null || chunk.texts == null)
        {
            return 0;
        }

        int count = 0;
        for (PaintText text : chunk.texts)
        {
            if (text != null && text.text != null && !text.text.trim().isEmpty() && text.fontStyle != null)
            {
                count++;
            }
        }
        return count;
    }

    private static void addDebugFrameStroke(PaintChunkData chunk, int plane, Color color,
                                            int startX, int startY, int width, int height, int strokeWidth)
    {
        PaintStroke stroke = new PaintStroke(plane, color, strokeWidth);
        addStrokePoint(stroke, new PaintTarget(startX, startY, plane, 64, 64));
        addStrokePoint(stroke, new PaintTarget(startX + width, startY, plane, 64, 64));
        addStrokePoint(stroke, new PaintTarget(startX + width, startY + height, plane, 64, 64));
        addStrokePoint(stroke, new PaintTarget(startX, startY + height, plane, 64, 64));
        addStrokePoint(stroke, new PaintTarget(startX, startY, plane, 64, 64));
        chunk.strokes.add(stroke);
    }

    private static void addDebugDiagonalStroke(PaintChunkData chunk, int plane, Color color,
                                               int startX, int startY, int endX, int endY, int strokeWidth)
    {
        PaintStroke stroke = new PaintStroke(plane, color, strokeWidth);
        int steps = 18;
        for (int i = 0; i <= steps; i++)
        {
            double t = i / (double) steps;
            addStrokePoint(stroke, new PaintTarget(
                (int) Math.round(startX + (endX - startX) * t),
                (int) Math.round(startY + (endY - startY) * t),
                plane,
                64,
                64));
        }
        chunk.strokes.add(stroke);
    }

    private static void addDebugWaveStroke(PaintChunkData chunk, int plane, Color color,
                                           int startX, int startY, int width, int seed, int strokeWidth)
    {
        PaintStroke stroke = new PaintStroke(plane, color, strokeWidth);
        for (int step = 0; step <= width; step++)
        {
            int worldX = startX + step;
            int worldY = startY + (int) Math.round(Math.sin((step + seed) / 4.0) * 6.0);
            addStrokePoint(stroke, new PaintTarget(worldX, worldY, plane, 64, 64));
        }
        chunk.strokes.add(stroke);
    }

    private static void addCenterStressPattern(PaintChunkData chunk, int plane, int baseX, int baseY)
    {
        for (int band = 0; band < 18; band++)
        {
            Color bandColor = Color.getHSBColor((band * 0.08f) % 1.0f, 0.9f, 1.0f);
            PaintStroke sweep = new PaintStroke(plane, bandColor, 3 + (band % 3));
            for (int step = 0; step < 48; step++)
            {
                int worldX = baseX + 8 + step;
                int worldY = baseY + 8 + ((band * 5 + step * 3) % 44);
                addStrokePoint(sweep, new PaintTarget(worldX, worldY, plane, 64, 64));
            }
            chunk.strokes.add(sweep);
        }

        for (int column = 0; column < 12; column++)
        {
            Color color = new Color(255, 255 - column * 12, 80 + column * 10, 220);
            PaintStroke vertical = new PaintStroke(plane, color, 2 + (column % 2));
            for (int step = 0; step < 42; step++)
            {
                int worldX = baseX + 10 + column * 3 + (step % 2);
                int worldY = baseY + 10 + step;
                addStrokePoint(vertical, new PaintTarget(worldX, worldY, plane, 64, 64));
            }
            chunk.strokes.add(vertical);
        }
    }

    private static void addStrokePoint(PaintStroke stroke, PaintTarget target)
    {
        if (stroke == null || target == null)
        {
            return;
        }
        stroke.points.add(new PaintPoint(target));
    }

    private PaintChunkData getCurrentSceneChunkData()
    {
        String key = getCurrentSceneChunkKey();
        return key == null ? null : getOrCreateChunk(sceneChunks, sceneChunkKeys, key, false);
    }

    private PaintChunkData getCurrentMapChunkData()
    {
        String key = getCurrentMapChunkKey();
        return key == null ? null : getOrCreateChunk(mapChunks, mapChunkKeys, key, false);
    }

    private String getCurrentSceneChunkKey()
    {
        if (activeSceneChunkKey != null)
        {
            return activeSceneChunkKey;
        }

        Point mouse = getMouseCanvasPosition();
        if (mouse != null)
        {
            PaintTarget target = findSceneTarget(mouse.getX(), mouse.getY());
            if (target != null)
            {
                return getSceneChunkKey(target.plane, target.getRegionId());
            }
        }

        if (client.getLocalPlayer() == null)
        {
            return null;
        }

        WorldPoint location = client.getLocalPlayer().getWorldLocation();
        if (location == null)
        {
            return null;
        }

        return getSceneChunkKey(location.getPlane(), location.getRegionID());
    }

    private String getCurrentMapChunkKey()
    {
        if (activeMapChunkKey != null)
        {
            return activeMapChunkKey;
        }

        Point mouse = getMouseCanvasPosition();
        if (mouse != null)
        {
            PaintTarget target = findMapTarget(mouse.getX(), mouse.getY());
            if (target != null)
            {
                return getMapChunkKey(target.getRegionId());
            }
        }

        int centerRegionId = worldMapOverlay.getCenterRegionId();
        return centerRegionId < 0 ? null : getMapChunkKey(centerRegionId);
    }

    private int resolveCurrentMapPreviewRegionId()
    {
        Point mouse = getMouseCanvasPosition();
        if (mouse != null)
        {
            PaintTarget target = findMapTarget(mouse.getX(), mouse.getY());
            if (target != null)
            {
                return target.getRegionId();
            }
        }

        return worldMapOverlay.getCenterRegionId();
    }

    private String getStatusSceneChunkKey()
    {
        return activeSceneChunkKey != null ? activeSceneChunkKey : cachedSceneStatusChunkKey;
    }

    private String getStatusMapChunkKey()
    {
        return activeMapChunkKey != null ? activeMapChunkKey : cachedMapStatusChunkKey;
    }

    private void captureCanvasSnapshot(File outputFile) throws InvocationTargetException, InterruptedException, IOException
    {
        if (outputFile == null)
        {
            return;
        }

        final Rectangle[] captureBounds = new Rectangle[1];
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                java.awt.Canvas canvas = client.getCanvas();
                if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0 || !canvas.isShowing())
                {
                    return;
                }

                java.awt.Point location = canvas.getLocationOnScreen();
                captureBounds[0] = new Rectangle(location.x, location.y, canvas.getWidth(), canvas.getHeight());
            }
            catch (IllegalComponentStateException ex)
            {
                throw new RuntimeException(ex);
            }
        });

        if (captureBounds[0] == null)
        {
            throw new IOException("Canvas is not available for screen capture");
        }

        try
        {
            BufferedImage image = new Robot().createScreenCapture(captureBounds[0]);
            ImageIO.write(image, "png", outputFile);
        }
        catch (AWTException ex)
        {
            throw new IOException("Failed to capture canvas screenshot", ex);
        }
    }

    private void writeChunkDiagnostics(PrintWriter writer, Map<String, PaintChunkData> cache, Set<String> knownKeys)
    {
        for (String key : new TreeSet<>(knownKeys))
        {
            PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
            if (chunk == null)
            {
                writer.println(key + " | missing");
                continue;
            }

            cleanChunk(chunk);
            String rawJson = serializeChunkForStorage(chunk);
            String encoded = encodeChunkPayload(rawJson);
            writer.println(key
                + " | strokes=" + chunk.strokes.size()
                + " | shapes=" + chunk.shapes.size()
                + " | texts=" + chunk.texts.size()
                + " | rawBytes=" + rawJson.getBytes(StandardCharsets.UTF_8).length
                + " | encodedChars=" + encoded.length()
                + " | sha256=" + sha256(encoded));
        }
    }

    private static String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest)
            {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    private static String encodeChunkPayload(String json)
    {
        if (json == null || json.isEmpty())
        {
            return json;
        }
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        if (jsonBytes.length > MAX_DECODED_CHUNK_BYTES)
        {
            throw new IllegalArgumentException("Paint chunk exceeds safe encoded size");
        }

        try
        {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream))
            {
                gzipOutputStream.write(jsonBytes);
            }
            String encoded = COMPRESSED_CHUNK_PREFIX + Base64.getEncoder().encodeToString(outputStream.toByteArray());
            if (encoded.length() > MAX_ENCODED_CHUNK_CHARS)
            {
                throw new IllegalArgumentException("Paint chunk payload exceeds safe size");
            }
            return encoded;
        }
        catch (IOException ex)
        {
            throw new RuntimeException("Failed to compress paint chunk", ex);
        }
    }

    private static String decodeChunkPayload(String payload)
    {
        if (payload == null)
        {
            return payload;
        }
        if (payload.length() > MAX_ENCODED_CHUNK_CHARS)
        {
            throw new IllegalArgumentException("Paint chunk payload exceeds safe size");
        }
        if (!payload.startsWith(COMPRESSED_CHUNK_PREFIX))
        {
            if (payload.length() > MAX_DECODED_CHUNK_BYTES
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_DECODED_CHUNK_BYTES)
            {
                throw new IllegalArgumentException("Paint chunk JSON exceeds safe size");
            }
            return payload;
        }

        byte[] compressed = Base64.getDecoder().decode(payload.substring(COMPRESSED_CHUNK_PREFIX.length()));
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream())
        {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzipInputStream.read(buffer)) != -1)
            {
                if (outputStream.size() + read > MAX_DECODED_CHUNK_BYTES)
                {
                    throw new IOException("Paint chunk expands beyond safe size");
                }
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8);
        }
        catch (IOException ex)
        {
            throw new RuntimeException("Failed to decompress paint chunk", ex);
        }
    }

    private static boolean profileKeysEqual(String first, String second)
    {
        return first == null ? second == null : first.equals(second);
    }

    static final class SceneClearPreview
    {
        final int plane;
        final int currentRegionId;
        final List<Integer> nearbyRegionIds;

        private SceneClearPreview(int plane, int currentRegionId, List<Integer> nearbyRegionIds)
        {
            this.plane = plane;
            this.currentRegionId = currentRegionId;
            this.nearbyRegionIds = nearbyRegionIds;
        }
    }

    static final class MapClearPreview
    {
        final int currentRegionId;
        final List<Integer> visibleRegionIds;

        private MapClearPreview(int currentRegionId, List<Integer> visibleRegionIds)
        {
            this.currentRegionId = currentRegionId;
            this.visibleRegionIds = visibleRegionIds;
        }
    }

    private List<String> getVisibleSceneChunkKeys()
    {
        List<String> visibleKeys = new ArrayList<>();
        if (client.getLocalPlayer() == null)
        {
            return visibleKeys;
        }

        WorldPoint location = client.getLocalPlayer().getWorldLocation();
        if (location == null)
        {
            return visibleKeys;
        }

        int regionX = location.getX() >> 6;
        int regionY = location.getY() >> 6;
        int plane = location.getPlane();

        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int rx = regionX + dx;
                int ry = regionY + dy;
                if (rx >= 0 && ry >= 0)
                {
                    visibleKeys.add(getSceneChunkKey(plane, (rx << 8) | ry));
                }
            }
        }

        return visibleKeys;
    }

    private int extractSceneChunkPlane(String key)
    {
        if (key == null || !key.startsWith(SCENE_PREFIX))
        {
            return 0;
        }

        int separatorIndex = key.indexOf('.', SCENE_PREFIX.length());
        if (separatorIndex < 0)
        {
            return 0;
        }

        try
        {
            return Integer.parseInt(key.substring(SCENE_PREFIX.length(), separatorIndex));
        }
        catch (NumberFormatException ex)
        {
            return 0;
        }
    }

    private Point toSceneCanvasPoint(int plane, int worldX, int worldY, int offsetX, int offsetY)
    {
        return toSceneCanvasPoint(client.getTopLevelWorldView(), plane, worldX, worldY, offsetX, offsetY);
    }

    static int clampBrushSize(int brushSize)
    {
        if (brushSize < MIN_BRUSH_SIZE)
        {
            return MIN_BRUSH_SIZE;
        }

        return Math.min(brushSize, MAX_BRUSH_SIZE);
    }

    private Point toSceneCanvasPoint(WorldView worldView, int plane, int worldX, int worldY, int offsetX, int offsetY)
    {
        if (worldView == null)
        {
            return null;
        }

        LocalPoint tileCenter = LocalPoint.fromWorld(worldView, worldX, worldY);
        if (tileCenter == null)
        {
            return null;
        }

        LocalPoint localPoint = tileCenter.plus(offsetX - 64, offsetY - 64);
        return Perspective.localToCanvas(client, localPoint, plane);
    }

    private Tile findSceneTileAtMouse(WorldView worldView, int mouseX, int mouseY)
    {
        Tile selectedTile = client.getSelectedSceneTile();
        if (selectedTile != null
            && selectedTile.getRenderLevel() == worldView.getPlane()
            && chooseCloserContainingTile(null, selectedTile, mouseX, mouseY, Double.MAX_VALUE) == selectedTile)
        {
            return selectedTile;
        }

        Tile nearby = findNearbySceneTileAtMouse(worldView, mouseX, mouseY);
        if (nearby != null)
        {
            return nearby;
        }
        return null;
    }

    private Tile findNearbySceneTileAtMouse(WorldView worldView, int mouseX, int mouseY)
    {
        if (lastSceneSearchTarget == null)
        {
            return null;
        }

        Scene scene = worldView.getScene();
        if (scene == null)
        {
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        int currentRenderLevel = worldView.getPlane();
        if (tiles == null || currentRenderLevel < 0 || currentRenderLevel >= tiles.length)
        {
            return null;
        }

        Tile[][] planeTiles = tiles[currentRenderLevel];
        if (planeTiles == null)
        {
            return null;
        }

        int anchorSceneX = lastSceneSearchTarget.worldX - worldView.getBaseX();
        int anchorSceneY = lastSceneSearchTarget.worldY - worldView.getBaseY();
        int minSceneX = Math.max(0, anchorSceneX - 6);
        int maxSceneX = Math.min(planeTiles.length - 1, anchorSceneX + 6);
        if (minSceneX > maxSceneX)
        {
            return null;
        }

        Tile bestTile = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int sceneX = minSceneX; sceneX <= maxSceneX; sceneX++)
        {
            Tile[] column = planeTiles[sceneX];
            if (column == null)
            {
                continue;
            }

            int minSceneY = Math.max(0, anchorSceneY - 6);
            int maxSceneY = Math.min(column.length - 1, anchorSceneY + 6);
            for (int sceneY = minSceneY; sceneY <= maxSceneY; sceneY++)
            {
                Tile tile = column[sceneY];
                if (tile == null || tile.getRenderLevel() != currentRenderLevel)
                {
                    continue;
                }

                bestTile = chooseCloserContainingTile(bestTile, tile, mouseX, mouseY, bestDistanceSquared);
                if (bestTile == tile)
                {
                    bestDistanceSquared = distanceSquaredToTileCenter(tile, mouseX, mouseY);
                }
            }
        }

        return bestTile;
    }

    private Tile chooseCloserContainingTile(Tile currentBest, Tile candidate, int mouseX, int mouseY, double currentBestDistanceSquared)
    {
        LocalPoint localLocation = candidate.getLocalLocation();
        if (localLocation == null)
        {
            return currentBest;
        }

        Polygon polygon = Perspective.getCanvasTilePoly(client, localLocation);
        if (polygon == null || !polygon.contains(mouseX, mouseY))
        {
            return currentBest;
        }

        double candidateDistanceSquared = distanceSquaredToTileCenter(candidate, mouseX, mouseY);
        return candidateDistanceSquared < currentBestDistanceSquared ? candidate : currentBest;
    }

    private double distanceSquaredToTileCenter(Tile tile, int mouseX, int mouseY)
    {
        LocalPoint localLocation = tile.getLocalLocation();
        if (localLocation == null)
        {
            return Double.MAX_VALUE;
        }

        Polygon polygon = Perspective.getCanvasTilePoly(client, localLocation);
        if (polygon == null)
        {
            return Double.MAX_VALUE;
        }

        Rectangle bounds = polygon.getBounds();
        double centerX = bounds.getCenterX();
        double centerY = bounds.getCenterY();
        double dx = centerX - mouseX;
        double dy = centerY - mouseY;
        return dx * dx + dy * dy;
    }

    private static boolean isLeftButtonDown(MouseEvent event)
    {
        return (event.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) != 0
            || event.getButton() == MouseEvent.BUTTON1
            || SwingUtilities.isLeftMouseButton(event);
    }

    private boolean isCanvasEvent(MouseEvent event)
    {
        return event != null && event.getComponent() == client.getCanvas();
    }

    private static PaintPoint interpolatePoint(double startX, double startY, double endX, double endY, double t)
    {
        return pointFromContinuous(
            startX + (endX - startX) * t,
            startY + (endY - startY) * t);
    }

    private static PaintPoint pointFromContinuous(double continuousX, double continuousY)
    {
        PaintPoint point = new PaintPoint();
        point.worldX = (int) Math.floor(continuousX + 0.5);
        point.worldY = (int) Math.floor(continuousY + 0.5);
        point.offsetX = clampOffset((int) Math.round((continuousX - point.worldX) * 128.0 + 64.0));
        point.offsetY = clampOffset((int) Math.round((continuousY - point.worldY) * 128.0 + 64.0));
        return point;
    }

    @FunctionalInterface
    interface CanvasPointResolver
    {
        Point resolve(PaintPoint point);
    }

    private static final class PendingMouseDrag
    {
        private final long gestureId;
        private final java.awt.Point point;
        private final boolean mapPointUsable;
        private boolean breakBefore;

        private PendingMouseDrag(long gestureId, java.awt.Point point, boolean mapPointUsable)
        {
            this.gestureId = gestureId;
            this.point = new java.awt.Point(point);
            this.mapPointUsable = mapPointUsable;
        }
    }

    static final class EraseStrokeResult
    {
        final boolean changed;
        final List<PaintStroke> strokes;

        private EraseStrokeResult(boolean changed, List<PaintStroke> strokes)
        {
            this.changed = changed;
            this.strokes = strokes;
        }
    }
}
