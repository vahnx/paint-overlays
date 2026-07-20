# Shape Editor

This directory is reserved for a local, developer-only shape editor for Paint Overlays.

The editor may read and write:

```text
src/main/resources/com/paintoverlays/shapes/catalog.json
```

The editor code itself must stay outside `src/main/java` and `src/main/resources` so it is not compiled into, or packaged with, the RuneLite plugin submitted to Plugin Hub.

## Packaging Boundary

RuneLite plugin runtime code lives in:

```text
src/main/java
src/main/resources
```

This tool directory is not part of Gradle's `main` source set. Do not add `tools/shape-editor` to `sourceSets.main`, `jar.from`, or any Plugin Hub release packaging task.

If the editor gets its own build system later, keep its build artifacts under this directory. The repo `.gitignore` excludes common editor outputs such as `tools/**/build/`, `tools/**/dist/`, and `tools/**/node_modules/`.

## Runtime Contract

Shape names must continue to match `PaintShapeType` enum constants in:

```text
src/main/java/com/paintoverlays/PaintModels.java
```

Shape definitions must continue to use the catalog command format loaded by:

```text
src/main/java/com/paintoverlays/PaintShapeCatalog.java
```

## Running

From the repo root:

```text
node tools/shape-editor/server.js
```

Then open:

```text
http://localhost:45731
```

No npm install is required. The server only uses Node built-in modules and is intentionally kept outside the RuneLite Gradle build.
