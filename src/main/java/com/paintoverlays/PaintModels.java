package com.paintoverlays;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;

enum PaintInputMode
{
    NONE("Off"),
    SCENE("In-Game"),
    WORLD_MAP("World Map");

    private final String displayName;

    PaintInputMode(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}

enum PaintTool
{
    BRUSH("Brush"),
    SHAPE("Shape"),
    STAMP("Stamp"),
    TEXT("Text"),
    ERASER("Eraser");

    private final String displayName;

    PaintTool(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}

enum PaintFontStyle
{
    RUNE_SCAPE("RuneScape")
        {
            @Override
            Font createUncachedFont(int size)
            {
                return FontManager.getRunescapeFont().deriveFont((float) size);
            }
        },
    RUNE_SCAPE_BOLD("RuneScape Bold")
        {
            @Override
            Font createUncachedFont(int size)
            {
                return FontManager.getRunescapeBoldFont().deriveFont((float) size);
            }
        },
    RUNE_SCAPE_SMALL("RuneScape Small")
        {
            @Override
            Font createUncachedFont(int size)
            {
                return FontManager.getRunescapeSmallFont().deriveFont((float) size);
            }
        };

    private static final int MAX_CACHED_FONT_SIZES = 128;
    private final String displayName;
    private final Map<Integer, Font> fontCache = new LinkedHashMap<Integer, Font>(16, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Font> eldest)
        {
            return size() > MAX_CACHED_FONT_SIZES;
        }
    };

    PaintFontStyle(String displayName)
    {
        this.displayName = displayName;
    }

    Font createFont(int size)
    {
        int safeSize = Math.max(1, size);
        synchronized (fontCache)
        {
            Font cached = fontCache.get(safeSize);
            if (cached == null)
            {
                cached = createUncachedFont(safeSize);
                fontCache.put(safeSize, cached);
            }
            return cached;
        }
    }

    abstract Font createUncachedFont(int size);

    @Override
    public String toString()
    {
        return displayName;
    }
}

final class PaintPanelState
{
    final long handledPanelToolRequestId;
    final PaintTool tool;
    final PaintInputMode inputMode;
    final PaintShapeType shapeType;
    final PaintStampType stampType;
    final PaintFontStyle fontStyle;
    final PaintFrameStyle frameStyle;
    final PaintTextFrameStyle textFrameStyle;
    final Color color;
    final Color textBackgroundColor;
    final Color textBorderColor;
    final Color frameColor;
    final int brushSize;
    final int shapeSize;
    final int shapeRotationDegrees;
    final boolean shapeFlipHorizontal;
    final int textSize;
    final String pendingText;
    final boolean shapeFillEnabled;
    final boolean frameRainbowEnabled;
    final boolean editingAvailable;
    final boolean sceneInputAvailable;
    final boolean worldMapInputAvailable;
    final boolean undoAvailable;
    final boolean clearAvailable;
    final boolean drawingTestAvailable;
    final boolean debugToolsEnabled;
    final String clearActionText;
    final String inputStatusText;

