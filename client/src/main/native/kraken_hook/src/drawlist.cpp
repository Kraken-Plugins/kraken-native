#include "drawlist.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <cmath>
#include <utility>

#include "calls.h"
#include "core/memory.h"
#include "frames.h"
#include "protocol.h"

namespace kraken::drawlist {
namespace {

using memory::readI32;
using memory::readU64;
using memory::safeRead;

constexpr uint32_t NONE = 0xffffffffu;   // an offset the mapping lacks
constexpr uint64_t MAX_BUCKETS = 1u << 20;
constexpr int MAX_CHAIN = 4096;          // nodes followed along one bucket's chain
constexpr int SLOTS = 4;                 // batches waiting for their present
constexpr int32_t MAX_SCENE = 4096;      // tiles a scene can span; more reads as corrupt
constexpr int32_t MAX_SCREEN = 16384;    // pixels a window or canvas can span
constexpr uint16_t MAX_TILE_SPAN = 64;   // tiles a tile area can span
constexpr float FILL_HALF_WIDTH = 1.0e6f; // a half width no fill pixel is near the edge of
constexpr float MITER_LIMIT = 4.0f;
constexpr float WHITE_UV = (DRAW_ATLAS_WHITE / 2.0f) / DRAW_ATLAS_SIZE; // the middle of the white corner
constexpr size_t ATLAS_ROW = DRAW_ATLAS_SIZE * 4;

/**
 * Where the client keeps what the items are resolved against, from the handshake, in the block's
 * order (NativeProtocol.DRAW_OFFSETS). Byte offsets.
 */
struct Layout {
    uint32_t topLevelWorld;  // Client: the top-level World*
    uint32_t scene;          // World: its Scene*
    uint32_t plane;          // World: the level the player is on
    uint32_t width;          // World: the scene's size in tiles, as the API bounds points
    uint32_t height;
    uint32_t npcs;           // World: hash map of index -> Npc*
    uint32_t mapBuckets;     // eastl hash map: the bucket array and its length
    uint32_t mapBucketCount;
    uint32_t nodeKey;        // a hash map node's key, value pointer and next node
    uint32_t nodeValue;
    uint32_t nodeNext;
    uint32_t spawnCycle;     // Npc: the cycle it was created on, which tells two NPCs at one index apart
    uint32_t fineX;          // Actor: its position in local units
    uint32_t fineZ;
    uint32_t pathX;          // Actor: its route's scene tiles; the first is the tile the server put it on
    uint32_t pathZ;
    uint32_t sizeX;          // Scene: its size in tiles, as the ground heights are laid out
    uint32_t sizeZ;
    uint32_t tileSettings;   // Scene: the tile flag bytes, a pointer
    uint32_t heights;        // Scene: the corner heights, a pointer
    uint32_t cameraTrig;     // Scene: sin and cos of the camera's pitch and yaw, four floats
    uint32_t cameraPos;      // Client: the camera's eye, three int32 (x, height, north)
    uint32_t scale;          // Client: the projection scale
    uint32_t renderer;       // Client: the Renderer*
    uint32_t viewport;       // Renderer: the viewport, inline
    uint32_t centerX;        // Viewport: its centre and offset in window pixels
    uint32_t centerY;
    uint32_t offsetX;
    uint32_t offsetY;
    uint32_t canvasWidth;    // Renderer: the canvas (the client's UI pixels) and the window
    uint32_t canvasHeight;
    uint32_t screenWidth;
    uint32_t screenHeight;
};

/**
 * One item of a DRAW_LIST command.
 */
struct Item {
    uint8_t kind = 0;
    uint8_t anchor = 0;
    uint32_t index = 0;
    int32_t spawnCycle = 0;
    int32_t dx = 0;          // the shift from the anchor in local units
    int32_t dy = 0;
    int32_t lift = 0;        // how far above the ground, in local units
    uint16_t sizeX = 0;      // DRAW_TILE_AREA: the area in tiles, its colours and stroke width
    uint16_t sizeY = 0;
    uint32_t stroke = 0;
    uint32_t fill = 0;
    uint16_t strokeWidth = 0;
    uint16_t atlasX = 0;     // DRAW_SPRITE: the sprite's place in the atlas and its offset from the anchor
    uint16_t atlasY = 0;
    uint16_t atlasW = 0;
    uint16_t atlasH = 0;
    int16_t offsetX = 0;
    int16_t offsetY = 0;
};

/**
 * The items a DRAW_LIST sent, and the atlas version its sprites need.
 */
struct List {
    std::vector<Item> items;
    uint32_t atlasVersion = 0;
};

/**
 * The camera a frame was recorded with: the eye, the sines and cosines of its pitch and yaw, the
 * projection scale and the viewport's centre, as Client.getCameraProjection reads them.
 */
struct Camera {
    float eyeX;
    float eyeY;
    float eyeZ;
    float sinPitch;
    float cosPitch;
    float sinYaw;
    float cosYaw;
    float scale;
    float centerX;
    float centerY;
    uint32_t screenWidth;
    uint32_t screenHeight;
    float windowPerCanvas;
};

/**
 * The top-level world's scene as the ground lookup needs it.
 */
struct Ground {
    uint64_t scene;
    uint64_t heights;
    uint64_t settings;
    int32_t sizeX;
    int32_t sizeZ;
    int32_t width;
    int32_t height;
    int32_t plane;
};

/**
 * A point on the screen in window pixels.
 */
struct ScreenPoint {
    float x;
    float y;
};

Layout g_layout;                  // written once, before g_ready
std::atomic<bool> g_ready{false};

// The list the last command (or a clear) sent, until the game thread takes it.
SRWLOCK g_pendingLock = SRWLOCK_INIT;
List g_pending;                   // guarded by g_pendingLock
std::atomic<bool> g_hasPending{false};

// Game thread only.
List g_list;
Batch g_build;

// Batches waiting for their present; seq 0 marks a slot empty or taken.
SRWLOCK g_slotLock = SRWLOCK_INIT;
Batch g_slots[SLOTS];             // guarded by g_slotLock
uint32_t g_seq = 0;               // guarded by g_slotLock

// The sprite atlas, BGRA, top row first; the rows [g_dirtyTop, g_dirtyBottom) changed since the
// present thread last copied them.
SRWLOCK g_atlasLock = SRWLOCK_INIT;
std::vector<uint8_t> g_atlas;     // guarded by g_atlasLock, as are the three below
uint32_t g_atlasVersion = 0;
uint32_t g_dirtyTop = 0;
uint32_t g_dirtyBottom = 0;

/**
 * Allocates the atlas on first use, clear except for its white corner. Call with g_atlasLock held
 * exclusively.
 */
void ensureAtlas() {
    if (!g_atlas.empty()) {
        return;
    }
    g_atlas.assign(static_cast<size_t>(DRAW_ATLAS_SIZE) * ATLAS_ROW, 0);
    for (uint32_t y = 0; y < DRAW_ATLAS_WHITE; y++) {
        std::fill_n(g_atlas.begin() + static_cast<ptrdiff_t>(y * ATLAS_ROW), DRAW_ATLAS_WHITE * 4, uint8_t{0xff});
    }
    g_dirtyTop = 0;
    g_dirtyBottom = DRAW_ATLAS_SIZE;
}

/**
 * Takes the list a command or a clear left, unless the reader thread holds it right now, in which
 * case the next frame takes it.
 */
void takePending() {
    if (!g_hasPending.load(std::memory_order_acquire) || !TryAcquireSRWLockExclusive(&g_pendingLock)) {
        return;
    }
    g_list = std::move(g_pending);
    g_pending = List{};
    g_hasPending.store(false, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

/**
 * Looks a key up in an eastl hash map, as StructReader.hashGet does.
 *
 * @param map the hash map object
 * @param key the key
 * @return the value pointer, or 0 if the key is absent or the map looks corrupt
 */
uint64_t hashGet(uint64_t map, int32_t key) {
    const Layout &l = g_layout;
    uint64_t buckets = readU64(map + l.mapBuckets, 0);
    uint64_t count = readU64(map + l.mapBucketCount, 0);
    if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
        return 0;
    }
    uint64_t node = readU64(buckets + static_cast<uint64_t>(static_cast<uint32_t>(key)) % count * 8, 0);
    for (int steps = 0; node != 0 && steps < MAX_CHAIN; steps++) {
        if (readI32(node + l.nodeKey, ~key) == key) {
            return readU64(node + l.nodeValue, 0);
        }
        node = readU64(node + l.nodeNext, 0);
    }
    return 0;
}

/**
 * Reads the camera the frame was recorded with, as Client.getCameraProjection does.
 *
 * @param client the Client*
 * @param scene  the top-level world's Scene*
 * @param c      receives the camera
 * @return false if it can't be read or the window or canvas has no size
 */
bool readCamera(uint64_t client, uint64_t scene, Camera &c) {
    const Layout &l = g_layout;
    int32_t eye[3];
    float trig[4];
    if (!safeRead(client + l.cameraPos, eye, sizeof eye) || !safeRead(scene + l.cameraTrig, trig, sizeof trig)) {
        return false;
    }
    uint64_t renderer = readU64(client + l.renderer, 0);
    if (renderer == 0) {
        return false;
    }
    int32_t screenWidth = readI32(renderer + l.screenWidth, 0);
    int32_t screenHeight = readI32(renderer + l.screenHeight, 0);
    int32_t canvasWidth = readI32(renderer + l.canvasWidth, 0);
    if (screenWidth <= 0 || screenHeight <= 0 || canvasWidth <= 0 || screenWidth > MAX_SCREEN
            || screenHeight > MAX_SCREEN || canvasWidth > MAX_SCREEN) {
        return false;
    }
    uint64_t viewport = renderer + l.viewport;
    c.eyeX = static_cast<float>(eye[0]);
    c.eyeY = static_cast<float>(eye[1]);
    c.eyeZ = static_cast<float>(eye[2]);
    c.sinPitch = trig[0];
    c.cosPitch = trig[1];
    c.sinYaw = trig[2];
    c.cosYaw = trig[3];
    c.scale = static_cast<float>(readI32(client + l.scale, 0));
    c.centerX = static_cast<float>(readI32(viewport + l.centerX, 0) + readI32(viewport + l.offsetX, 0));
    c.centerY = static_cast<float>(readI32(viewport + l.centerY, 0) + readI32(viewport + l.offsetY, 0));
    c.screenWidth = static_cast<uint32_t>(screenWidth);
    c.screenHeight = static_cast<uint32_t>(screenHeight);
    c.windowPerCanvas = static_cast<float>(screenWidth) / static_cast<float>(canvasWidth);
    return true;
}

/**
 * Projects a point of the top-level scene with CameraProjection.project's arithmetic, kept in floats
 * rather than truncated to whole pixels, so a point moves smoothly.
 *
 * @param c      the camera
 * @param x      the local x
 * @param north  the local y (north)
 * @param height the height (more negative is higher)
 * @param out    receives the point in window pixels
 * @return false if the point is at or behind the camera
 */
bool project(const Camera &c, float x, float north, float height, ScreenPoint &out) {
    float dx = x - c.eyeX;
    float dy = height - c.eyeY;
    float dz = north - c.eyeZ;
    float right = c.cosYaw * dx + c.sinYaw * dz;
    float forward = c.cosYaw * dz - c.sinYaw * dx;
    float down = c.cosPitch * dy - c.sinPitch * forward;
    float depth = c.sinPitch * dy + c.cosPitch * forward;
    if (!(depth > 0)) {
        return false;
    }
    out.x = c.scale * right / depth + c.centerX;
    out.y = c.scale * down / depth + c.centerY;
    return std::isfinite(out.x) && std::isfinite(out.y);
}

/**
 * Reads the top-level world's scene and plane.
 *
 * @param world the top-level World*
 * @param g     receives the scene
 * @return false if the world has no readable scene
 */
bool readGround(uint64_t world, Ground &g) {
    const Layout &l = g_layout;
    uint64_t scene = readU64(world + l.scene, 0);
    if (scene == 0) {
        return false;
    }
    g.scene = scene;
    g.sizeX = readI32(scene + l.sizeX, 0);
    g.sizeZ = readI32(scene + l.sizeZ, 0);
    g.settings = readU64(scene + l.tileSettings, 0);
    g.heights = readU64(scene + l.heights, 0);
    g.width = readI32(world + l.width, 0);
    g.height = readI32(world + l.height, 0);
    g.plane = readI32(world + l.plane, -1);
    return g.heights != 0 && g.sizeX > 0 && g.sizeX <= MAX_SCENE && g.sizeZ > 0 && g.sizeZ <= MAX_SCENE
           && g.width > 0 && g.width <= MAX_SCENE && g.height > 0 && g.height <= MAX_SCENE && g.plane >= 0
           && g.plane <= 3;
}

/**
 * One corner height: the heights are a (levels x (sizeX + 1) x (sizeZ + 1)) int32 array.
 *
 * @param g     the scene
 * @param level the level
 * @param x     the corner's x
 * @param z     the corner's z
 * @return the height, 0 if it can't be read
 */
int32_t cornerHeight(const Ground &g, int32_t level, int32_t x, int32_t z) {
    int64_t index = (static_cast<int64_t>(level) * (g.sizeX + 1) + x) * (g.sizeZ + 1) + z;
    return readI32(g.heights + static_cast<uint64_t>(index) * 4, 0);
}

/**
 * The ground's height at a point, as ActorReader.groundHeight works it out: bilinear between the
 * tile's corner heights, taken from the level above under a bridge.
 *
 * @param g     the scene
 * @param fx    the local x
 * @param fz    the local y (north)
 * @param plane the level
 * @return the height (more negative is higher), 0 outside the scene
 */
int32_t groundHeight(const Ground &g, int32_t fx, int32_t fz, int32_t plane) {
    int32_t tx = fx >> 7;
    int32_t tz = fz >> 7;
    if (tx < 0 || tz < 0 || tx > g.sizeX - 1 || tz > g.sizeZ - 1) {
        return 0;
    }
    int32_t level = plane;
    if (plane < 3 && g.settings != 0) {
        uint8_t flags = 0;
        uint64_t at = g.settings + static_cast<uint64_t>(g.sizeX + tx) * static_cast<uint64_t>(g.sizeZ) + tz;
        if (safeRead(at, &flags, 1) && (flags & 2) != 0) {
            level = plane + 1;
        }
    }
    int32_t xf = fx & 127;
    int32_t zf = fz & 127;
    int32_t sw = cornerHeight(g, level, tx, tz);
    int32_t se = cornerHeight(g, level, tx + 1, tz);
    int32_t nw = cornerHeight(g, level, tx, tz + 1);
    int32_t ne = cornerHeight(g, level, tx + 1, tz + 1);
    int32_t south = (sw * (128 - xf) + se * xf) >> 7;
    int32_t north = (nw * (128 - xf) + ne * xf) >> 7;
    return (south * (128 - zf) + north * zf) >> 7;
}

/**
 * The NPC an item is anchored to, if the top-level world still holds it.
 *
 * @param world the top-level World*
 * @param item  the item
 * @return the Npc*, or 0 if its index is empty or holds another NPC
 */
uint64_t findNpc(uint64_t world, const Item &item) {
    uint64_t npc = hashGet(world + g_layout.npcs, static_cast<int32_t>(item.index));
    if (npc == 0 || readI32(npc + g_layout.spawnCycle, ~item.spawnCycle) != item.spawnCycle) {
        return 0;
    }
    return npc;
}

/**
 * An item's point in local units, shifted by its dx and dy: the NPC's fine position, or the centre of
 * the tile the server put it on.
 *
 * @param npc  the Npc*
 * @param item the item
 * @param x    receives the local x
 * @param y    receives the local y (north)
 * @return false if the position can't be read
 */
bool anchorPoint(uint64_t npc, const Item &item, int32_t &x, int32_t &y) {
    const Layout &l = g_layout;
    if (item.anchor == ANCHOR_NPC) {
        x = readI32(npc + l.fineX, INT_MIN);
        y = readI32(npc + l.fineZ, INT_MIN);
        if (x == INT_MIN || y == INT_MIN) {
            return false;
        }
    } else {
        int32_t tx = readI32(npc + l.pathX, -1);
        int32_t tz = readI32(npc + l.pathZ, -1);
        if (tx < 0 || tz < 0 || tx >= MAX_SCENE || tz >= MAX_SCENE) {
            return false;
        }
        x = tx * 128 + 64;
        y = tz * 128 + 64;
    }
    x += item.dx;
    y += item.dy;
    return true;
}

/**
 * Whether a point lies in the world's scene, as WorldView.contains bounds it.
 *
 * @param g the scene
 * @param x the local x
 * @param y the local y
 * @return true if it does
 */
bool inScene(const Ground &g, int32_t x, int32_t y) {
    return x >= 0 && y >= 0 && (x >> 7) < g.width && (y >> 7) < g.height;
}

/**
 * Premultiplies an ARGB colour.
 *
 * @param argb the colour, alpha in the high byte
 * @return premultiplied RGBA, red in the low byte
 */
uint32_t premultiply(uint32_t argb) {
    uint32_t a = argb >> 24;
    uint32_t r = ((argb >> 16 & 0xff) * a + 127) / 255;
    uint32_t g = ((argb >> 8 & 0xff) * a + 127) / 255;
    uint32_t b = ((argb & 0xff) * a + 127) / 255;
    return r | g << 8 | b << 16 | a << 24;
}

/**
 * Adds an untextured vertex.
 *
 * @param out       the vertices
 * @param p         where
 * @param colour    premultiplied RGBA
 * @param distance  the signed distance across a stroke
 * @param halfWidth the stroke's half width, FILL_HALF_WIDTH for a fill
 */
void pushPlain(std::vector<Vertex> &out, ScreenPoint p, uint32_t colour, float distance, float halfWidth) {
    out.push_back(Vertex{p.x, p.y, 0.0f, 0.0f, WHITE_UV, WHITE_UV, colour, distance, halfWidth});
}

/**
 * The unit vector from one point to another, or +x if they coincide.
 *
 * @param from the start
 * @param to   the end
 * @return the direction
 */
ScreenPoint direction(ScreenPoint from, ScreenPoint to) {
    float dx = to.x - from.x;
    float dy = to.y - from.y;
    float length = std::sqrt(dx * dx + dy * dy);
    if (length < 1.0e-6f) {
        return {1.0f, 0.0f};
    }
    return {dx / length, dy / length};
}

/**
 * Adds the outline of a closed polygon as triangles: a miter-joined band reaching one pixel past the
 * line's half width on both sides, which the fragment shader fades out for anti-aliasing.
 *
 * @param out       the vertices
 * @param points    the polygon's corners
 * @param n         how many
 * @param halfWidth the line's half width in window pixels
 * @param colour    premultiplied RGBA
 */
void strokePolygon(std::vector<Vertex> &out, const ScreenPoint *points, int n, float halfWidth, uint32_t colour) {
    constexpr int MAX_CORNERS = 8;
    ScreenPoint inner[MAX_CORNERS];
    ScreenPoint outer[MAX_CORNERS];
    float reach = halfWidth + 1.0f;
    for (int i = 0; i < n; i++) {
        ScreenPoint p = points[i];
        ScreenPoint in = direction(points[(i + n - 1) % n], p);
        ScreenPoint on = direction(p, points[(i + 1) % n]);
        ScreenPoint n0{-in.y, in.x};
        ScreenPoint n1{-on.y, on.x};
        ScreenPoint miter{n0.x + n1.x, n0.y + n1.y};
        float length = std::sqrt(miter.x * miter.x + miter.y * miter.y);
        if (length < 1.0e-4f) {
            miter = n1;
        } else {
            miter = {miter.x / length, miter.y / length};
        }
        float cosine = std::max(miter.x * n1.x + miter.y * n1.y, 1.0f / MITER_LIMIT);
        float extent = reach / cosine;
        outer[i] = {p.x + miter.x * extent, p.y + miter.y * extent};
        inner[i] = {p.x - miter.x * extent, p.y - miter.y * extent};
    }
    for (int i = 0; i < n; i++) {
        int j = (i + 1) % n;
        pushPlain(out, inner[i], colour, -reach, halfWidth);
        pushPlain(out, outer[i], colour, reach, halfWidth);
        pushPlain(out, outer[j], colour, reach, halfWidth);
        pushPlain(out, inner[i], colour, -reach, halfWidth);
        pushPlain(out, outer[j], colour, reach, halfWidth);
        pushPlain(out, inner[j], colour, -reach, halfWidth);
    }
}

/**
 * Adds a tile area: its outline, then its fill over it, as OverlayUtil.renderPolygon draws them. The
 * corners follow the ground, each looked up just inside the scene, as
 * Perspective.getCanvasTileAreaPoly does.
 *
 * @param out  the vertices
 * @param c    the camera
 * @param g    the scene
 * @param item the item
 * @param x    the area's centre, local x
 * @param y    the area's centre, local y
 */
void tileArea(std::vector<Vertex> &out, const Camera &c, const Ground &g, const Item &item, int32_t x, int32_t y) {
    int32_t halfX = item.sizeX * 128 / 2;
    int32_t halfY = item.sizeY * 128 / 2;
    int32_t corners[4][2] = {{x - halfX, y - halfY}, {x + halfX, y - halfY}, {x + halfX, y + halfY},
                             {x - halfX, y + halfY}};
    int32_t lastX = g.width * 128 - 1;
    int32_t lastY = g.height * 128 - 1;
    ScreenPoint points[4];
    for (int i = 0; i < 4; i++) {
        int32_t groundX = std::clamp(corners[i][0], 0, lastX);
        int32_t groundY = std::clamp(corners[i][1], 0, lastY);
        float height = static_cast<float>(groundHeight(g, groundX, groundY, g.plane) - item.lift);
        if (!project(c, static_cast<float>(corners[i][0]), static_cast<float>(corners[i][1]), height, points[i])) {
            return;
        }
    }
    uint32_t stroke = premultiply(item.stroke);
    float halfWidth = item.strokeWidth / 16.0f * c.windowPerCanvas / 2.0f;
    if (stroke != 0 && halfWidth > 0) {
        strokePolygon(out, points, 4, halfWidth, stroke);
    }
    uint32_t fill = premultiply(item.fill);
    if (fill != 0) {
        for (int i : {0, 1, 2, 0, 2, 3}) {
            pushPlain(out, points[i], fill, 0.0f, FILL_HALF_WIDTH);
        }
    }
}

/**
 * Adds a sprite: two triangles whose corners sit on the projected point and carry their offset from
 * it, so the present can place them on whole framebuffer pixels.
 *
 * @param out  the vertices
 * @param c    the camera
 * @param g    the scene
 * @param item the item
 * @param x    the anchor's local x
 * @param y    the anchor's local y
 */
void sprite(std::vector<Vertex> &out, const Camera &c, const Ground &g, const Item &item, int32_t x, int32_t y) {
    ScreenPoint at{};
    float height = static_cast<float>(groundHeight(g, x, y, g.plane) - item.lift);
    if (!project(c, static_cast<float>(x), static_cast<float>(y), height, at)) {
        return;
    }
    constexpr float SIZE = static_cast<float>(DRAW_ATLAS_SIZE);
    float u0 = item.atlasX / SIZE;
    float v0 = item.atlasY / SIZE;
    float u1 = (item.atlasX + item.atlasW) / SIZE;
    float v1 = (item.atlasY + item.atlasH) / SIZE;
    float x0 = item.offsetX;
    float y0 = item.offsetY;
    float x1 = x0 + item.atlasW;
    float y1 = y0 + item.atlasH;
    Vertex corners[4] = {{at.x, at.y, x0, y0, u0, v0, 0xffffffffu, 0.0f, -1.0f},
                         {at.x, at.y, x1, y0, u1, v0, 0xffffffffu, 0.0f, -1.0f},
                         {at.x, at.y, x1, y1, u1, v1, 0xffffffffu, 0.0f, -1.0f},
                         {at.x, at.y, x0, y1, u0, v1, 0xffffffffu, 0.0f, -1.0f}};
    for (int i : {0, 1, 2, 0, 2, 3}) {
        out.push_back(corners[i]);
    }
}

/**
 * Resolves every item against the frame's state into a batch: the tile areas first, then the sprites
 * over them. An item whose NPC is gone, whose point is outside the scene or behind the camera, adds
 * nothing.
 *
 * @param b the batch, empty
 */
void build(Batch &b) {
    uint64_t client = calls::clientPointer();
    uint64_t world = client == 0 ? 0 : readU64(client + g_layout.topLevelWorld, 0);
    Ground g{};
    Camera c{};
    if (world == 0 || !readGround(world, g)) {
        return;
    }
    if (!readCamera(client, g.scene, c)) {
        return;
    }
    b.screenWidth = c.screenWidth;
    b.screenHeight = c.screenHeight;
    for (uint8_t pass : {DRAW_TILE_AREA, DRAW_SPRITE}) {
        if (pass == DRAW_SPRITE) {
            b.spriteStart = b.vertices.size();
        }
        uint32_t lastIndex = 0xffffffffu;
        int32_t lastCycle = 0;
        uint64_t npc = 0;
        for (const Item &item : g_list.items) {
            if (item.kind != pass) {
                continue;
            }
            if (item.index != lastIndex || item.spawnCycle != lastCycle) {
                npc = findNpc(world, item);
                lastIndex = item.index;
                lastCycle = item.spawnCycle;
            }
            int32_t x = 0;
            int32_t y = 0;
            if (npc == 0 || !anchorPoint(npc, item, x, y) || !inScene(g, x, y)) {
                continue;
            }
            if (pass == DRAW_TILE_AREA) {
                tileArea(b.vertices, c, g, item, x, y);
            } else {
                sprite(b.vertices, c, g, item, x, y);
            }
        }
    }
}

/**
 * Puts a built batch in the oldest slot, giving the batch that slot's storage to build into next.
 *
 * @param b the batch; holds the old slot's contents after
 */
void publish(Batch &b) {
    AcquireSRWLockExclusive(&g_slotLock);
    int oldest = 0;
    for (int i = 1; i < SLOTS; i++) {
        if (g_slots[i].seq < g_slots[oldest].seq) {
            oldest = i;
        }
    }
    if (++g_seq == 0) {
        ++g_seq; // 0 marks an empty slot
    }
    b.seq = g_seq;
    std::swap(g_slots[oldest], b);
    ReleaseSRWLockExclusive(&g_slotLock);
}

/**
 * Reads one item of a DRAW_LIST command.
 *
 * @param r    the command's reader
 * @param item receives the item
 * @return false if it is malformed
 */
bool readItem(FrameReader &r, Item &item) {
    if (!r.get(item.kind) || !r.get(item.anchor) || !r.get(item.index) || !r.get(item.spawnCycle)
            || !r.get(item.dx) || !r.get(item.dy) || !r.get(item.lift) || item.anchor > ANCHOR_NPC_TILE) {
        return false;
    }
    if (item.kind == DRAW_TILE_AREA) {
        return r.get(item.sizeX) && r.get(item.sizeY) && r.get(item.stroke) && r.get(item.fill)
               && r.get(item.strokeWidth) && item.sizeX > 0 && item.sizeY > 0 && item.sizeX <= MAX_TILE_SPAN
               && item.sizeY <= MAX_TILE_SPAN;
    }
    if (item.kind == DRAW_SPRITE) {
        return r.get(item.atlasX) && r.get(item.atlasY) && r.get(item.atlasW) && r.get(item.atlasH)
               && r.get(item.offsetX) && r.get(item.offsetY) && item.atlasW > 0 && item.atlasH > 0
               && static_cast<uint32_t>(item.atlasX) + item.atlasW <= DRAW_ATLAS_SIZE
               && static_cast<uint32_t>(item.atlasY) + item.atlasH <= DRAW_ATLAS_SIZE;
    }
    return false;
}

} // namespace

void install(FrameReader r) {
    if (g_ready.load(std::memory_order_acquire)) {
        return;
    }
    Layout l{};
    uint32_t *fields[] = {&l.topLevelWorld, &l.scene, &l.plane, &l.width, &l.height, &l.npcs, &l.mapBuckets,
                          &l.mapBucketCount, &l.nodeKey, &l.nodeValue, &l.nodeNext, &l.spawnCycle, &l.fineX,
                          &l.fineZ, &l.pathX, &l.pathZ, &l.sizeX, &l.sizeZ, &l.tileSettings, &l.heights,
                          &l.cameraTrig, &l.cameraPos, &l.scale, &l.renderer, &l.viewport, &l.centerX,
                          &l.centerY, &l.offsetX, &l.offsetY, &l.canvasWidth, &l.canvasHeight, &l.screenWidth,
                          &l.screenHeight};
    for (uint32_t *field : fields) {
        if (!r.get(*field) || *field == NONE) {
            return;
        }
    }
    g_layout = l;
    g_ready.store(true, std::memory_order_release);
}

bool live() {
    return g_ready.load(std::memory_order_acquire) && frames::live();
}

void sprites(FrameReader r) {
    if (!live()) {
        return;
    }
    uint16_t count = 0;
    if (!r.get(count)) {
        return;
    }
    AcquireSRWLockExclusive(&g_atlasLock);
    ensureAtlas();
    for (uint16_t i = 0; i < count; i++) {
        uint16_t x16 = 0;
        uint16_t y16 = 0;
        uint16_t w16 = 0;
        uint16_t h16 = 0;
        if (!r.get(x16) || !r.get(y16) || !r.get(w16) || !r.get(h16)) {
            break;
        }
        uint32_t x = x16;
        uint32_t y = y16;
        uint32_t w = w16;
        uint32_t h = h16;
        if (w == 0 || h == 0 || x + w > DRAW_ATLAS_SIZE || y + h > DRAW_ATLAS_SIZE
                || (x < DRAW_ATLAS_WHITE && y < DRAW_ATLAS_WHITE) || r.left < static_cast<size_t>(w) * h * 4) {
            break; // a malformed sprite: keep the ones before it
        }
        for (uint32_t row = 0; row < h; row++) {
            r.bytes(g_atlas.data() + (y + row) * ATLAS_ROW + static_cast<size_t>(x) * 4, static_cast<size_t>(w) * 4);
        }
        g_dirtyTop = std::min(g_dirtyTop, y);
        g_dirtyBottom = std::max(g_dirtyBottom, y + h);
    }
    g_atlasVersion++;
    ReleaseSRWLockExclusive(&g_atlasLock);
}

void command(FrameReader r) {
    if (!live()) {
        return;
    }
    List list;
    uint16_t count = 0;
    if (!r.get(count) || count > MAX_DRAW_ITEMS) {
        return;
    }
    list.items.resize(count);
    for (Item &item : list.items) {
        if (!readItem(r, item)) {
            return; // a malformed command changes nothing
        }
    }
    AcquireSRWLockShared(&g_atlasLock);
    list.atlasVersion = g_atlasVersion; // every sprite sent before this list is in the atlas by now
    ReleaseSRWLockShared(&g_atlasLock);
    AcquireSRWLockExclusive(&g_pendingLock);
    g_pending = std::move(list);
    g_hasPending.store(true, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

void resolve(uint64_t frame) {
    if (!g_ready.load(std::memory_order_acquire)) {
        return;
    }
    takePending();
    Batch &b = g_build;
    b.vertices.clear();
    b.frame = frame;
    b.screenWidth = 0;
    b.screenHeight = 0;
    b.atlasVersion = g_list.atlasVersion;
    b.spriteStart = 0;
    if (!g_list.items.empty()) {
        build(b);
    }
    publish(b);
}

void clear() {
    AcquireSRWLockExclusive(&g_pendingLock);
    g_pending = List{};
    g_hasPending.store(true, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

bool takeBatch(uint64_t frame, Batch &out) {
    AcquireSRWLockExclusive(&g_slotLock);
    int match = -1;
    int newest = -1;
    for (int i = 0; i < SLOTS; i++) {
        const Batch &slot = g_slots[i];
        if (slot.seq == 0) {
            continue;
        }
        if (newest < 0 || slot.seq > g_slots[newest].seq) {
            newest = i;
        }
        if (slot.frame == frame && (match < 0 || slot.seq > g_slots[match].seq)) {
            match = i;
        }
    }
    int pick = match >= 0 ? match : newest;
    if (pick < 0) {
        ReleaseSRWLockExclusive(&g_slotLock);
        return false;
    }
    std::swap(out, g_slots[pick]);
    g_slots[pick].seq = 0;
    for (Batch &slot : g_slots) {
        if (slot.seq != 0 && slot.seq < out.seq) {
            slot.seq = 0; // queued before the frame being presented: never presented now
        }
    }
    ReleaseSRWLockExclusive(&g_slotLock);
    return true;
}

bool copyAtlas(bool all, std::vector<uint8_t> &pixels, uint32_t &top, uint32_t &bottom, uint32_t &version) {
    if (!TryAcquireSRWLockExclusive(&g_atlasLock)) {
        return false;
    }
    ensureAtlas();
    top = all ? 0 : g_dirtyTop;
    bottom = all ? DRAW_ATLAS_SIZE : g_dirtyBottom;
    if (bottom > top) {
        pixels.assign(g_atlas.begin() + static_cast<ptrdiff_t>(top * ATLAS_ROW),
                      g_atlas.begin() + static_cast<ptrdiff_t>(bottom * ATLAS_ROW));
    } else {
        bottom = top;
    }
    version = g_atlasVersion;
    g_dirtyTop = DRAW_ATLAS_SIZE;
    g_dirtyBottom = 0;
    ReleaseSRWLockExclusive(&g_atlasLock);
    return true;
}

} // namespace kraken::drawlist
