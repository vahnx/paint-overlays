const fs = require("fs");
const http = require("http");
const path = require("path");
const url = require("url");

const PORT = Number(process.env.PAINT_SHAPE_EDITOR_PORT || 45731);
const ROOT = path.resolve(__dirname, "..", "..");
const EDITOR_ROOT = __dirname;
const CATALOG_PATH = path.join(ROOT, "src", "main", "resources", "com", "paintoverlays", "shapes", "catalog.json");
const MODELS_PATH = path.join(ROOT, "src", "main", "java", "com", "paintoverlays", "PaintModels.java");

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
};

function send(res, status, body, type = "text/plain; charset=utf-8") {
  res.writeHead(status, {
    "Content-Type": type,
    "Cache-Control": "no-store",
  });
  res.end(body);
}

function sendJson(res, status, value) {
  send(res, status, JSON.stringify(value), "application/json; charset=utf-8");
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      body += chunk;
      if (body.length > 1024 * 1024) {
        reject(new Error("Request body is too large"));
        req.destroy();
      }
    });
    req.on("end", () => resolve(body));
    req.on("error", reject);
  });
}

function shapeTypes() {
  const models = fs.readFileSync(MODELS_PATH, "utf8");
  const enumMatch = models.match(/enum\s+PaintShapeType\s*\{([\s\S]*?)\n\}/);
  if (!enumMatch) {
    return [];
  }

  return enumMatch[1]
    .split("\n")
    .map(line => line.trim().match(/^([A-Z0-9_]+)\("/))
    .filter(Boolean)
    .map(match => match[1]);
}

function validateCatalog(catalog, allowedTypes) {
  if (!catalog || Array.isArray(catalog) || typeof catalog !== "object") {
    throw new Error("Catalog must be a JSON object");
  }

  const allowed = new Set(allowedTypes);
  for (const [name, definition] of Object.entries(catalog)) {
    if (!allowed.has(name)) {
      throw new Error(`${name} is not a PaintShapeType enum constant`);
    }
    if (!definition || Array.isArray(definition) || typeof definition !== "object") {
      throw new Error(`${name} must be an object`);
    }
    if (typeof definition.fillable !== "boolean") {
      throw new Error(`${name}.fillable must be true or false`);
    }
    if (!Array.isArray(definition.commands) || definition.commands.length === 0) {
      throw new Error(`${name}.commands must be a non-empty array`);
    }
    for (const [index, command] of definition.commands.entries()) {
      if (!command || typeof command !== "object" || Array.isArray(command)) {
        throw new Error(`${name}.commands[${index}] must be an object`);
      }
      if (typeof command.op !== "string") {
        throw new Error(`${name}.commands[${index}].op must be a string`);
      }
    }
  }
}

function orderedCatalog(catalog, allowedTypes) {
  const ordered = {};
  for (const type of allowedTypes) {
    if (Object.prototype.hasOwnProperty.call(catalog, type)) {
      ordered[type] = catalog[type];
    }
  }
  return ordered;
}

function serveStatic(req, res, pathname) {
  const relativePath = pathname === "/" ? "index.html" : pathname.slice(1);
  const requestedPath = path.resolve(EDITOR_ROOT, relativePath);
  if (!requestedPath.startsWith(EDITOR_ROOT)) {
    send(res, 403, "Forbidden");
    return;
  }

  fs.readFile(requestedPath, (error, content) => {
    if (error) {
      send(res, 404, "Not found");
      return;
    }
    send(res, 200, content, CONTENT_TYPES[path.extname(requestedPath)] || "application/octet-stream");
  });
}

const server = http.createServer(async (req, res) => {
  const pathname = url.parse(req.url).pathname;

  try {
    if (req.method === "GET" && pathname === "/api/catalog") {
      sendJson(res, 200, {
        shapeTypes: shapeTypes(),
        catalog: JSON.parse(fs.readFileSync(CATALOG_PATH, "utf8")),
      });
      return;
    }

    if (req.method === "POST" && pathname === "/api/catalog") {
      const allowedTypes = shapeTypes();
      const catalog = JSON.parse(await readBody(req));
      validateCatalog(catalog, allowedTypes);
      const formatted = JSON.stringify(orderedCatalog(catalog, allowedTypes), null, 2) + "\n";
      fs.writeFileSync(CATALOG_PATH, formatted, "utf8");
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method === "GET") {
      serveStatic(req, res, pathname);
      return;
    }

    send(res, 405, "Method not allowed");
  } catch (error) {
    sendJson(res, 400, { error: error.message });
  }
});

server.listen(PORT, () => {
  console.log(`Paint shape editor: http://localhost:${PORT}`);
  console.log(`Editing: ${CATALOG_PATH}`);
});
