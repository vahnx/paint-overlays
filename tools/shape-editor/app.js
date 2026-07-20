const shapeList = document.querySelector("#shapeList");
const commandsInput = document.querySelector("#commandsInput");
const fillableInput = document.querySelector("#fillableInput");
const strokeInput = document.querySelector("#strokeInput");
const fillInput = document.querySelector("#fillInput");
const preview = document.querySelector("#preview");
const status = document.querySelector("#status");

let catalog = {};
let shapeTypes = [];
let selectedShape = "";
let activeHandle = null;
let selectedHandle = null;
let selectedSegment = null;

function setStatus(message, kind = "") {
  status.textContent = message;
  status.className = kind;
}

function selectedDefinition() {
  return catalog[selectedShape] || { fillable: false, commands: [] };
}

async function loadCatalog() {
  const response = await fetch("/api/catalog");
  if (!response.ok) {
    throw new Error(await response.text());
  }
  const payload = await response.json();
  catalog = payload.catalog;
  shapeTypes = payload.shapeTypes;
  selectedShape = selectedShape || shapeTypes[0] || Object.keys(catalog)[0] || "";
  renderShapeList();
  loadSelectedShape();
  setStatus("Catalog loaded", "success");
}

function renderShapeList() {
  shapeList.innerHTML = "";
  for (const type of shapeTypes) {
    const option = document.createElement("option");
    option.value = type;
    option.textContent = catalog[type] ? type : `${type} (missing)`;
    option.selected = type === selectedShape;
    shapeList.append(option);
  }
}

function loadSelectedShape() {
  selectedHandle = null;
  selectedSegment = null;
  const definition = selectedDefinition();
  fillableInput.checked = Boolean(definition.fillable);
  commandsInput.value = JSON.stringify(definition, null, 2);
  renderPreview(definition);
}

function parseEditor() {
  const parsed = JSON.parse(commandsInput.value);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error("Selected shape JSON must be an object");
  }
  if (typeof parsed.fillable !== "boolean") {
    throw new Error("fillable must be true or false");
  }
  if (!Array.isArray(parsed.commands)) {
    throw new Error("commands must be an array");
  }
  return parsed;
}

function applyEditor() {
  const parsed = parseEditor();
  catalog[selectedShape] = parsed;
  selectedHandle = null;
  selectedSegment = null;
  fillableInput.checked = parsed.fillable;
  renderPreview(parsed);
  renderShapeList();
  setStatus(`Applied ${selectedShape} locally`, "success");
}

function scale(value) {
  return 40 + value * 320;
}

function pathFromCommands(commands) {
  let d = "";
  for (const command of commands) {
    switch (command.op) {
      case "move":
        d += `M ${scale(command.x)} ${scale(command.y)} `;
        break;
      case "line":
        d += `L ${scale(command.x)} ${scale(command.y)} `;
        break;
      case "quad":
        d += `Q ${scale(command.cx)} ${scale(command.cy)} ${scale(command.x)} ${scale(command.y)} `;
        break;
      case "cubic":
        d += `C ${scale(command.cx1)} ${scale(command.cy1)} ${scale(command.cx2)} ${scale(command.cy2)} ${scale(command.x)} ${scale(command.y)} `;
        break;
      case "close":
        d += "Z ";
        break;
      case "rect":
        d += `M ${scale(command.x)} ${scale(command.y)} h ${command.w * 320} v ${command.h * 320} h ${-command.w * 320} Z `;
        break;
      case "ellipse":
        d += ellipsePath(command);
        break;
      case "polygon":
        d += polygonPath(command);
        break;
      default:
        throw new Error(`Unsupported op: ${command.op}`);
    }
  }
  return d.trim();
}

function ellipsePath(command) {
  const x = scale(command.x);
  const y = scale(command.y);
  const rx = command.w * 160;
  const ry = command.h * 160;
  const cx = x + rx;
  const cy = y + ry;
  return `M ${cx - rx} ${cy} A ${rx} ${ry} 0 1 0 ${cx + rx} ${cy} A ${rx} ${ry} 0 1 0 ${cx - rx} ${cy} Z `;
}