    PaintPanelState(
        long handledPanelToolRequestId,
        PaintTool tool,
        PaintInputMode inputMode,
        PaintShapeType shapeType,
        PaintStampType stampType,
        PaintFontStyle fontStyle,
        PaintFrameStyle frameStyle,
        PaintTextFrameStyle textFrameStyle,
        Color color,
        Color textBackgroundColor,
        Color textBorderColor,
        Color frameColor,
        int brushSize,
        int shapeSize,
        int shapeRotationDegrees,
        boolean shapeFlipHorizontal,
        int textSize,
        String pendingText,
        boolean shapeFillEnabled,
        boolean frameRainbowEnabled,
        boolean editingAvailable,
        boolean sceneInputAvailable,
        boolean worldMapInputAvailable,
        boolean undoAvailable,
        boolean clearAvailable,
        boolean drawingTestAvailable,
        boolean debugToolsEnabled,
        String clearActionText,
        String inputStatusText)
    {
        this.handledPanelToolRequestId = handledPanelToolRequestId;
        this.tool = tool;
        this.inputMode = inputMode;
        this.shapeType = shapeType;
        this.stampType = stampType;
        this.fontStyle = fontStyle;
        this.frameStyle = frameStyle;
        this.textFrameStyle = textFrameStyle;
        this.color = color;
        this.textBackgroundColor = textBackgroundColor;
        this.textBorderColor = textBorderColor;
        this.frameColor = frameColor;
        this.brushSize = brushSize;
        this.shapeSize = shapeSize;
        this.shapeRotationDegrees = shapeRotationDegrees;
        this.shapeFlipHorizontal = shapeFlipHorizontal;
        this.textSize = textSize;
        this.pendingText = pendingText;
        this.shapeFillEnabled = shapeFillEnabled;
        this.frameRainbowEnabled = frameRainbowEnabled;
        this.editingAvailable = editingAvailable;
        this.sceneInputAvailable = sceneInputAvailable;
        this.worldMapInputAvailable = worldMapInputAvailable;
        this.undoAvailable = undoAvailable;
        this.clearAvailable = clearAvailable;
        this.drawingTestAvailable = drawingTestAvailable;
        this.debugToolsEnabled = debugToolsEnabled;
        this.clearActionText = clearActionText;
        this.inputStatusText = inputStatusText;
    }
}

enum PaintFrameStyle
{
    SOLID("Solid"),
    DASHED("Dashed"),
    DOTTED("Dotted");

    private final String displayName;

    PaintFrameStyle(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}

enum PaintTextFrameStyle
{
    RECTANGLE,
    SPEECH_BUBBLE
}

enum PaintShapeType
{
    RECTANGLE("Square"),
    LINE("Line"),
    CIRCLE("Circle"),
    X("Cross"),
    TRIANGLE("Triangle"),
    DIAMOND("Diamond"),
    STAR("Star"),
    PLUS("Plus"),
    SKULL("Skull"),
    PRAYER_STAR("Prayer Star"),
    TREASURE_CHEST("Treasure Chest"),
    SPIDER_WEB("Spider Web"),
    TARGET("Target"),
    HEART("Heart"),
    ARROW("Arrow"),
    CHECK_MARK("Check Mark"),
    LOBSTER("Lobster"),
    SWORD("Sword"),
    BATTLE_AXE("Battle Axe"),
    SHIELD("Shield"),
    DRAGON("Dragon Icon");

    private final String displayName;

    PaintShapeType(String displayName)
    {
        this.displayName = displayName;
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}

enum PaintStampType
{
    /*FILENAME("DisplayedText")*/

    /*NPCs*/
    CHICKEN("Chicken"),
    DELRITH("Delrith"),
    ELVARG("Elvarg"),
    KBD("King Black Dragon"),
    VORKATH("Vorkath"),
    JAD("Jad"),
    MAGGOT_KING("Maggot King"),
    OLM("Olm"),
    VERZIK("Verzik"),
    WARDEN("Warden"),

    /*GEAR*/
    RUNE_2H("Rune 2H"),
    DRAGON_SWORD("Dragon sword"),
    TBOW("Twisted bow"),
    SCYTHE("Scythe"),
    SHADOW("Tumeken's shadow"),

    /*PROJECTILES*/
    DRAGONFIRE("Dragon fire"),

    /*PLAYERS*/
    BOT_SKINNY("Bot (Thin)"),
    BOT_FAT("Bot (Fat)"),

    /*RESOURCES*/
    LOBSTER("Lobster"),

    /*MISC*/
    ROCK("Rock"),
    TREASURE("Treasure Chest"),
    TREE("Tree"),
    THOUGHT("Thought Bubble"),
    OSRS_LOGO("OSRS Logo");

    private final String displayName;

