# Data Compatibility Rules

Paint Overlays persists user drawings as JSON. Shape and stamp entries store enum names, so enum names are part of the save-file format.

## Core Rule

Do not rename or remove persisted enum constants after release.

If an enum has ever been used by saved paint data, treat its constant name as a stable data ID. This applies even if the picker label, PNG, or shape definition changes later.

Backwards compatibility is a release requirement, not a nice-to-have. Any change to persisted paint JSON, enum-backed assets, shape/stamp rendering, or chunk loading must preserve drawings created by older released versions unless there is an explicit migration and test coverage for the old data.

When adding a new storage or rendering strategy, keep the old data path readable. For example, if new in-game shapes are stored as baked stroke points, existing saved `PaintShape` entries must still load and render unless a migration deliberately converts them.

## Shape Rules

- Keep existing `PaintShapeType` constants in `PaintModels.java`.
- Add new shape types as new enum constants.
- Do not rename old constants to make labels nicer. Change display labels or catalog data instead.
- Do not remove deprecated shapes. Hide them from the picker if needed, but keep the enum so old saved drawings still load.
- Keep shape definitions in `src/main/resources/com/paintoverlays/shapes/catalog.json` keyed by the stable enum names.

Legacy shape constants that should remain supported:

```text
RECTANGLE
CIRCLE
X
TRIANGLE
DIAMOND
STAR
PLUS
SKULL
PRAYER_STAR
TREASURE_CHEST
SPIDER_WEB
TARGET
```

## Stamp Rules

- Keep existing `PaintStampType` constants in `PaintModels.java`.
- Add new stamps as new enum constants.
- Do not rename old stamp constants when replacing art or changing labels.
- If a stamp is retired, hide or lock it in the picker but keep the enum and PNG fallback path.
- PNG file names are derived from enum names by `PaintStampType.getAssetName()`, so changing the enum changes the expected resource path.

Example:

```text
DRAGON_SWORD -> src/main/resources/com/paintoverlays/stamps/dragon_sword.png
```

If the visual name needs to change, keep the enum stable and only change the display name:

```java
DRAGON_SWORD("New Display Name")
```

## Renaming Policy

Only rename a shape or stamp enum if a migration is added at the same time.

A safe migration must:

- Map old enum names to new enum names during JSON load.
- Preserve unknown or unsupported names where possible.
- Log or show a clear missing asset/status message for data that cannot be migrated.
- Include tests for old saved JSON.
- Avoid deleting or rewriting user data unless the replacement format has been validated.

The current safer default is to avoid renames entirely.

## Current Fallback Behavior

Unknown stamp enum names are preserved as unsupported stamp entries when loading old JSON. They render with the fallback missing-stamp image and report the missing enum name in status/log output.

Unknown shape enum names should be avoided by keeping old `PaintShapeType` constants. If shape renames ever become necessary, add an explicit migration before release.