function polygonPath(command) {
  const sides = Math.max(3, Number(command.sides || 3));
  const cx = scale(command.cx);
  const cy = scale(command.cy);
  const radius = Number(command.r || 0) * 320;
  const start = Number(command.start || 0);
  let d = "";
  for (let i = 0; i < sides; i += 1) {
    const angle = start + i * (Math.PI * 2 / sides);
    const x = cx + Math.cos(angle) * radius;
    const y = cy + Math.sin(angle) * radius;
    d += `${i === 0 ? "M" : "L"} ${x} ${y} `;
  }
  return command.closed ? `${d}Z ` : d;
}

function renderPreview(definition) {
  preview.innerHTML = "";

  const border = document.createElementNS("http://www.w3.org/2000/svg", "rect");
  border.setAttribute("x", "40");
  border.setAttribute("y", "40");
  border.setAttribute("width", "320");
  border.setAttribute("height", "320");
  border.setAttribute("fill", "none");
  border.setAttribute("stroke", "rgba(244,234,210,0.25)");
  border.setAttribute("stroke-dasharray", "8 8");
  preview.append(border);

  const shape = document.createElementNS("http://www.w3.org/2000/svg", "path");
  shape.setAttribute("d", pathFromCommands(definition.commands || []));
  shape.setAttribute("fill", definition.fillable ? fillInput.value : "none");
  shape.setAttribute("fill-opacity", "0.42");
  shape.setAttribute("stroke", strokeInput.value);
  shape.setAttribute("stroke-width", "7");
  shape.setAttribute("stroke-linecap", "round");
  shape.setAttribute("stroke-linejoin", "round");
  preview.append(shape);
  renderHandles(definition);
}

function renderHandles(definition) {
  const commands = definition.commands || [];
  let currentEnd = null;
  let subpathStart = null;

  for (const [commandIndex, command] of commands.entries()) {
    const handles = editableHandles(command);
    const endHandle = handles.find(handle => handle.role === "end");

    if (currentEnd && command.op === "line") {
      appendSelectableSegment(commandIndex, currentEnd, { x: command.x, y: command.y });
    } else if (currentEnd && subpathStart && command.op === "close") {
      appendSelectableSegment(commandIndex, currentEnd, subpathStart);
    }

    if (currentEnd && handles.length > 1) {
      for (const handle of handles.filter(item => item.role !== "end")) {
        appendControlLine(currentEnd, handle);
      }
    }

    for (const handle of handles) {
      appendHandle(commandIndex, handle);
    }

    if (endHandle) {
      currentEnd = { x: command.x, y: command.y };
    } else if (command.op === "move") {
      currentEnd = { x: command.x, y: command.y };
      subpathStart = currentEnd;
    } else if (command.op === "close") {
      currentEnd = null;
      subpathStart = null;
    }
  }
}

function appendSelectableSegment(commandIndex, from, to) {
  appendSelectedSegmentIndicator(commandIndex, from, to);

  const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
  line.setAttribute("class", "selectable-segment");
  line.setAttribute("x1", scale(from.x));
  line.setAttribute("y1", scale(from.y));
  line.setAttribute("x2", scale(to.x));
  line.setAttribute("y2", scale(to.y));
  line.dataset.commandIndex = String(commandIndex);
  line.addEventListener("pointerdown", event => {
    event.preventDefault();
    selectedHandle = null;
    selectedSegment = { commandIndex };
    renderPreview(selectedDefinition());
    setStatus(`Selected line in ${selectedShape}`, "success");
  });
  preview.append(line);
}

function appendSelectedSegmentIndicator(commandIndex, from, to) {
  if (!selectedSegment || selectedSegment.commandIndex !== commandIndex) {
    return;
  }

  const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
  line.setAttribute("class", "selected-segment");
  line.setAttribute("x1", scale(from.x));
  line.setAttribute("y1", scale(from.y));
  line.setAttribute("x2", scale(to.x));
  line.setAttribute("y2", scale(to.y));
  preview.append(line);
}

function editableHandles(command) {
  switch (command.op) {
    case "move":
    case "line":
      return [{ role: "end", xKey: "x", yKey: "y", x: command.x, y: command.y }];
    case "quad":
      return [
        { role: "control", xKey: "cx", yKey: "cy", x: command.cx, y: command.cy },
        { role: "end", xKey: "x", yKey: "y", x: command.x, y: command.y },
      ];
    case "cubic":
      return [
        { role: "control", xKey: "cx1", yKey: "cy1", x: command.cx1, y: command.cy1 },
        { role: "control", xKey: "cx2", yKey: "cy2", x: command.cx2, y: command.cy2 },
        { role: "end", xKey: "x", yKey: "y", x: command.x, y: command.y },
      ];
    default:
      return [];
  }
}

