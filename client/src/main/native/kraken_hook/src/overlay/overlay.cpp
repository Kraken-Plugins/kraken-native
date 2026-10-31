#include "overlay/overlay.h"

#include <atomic>

#include "core/guard.h"
#include "core/hooks.h"
#include "core/ring.h"
#include "frames.h"
#include "overlay/gl.h"
#include "overlay/gl_state.h"
#include "overlay/surface.h"
#include "protocol.h"

namespace kraken::overlay {
namespace {

constexpr uint32_t NO_SLOT = 0xffffffffu; // the texture holds no slot's frame yet: upload whatever is taken

/**
 * The overlay's GL objects and upload state for one context. Present thread only.
 */
struct GlOverlay {
    HGLRC context = nullptr;
    int major = 0;
    int minor = 0;
    bool core = false;
    bool loaded = false;     // the entry points beyond GL 1.1 were looked up (broken if one is missing)
    bool built = false;      // the program and the VAO were built (broken if that failed)
    bool broken = false;     // this context can't draw the overlay (too old, a missing entry point, a shader error)
    GlFunctions fn{};
    GLuint program = 0;
    GLuint vao = 0;
    GLuint texture = 0;
    uint32_t texWidth = 0;
    uint32_t texHeight = 0;
    bool texValid = false;   // the texture has had a full upload since it was allocated
    uint32_t texTop = 0;     // the rows [texTop, texBottom) of the texture that may hold pixels
    uint32_t texBottom = 0;
    bool linear = false;     // the texture's filter: linear when it is stretched to the viewport
    uint32_t lastSlot = NO_SLOT; // the slot and sequence of the frame the texture holds
    uint32_t lastSeq = 0;
};

void *g_presentTrampoline = nullptr;
std::atomic<bool> g_presentHooked{false};

// Presents counted for FPS.
std::atomic<uint64_t> g_presents{0};

// What the present thread last saw, for FRAME. Written by the present thread, sent by the writer.
std::atomic<uint32_t> g_fbWidth{0};
std::atomic<uint32_t> g_fbHeight{0};
std::atomic<uint32_t> g_glVersion{0};
std::atomic<uint32_t> g_drawnGeneration{0};

GlOverlay g_gl;
SurfaceView *g_surface = nullptr; // the surface being drawn; present thread only
bool g_overlayFaulted = false;    // a fault while drawing turns the overlay off for the session
HDC g_lastDc = nullptr;
HWND g_lastWnd = nullptr;

/**
 * Reads the current context's GL version and profile into g_gl.
 */
void readGlVersion() {
    g_gl.major = 0;
    g_gl.minor = 0;
    const char *version = reinterpret_cast<const char *>(glGetString(GL_VERSION));
    if (version != nullptr) {
        int i = 0;
        while (version[i] >= '0' && version[i] <= '9') {
            g_gl.major = g_gl.major * 10 + (version[i++] - '0');
        }
        if (version[i] == '.') {
            i++;
            while (version[i] >= '0' && version[i] <= '9') {
                g_gl.minor = g_gl.minor * 10 + (version[i++] - '0');
            }
        }
    }
    g_gl.core = false;
    if (g_gl.major > 3 || (g_gl.major == 3 && g_gl.minor >= 2)) {
        GLint mask = 0;
        glGetIntegerv(KGL_CONTEXT_PROFILE_MASK, &mask);
        g_gl.core = (mask & KGL_CONTEXT_CORE_PROFILE_BIT) != 0;
    }
}

/**
 * Whether the context is at least a given GL version.
 *
 * @param major the major version
 * @param minor the minor version
 * @return true if the context's version is at least major.minor
 */
bool glAtLeast(int major, int minor) {
    return g_gl.major > major || (g_gl.major == major && g_gl.minor >= minor);
}

/**
 * Compiles one shader from a version line and a body.
 *
 * @param type   the shader type
 * @param header the {@code #version} line
 * @param body   the rest of the GLSL source
 * @return the shader, or 0 if it didn't compile
 */
GLuint compileShader(GLenum type, const char *header, const char *body) {
    GLuint shader = g_gl.fn.createShader(type);
    if (shader == 0) {
        return 0;
    }
    const char *sources[2] = {header, body};
    g_gl.fn.shaderSource(shader, 2, sources, nullptr);
    g_gl.fn.compileShader(shader);
    GLint ok = 0;
    g_gl.fn.getShaderiv(shader, KGL_COMPILE_STATUS, &ok);
    if (!ok) {
        g_gl.fn.deleteShader(shader);
        return 0;
    }
    return shader;
}

/**
 * Looks up the entry points beyond GL 1.1 for the current context. Leaves g_gl.broken set when the
 * context is older than GL 3.0 or lacks one. Changes no GL state.
 */
void loadGlFunctions() {
    g_gl.loaded = true;
    if (!glAtLeast(3, 0)) {
        g_gl.broken = true;
        return;
    }
    if (!lookupGlFunctions(g_gl.fn, glAtLeast(3, 3))) {
        g_gl.broken = true;
    }
}

/**
 * Builds the program and the empty VAO for the current context. Leaves g_gl.broken set when a shader
 * doesn't compile or the program doesn't link. Runs with the client's GL state saved, since it binds
 * the new program to set its sampler.
 */
void buildGl() {
    g_gl.built = true;
    GlFunctions &f = g_gl.fn;
    // A full-screen triangle from gl_VertexID; image rows are top-down, so the sample flips v.
    const char *header = glAtLeast(3, 3) ? "#version 330 core\n" : glAtLeast(3, 2) ? "#version 150\n" : "#version 130\n";
    const char *vertexBody =
            "out vec2 uv;\n"
            "void main() {\n"
            "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
            "    uv = p;\n"
            "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
            "}\n";
    const char *fragmentBody =
            "in vec2 uv;\n"
            "uniform sampler2D overlay;\n"
            "out vec4 colour;\n"
            "void main() {\n"
            "    colour = texture(overlay, vec2(uv.x, 1.0 - uv.y));\n"
            "}\n";
    GLuint vs = compileShader(KGL_VERTEX_SHADER, header, vertexBody);
    GLuint fs = compileShader(KGL_FRAGMENT_SHADER, header, fragmentBody);
    if (vs == 0 || fs == 0) {
        if (vs) f.deleteShader(vs);
        if (fs) f.deleteShader(fs);
        g_gl.broken = true;
        return;
    }
    GLuint program = f.createProgram();
    f.attachShader(program, vs);
    f.attachShader(program, fs);
    f.bindFragDataLocation(program, 0, "colour");
    f.linkProgram(program);
    f.deleteShader(vs);
    f.deleteShader(fs);
    GLint linked = 0;
    f.getProgramiv(program, KGL_LINK_STATUS, &linked);
    if (!linked) {
        f.deleteProgram(program);
        g_gl.broken = true;
        return;
    }
    f.useProgram(program);
    f.uniform1i(f.getUniformLocation(program, "overlay"), 0);
    g_gl.program = program;
    f.genVertexArrays(1, &g_gl.vao);
}

/**
 * Uploads rows [top, bottom) of a slot into the bound texture.
 *
 * @param pixels the slot's pixels
 * @param width  the surface width
 * @param top    the first row
 * @param bottom one past the last row
 */
void uploadRows(const uint8_t *pixels, uint32_t width, uint32_t top, uint32_t bottom) {
    if (bottom <= top) {
        return;
    }
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, static_cast<GLint>(top), static_cast<GLsizei>(width),
                    static_cast<GLsizei>(bottom - top), GL_BGRA, GL_UNSIGNED_BYTE,
                    pixels + static_cast<size_t>(top) * width * 4);
}

/**
 * A slot's header: top, bottom, tag, sequence.
 *
 * @param surface the open surface
 * @param slot    the slot
 * @return its header words
 */
volatile const uint32_t *slotHeader(const SurfaceView &surface, uint32_t slot) {
    return reinterpret_cast<volatile const uint32_t *>(surface.view + SURFACE_SLOT_HEADERS
                                                       + slot * SURFACE_SLOT_HEADER_SIZE);
}

/**
 * The slot holding the overlay Java drew for a frame, the newest if two do.
 *
 * @param surface the open surface
 * @param tag     the frame's tag
 * @return the slot, or NO_SLOT if none holds it
 */
uint32_t taggedSlot(const SurfaceView &surface, uint32_t tag) {
    uint32_t found = NO_SLOT;
    uint32_t newest = 0;
    for (uint32_t slot = 0; slot < surface.slots; slot++) {
        volatile const uint32_t *header = slotHeader(surface, slot);
        uint32_t seq = header[SURFACE_SLOT_SEQ / 4];
        if (header[SURFACE_SLOT_TAG / 4] == tag && seq != 0 && (found == NO_SLOT || seq > newest)) {
            found = slot;
            newest = seq;
        }
    }
    return found;
}

/**
 * Takes a frame of the surface into the bound texture, if the texture doesn't have it yet: the slot
 * Java drew for the frame being presented when it is tagged and Java filled one for it, otherwise the
 * newest published frame. Follows the surface's handshake: claim the slot in {@code reading}, fence,
 * confirm the slot still holds that frame (the tag for a tagged slot, {@code published} for the
 * newest), upload the rows that changed against the texture, release the slot.
 *
 * @param surface the open surface
 * @param want    the tag of the frame being presented, 0 for the newest
 */
void takeFrame(const SurfaceView &surface, uint32_t want) {
    volatile uint32_t *published = reinterpret_cast<volatile uint32_t *>(surface.view + SURFACE_PUBLISHED);
    volatile int32_t *reading = reinterpret_cast<volatile int32_t *>(surface.view + SURFACE_READING);
    uint32_t pub = *published;
    if (pub == PUBLISHED_NONE) {
        return;
    }
    uint32_t slot = want != 0 ? taggedSlot(surface, want) : NO_SLOT;
    bool tagged = slot != NO_SLOT;
    if (!tagged) {
        slot = pub & 0xf;
    }
    if (slot >= surface.slots) {
        return;
    }
    volatile const uint32_t *header = slotHeader(surface, slot);
    *reading = static_cast<int32_t>(slot);
    MemoryBarrier();
    if (tagged ? header[SURFACE_SLOT_TAG / 4] != want : *published != pub) {
        *reading = -1;
        return; // Java is writing that slot, or published again in between; try again next present
    }
    uint32_t seq = header[SURFACE_SLOT_SEQ / 4];
    if (seq != 0 && slot == g_gl.lastSlot && seq == g_gl.lastSeq) {
        *reading = -1;
        return; // the texture has this frame already
    }
    uint32_t top = header[0];
    uint32_t bottom = header[1];
    if (top > bottom || bottom > surface.height) {
        top = 0;
        bottom = surface.height;
    }
    const uint8_t *pixels = surface.view + SURFACE_PIXELS + static_cast<size_t>(slot) * surface.width * surface.height * 4;
    if (!g_gl.texValid) {
        uploadRows(pixels, surface.width, 0, surface.height);
    } else {
        // Rows outside a slot's band are clear, so only the texture's old band and the new one can differ
        uint32_t t0 = g_gl.texTop, t1 = g_gl.texBottom;
        bool texEmpty = t1 <= t0, slotEmpty = bottom <= top;
        if (texEmpty) {
            uploadRows(pixels, surface.width, top, bottom);
        } else if (slotEmpty) {
            uploadRows(pixels, surface.width, t0, t1);
        } else if (t0 <= bottom && top <= t1) {
            uploadRows(pixels, surface.width, t0 < top ? t0 : top, t1 > bottom ? t1 : bottom);
        } else {
            uploadRows(pixels, surface.width, t0, t1);
            uploadRows(pixels, surface.width, top, bottom);
        }
    }
    MemoryBarrier();
    *reading = -1;
    g_gl.texValid = true;
    g_gl.texTop = top;
    g_gl.texBottom = bottom;
    g_gl.lastSlot = slot;
    g_gl.lastSeq = seq;
}

/**
 * The body of drawOverlay, between saving and restoring the client's state.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @param s       the saved state, whose draw buffer this fills in once framebuffer 0 is bound
 * @param want    the tag of the frame being presented, 0 for the newest
 */
void drawOverlayBody(const SurfaceView &surface, uint32_t width, uint32_t height, GlSaved &s, uint32_t want) {
    GlFunctions &f = g_gl.fn;
    if (!g_gl.built) {
        buildGl();
        if (g_gl.broken) {
            return;
        }
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
    f.bindBuffer(KGL_PIXEL_UNPACK_BUFFER, 0);
    if (f.bindSampler) {
        f.bindSampler(0, 0);
    }
    if (g_gl.texture == 0) {
        glGenTextures(1, &g_gl.texture);
        g_gl.texWidth = 0;
    }
    glBindTexture(GL_TEXTURE_2D, g_gl.texture);
    bool linear = surface.width != width || surface.height != height;
    if (g_gl.texWidth != surface.width || g_gl.texHeight != surface.height) {
        glTexImage2D(GL_TEXTURE_2D, 0, KGL_RGBA8, static_cast<GLsizei>(surface.width),
                     static_cast<GLsizei>(surface.height), 0, GL_BGRA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_BASE_LEVEL, 0);
        glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_MAX_LEVEL, 0);
        g_gl.texWidth = surface.width;
        g_gl.texHeight = surface.height;
        g_gl.texValid = false;
        g_gl.lastSlot = NO_SLOT;
        g_gl.linear = !linear; // forces the filter below
    }
    if (g_gl.linear != linear) {
        GLint filter = linear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        g_gl.linear = linear;
    }
    takeFrame(surface, want);
    if (!g_gl.texValid || g_gl.texBottom <= g_gl.texTop) {
        return; // nothing to show: an empty overlay costs no draw
    }

    f.bindFramebuffer(KGL_DRAW_FRAMEBUFFER, 0);
    GLint drawBuffer = GL_BACK;
    glGetIntegerv(GL_DRAW_BUFFER, &drawBuffer);
    if (drawBuffer != GL_BACK) {
        s.drawBuffer = drawBuffer;
        glDrawBuffer(GL_BACK);
    }
    glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(KGL_FRAMEBUFFER_SRGB);
    glDisable(KGL_RASTERIZER_DISCARD);
    glDisable(KGL_SAMPLE_ALPHA_TO_COVERAGE);
    glDisable(GL_COLOR_LOGIC_OP);
    glEnable(GL_BLEND);
    f.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    f.blendEquationSeparate(KGL_FUNC_ADD, KGL_FUNC_ADD);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    if (s.polygonMode[0] != GL_FILL) {
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }
    f.useProgram(g_gl.program);
    f.bindVertexArray(g_gl.vao);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

/**
 * Saves the client's GL state under an SEH guard.
 *
 * @param s where to save it
 * @return false if it faulted
 */
bool guardedSave(GlSaved &s) {
    bool faulted = false;
    GUARD_BEGIN saveGl(g_gl.fn, s); GUARD_END
    return !faulted;
}

/**
 * Runs the draw under an SEH guard.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @param s       the saved state
 * @param want    the tag of the frame being presented, 0 for the newest
 * @return false if it faulted
 */
bool guardedDraw(const SurfaceView &surface, uint32_t width, uint32_t height, GlSaved &s, uint32_t want) {
    bool faulted = false;
    GUARD_BEGIN drawOverlayBody(surface, width, height, s, want); GUARD_END
    return !faulted;
}

/**
 * Restores the client's GL state under an SEH guard.
 *
 * @param s the saved state
 * @return false if it faulted
 */
bool guardedRestore(const GlSaved &s) {
    bool faulted = false;
    GUARD_BEGIN restoreGl(g_gl.fn, s); GUARD_END
    return !faulted;
}

/**
 * Uploads the newest frame and draws the overlay over framebuffer 0. Saves the client's GL state
 * first and restores it after, even when drawing faults.
 *
 * @param surface the open surface
 * @param width   the window's client width
 * @param height  the window's client height
 * @param want    the tag of the frame being presented, 0 for the newest
 * @return false if drawing faulted
 */
bool drawOverlay(const SurfaceView &surface, uint32_t width, uint32_t height, uint32_t want) {
    if (!g_gl.loaded) {
        bool faulted = false;
        GUARD_BEGIN loadGlFunctions(); GUARD_END
        if (faulted) {
            return false;
        }
    }
    if (g_gl.broken) {
        return true;
    }
    GlSaved s{};
    if (!guardedSave(s)) {
        return false;
    }
    bool drew = guardedDraw(surface, width, height, s, want);
    bool restored = guardedRestore(s);
    return drew && restored;
}

/**
 * Takes a pending surface (or stop request) from the reader thread, dropping the one being drawn.
 */
void adoptPendingSurface() {
    SurfaceView *next = takePendingSurface();
    if (next == nullptr) {
        return;
    }
    freeSurface(g_surface);
    g_surface = nullptr;
    if (next->view == nullptr) {
        freeSurface(next);
        return;
    }
    g_surface = next;
    *reinterpret_cast<volatile int32_t *>(g_surface->view + SURFACE_READING) = -1;
    g_gl.texValid = false;
    g_gl.lastSlot = NO_SLOT;
}

/**
 * Publishes a value for FRAME.
 *
 * @param slot  the atomic
 * @param value the new value
 * @return true if it changed
 */
bool publishFrameValue(std::atomic<uint32_t> &slot, uint32_t value) {
    return slot.exchange(value, std::memory_order_relaxed) != value;
}

/**
 * The overlay's share of one present: track the framebuffer and context, take a new surface, and
 * draw the frame for this present, waking the writer thread when what FRAME reports changed. Present
 * thread only; never touches the ring.
 *
 * @param want the tag of the frame being presented, 0 for the newest
 */
void overlayFrame(uint32_t want) {
    adoptPendingSurface();
    HDC dc = wglGetCurrentDC();
    HGLRC rc = wglGetCurrentContext();
    if (dc == nullptr || rc == nullptr) {
        return;
    }
    if (dc != g_lastDc) {
        g_lastDc = dc;
        g_lastWnd = WindowFromDC(dc);
    }
    RECT rect{};
    uint32_t width = 0;
    uint32_t height = 0;
    if (g_lastWnd != nullptr && GetClientRect(g_lastWnd, &rect)) {
        width = static_cast<uint32_t>(rect.right - rect.left);
        height = static_cast<uint32_t>(rect.bottom - rect.top);
    }
    if (rc != g_gl.context) {
        // A new context: its objects are not ours to reuse, so start over
        g_gl = GlOverlay();
        g_gl.context = rc;
        readGlVersion();
    }
    bool changed = publishFrameValue(g_fbWidth, width);
    changed |= publishFrameValue(g_fbHeight, height);
    changed |= publishFrameValue(g_glVersion, (static_cast<uint32_t>(g_gl.major) << 16) | static_cast<uint32_t>(g_gl.minor));

    uint32_t generation = 0;
    if (g_surface != nullptr && !g_overlayFaulted && !g_gl.broken && glAtLeast(3, 0) && width > 0 && height > 0) {
        if (drawOverlay(*g_surface, width, height, want)) {
            generation = g_gl.broken ? 0 : g_surface->generation;
        } else {
            g_overlayFaulted = true;
        }
    }
    changed |= publishFrameValue(g_drawnGeneration, generation);
    if (changed) {
        ring::wakeWriter();
    }
}

/**
 * The present detour: draws the overlay for the frame being presented over it, then presents it.
 * With frame sync on, that is the overlay Java drew for the frame's tag, waited for if Java is still
 * drawing it. Otherwise it is the newest,
 * and while a surface is drawn the detour wakes the writer thread before presenting, so the writer
 * sends DRAW and Java starts the overlay's next frame while the game waits on the swap.
 *
 * @param self the client's GL context object
 */
void __fastcall presentDetour(void *self) {
    g_presents.fetch_add(1, std::memory_order_relaxed);
    uint32_t want = frames::tagOf(self);
    frames::awaitOverlay(want);
    overlayFrame(want);
    if (g_drawnGeneration.load(std::memory_order_relaxed) != 0 && !frames::syncing()) {
        ring::wakeWriter();
    }
    using Fn = void(__fastcall *)(void *);
    reinterpret_cast<Fn>(g_presentTrampoline)(self);
}

} // namespace

void install(uint64_t rva) {
    hooks::installOnce(rva, reinterpret_cast<void *>(&presentDetour), &g_presentTrampoline, g_presentHooked);
}

bool hooked() {
    return g_presentHooked.load(std::memory_order_acquire);
}

uint64_t presents() {
    return g_presents.load(std::memory_order_relaxed);
}

bool drawing() {
    return g_drawnGeneration.load(std::memory_order_relaxed) != 0;
}

FrameInfo frameInfo() {
    return {g_fbWidth.load(std::memory_order_relaxed), g_fbHeight.load(std::memory_order_relaxed),
            g_glVersion.load(std::memory_order_relaxed), g_drawnGeneration.load(std::memory_order_relaxed)};
}

} // namespace kraken::overlay
