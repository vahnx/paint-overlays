package com.paintoverlays;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.AWTException;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.BasicStroke;
import java.awt.Color;
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
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
    private static final int REGION_SIZE = 64;
    private static final int DEFAULT_BRUSH_SIZE = 4;
    private static final int DEFAULT_TEXT_SIZE = 16;
    private static final int MIN_TEXT_SIZE = 1;
    private static final int MAX_TEXT_SIZE = 5000;
    private static final int DEFAULT_SHAPE_SIZE = 48;
    private static final int MIN_SHAPE_SIZE = 4;
    private static final int MAX_SHAPE_SIZE = 1000;
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
    private PaintOverlaysOverlay sceneOverlay;

    @Inject
    private PaintOverlaysWorldMapOverlay worldMapOverlay;

    @Inject
    private PaintOverlaysInputFrameOverlay inputFrameOverlay;

    private final Map<String, PaintChunkData> sceneChunks = new HashMap<>();
    private final Map<String, PaintChunkData> mapChunks = new HashMap<>();
    private final Set<String> sceneChunkKeys = new HashSet<>();
    private final Set<String> mapChunkKeys = new HashSet<>();
    private final Deque<PaintUndoAction> undoHistory = new ArrayDeque<>();

    private PaintOverlaysPanel panel;
    private NavigationButton navigationButton;
    private PaintOverlaysMouseListener mouseListener;
    private KeyEventDispatcher keyEventDispatcher;
    private BufferedImage iconImage;

    private PaintTool tool;
    private PaintShapeType shapeType = PaintShapeType.RECTANGLE;
    private PaintFontStyle fontStyle = PaintFontStyle.RUNE_SCAPE;
    private PaintFrameStyle frameStyle = PaintFrameStyle.SOLID;
    private Color color = DEFAULT_COLOR;
    private Color textBackgroundColor = DEFAULT_TEXT_BACKGROUND_COLOR;
    private Color textBorderColor = DEFAULT_TEXT_BORDER_COLOR;
    private Color frameColor = DEFAULT_FRAME_COLOR;
    private int brushSize = DEFAULT_BRUSH_SIZE;
    private int shapeSize = DEFAULT_SHAPE_SIZE;
    private int textSize = DEFAULT_TEXT_SIZE;
    private String pendingText = "This is a sample message.";
    private boolean frameRainbowEnabled = true;

    private PaintStroke activeSceneStroke;
    private PaintStroke activeMapStroke;
    private String activeSceneChunkKey;
    private String activeMapChunkKey;
    private String loadedRsProfileKey;
    private volatile String cachedSceneStatusChunkKey;
    private volatile String cachedMapStatusChunkKey;
    private volatile boolean inputCaptureActive;
    private volatile Point previewMouseCanvasPosition;
    private volatile boolean worldMapOpen;
    private PaintTarget lastSceneSearchTarget;
    private PaintUndoAction pendingUndoAction;

    @Provides
    PaintOverlaysConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(PaintOverlaysConfig.class);
    }

    @Override
    protected void startUp()
    {
        panel = new PaintOverlaysPanel(this, colorPickerManager);
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
        finalizePendingPaintAction();
        flushPersistedChanges();
        inputCaptureActive = false;
        mouseManager.unregisterMouseListener(mouseListener);
        if (keyEventDispatcher != null)
        {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher);
        }
        overlayManager.remove(inputFrameOverlay);
        overlayManager.remove(worldMapOverlay);
        overlayManager.remove(sceneOverlay);
        clientToolbar.removeNavigation(navigationButton);

        sceneChunks.clear();
        mapChunks.clear();
        sceneChunkKeys.clear();
        mapChunkKeys.clear();

        activeSceneStroke = null;
        activeMapStroke = null;
        activeSceneChunkKey = null;
        activeMapChunkKey = null;
        lastSceneSearchTarget = null;
        worldMapOpen = false;
        pendingUndoAction = null;
        undoHistory.clear();

        panel = null;
        navigationButton = null;
        mouseListener = null;
        keyEventDispatcher = null;
        iconImage = null;
    }

    @Subscribe
    public void onProfileChanged(ProfileChanged event)
    {
        reloadAllChunks();
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
    {
        reloadAllChunks();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            reloadAllChunks();
        }
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
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

    PaintFrameStyle getFrameStyle()
    {
        return frameStyle;
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

    boolean isFrameRainbowEnabled()
    {
        return frameRainbowEnabled;
    }

    PaintOverlaysConfig getPluginConfig()
    {
        return config;
    }

    int getSceneChunkCount()
    {
        return sceneChunkKeys.size();
    }

    int getMapChunkCount()
    {
        return mapChunkKeys.size();
    }

    boolean canUndo()
    {
        PaintUndoAction action = undoHistory.peekFirst();
        if (action == null)
        {
            return false;
        }

        return profileKeysEqual(action.rsProfileKey, loadedRsProfileKey);
    }

    void undoLastAction()
    {
        clientThread.invoke(this::undoLastActionOnClientThread);
    }

    void setTool(PaintTool tool)
    {
        this.tool = tool;
        inputCaptureActive = false;
        lastSceneSearchTarget = null;
        finalizePendingPaintAction();
        refreshPanel();
    }

    void setFontStyle(PaintFontStyle fontStyle)
    {
        this.fontStyle = fontStyle;
        refreshPanel();
    }

    void setShapeType(PaintShapeType shapeType)
    {
        this.shapeType = shapeType == null ? PaintShapeType.RECTANGLE : shapeType;
        refreshPanel();
    }

    void setFrameStyle(PaintFrameStyle frameStyle)
    {
        this.frameStyle = frameStyle == null ? PaintFrameStyle.SOLID : frameStyle;
        refreshPanel();
    }

    void setColor(Color color)
    {
        this.color = color;
        refreshPanel();
    }

    void setTextBackgroundColor(Color textBackgroundColor)
    {
        if (textBackgroundColor == null)
        {
            return;
        }

        this.textBackgroundColor = textBackgroundColor;
        refreshPanel();
    }

    void setTextBorderColor(Color textBorderColor)
    {
        if (textBorderColor == null)
        {
            return;
        }

        this.textBorderColor = textBorderColor;
        refreshPanel();
    }

    void setFrameColor(Color frameColor)
    {
        if (frameColor == null)
        {
            return;
        }

        this.frameColor = frameColor;
        refreshPanel();
    }

    void setBrushSize(int brushSize)
    {
        this.brushSize = brushSize;
        refreshPanel();
    }

    void setShapeSize(int shapeSize)
    {
        this.shapeSize = clampShapeSize(shapeSize);
        refreshPanel();
    }

    void setTextSize(int textSize)
    {
        this.textSize = clampTextSize(textSize);
        refreshPanel();
    }

    void setPendingText(String pendingText)
    {
        String sanitized = PaintMath.sanitizePendingText(pendingText);
        if (!sanitized.equals(this.pendingText))
        {
            this.pendingText = sanitized;
            refreshPanel();
        }
    }

    void setFrameRainbowEnabled(boolean frameRainbowEnabled)
    {
        this.frameRainbowEnabled = frameRainbowEnabled;
        refreshPanel();
    }

    boolean isWorldMapOpen()
    {
        return worldMapOpen;
    }

    boolean isSceneInputAvailable()
    {
        return !worldMapOpen;
    }

    boolean isWorldMapInputAvailable()
    {
        return worldMapOpen;
    }

    void clearCurrentSurfaceChunk()
    {
        clientThread.invoke(this::clearCurrentSurfaceChunkOnClientThread);
    }

    void clearSecondarySurfaceSelection()
    {
        clientThread.invoke(this::clearSecondarySurfaceSelectionOnClientThread);
    }

    String getClearActionText()
    {
        return worldMapOpen ? "Clear Map Paint" : "Clear Paint";
    }

    boolean canClearSurface()
    {
        return worldMapOpen ? !mapChunkKeys.isEmpty() : !sceneChunkKeys.isEmpty();
    }

    boolean canGenerateDrawingTest()
    {
        return !worldMapOpen;
    }

    void generateDrawingTest()
    {
        clientThread.invoke(this::generateDrawingTestOnClientThread);
    }

    void exportDebugSnapshot()
    {
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

        flushPersistedChanges();
        refreshPanel();
    }

    private void exportDebugSnapshotOnClientThread()
    {
        finalizePendingPaintAction();
        flushPersistedChanges();

        File debugDir = new File(System.getProperty("user.home"), "paint-overlays-debug");
        if (!debugDir.exists() && !debugDir.mkdirs())
        {
            log.warn("Failed to create debug export directory {}", debugDir);
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        File textFile = new File(debugDir, "paint-overlays-debug-" + timestamp + ".txt");
        File imageFile = new File(debugDir, "paint-overlays-debug-" + timestamp + ".png");

        try (PrintWriter writer = new PrintWriter(textFile, StandardCharsets.UTF_8.name()))
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
        catch (IOException ex)
        {
            log.warn("Failed to write paint debug report", ex);
        }

        try
        {
            captureCanvasSnapshot(imageFile);
        }
        catch (IOException | InvocationTargetException | InterruptedException ex)
        {
            log.warn("Failed to capture paint debug snapshot", ex);
        }
    }

    boolean hasCurrentSurfacePaint()
    {
        String key = worldMapOpen ? getCurrentMapChunkKey() : getCurrentSceneChunkKey();
        if (key == null)
        {
            return false;
        }

        return worldMapOpen ? mapChunkKeys.contains(key) : sceneChunkKeys.contains(key);
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
            inputCaptureActive = false;
            clientThread.invoke(this::finalizePendingPaintAction);
            return false;
        }

        PaintInputMode inputMode = getInputMode();
        if (inputMode == PaintInputMode.NONE || SwingUtilities.isMiddleMouseButton(event) || !isInputModeAvailable(inputMode))
        {
            return false;
        }

        if (!SwingUtilities.isLeftMouseButton(event))
        {
            clientThread.invoke(this::finalizePendingPaintAction);
            return false;
        }

        inputCaptureActive = true;
        java.awt.Point point = event.getPoint();
        clientThread.invoke(() -> processMousePressed(point));
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

        java.awt.Point point = event.getPoint();
        clientThread.invoke(() -> processMouseDragged(point));
        return true;
    }

    boolean handleMouseReleased(MouseEvent event)
    {
        if (!isCanvasEvent(event))
        {
            inputCaptureActive = false;
            clientThread.invoke(this::finalizePendingPaintAction);
            return false;
        }

        if (getInputMode() == PaintInputMode.NONE || SwingUtilities.isMiddleMouseButton(event))
        {
            return false;
        }

        boolean consumed = inputCaptureActive && (event.getButton() == MouseEvent.BUTTON1 || SwingUtilities.isLeftMouseButton(event));
        inputCaptureActive = false;
        clientThread.invoke(this::finalizePendingPaintAction);
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
            && !(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent)
            && canUndo())
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

    private void processMousePressed(java.awt.Point point)
    {
        PaintInputMode inputMode = getInputMode();
        if (!inputCaptureActive || inputMode == PaintInputMode.NONE || !isInputModeAvailable(inputMode))
        {
            return;
        }

        if (inputMode == PaintInputMode.SCENE)
        {
            handleScenePress(point);
            return;
        }

        handleMapPress(point);
    }

    private void processMouseDragged(java.awt.Point point)
    {
        PaintInputMode inputMode = getInputMode();
        if (!inputCaptureActive || inputMode == PaintInputMode.NONE || !isInputModeAvailable(inputMode))
        {
            return;
        }

        if (inputMode == PaintInputMode.SCENE)
        {
            handleSceneDrag(point);
            return;
        }

        handleMapDrag(point);
    }

    Collection<PaintChunkData> getVisibleSceneChunks()
    {
        List<PaintChunkData> visible = new ArrayList<>();
        if (client.getLocalPlayer() == null)
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

                PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, getSceneChunkKey(plane, (rx << 8) | ry), false);
                if (chunk != null && !chunk.isEmpty())
                {
                    visible.add(chunk);
                }
            }
        }

        return visible;
    }

    PaintChunkData getMapChunk(int regionId)
    {
        return getOrCreateChunk(mapChunks, mapChunkKeys, getMapChunkKey(regionId), false);
    }

    PaintStroke getActiveSceneStroke()
    {
        return activeSceneStroke;
    }

    PaintStroke getActiveMapStroke()
    {
        return activeMapStroke;
    }

    PaintTarget getScenePreviewTarget()
    {
        Point mouse = getMouseCanvasPosition();
        return findSceneTarget(mouse.getX(), mouse.getY());
    }

    PaintTarget getMapPreviewTarget()
    {
        Point mouse = getMouseCanvasPosition();
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
        PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, true);
        chunk.texts.add(new PaintText(target, color, textSize, fontStyle,
            textBackgroundColor, textBorderColor,
            text));
        saveChunk(sceneChunks, sceneChunkKeys, key);
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
        PaintChunkData chunk = getOrCreateChunk(sceneChunks, sceneChunkKeys, key, true);
        chunk.shapes.add(new PaintShape(target, color, shapeSize, shapeType));
        saveChunk(sceneChunks, sceneChunkKeys, key);
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
        PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, true);
        chunk.texts.add(new PaintText(target, color, textSize, fontStyle,
            textBackgroundColor, textBorderColor,
            text));
        saveChunk(mapChunks, mapChunkKeys, key);
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
        PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, true);
        chunk.shapes.add(new PaintShape(target, color, shapeSize, shapeType));
        saveChunk(mapChunks, mapChunkKeys, key);
        commitUndoAction(undoAction);
        refreshPanel();
    }

    private void eraseVisibleScene(java.awt.Point mousePoint)
    {
        if (client.getLocalPlayer() == null)
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
            String snapshotJson = gson.toJson(chunk);
            if (eraseSceneFromChunk(chunk, plane, mousePoint.x, mousePoint.y))
            {
                undoAction.addSnapshot(key, snapshotJson);
                saveChunk(sceneChunks, sceneChunkKeys, key);
            }
        }
        refreshPanel();
    }

    private void eraseVisibleMap(java.awt.Point mousePoint)
    {
        PaintUndoAction undoAction = beginUndoAction();
        for (Integer regionId : worldMapOverlay.getVisibleRegionIds())
        {
            String key = getMapChunkKey(regionId);
            PaintChunkData chunk = getOrCreateChunk(mapChunks, mapChunkKeys, key, false);
            if (chunk == null)
            {
                continue;
            }

            String snapshotJson = gson.toJson(chunk);
            if (eraseMapFromChunk(chunk, mousePoint.x, mousePoint.y))
            {
                undoAction.addSnapshot(key, snapshotJson);
                saveChunk(mapChunks, mapChunkKeys, key);
            }
        }
        refreshPanel();
    }

    private boolean eraseSceneFromChunk(PaintChunkData chunk, int plane, int mouseX, int mouseY)
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

            EraseStrokeResult result = eraseStroke(stroke, mouseX, mouseY, radius + Math.max(1, stroke.width / 2),
                point -> toSceneCanvasPoint(stroke.plane, point.worldX, point.worldY, point.offsetX, point.offsetY));
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
                && shapeContainsMouse(sceneShapeBounds(shape), mouseX, mouseY, radius));

        boolean textChanged = chunk.texts.removeIf(text ->
            text != null
                && text.plane == plane
                && PaintMath.textWithinCanvasRadius(
                    toSceneCanvasPoint(text.plane, text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text.fontStyle.createFont(text.fontSize),
                    text.text,
                    mouseX,
                    mouseY,
                    radius));

        return changed || shapeChanged || textChanged;
    }

    private boolean eraseMapFromChunk(PaintChunkData chunk, int mouseX, int mouseY)
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

            EraseStrokeResult result = eraseStroke(stroke, mouseX, mouseY, radius + Math.max(1, stroke.width / 2),
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
            shape != null
                && shapeContainsMouse(mapShapeBounds(shape), mouseX, mouseY, radius));

        boolean textChanged = chunk.texts.removeIf(text ->
            text != null
                && PaintMath.textWithinCanvasRadius(
                    worldMapOverlay.toCanvasPoint(text.worldX, text.worldY, text.offsetX, text.offsetY),
                    text.fontStyle.createFont(text.fontSize),
                    text.text,
                    mouseX,
                    mouseY,
                    radius));

        return changed || shapeChanged || textChanged;
    }

    private EraseStrokeResult eraseStroke(PaintStroke stroke, int mouseX, int mouseY, int radius, CanvasPointResolver pointResolver)
    {
        List<PaintStroke> remaining = new ArrayList<>();
        PaintStroke currentFragment = null;
        Point previousCanvasPoint = null;
        boolean changed = false;

        for (PaintPoint point : stroke.points)
        {
            if (point == null)
            {
                currentFragment = null;
                previousCanvasPoint = null;
                changed = true;
                continue;
            }

            Point canvasPoint = pointResolver.resolve(point);
            boolean pointHit = PaintMath.withinCanvasRadius(canvasPoint, mouseX, mouseY, radius);
            boolean segmentHit = previousCanvasPoint != null
                && canvasPoint != null
                && PaintMath.segmentWithinCanvasRadius(previousCanvasPoint, canvasPoint, mouseX, mouseY, radius);

            if (pointHit)
            {
                changed = true;
                currentFragment = null;
            }
            else
            {
                if (segmentHit)
                {
                    changed = true;
                    currentFragment = null;
                }

                if (canvasPoint == null)
                {
                    currentFragment = null;
                }
                else
                {
                    if (currentFragment == null)
                    {
                        currentFragment = copyStrokeMetadata(stroke);
                        remaining.add(currentFragment);
                    }

                    addCopiedPoint(currentFragment, point);
                }
            }

            previousCanvasPoint = canvasPoint;
        }

        if (!changed)
        {
            remaining.clear();
            remaining.add(stroke);
        }

        return new EraseStrokeResult(changed, remaining);
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
        PaintPoint point = new PaintPoint();
        point.worldX = source.worldX;
        point.worldY = source.worldY;
        point.offsetX = source.offsetX;
        point.offsetY = source.offsetY;

        if (!stroke.points.isEmpty())
        {
            PaintPoint previous = stroke.points.get(stroke.points.size() - 1);
            if (previous.worldX == point.worldX
                && previous.worldY == point.worldY
                && previous.offsetX == point.offsetX
                && previous.offsetY == point.offsetY)
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
        int interpolationSteps = Math.max(1, (int) Math.ceil(distance / 0.15));
        for (int i = 1; i < interpolationSteps; i++)
        {
            double t = i / (double) interpolationSteps;
            stroke.points.add(interpolatePoint(startX, startY, endX, endY, t));
        }

        stroke.points.add(new PaintPoint(target));
    }

    private void rollSceneStrokeSegmentIfNeeded()
    {
        if (activeSceneStroke == null || activeSceneChunkKey == null || activeSceneStroke.points.size() < MAX_POINTS_PER_STROKE)
        {
            return;
        }

        PaintPoint carryPoint = activeSceneStroke.points.get(activeSceneStroke.points.size() - 1);
        if (!persistStrokeSegment(sceneChunks, sceneChunkKeys, activeSceneChunkKey, activeSceneStroke))
        {
            activeSceneStroke = null;
            activeSceneChunkKey = null;
            inputCaptureActive = false;
            refreshPanel();
            return;
        }

        if (!hasStrokeCapacity(sceneChunks, sceneChunkKeys, activeSceneChunkKey))
        {
            activeSceneStroke = null;
            activeSceneChunkKey = null;
            inputCaptureActive = false;
            refreshPanel();
            return;
        }

        activeSceneStroke = copyStrokeMetadata(activeSceneStroke);
        addCopiedPoint(activeSceneStroke, carryPoint);
    }

    private void rollMapStrokeSegmentIfNeeded()
    {
        if (activeMapStroke == null || activeMapChunkKey == null || activeMapStroke.points.size() < MAX_POINTS_PER_STROKE)
        {
            return;
        }

        PaintPoint carryPoint = activeMapStroke.points.get(activeMapStroke.points.size() - 1);
        if (!persistStrokeSegment(mapChunks, mapChunkKeys, activeMapChunkKey, activeMapStroke))
        {
            activeMapStroke = null;
            activeMapChunkKey = null;
            inputCaptureActive = false;
            refreshPanel();
            return;
        }

        if (!hasStrokeCapacity(mapChunks, mapChunkKeys, activeMapChunkKey))
        {
            activeMapStroke = null;
            activeMapChunkKey = null;
            inputCaptureActive = false;
            refreshPanel();
            return;
        }

        activeMapStroke = copyStrokeMetadata(activeMapStroke);
        addCopiedPoint(activeMapStroke, carryPoint);
    }

    private void finishActiveStrokes()
    {
        finishSceneStroke();
        finishMapStroke();
    }

    private void finalizePendingPaintAction()
    {
        finishActiveStrokes();
        commitUndoAction(pendingUndoAction);
        refreshPanel();
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
        refreshPanel();
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
        refreshPanel();
    }

    private boolean persistStrokeSegment(Map<String, PaintChunkData> cache, Set<String> knownKeys, String chunkKey, PaintStroke stroke)
    {
        if (stroke == null || chunkKey == null || stroke.points == null || stroke.points.isEmpty())
        {
            return false;
        }

        if (!hasStrokeCapacity(cache, knownKeys, chunkKey))
        {
            return false;
        }

        captureUndoSnapshot(beginUndoAction(), cache, knownKeys, chunkKey);
        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, chunkKey, true);
        chunk.strokes.add(stroke);
        saveChunk(cache, knownKeys, chunkKey);
        return true;
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
        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        cleanChunk(chunk);
        return chunk == null || chunk.strokes.size() < MAX_STROKES_PER_CHUNK;
    }

    private boolean hasShapeCapacity(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        cleanChunk(chunk);
        return chunk == null || chunk.shapes.size() < MAX_SHAPES_PER_CHUNK;
    }

    private boolean hasTextCapacity(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
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

        PaintChunkData chunk = getOrCreateChunk(cache, knownKeys, key, false);
        undoAction.addSnapshot(key, chunk == null ? null : gson.toJson(chunk));
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

        if (snapshot.json == null || snapshot.json.trim().isEmpty())
        {
            removeChunk(cache, knownKeys, snapshot.key);
            return;
        }

        PaintChunkData chunk = deserializeChunk(snapshot.json);
        if (chunk == null || chunk.isEmpty())
        {
            removeChunk(cache, knownKeys, snapshot.key);
            return;
        }

        cache.put(snapshot.key, chunk);
        knownKeys.add(snapshot.key);
        saveChunk(cache, knownKeys, snapshot.key);
    }

    private void reloadAllChunks()
    {
        finalizePendingPaintAction();

        sceneChunks.clear();
        mapChunks.clear();
        sceneChunkKeys.clear();
        mapChunkKeys.clear();
        pendingUndoAction = null;
        undoHistory.clear();

        loadedRsProfileKey = configManager.getRSProfileKey();
        if (loadedRsProfileKey != null)
        {
            sceneChunkKeys.addAll(configManager.getRSProfileConfigurationKeys(PaintOverlaysConfig.GROUP, loadedRsProfileKey, SCENE_PREFIX));
            mapChunkKeys.addAll(configManager.getRSProfileConfigurationKeys(PaintOverlaysConfig.GROUP, loadedRsProfileKey, MAP_PREFIX));
        }

        refreshPanel();
    }

    private PaintChunkData getOrCreateChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key, boolean create)
    {
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
            return deserializeChunk(json);
        }
        catch (RuntimeException ex)
        {
            log.warn("Failed to load paint chunk {}", key, ex);
            return new PaintChunkData();
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
        chunk.normalizeLoadedState();
        cleanChunk(chunk);
        return chunk;
    }

    private void saveChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        PaintChunkData chunk = cache.get(key);
        cleanChunk(chunk);
        if (chunk == null || chunk.isEmpty())
        {
            removeChunk(cache, knownKeys, key);
            return;
        }

        knownKeys.add(key);
        if (loadedRsProfileKey == null)
        {
            log.warn("Skipping save for paint chunk {} because no RS profile is loaded", key);
            return;
        }

        configManager.setConfiguration(PaintOverlaysConfig.GROUP, loadedRsProfileKey, key, encodeChunkPayload(gson.toJson(chunk)));
    }

    private void removeChunk(Map<String, PaintChunkData> cache, Set<String> knownKeys, String key)
    {
        cache.remove(key);
        knownKeys.remove(key);
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

    PaintTarget findSceneTarget(int mouseX, int mouseY)
    {
        WorldView worldView = client.getTopLevelWorldView();
        if (worldView == null)
        {
            return null;
        }

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

        PaintTarget target = new PaintTarget(
            worldLocation.getX(),
            worldLocation.getY(),
            worldLocation.getPlane(),
            clampOffset((int) Math.round(uv[1] * 128.0)),
            clampOffset((int) Math.round(uv[0] * 128.0)));
        lastSceneSearchTarget = target;
        cachedSceneStatusChunkKey = getSceneChunkKey(target.plane, target.getRegionId());
        return target;
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
        if (panel != null)
        {
            panel.refreshState();
        }
    }

    String getInputStatusText()
    {
        PaintInputMode inputMode = getInputMode();
        String chunkUsage = getCurrentChunkUsageStatus();
        if (inputMode == PaintInputMode.NONE)
        {
            return chunkUsage == null ? "Off" : "Off | " + chunkUsage;
        }

        if (!isInputModeAvailable(inputMode))
        {
            if (inputMode == PaintInputMode.WORLD_MAP)
            {
                return chunkUsage == null
                    ? "World map mode selected | open the world map to paint"
                    : "World map mode selected | open the world map to paint | " + chunkUsage;
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

    private Rectangle2D sceneShapeBounds(PaintShape shape)
    {
        return shapeBounds(toSceneCanvasPoint(shape.plane, shape.worldX, shape.worldY, shape.offsetX, shape.offsetY), shape.size);
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

    private static boolean shapeContainsMouse(Rectangle2D bounds, int mouseX, int mouseY, int radius)
    {
        if (bounds == null)
        {
            return false;
        }

        double closestX = Math.max(bounds.getMinX(), Math.min(mouseX, bounds.getMaxX()));
        double closestY = Math.max(bounds.getMinY(), Math.min(mouseY, bounds.getMaxY()));
        double diffX = closestX - mouseX;
        double diffY = closestY - mouseY;
        return diffX * diffX + diffY * diffY <= radius * (double) radius;
    }

    private void updateContextState()
    {
        boolean currentWorldMapOpen = isWorldMapWidgetVisible();
        updateCachedStatusChunkKeys(currentWorldMapOpen);
        if (worldMapOpen != currentWorldMapOpen)
        {
            worldMapOpen = currentWorldMapOpen;
            inputCaptureActive = false;
            refreshPanel();
            return;
        }

        worldMapOpen = currentWorldMapOpen;
    }

    private void updateCachedStatusChunkKeys(boolean currentWorldMapOpen)
    {
        if (client.getLocalPlayer() != null)
        {
            WorldPoint location = client.getLocalPlayer().getWorldLocation();
            if (location != null)
            {
                cachedSceneStatusChunkKey = getSceneChunkKey(location.getPlane(), location.getRegionID());
            }
        }

        if (currentWorldMapOpen)
        {
            int centerRegionId = worldMapOverlay.getCenterRegionId();
            if (centerRegionId >= 0)
            {
                cachedMapStatusChunkKey = getMapChunkKey(centerRegionId);
            }
        }
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
            return worldMapOpen;
        }

        if (mode == PaintInputMode.SCENE)
        {
            return !worldMapOpen;
        }

        return false;
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
        PaintChunkData chunk = chunkKey == null
            ? null
            : getOrCreateChunk(worldMapOpen ? mapChunks : sceneChunks, worldMapOpen ? mapChunkKeys : sceneChunkKeys, chunkKey, false);
        int strokes = countValidStrokes(chunk);
        int shapes = countValidShapes(chunk);
        int texts = countValidTexts(chunk);
        String scopeLabel = worldMapOpen ? "Map chunk" : "Chunk";
        String displayChunkKey = chunkKey == null ? "n/a" : chunkKey;
        if (chunk == null)
        {
            return scopeLabel + " " + displayChunkKey
                + " | Brush 0/" + MAX_STROKES_PER_CHUNK
                + " | Shapes 0/" + MAX_SHAPES_PER_CHUNK
                + " | Text 0/" + MAX_TEXTS_PER_CHUNK;
        }

        return scopeLabel + " " + displayChunkKey
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

    private String getStatusSceneChunkKey()
    {
        return activeSceneChunkKey != null ? activeSceneChunkKey : cachedSceneStatusChunkKey;
    }

    private String getStatusMapChunkKey()
    {
        return activeMapChunkKey != null ? activeMapChunkKey : cachedMapStatusChunkKey;
    }

    private void flushPersistedChanges()
    {
        configManager.sendConfig();
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
            String rawJson = gson.toJson(chunk);
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

        try
        {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream))
            {
                gzipOutputStream.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return COMPRESSED_CHUNK_PREFIX + Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
        catch (IOException ex)
        {
            throw new RuntimeException("Failed to compress paint chunk", ex);
        }
    }

    private static String decodeChunkPayload(String payload)
    {
        if (payload == null || !payload.startsWith(COMPRESSED_CHUNK_PREFIX))
        {
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
        WorldView worldView = client.getTopLevelWorldView();
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
        Tile nearby = findNearbySceneTileAtMouse(worldView, mouseX, mouseY);
        if (nearby != null)
        {
            return nearby;
        }

        Scene scene = worldView.getScene();
        if (scene == null)
        {
            return null;
        }

        Tile[][][] tiles = scene.getTiles();
        if (tiles == null || tiles.length == 0)
        {
            return null;
        }

        Tile bestTile = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        int currentRenderLevel = worldView.getPlane();
        for (Tile[][] planeTiles : tiles)
        {
            if (planeTiles == null)
            {
                continue;
            }

            for (Tile[] column : planeTiles)
            {
                if (column == null)
                {
                    continue;
                }

                for (Tile tile : column)
                {
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
        }

        return bestTile;
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

        int anchorSceneX = lastSceneSearchTarget.worldX - worldView.getBaseX();
        int anchorSceneY = lastSceneSearchTarget.worldY - worldView.getBaseY();
        if (anchorSceneX < 0 || anchorSceneY < 0)
        {
            return null;
        }

        Tile bestTile = null;
        double bestDistanceSquared = Double.MAX_VALUE;
        for (int radius = 0; radius <= 6; radius++)
        {
            for (int sceneX = anchorSceneX - radius; sceneX <= anchorSceneX + radius; sceneX++)
            {
                for (int sceneY = anchorSceneY - radius; sceneY <= anchorSceneY + radius; sceneY++)
                {
                    if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[currentRenderLevel].length || sceneY >= tiles[currentRenderLevel][sceneX].length)
                    {
                        continue;
                    }

                    Tile tile = tiles[currentRenderLevel][sceneX][sceneY];
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

            if (bestTile != null)
            {
                return bestTile;
            }
        }

        return null;
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
    private interface CanvasPointResolver
    {
        Point resolve(PaintPoint point);
    }

    private static final class EraseStrokeResult
    {
        private final boolean changed;
        private final List<PaintStroke> strokes;

        private EraseStrokeResult(boolean changed, List<PaintStroke> strokes)
        {
            this.changed = changed;
            this.strokes = strokes;
        }
    }
}
