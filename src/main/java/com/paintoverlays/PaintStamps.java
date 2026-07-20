package com.paintoverlays;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

final class PaintStamps
{
    private static final String STAMP_RESOURCE_DIR = "/com/paintoverlays/stamps/";
    private static final int FALLBACK_SIZE = 32;
    private static final Map<PaintStampType, BufferedImage> IMAGE_CACHE = new EnumMap<>(PaintStampType.class);

    private PaintStamps()
    {
    }

    static BufferedImage getImage(PaintStampType type)
    {
        if (type == null)
        {
            return null;
        }

        synchronized (IMAGE_CACHE)
        {
            BufferedImage image = IMAGE_CACHE.get(type);
            if (image == null)
            {
                image = loadImage(type);
                IMAGE_CACHE.put(type, image);
            }
            return image;
        }
    }

    static BufferedImage getImage(PaintStamp stamp)
    {
        if (stamp == null)
        {
            return null;
        }

        if (stamp.stampType != null)
        {
            return getImage(stamp.stampType);
        }

        return createMissingStampImage(stamp.unsupportedStampType);
    }

    static BufferedImage createPreview(PaintStampType type, int size)
    {
        BufferedImage source = getImage(type);
        if (source == null)
        {
            return null;
        }

        int safeSize = Math.max(1, size);
        BufferedImage preview = new BufferedImage(safeSize, safeSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = preview.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(source, 0, 0, safeSize, safeSize, null);
        }
        finally
        {
            graphics.dispose();
        }
        return preview;
    }

    private static BufferedImage loadImage(PaintStampType type)
    {
        String resourcePath = STAMP_RESOURCE_DIR + type.getAssetName() + ".png";
        try (InputStream inputStream = PaintStamps.class.getResourceAsStream(resourcePath))
        {
            if (inputStream == null)
            {
                return createMissingStampImage(type);
            }

            BufferedImage image = ImageIO.read(inputStream);
            return image == null ? createMissingStampImage(type) : image;
        }
        catch (IOException ex)
        {
            return createMissingStampImage(type);
        }
    }

    private static BufferedImage createMissingStampImage(PaintStampType type)
    {
        return createMissingStampImage(type == null ? "?" : type.name().substring(0, 1));
    }

    private static BufferedImage createMissingStampImage(String label)
    {
        BufferedImage image = new BufferedImage(FALLBACK_SIZE, FALLBACK_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try
        {
            graphics.setColor(new Color(210, 60, 60, 220));
            graphics.drawRect(1, 1, FALLBACK_SIZE - 3, FALLBACK_SIZE - 3);
            graphics.drawLine(4, 4, FALLBACK_SIZE - 5, FALLBACK_SIZE - 5);
            graphics.drawLine(FALLBACK_SIZE - 5, 4, 4, FALLBACK_SIZE - 5);
            graphics.setColor(Color.WHITE);
            String safeLabel = label == null || label.isEmpty() ? "?" : label.substring(0, 1);
            graphics.drawString(safeLabel, FALLBACK_SIZE / 2 - 3, FALLBACK_SIZE / 2 + 5);
        }
        finally
        {
            graphics.dispose();
        }
        return image;
    }
}