function appendControlLine(from, to) {
  const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
  line.setAttribute("class", "control-line");
  line.setAttribute("x1", scale(from.x));
  line.setAttribute("y1", scale(from.y));
  line.setAttribute("x2", scale(to.x));
  line.setAttribute("y2", scale(to.y));
  line.setAttribute("stroke", "rgba(245,211,107,0.38)");
  line.setAttribute("stroke-width", "2");
  line.setAttribute("stroke-dasharray", "6 6");
  preview.append(line);
}

function appendHandle(commandIndex, handle) {
  if (!Number.isFinite(handle.x) || !Number.isFinite(handle.y)) {
    return;
  }

  const circle = document.createElementNS("http://www.w3.org/2000/svg", "circle");
  const selected = selectedHandle
    && selectedHandle.commandIndex === commandIndex
    && selectedHandle.xKey === handle.xKey
    && selectedHandle.yKey === handle.yKey;
  circle.setAttribute("class", selected ? "handle selected" : "handle");
  circle.setAttribute("cx", scale(handle.x));
  circle.setAttribute("cy", scale(handle.y));
  circle.setAttribute("r", handle.role === "control" ? "7" : "9");
  circle.setAttribute("fill", handle.role === "control" ? "#8bd3ff" : "#f5d36b");
  circle.setAttribute("stroke", "#15130f");
  circle.setAttribute("stroke-width", "3");
  circle.dataset.commandIndex = String(commandIndex);
  circle.dataset.xKey = handle.xKey;
  circle.dataset.yKey = handle.yKey;
  circle.addEventListener("pointerdown", event => {
    event.preventDefault();
    selectedHandle = {
      commandIndex,
      xKey: handle.xKey,
      yKey: handle.yKey,
    };
    selectedSegment = null;
    activeHandle = {
      commandIndex,
      xKey: handle.xKey,
      yKey: handle.yKey,
      pointerId: event.pointerId,
    };
    circle.setPointerCapture(event.pointerId);
  });
  preview.append(circle);
}

function addPointAfterSelection() {
  const definition = selectedDefinition();
  if (!selectedHandle) {
    throw new Error("Select a gold path point first");
  }

  const command = definition.commands[selectedHandle.commandIndex];
  if (!command || !["move", "line"].includes(command.op)) {
    throw new Error("Add point works on selected move/line points");
  }

  const nextPoint = nextEditableEndPoint(definition.commands, selectedHandle.commandIndex);
  const newPoint = nextPoint
    ? { x: roundCoordinate((command.x + nextPoint.x) / 2), y: roundCoordinate((command.y + nextPoint.y) / 2) }
    : { x: roundCoordinate(clamp01(command.x + 0.08)), y: command.y };

  const insertIndex = selectedHandle.commandIndex + 1;
  definition.commands.splice(insertIndex, 0, { op: "line", x: newPoint.x, y: newPoint.y });
  selectedHandle = { commandIndex: insertIndex, xKey: "x", yKey: "y" };
  updateEditorFromCatalog();
  renderPreview(definition);
  setStatus(`Added point to ${selectedShape} locally`, "success");
}

function removeSelectedPoint() {
  const definition = selectedDefinition();
  if (!selectedHandle) {
    throw new Error("Select a gold path point first");
  }

  const command = definition.commands[selectedHandle.commandIndex];
  if (!command || !["move", "line"].includes(command.op)) {
    throw new Error("Remove point works on selected move/line points");
  }

  if (definition.commands.length <= 1) {
    throw new Error("Cannot remove the only command");
  }

  definition.commands.splice(selectedHandle.commandIndex, 1);
  selectedHandle = null;
  updateEditorFromCatalog();
  renderPreview(definition);
  setStatus(`Removed point from ${selectedShape} locally`, "success");
}

function deleteSelectedLine() {
  const definition = selectedDefinition();
  if (!selectedSegment) {
    throw new Error("Select a line first");
  }

  const command = definition.commands[selectedSegment.commandIndex];
  if (!command) {
    throw new Error("Selected line no longer exists");
  }

  if (command.op === "line") {
    command.op = "move";
  } else if (command.op === "close") {
    definition.commands.splice(selectedSegment.commandIndex, 1);
  } else {
    throw new Error("Selected command is not a deletable line");
  }

  selectedSegment = null;
  updateEditorFromCatalog();
  renderPreview(definition);
  setStatus(`Deleted line from ${selectedShape} locally`, "success");
}

