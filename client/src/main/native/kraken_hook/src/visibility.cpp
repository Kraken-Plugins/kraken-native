#include "visibility.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <utility>
#include <vector>

#include "calls.h"
#include "core/memory.h"
#include "protocol.h"
#include "pump.h"

namespace kraken::visibility {
namespace {

using memory::readI32;
using memory::readU64;
using memory::safeRead;
using memory::safeWrite;

constexpr uint32_t NONE = 0xffffffffu;  // an offset the mapping lacks
constexpr uint64_t MAX_BUCKETS = 1u << 20;
constexpr uint64_t BUCKET_CHUNK = 256;  // bucket heads read at a time
constexpr int MAX_CHAIN = 4096;         // nodes followed along one bucket's chain
constexpr int MAX_LIST = 16384;         // nodes followed along one list
constexpr int MAX_WORLDS = 64;

/**
 * Where the entities and their flags are, from the handshake, in the block's order. Byte offsets;
 * NONE marks one the mapping lacks, which leaves that kind of entity alone.
 */
struct Layout {
    uint32_t topLevelWorld = NONE;    // Client: the top-level World*
    uint32_t worlds = NONE;           // Client: hash map of world id -> World*
    uint32_t localPlayerIndex = NONE; // Client: the local player's key in the top-level world's players
    uint32_t players = NONE;          // World: hash map of index -> Player*
    uint32_t npcs = NONE;             // World: hash map of index -> Npc*
    uint32_t graphicsObjects = NONE;  // World: list of GraphicsObject*
    uint32_t projectiles = NONE;      // Client: list of Projectile*
    uint32_t keyedProjectiles = NONE; // Client: hash map of key -> KeyedProjectile*
    uint32_t actorHidden = NONE;      // nonzero hides the actor's model
    uint32_t actor2D = NONE;          // zero hides the actor's 2D elements
    uint32_t projectileHidden = NONE;
    uint32_t keyedHidden = NONE;
    uint32_t graphicsHidden = NONE;
    uint32_t graphicsId = NONE;       // a graphics object's spot anim id
    uint32_t mapBuckets = NONE;       // eastl hash map: the bucket array and its length
    uint32_t mapBucketCount = NONE;
    uint32_t nodeKey = NONE;          // a hash map node's key, value pointer and next node
    uint32_t nodeValue = NONE;
    uint32_t nodeNext = NONE;
    uint32_t listNext = NONE;         // eastl list: a node's next node and value pointer
    uint32_t listValue = NONE;
};

/**
 * One actor's decision, overriding its kind's: which layers it decides and which of those it hides.
 */
struct Override {
    uint64_t address;
    uint8_t decided;
    uint8_t hidden;
};

/**
 * What to hide: HIDE_* bits for each kind of entity, the overrides by actor address and the graphics
 * object ids, both sorted.
 */
struct Rules {
    uint8_t classes = 0;
    std::vector<Override> overrides;
    std::vector<int32_t> graphics;

