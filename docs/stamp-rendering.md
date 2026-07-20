# Stamp Rendering Notes

Paint Overlays stores stamps as compact `PaintStamp` entries: world position, offset, plane, size, rotation, flip state, and enum-backed stamp type.

## Current In-Game Method

In-game stamps are rendered with ground-projected image drawing.

The stamp remains a normal `PaintStamp` in saved JSON, but the renderer projects its image axes from nearby ground-relative points. This makes zooming and camera movement behave more like scene painting without converting the PNG into thousands of brush points.

Keep the eraser hitbox aligned with the projected visual footprint. If the renderer changes, update `sceneStampBounds` in `PaintOverlaysPlugin` at the same time.

## Previous In-Game Method

The previous method was screen-space billboard rendering:

```java
Point center = toCanvasPoint(worldView, stamp.plane, stamp.worldX, stamp.worldY, stamp.offsetX, stamp.offsetY);
int size = scaledSceneObjectSize(stamp.size, worldView);
applyObjectTransform(graphics, center, stamp.rotationDegrees, stamp.flipHorizontal);
graphics.drawImage(image, center.getX() - size / 2, center.getY() - size / 2, size, size, null);
```

That method kept stamps visually square and less distorted, but it was more prone to camera/zoom wobble because only the stamp center was anchored to the scene.

## Tradeoff

- Ground-projected stamps are more stable under camera rotation and zoom, but can look slightly skewed because they follow the scene plane.
- Screen-space billboard stamps preserve the PNG shape better, but can feel detached from the scene when the camera or zoom changes.
- Do not change the saved stamp JSON format just to switch renderers. Renderer changes should stay backwards-compatible with existing `PaintStamp` entries.