    PaintStampType(String displayName)
    {
        this.displayName = displayName;
    }

    String getAssetName()
    {
        return name().toLowerCase();
    }

    @Override
    public String toString()
    {
        return displayName;
    }
}

final class PaintTarget
{
    final int worldX;
    final int worldY;
    final int plane;
    final int offsetX;
    final int offsetY;

    PaintTarget(int worldX, int worldY, int plane, int offsetX, int offsetY)
    {
        this.worldX = worldX;
        this.worldY = worldY;
        this.plane = plane;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    WorldPoint toWorldPoint()
    {
        return new WorldPoint(worldX, worldY, plane);
    }

    int getRegionId()
    {
        return toWorldPoint().getRegionID();
    }

    double getContinuousX()
    {
        return PaintMath.continuousCoordinate(worldX, offsetX);
    }

    double getContinuousY()
    {
        return PaintMath.continuousCoordinate(worldY, offsetY);
    }
}

final class PaintPoint
{
    int worldX;
    int worldY;
    int offsetX;
    int offsetY;
    Boolean startsNewSegment;

    PaintPoint()
    {
    }

    PaintPoint(PaintTarget target)
    {
        this.worldX = target.worldX;
        this.worldY = target.worldY;
        this.offsetX = target.offsetX;
        this.offsetY = target.offsetY;
    }

    double getContinuousX()
    {
        return PaintMath.continuousCoordinate(worldX, offsetX);
    }

    double getContinuousY()
    {
        return PaintMath.continuousCoordinate(worldY, offsetY);
    }

    boolean startsNewSegment()
    {
        return Boolean.TRUE.equals(startsNewSegment);
    }
}

final class PaintStroke
{
    int plane;
    int colorArgb;
    int width;
    List<PaintPoint> points = new ArrayList<>();
    private transient Color cachedColor;

    PaintStroke()
    {
    }

    PaintStroke(int plane, Color color, int width)
    {
        this.plane = plane;
        this.colorArgb = color.getRGB();
        this.width = width;
    }

    Color getColor()
    {
        if (cachedColor == null || cachedColor.getRGB() != colorArgb)
        {
            cachedColor = new Color(colorArgb, true);
        }
        return cachedColor;
    }
}

final class PaintText
{
    int plane;
    int worldX;
    int worldY;
    int offsetX;
    int offsetY;
    int colorArgb;
    int fontSize;
    PaintFontStyle fontStyle;
    PaintTextFrameStyle frameStyle;
    // Legacy fields kept for load compatibility with previously persisted notes.
    boolean backgroundEnabled;
    int backgroundColorArgb;
    boolean borderEnabled;
    int borderColorArgb;
    String text;
    private transient Color cachedColor;
    private transient Color cachedBackgroundColor;
    private transient Color cachedBorderColor;
    private transient Color cachedShadowColor;

    PaintText()
    {
    }

    PaintText(PaintTarget target, Color color, int fontSize, PaintFontStyle fontStyle,
              Color backgroundColor, Color borderColor, String text)
    {
        this.plane = target.plane;
        this.worldX = target.worldX;
        this.worldY = target.worldY;
        this.offsetX = target.offsetX;
        this.offsetY = target.offsetY;
        this.colorArgb = color.getRGB();
        this.fontSize = fontSize;
        this.fontStyle = fontStyle;
        this.frameStyle = PaintTextFrameStyle.RECTANGLE;
        this.backgroundColorArgb = backgroundColor == null ? 0 : backgroundColor.getRGB();
        this.borderColorArgb = borderColor == null ? 0 : borderColor.getRGB();
        this.backgroundEnabled = new Color(this.backgroundColorArgb, true).getAlpha() > 0;
        this.borderEnabled = new Color(this.borderColorArgb, true).getAlpha() > 0;
        this.text = text;
    }