    bool any() const {
        return classes != 0 || !overrides.empty() || !graphics.empty();
    }
};

/**
 * An entity this module holds hidden, and which layers.
 */
struct Hid {
    uint64_t address;
    uint8_t layers;
};

Layout g_layout;                  // written once, before g_ready
std::atomic<bool> g_ready{false};

// The rules the last command (or a clear) sent, until the game thread takes them.
SRWLOCK g_pendingLock = SRWLOCK_INIT;
Rules g_pending;                  // guarded by g_pendingLock
std::atomic<bool> g_hasPending{false};

// Game thread only.
Rules g_rules;
std::vector<Hid> g_hid;           // what this module held hidden after the last apply, sorted by address
std::vector<Hid> g_nextHid;       // what it holds hidden as of the apply running

/**
 * Takes the rules a command or a clear left, unless the reader thread holds them right now, in which
 * case the next frame takes them.
 */
void takePending() {
    if (!g_hasPending.load(std::memory_order_acquire) || !TryAcquireSRWLockExclusive(&g_pendingLock)) {
        return;
    }
    g_rules = std::move(g_pending);
    g_pending = Rules{};
    g_hasPending.store(false, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

/**
 * The layers this module held an entity hidden in after the last apply.
 *
 * @param address the entity
 * @return LAYER_* bits, 0 if it hid nothing of it
 */
uint8_t heldBefore(uint64_t address) {
    auto it = std::lower_bound(g_hid.begin(), g_hid.end(), address,
                               [](const Hid &hid, uint64_t a) { return hid.address < a; });
    return it != g_hid.end() && it->address == address ? it->layers : 0;
}

/**
 * Applies an actor's override, if it has one, over its kind's decision.
 *
 * @param address the actor
 * @param hide3D  its kind's decision for the model, replaced where the override decides it
 * @param hide2D  its kind's decision for the 2D elements, likewise
 */
void applyOverride(uint64_t address, bool &hide3D, bool &hide2D) {
    const std::vector<Override> &all = g_rules.overrides;
    auto it = std::lower_bound(all.begin(), all.end(), address,
                               [](const Override &o, uint64_t a) { return o.address < a; });
    if (it == all.end() || it->address != address) {
        return;
    }
    if ((it->decided & LAYER_3D) != 0) {
        hide3D = (it->hidden & LAYER_3D) != 0;
    }
    if ((it->decided & LAYER_2D) != 0) {
        hide2D = (it->hidden & LAYER_2D) != 0;
    }
}

/**
 * Brings one flag byte to what the rules want: hides the layer while that is wanted, and shows it
 * again once it isn't, but only if this module hid it.
 *
 * @param flag     the flag byte's address
 * @param layer    the LAYER_* bit it controls
 * @param want     whether the layer should be hidden
 * @param held     the layers this module held hidden before
 * @param inverted true for a "drawn" flag, where 0 hides; false for a "hidden" flag, where nonzero hides
 * @return the layer bit if this module holds the layer hidden now, else 0
 */
uint8_t settle(uint64_t flag, uint8_t layer, bool want, uint8_t held, bool inverted) {
    uint8_t current = 0;
    if (!safeRead(flag, &current, 1)) {
        return 0;
    }
    bool hidden = inverted ? current == 0 : current != 0;
    uint8_t hide = inverted ? 0 : 1;
    uint8_t show = inverted ? 1 : 0;
    if (want) {
        if (!hidden) {
            return safeWrite(flag, &hide, 1) ? layer : 0;
        }
        return held & layer; // hidden already: held only if this module hid it
    }
    if ((held & layer) != 0 && hidden) {
        safeWrite(flag, &show, 1);
    }
    return 0;
}

/**
 * Settles one entity's layers and notes what this module now holds hidden of it.
 *
 * @param entity   the entity
 * @param hidden   its hidden byte's offset
 * @param drawn2D  its 2D "drawn" byte's offset, or NONE for an entity without 2D elements
 * @param hide3D   whether its model should be hidden
 * @param hide2D   whether its 2D elements should be hidden
 */
void visit(uint64_t entity, uint32_t hidden, uint32_t drawn2D, bool hide3D, bool hide2D) {
    uint8_t held = heldBefore(entity);
    uint8_t now = settle(entity + hidden, LAYER_3D, hide3D, held, false);
    if (drawn2D != NONE) {
        now |= settle(entity + drawn2D, LAYER_2D, hide2D, held, true);
    }
    if (now != 0) {
        g_nextHid.push_back({entity, now});
    }
}

/**
 * Calls visit(key, value) for each node of an eastl hash map whose value pointer isn't null, reading
 * the bucket array a chunk at a time. Stops at a bucket array that can't be read or looks corrupt.
 *
 * @param map   the hash map object
 * @param visit what to call
 */
template<typename Visit>
void forEachNode(uint64_t map, Visit visit) {
    const Layout &l = g_layout;
    uint64_t buckets = readU64(map + l.mapBuckets, 0);
    uint64_t count = readU64(map + l.mapBucketCount, 0);
    if (buckets == 0 || count == 0 || count > MAX_BUCKETS) {
        return;
    }
    uint64_t heads[BUCKET_CHUNK];
    for (uint64_t start = 0; start < count; start += BUCKET_CHUNK) {
        uint64_t n = std::min(BUCKET_CHUNK, count - start);
        if (!safeRead(buckets + start * 8, heads, static_cast<size_t>(n * 8))) {
            return;
        }
        for (uint64_t b = 0; b < n; b++) {
            uint64_t node = heads[b];
            for (int steps = 0; node != 0 && steps < MAX_CHAIN; steps++) {
                uint64_t value = readU64(node + l.nodeValue, 0);
                if (value != 0) {
                    visit(readI32(node + l.nodeKey, -1), value);
                }
                node = readU64(node + l.nodeNext, 0);
            }
        }
    }
}

/**
 * Calls visit(value) for each node of an eastl list (a ring through its head) whose value pointer
 * isn't null.
 *
 * @param head  the list object
 * @param visit what to call
 */
template<typename Visit>
void forEachInList(uint64_t head, Visit visit) {
    const Layout &l = g_layout;
    uint64_t node = readU64(head + l.listNext, 0);
    for (int steps = 0; node != 0 && node != head && steps < MAX_LIST; steps++) {
        uint64_t value = readU64(node + l.listValue, 0);
        if (value != 0) {
            visit(value);
        }
        node = readU64(node + l.listNext, 0);
    }
}

/**
 * Every loaded world: the top-level one first, then the sub-worlds the client's world table holds.
 *
 * @param client the Client*
 * @param top    the top-level World*
 * @param out    receives the worlds, MAX_WORLDS long
 * @return how many there are
 */
int worlds(uint64_t client, uint64_t top, uint64_t *out) {
    int n = 0;
    out[n++] = top;
    if (g_layout.worlds != NONE) {
        forEachNode(client + g_layout.worlds, [&](int32_t, uint64_t world) {
            if (n < MAX_WORLDS && std::find(out, out + n, world) == out + n) {
                out[n++] = world;
            }
        });
    }
    return n;
}

/**
 * Whether the rules hide graphics objects with a spot anim id.
 *
 * @param id the id
 * @return true if listed
 */
bool hidesGraphic(int32_t id) {
    return std::binary_search(g_rules.graphics.begin(), g_rules.graphics.end(), id);
}

} // namespace

void install(FrameReader r) {
    if (g_ready.load(std::memory_order_acquire)) {
        return;
    }
    Layout l;
    uint32_t *fields[] = {&l.topLevelWorld, &l.worlds, &l.localPlayerIndex, &l.players, &l.npcs,
                          &l.graphicsObjects, &l.projectiles, &l.keyedProjectiles, &l.actorHidden, &l.actor2D,
                          &l.projectileHidden, &l.keyedHidden, &l.graphicsHidden, &l.graphicsId, &l.mapBuckets,
                          &l.mapBucketCount, &l.nodeKey, &l.nodeValue, &l.nodeNext, &l.listNext, &l.listValue};
    for (uint32_t *field : fields) {
        if (!r.get(*field)) {
            return;
        }
    }
    uint32_t required[] = {l.topLevelWorld, l.localPlayerIndex, l.players, l.npcs, l.actorHidden, l.actor2D,
                           l.mapBuckets, l.mapBucketCount, l.nodeKey, l.nodeValue, l.nodeNext};
    for (uint32_t offset : required) {
        if (offset == NONE) {
            return;
        }
    }
    if (l.listNext == NONE || l.listValue == NONE) {
        l.graphicsObjects = NONE;
        l.projectiles = NONE;
    }
    g_layout = l;
    g_ready.store(true, std::memory_order_release);
}

bool live() {
    return g_ready.load(std::memory_order_acquire) && pump::hooked();
}

void command(FrameReader r) {
    if (!live()) {
        return;
    }
    Rules rules;
    uint16_t count = 0;
    if (!r.get(rules.classes) || !r.get(count) || count > MAX_VISIBILITY_OVERRIDES) {
        return;
    }
    rules.classes &= HIDE_LOCAL_3D | HIDE_LOCAL_2D | HIDE_PLAYERS_3D | HIDE_PLAYERS_2D | HIDE_NPCS_3D | HIDE_NPCS_2D
                     | HIDE_PROJECTILES;
    rules.overrides.reserve(count);
    for (uint16_t i = 0; i < count; i++) {
        Override o{};
        if (!r.get(o.address) || !r.get(o.decided) || !r.get(o.hidden)) {
            return; // a malformed command changes nothing
        }
        o.decided &= LAYER_3D | LAYER_2D;
        o.hidden &= o.decided;
        if (o.address != 0 && o.decided != 0) {
            rules.overrides.push_back(o);
        }
    }
    uint8_t graphics = 0;
    if (!r.get(graphics) || graphics > MAX_HIDDEN_GRAPHICS) {
        return;
    }
    for (uint8_t i = 0; i < graphics; i++) {
        int32_t id = 0;
        if (!r.get(id)) {
            return;
        }
        rules.graphics.push_back(id);
    }
    std::stable_sort(rules.overrides.begin(), rules.overrides.end(),
                     [](const Override &a, const Override &b) { return a.address < b.address; });
    rules.overrides.erase(std::unique(rules.overrides.begin(), rules.overrides.end(),
                                      [](const Override &a, const Override &b) { return a.address == b.address; }),
                          rules.overrides.end());
    std::sort(rules.graphics.begin(), rules.graphics.end());
    rules.graphics.erase(std::unique(rules.graphics.begin(), rules.graphics.end()), rules.graphics.end());
    AcquireSRWLockExclusive(&g_pendingLock);
    g_pending = std::move(rules);
    g_hasPending.store(true, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

void apply() {
    if (!g_ready.load(std::memory_order_acquire)) {
        return;
    }
    takePending();
    if (!g_rules.any() && g_hid.empty()) {
        return;
    }
    const Layout &l = g_layout;
    uint64_t client = calls::clientPointer();
    uint64_t top = client == 0 ? 0 : readU64(client + l.topLevelWorld, 0);
    if (top == 0) {
        g_hid.clear(); // no world: what this module hid has been destroyed with it
        return;
    }
    g_nextHid.clear();
    int32_t local = readI32(client + l.localPlayerIndex, -1);
    uint8_t classes = g_rules.classes;
    uint64_t all[MAX_WORLDS];
    int count = worlds(client, top, all);
    for (int w = 0; w < count; w++) {
        uint64_t world = all[w];
        forEachNode(world + l.players, [&](int32_t key, uint64_t player) {
            bool isLocal = world == top && key == local;
            bool hide3D = (classes & (isLocal ? HIDE_LOCAL_3D : HIDE_PLAYERS_3D)) != 0;
            bool hide2D = (classes & (isLocal ? HIDE_LOCAL_2D : HIDE_PLAYERS_2D)) != 0;
            applyOverride(player, hide3D, hide2D);
            visit(player, l.actorHidden, l.actor2D, hide3D, hide2D);
        });
        forEachNode(world + l.npcs, [&](int32_t, uint64_t npc) {
            bool hide3D = (classes & HIDE_NPCS_3D) != 0;
            bool hide2D = (classes & HIDE_NPCS_2D) != 0;
            applyOverride(npc, hide3D, hide2D);
            visit(npc, l.actorHidden, l.actor2D, hide3D, hide2D);
        });
        if (l.graphicsObjects != NONE && l.graphicsHidden != NONE && l.graphicsId != NONE) {
            forEachInList(world + l.graphicsObjects, [&](uint64_t object) {
                bool hide = !g_rules.graphics.empty() && hidesGraphic(readI32(object + l.graphicsId, -1));
                visit(object, l.graphicsHidden, NONE, hide, false);
            });
        }
    }
    bool projectiles = (classes & HIDE_PROJECTILES) != 0;
    if (l.projectiles != NONE && l.projectileHidden != NONE) {
        forEachInList(client + l.projectiles, [&](uint64_t projectile) {
            visit(projectile, l.projectileHidden, NONE, projectiles, false);
        });
    }
    if (l.keyedProjectiles != NONE && l.keyedHidden != NONE) {
        forEachNode(client + l.keyedProjectiles, [&](int32_t, uint64_t projectile) {
            visit(projectile, l.keyedHidden, NONE, projectiles, false);
        });
    }
    std::sort(g_nextHid.begin(), g_nextHid.end(), [](const Hid &a, const Hid &b) { return a.address < b.address; });
    g_hid.swap(g_nextHid);
}

void clear() {
    AcquireSRWLockExclusive(&g_pendingLock);
    g_pending = Rules{};
    g_hasPending.store(true, std::memory_order_release);
    ReleaseSRWLockExclusive(&g_pendingLock);
}

} // namespace kraken::visibility
