# Stamp PNG Assets

Paint Overlay stamp images are bundled plugin resources. They are not loaded dynamically from arbitrary user folders.

## Asset Location

Put stamp PNG files here:

```text
src/main/resources/com/paintoverlays/stamps/
```

The file name must match the `PaintStampType` enum constant after lowercasing it.

Examples:

```text
JAD -> jad.png
DRAGON_SWORD -> dragon_sword.png
RUNE_2H -> rune_2h.png
```

The mapping is implemented by `PaintStampType.getAssetName()` in:

```text
src/main/java/com/paintoverlays/PaintModels.java
```

Images are loaded by:

```text
src/main/java/com/paintoverlays/PaintStamps.java
```

## Adding A New Stamp

1. Add the PNG file to `src/main/resources/com/paintoverlays/stamps/`.
2. Add a matching enum constant to `PaintStampType` in `PaintModels.java`.
3. Give the enum constant a clear display name.
4. Follow the enum compatibility rules in `docs/data-compatibility.md`.
5. Run `./gradlew test`.
6. Launch the dev client with `./gradlew run` and verify the stamp appears in the shape/stamp picker.

Example:

```java
RUNE_SCIMITAR("Rune Scimitar")
```

Requires:

```text
src/main/resources/com/paintoverlays/stamps/rune_scimitar.png
```

## PNG Requirements

- Use real PNG files. Do not rename JPEG, ICO, or WebP files to `.png`.
- Prefer transparent-background PNGs.
- Keep dimensions modest. Java loads images in memory as roughly `width * height * 4` bytes before scaling.
- Recommended working sizes: `32x32`, `48x48`, or `64x64`.
- Avoid large source art such as `512x512` or larger unless there is a specific reason.
- Optimize PNGs before committing.
- Keep file names lowercase with underscores.
- Avoid copyrighted, adult, or moderation-sensitive content.

## Why Not Dynamic Folder Loading?

Plugin Hub-safe stamps should be known at build time and bundled in the plugin jar. Dynamic user-folder loading would require extra file I/O, validation, UI for selecting files, persistence behavior, and moderation considerations.

This plugin currently uses enum-backed bundled assets because it is predictable, reviewable, and easy for RuneLite Plugin Hub reviewers to inspect.

Dynamic loading from inside `.runelite/paint-overlays/` may be technically possible for a future opt-in feature, but it should be treated as a separate design and review task rather than the default stamp workflow.

## Compatibility Note

Stamp enum names are persisted in saved paint data. Do not rename or remove old `PaintStampType` constants after release unless a migration is added.

See `docs/data-compatibility.md` for the shared shape and stamp compatibility rules.

See `docs/stamp-rendering.md` for the current in-game stamp rendering method and the previous billboard method.