    void normalizeLegacyDecorationState()
    {
        Color backgroundColor = new Color(backgroundColorArgb, true);
        if (!backgroundEnabled && backgroundColor.getAlpha() > 0)
        {
            backgroundColorArgb = new Color(
                backgroundColor.getRed(),
                backgroundColor.getGreen(),
                backgroundColor.getBlue(),
                0).getRGB();
            backgroundColor = new Color(backgroundColorArgb, true);
        }

        Color borderColor = new Color(borderColorArgb, true);
        if (!borderEnabled && borderColor.getAlpha() > 0)
        {
            borderColorArgb = new Color(
                borderColor.getRed(),
                borderColor.getGreen(),
                borderColor.getBlue(),
                0).getRGB();
            borderColor = new Color(borderColorArgb, true);
        }

        backgroundEnabled = backgroundColor.getAlpha() > 0;
        borderEnabled = borderColor.getAlpha() > 0;
    }

    Color getColor()
    {
        cachedColor = cachedColor(cachedColor, colorArgb);
        return cachedColor;
    }

    Color getBackgroundColor()
    {
        cachedBackgroundColor = cachedColor(cachedBackgroundColor, backgroundColorArgb);
        return cachedBackgroundColor;
    }

    Color getBorderColor()
    {
        cachedBorderColor = cachedColor(cachedBorderColor, borderColorArgb);
        return cachedBorderColor;
    }

    Color getShadowColor()
    {
        int shadowArgb = colorArgb & 0xFF000000;
        cachedShadowColor = cachedColor(cachedShadowColor, shadowArgb);
        return cachedShadowColor;
    }

    private static Color cachedColor(Color cached, int argb)
    {
        return cached == null || cached.getRGB() != argb ? new Color(argb, true) : cached;
    }

    double getContinuousX()
    {
        return PaintMath.continuousCoordinate(worldX, offsetX);
    }

    double getContinuousY()
    {
        return PaintMath.continuousCoordinate(worldY, offsetY);
    }
}

final class PaintShape
{
    int plane;
    int worldX;
    int worldY;
    int offsetX;
    int offsetY;
    int colorArgb;
    int size;
    int rotationDegrees;
    boolean flipHorizontal;
    PaintShapeType shapeType;
    String unsupportedShapeType;
    boolean filled;
    private transient Color cachedColor;

    PaintShape()
    {
    }

    PaintShape(PaintTarget target, Color color, int size, PaintShapeType shapeType)
    {
        this(target, color, size, shapeType, false);
    }

    PaintShape(PaintTarget target, Color color, int size, PaintShapeType shapeType, boolean filled)
    {
        this(target, color, size, 0, false, shapeType, filled);
    }

    PaintShape(PaintTarget target, Color color, int size, int rotationDegrees, PaintShapeType shapeType, boolean filled)
    {
        this(target, color, size, rotationDegrees, false, shapeType, filled);
    }

    PaintShape(PaintTarget target, Color color, int size, int rotationDegrees, boolean flipHorizontal, PaintShapeType shapeType, boolean filled)
    {
        this.plane = target.plane;
        this.worldX = target.worldX;
        this.worldY = target.worldY;
        this.offsetX = target.offsetX;
        this.offsetY = target.offsetY;
        this.colorArgb = color.getRGB();
        this.size = size;
        this.rotationDegrees = rotationDegrees;
        this.flipHorizontal = flipHorizontal;
        this.shapeType = shapeType;
        this.filled = filled;
    }

    Color getColor()
    {
        if (cachedColor == null || cachedColor.getRGB() != colorArgb)
        {
            cachedColor = new Color(colorArgb, true);
        }
        return cachedColor;
    }
}

final class PaintStamp
{
    int plane;
    int worldX;
    int worldY;
    int offsetX;
    int offsetY;
    int size;
    int rotationDegrees;
    boolean flipHorizontal;
    PaintStampType stampType;
    String unsupportedStampType;

    PaintStamp()
    {
    }