function mirrorSelectedShape(keepSide) {
  const definition = selectedDefinition();
  const commands = definition.commands || [];
  if (!commands.every(isSimplePathCommand)) {
    throw new Error("Mirror currently supports move, line, and close commands only");
  }

  const keptSegments = extractSimpleSubpaths(commands)
    .flatMap(segment => sidePieces(segment, keepSide))
    .filter(segment => segment.points.length >= 2);

  if (keptSegments.length === 0) {
    throw new Error(`No editable ${keepSide} side path points found`);
  }

  const mirroredSegments = keptSegments.map(segment => {
    const mirrored = segment.points
      .map(point => ({ x: roundCoordinate(1 - point.x), y: point.y }))
      .reverse();
    return {
      points: keepSide === "left" ? segment.points.concat(mirrored) : mirrored.concat(segment.points),
      closed: segment.closed,
    };
  });

  definition.commands = simpleSubpathsToCommands(mirroredSegments);
  selectedHandle = null;
  selectedSegment = null;
  updateEditorFromCatalog();
  renderPreview(definition);
  setStatus(`Mirrored ${keepSide} side of ${selectedShape} locally`, "success");
}

function isSimplePathCommand(command) {
  return command && ["move", "line", "close"].includes(command.op);
}

function sidePieces(segment, keepSide) {
  const source = segment.closed
    ? segment.points.concat([segment.points[0]])
    : segment.points;
  const pieces = [];
  let current = [];

  for (let index = 0; index < source.length - 1; index += 1) {
    const from = source[index];
    const to = source[index + 1];
    const fromInside = pointIsOnKeptSide(from, keepSide);
    const toInside = pointIsOnKeptSide(to, keepSide);
    const crossing = crossesCenterLine(from, to);

    if (fromInside && current.length === 0) {
      current.push(from);
    }

    if (crossing) {
      const center = centerLineIntersection(from, to);
      if (fromInside) {
        current.push(center);
        pushPiece(pieces, current, segment.closed);
        current = [];
      } else if (toInside) {
        current = [center];
      }
    }

    if (toInside) {
      current.push(to);
    } else if (!crossing && current.length > 0) {
      pushPiece(pieces, current, segment.closed);
      current = [];
    }
  }

  if (current.length > 0) {
    pushPiece(pieces, current, segment.closed);
  }

  return pieces;
}

function pointIsOnKeptSide(point, keepSide) {
  return keepSide === "left" ? point.x <= 0.5 : point.x >= 0.5;
}

function crossesCenterLine(from, to) {
  return (from.x < 0.5 && to.x > 0.5) || (from.x > 0.5 && to.x < 0.5);
}

function centerLineIntersection(from, to) {
  const t = (0.5 - from.x) / (to.x - from.x);
  return {
    x: 0.5,
    y: roundCoordinate(from.y + (to.y - from.y) * t),
  };
}

function pushPiece(pieces, points, closed) {
  const normalized = removeDuplicateAdjacentPoints(points);
  if (normalized.length >= 2) {
    pieces.push({ points: normalized, closed });
  }
}

function extractSimpleSubpaths(commands) {
  const segments = [];
  let current = [];

  for (const command of commands) {
    if (command.op === "move") {
      if (current.length > 0) {
        segments.push({ points: current, closed: false });
      }
      current = [{ x: command.x, y: command.y }];
    } else if (command.op === "line") {
      current.push({ x: command.x, y: command.y });
    } else if (command.op === "close") {
      if (current.length > 0) {
        segments.push({ points: current, closed: true });
      }
      current = [];
    }
  }

  if (current.length > 0) {
    segments.push({ points: current, closed: false });
  }
  return segments;
}

function simpleSubpathsToCommands(segments) {
  const commands = [];
  for (const segment of segments) {
    const normalized = removeDuplicateAdjacentPoints(segment.points);
    if (normalized.length < 2) {
      continue;
    }
    commands.push({ op: "move", x: normalized[0].x, y: normalized[0].y });
    for (const point of normalized.slice(1)) {
      commands.push({ op: "line", x: point.x, y: point.y });
    }
    if (segment.closed) {
      commands.push({ op: "close" });
    }
  }
  return commands;
}

