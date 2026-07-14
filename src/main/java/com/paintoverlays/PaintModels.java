package com.paintoverlays;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
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
            Font createFont(int size)
            {
                return FontManager.getRunescapeFont().deriveFont((float) size);
            }
        },
    RUNE_SCAPE_BOLD("RuneScape Bold")
        {
            @Override
            Font createFont(int size)
            {
                return FontManager.getRunescapeBoldFont().deriveFont((float) size);
            }
        },
    RUNE_SCAPE_SMALL("RuneScape Small")
        {
            @Override
            Font createFont(int size)
            {
                return FontManager.getRunescapeSmallFont().deriveFont((float) size);
            }
        };

    private final String displayName;

    PaintFontStyle(String displayName)
    {
        this.displayName = displayName;
    }

    abstract Font createFont(int size);

    @Override
    public String toString()
    {
        return displayName;
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

enum PaintShapeType
{
    RECTANGLE("Square"),
    CIRCLE("Circle"),
    X("X"),
    TRIANGLE("Triangle"),
    DIAMOND("Diamond"),
    STAR("Star"),
    PLUS("Plus"),
    SKULL("Skull"),
    PRAYER_STAR("Prayer Star"),
    TREASURE_CHEST("Treasure Chest"),
    SPIDER_WEB("Spider Web"),
    TARGET("Target");

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
}

final class PaintStroke
{
    int plane;
    int colorArgb;
    int width;
    List<PaintPoint> points = new ArrayList<>();

    PaintStroke()
    {
    }

    PaintStroke(int plane, Color color, int width)
    {
        this.plane = plane;
        this.colorArgb = color.getRGB();
        this.width = width;
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
    // Legacy fields kept for load compatibility with previously persisted notes.
    boolean backgroundEnabled;
    int backgroundColorArgb;
    boolean borderEnabled;
    int borderColorArgb;
    String text;

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
    PaintShapeType shapeType;

    PaintShape()
    {
    }

    PaintShape(PaintTarget target, Color color, int size, PaintShapeType shapeType)
    {
        this.plane = target.plane;
        this.worldX = target.worldX;
        this.worldY = target.worldY;
        this.offsetX = target.offsetX;
        this.offsetY = target.offsetY;
        this.colorArgb = color.getRGB();
        this.size = size;
        this.shapeType = shapeType;
    }
}

final class PaintChunkData
{
    List<PaintStroke> strokes = new ArrayList<>();
    List<PaintShape> shapes = new ArrayList<>();
    List<PaintText> texts = new ArrayList<>();

    void normalizeLoadedState()
    {
        if (texts == null)
        {
            return;
        }

        for (PaintText text : texts)
        {
            if (text != null)
            {
                text.normalizeLegacyDecorationState();
            }
        }
    }

    boolean isEmpty()
    {
        return strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty();
    }
}

final class PaintUndoChunkSnapshot
{
    final String key;
    final String json;

    PaintUndoChunkSnapshot(String key, String json)
    {
        this.key = key;
        this.json = json;
    }
}

final class PaintUndoAction
{
    final String rsProfileKey;
    final List<PaintUndoChunkSnapshot> snapshots = new ArrayList<>();

    PaintUndoAction(String rsProfileKey)
    {
        this.rsProfileKey = rsProfileKey;
    }

    boolean hasSnapshot(String key)
    {
        for (PaintUndoChunkSnapshot snapshot : snapshots)
        {
            if (snapshot != null && key.equals(snapshot.key))
            {
                return true;
            }
        }
        return false;
    }

    void addSnapshot(String key, String json)
    {
        if (key == null || hasSnapshot(key))
        {
            return;
        }

        snapshots.add(new PaintUndoChunkSnapshot(key, json));
    }

    boolean isEmpty()
    {
        return snapshots.isEmpty();
    }
}