    PaintStamp(PaintTarget target, int size, PaintStampType stampType)
    {
        this(target, size, 0, stampType);
    }

    PaintStamp(PaintTarget target, int size, int rotationDegrees, PaintStampType stampType)
    {
        this(target, size, rotationDegrees, false, stampType);
    }

    PaintStamp(PaintTarget target, int size, int rotationDegrees, boolean flipHorizontal, PaintStampType stampType)
    {
        this.plane = target.plane;
        this.worldX = target.worldX;
        this.worldY = target.worldY;
        this.offsetX = target.offsetX;
        this.offsetY = target.offsetY;
        this.size = size;
        this.rotationDegrees = rotationDegrees;
        this.flipHorizontal = flipHorizontal;
        this.stampType = stampType;
    }
}

final class PaintChunkData
{
    List<PaintStroke> strokes = new ArrayList<>();
    List<PaintShape> shapes = new ArrayList<>();
    List<PaintStamp> stamps = new ArrayList<>();
    List<PaintText> texts = new ArrayList<>();

    void normalizeLoadedState()
    {
        if (strokes == null)
        {
            strokes = new ArrayList<>();
        }
        if (shapes == null)
        {
            shapes = new ArrayList<>();
        }
        if (stamps == null)
        {
            stamps = new ArrayList<>();
        }
        if (texts == null)
        {
            texts = new ArrayList<>();
        }

        List<PaintStroke> compactedStrokes = new ArrayList<>(strokes.size());
        PaintStroke previous = null;
        for (PaintStroke stroke : strokes)
        {
            if (stroke == null || stroke.points == null)
            {
                continue;
            }

            stroke.points.removeIf(point -> point == null);
            if (stroke.points.isEmpty())
            {
                continue;
            }

            if (previous != null
                && previous.plane == stroke.plane
                && previous.colorArgb == stroke.colorArgb
                && previous.width == stroke.width)
            {
                stroke.points.get(0).startsNewSegment = true;
                previous.points.addAll(stroke.points);
                continue;
            }

            compactedStrokes.add(stroke);
            previous = stroke;
        }
        strokes = compactedStrokes;

        for (PaintText text : texts)
        {
            if (text != null)
            {
                text.text = PaintMath.sanitizePendingText(text.text);
                text.normalizeLegacyDecorationState();
            }
        }
    }

    boolean isEmpty()
    {
        return strokes.isEmpty() && shapes.isEmpty() && stamps.isEmpty() && texts.isEmpty();
    }
}

final class PaintUndoChunkSnapshot
{
    final String key;
    final String payload;
    final boolean rawPayload;

    PaintUndoChunkSnapshot(String key, String payload, boolean rawPayload)
    {
        this.key = key;
        this.payload = payload;
        this.rawPayload = rawPayload;
    }
}

final class PaintUndoAction
{
    final String rsProfileKey;
    final List<PaintUndoChunkSnapshot> snapshots = new ArrayList<>();
    private final Set<String> snapshotKeys = new HashSet<>();

    PaintUndoAction(String rsProfileKey)
    {
        this.rsProfileKey = rsProfileKey;
    }

    boolean hasSnapshot(String key)
    {
        return key != null && snapshotKeys.contains(key);
    }

    void addSnapshot(String key, String payload)
    {
        addSnapshot(key, payload, false);
    }

    void addSnapshot(String key, String payload, boolean rawPayload)
    {
        if (key == null || hasSnapshot(key))
        {
            return;
        }

        snapshotKeys.add(key);
        snapshots.add(new PaintUndoChunkSnapshot(key, payload, rawPayload));
    }

    void removeSnapshot(String key)
    {
        if (key == null || !snapshotKeys.remove(key))
        {
            return;
        }

        snapshots.removeIf(snapshot -> key.equals(snapshot.key));
    }

    boolean isEmpty()
    {
        return snapshots.isEmpty();
    }
}