function removeDuplicateAdjacentPoints(points) {
  const filtered = [];
  for (const point of points) {
    const previous = filtered[filtered.length - 1];
    if (!previous || previous.x !== point.x || previous.y !== point.y) {
      filtered.push(point);
    }
  }
  return filtered;
}

function nextEditableEndPoint(commands, startIndex) {
  for (let index = startIndex + 1; index < commands.length; index += 1) {
    const command = commands[index];
    if (command.op === "move") {
      return null;
    }
    if (["line", "quad", "cubic"].includes(command.op)) {
      return { x: command.x, y: command.y };
    }
  }
  return null;
}

function normalizedPointFromEvent(event) {
  const point = preview.createSVGPoint();
  point.x = event.clientX;
  point.y = event.clientY;
  const transformed = point.matrixTransform(preview.getScreenCTM().inverse());
  return {
    x: clamp01((transformed.x - 40) / 320),
    y: clamp01((transformed.y - 40) / 320),
  };
}

function clamp01(value) {
  return Math.max(0, Math.min(1, value));
}

function roundCoordinate(value) {
  return Math.round(value * 1000) / 1000;
}

function updateEditorFromCatalog() {
  commandsInput.value = JSON.stringify(selectedDefinition(), null, 2);
}

async function saveCatalog() {
  applyEditor();
  const response = await fetch("/api/catalog", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(catalog),
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload.error || "Save failed");
  }
  setStatus("Catalog saved", "success");
}

document.querySelector("#reloadButton").addEventListener("click", () => {
  loadCatalog().catch(error => setStatus(error.message, "error"));
});

document.querySelector("#saveButton").addEventListener("click", () => {
  saveCatalog().catch(error => setStatus(error.message, "error"));
});

document.querySelector("#applyButton").addEventListener("click", () => {
  try {
    applyEditor();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#formatButton").addEventListener("click", () => {
  try {
    commandsInput.value = JSON.stringify(parseEditor(), null, 2);
    setStatus("Formatted selected shape JSON", "success");
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#addMissingButton").addEventListener("click", () => {
  for (const type of shapeTypes) {
    if (!catalog[type]) {
      catalog[type] = {
        fillable: false,
        commands: [{ op: "move", x: 0.25, y: 0.5 }, { op: "line", x: 0.75, y: 0.5 }],
      };
    }
  }
  renderShapeList();
  loadSelectedShape();
  setStatus("Missing enum shapes added locally", "success");
});

document.querySelector("#addPointButton").addEventListener("click", () => {
  try {
    addPointAfterSelection();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#removePointButton").addEventListener("click", () => {
  try {
    removeSelectedPoint();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#deleteLineButton").addEventListener("click", () => {
  try {
    deleteSelectedLine();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#mirrorLeftButton").addEventListener("click", () => {
  try {
    mirrorSelectedShape("left");
  } catch (error) {
    setStatus(error.message, "error");
  }
});

document.querySelector("#mirrorRightButton").addEventListener("click", () => {
  try {
    mirrorSelectedShape("right");
  } catch (error) {
    setStatus(error.message, "error");
  }
});

shapeList.addEventListener("change", () => {
  selectedShape = shapeList.value;
  loadSelectedShape();
});

fillableInput.addEventListener("change", () => {
  try {
    const definition = parseEditor();
    definition.fillable = fillableInput.checked;
    commandsInput.value = JSON.stringify(definition, null, 2);
    applyEditor();
  } catch (error) {
    setStatus(error.message, "error");
  }
});

strokeInput.addEventListener("input", () => renderPreview(selectedDefinition()));
fillInput.addEventListener("input", () => renderPreview(selectedDefinition()));

preview.addEventListener("pointermove", event => {
  if (!activeHandle) {
    return;
  }

  const definition = selectedDefinition();
  const command = definition.commands[activeHandle.commandIndex];
  if (!command) {
    return;
  }

  const point = normalizedPointFromEvent(event);
  command[activeHandle.xKey] = roundCoordinate(point.x);
  command[activeHandle.yKey] = roundCoordinate(point.y);
  updateEditorFromCatalog();
  renderPreview(definition);
});

preview.addEventListener("pointerup", event => {
  if (activeHandle && activeHandle.pointerId === event.pointerId) {
    activeHandle = null;
    setStatus(`Updated ${selectedShape} point locally`, "success");
  }
});

preview.addEventListener("pointercancel", () => {
  activeHandle = null;
});

loadCatalog().catch(error => setStatus(error.message, "error"));
