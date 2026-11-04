#include "overlay/batch.h"

#include <cstddef>
#include <vector>

#include "drawlist.h"
#include "protocol.h"

namespace kraken::overlay::batch {
namespace {

using drawlist::Vertex;

// Attribute locations, bound before the program links.
constexpr GLuint POSITION = 0;
constexpr GLuint OFFSET = 1;
constexpr GLuint UV = 2;
constexpr GLuint COLOUR = 3;
constexpr GLuint EDGE = 4;
constexpr GLsizei ATLAS_SIDE = static_cast<GLsizei>(DRAW_ATLAS_SIZE);

/**
 * The batch renderer's GL objects for one context.
 */
struct BatchGl {
    bool built = false;      // the program, VAO, buffer and texture were made (broken if that failed)
    bool broken = false;     // this context can't draw batches
    GLuint program = 0;
    GLuint vao = 0;
    GLuint vbo = 0;
    GLuint atlas = 0;
    GLint scaleAt = -1;      // uniform: framebuffer pixels per window pixel
    GLint sizeAt = -1;       // uniform: the framebuffer's size
    bool atlasValid = false; // the texture has had a full upload
    bool haveVersion = false;
    uint32_t atlasVersion = 0; // the atlas version the texture holds
};

BatchGl g_gl;
drawlist::Batch g_batch;     // the batch taken for the frame being presented
std::vector<uint8_t> g_rows; // atlas rows copied out for upload

// Geometry is placed in window pixels and scaled to the framebuffer; a sprite corner (half width
// below 0) puts its anchor on a whole pixel and adds its offset, so sprites draw texel for pixel.
const char *const VERTEX_BODY =
        "in vec2 position;\n"
        "in vec2 offset;\n"
        "in vec2 uv;\n"
        "in vec4 colour;\n"
        "in vec2 edge;\n"
        "uniform vec2 scale;\n"
        "uniform vec2 size;\n"
        "out vec2 vUv;\n"
        "out vec4 vColour;\n"
        "out vec2 vEdge;\n"
        "void main() {\n"
        "    vec2 p = position * scale;\n"
        "    if (edge.y < 0.0) {\n"
        "        p = floor(p + 0.5) + offset;\n"
        "    }\n"
        "    vUv = uv;\n"
        "    vColour = colour;\n"
        "    vEdge = edge;\n"
        "    gl_Position = vec4(p.x / size.x * 2.0 - 1.0, 1.0 - p.y / size.y * 2.0, 0.0, 1.0);\n"
        "}\n";

// A stroke fades out over the pixel past its half width: edge is (distance across, half width).
const char *const FRAGMENT_BODY =
        "in vec2 vUv;\n"
        "in vec4 vColour;\n"
        "in vec2 vEdge;\n"
        "uniform sampler2D atlas;\n"
        "out vec4 fragColour;\n"
        "void main() {\n"
        "    float cover = vEdge.y < 0.0 ? 1.0 : clamp(vEdge.y + 0.5 - abs(vEdge.x), 0.0, 1.0);\n"
        "    fragColour = texture(atlas, vUv) * vColour * cover;\n"
        "}\n";

/**
 * Builds the program, the VAO with its vertex buffer, and the atlas texture. Leaves g_gl.broken set
 * when a shader doesn't compile or the program doesn't link. Runs with the client's state saved.
 *
 * @param f     the context's entry points
 * @param major the context's GL major version
 * @param minor its minor version
 */
void build(const GlFunctions &f, int major, int minor) {
    g_gl.built = true;
    const char *header = glslHeader(major, minor);
    GLuint vs = compileShader(f, KGL_VERTEX_SHADER, header, VERTEX_BODY);
    GLuint fs = compileShader(f, KGL_FRAGMENT_SHADER, header, FRAGMENT_BODY);
    if (vs == 0 || fs == 0) {
        if (vs) f.deleteShader(vs);
        if (fs) f.deleteShader(fs);
        g_gl.broken = true;
        return;
    }
    GLuint program = f.createProgram();
    f.attachShader(program, vs);
    f.attachShader(program, fs);
    f.bindAttribLocation(program, POSITION, "position");
    f.bindAttribLocation(program, OFFSET, "offset");
    f.bindAttribLocation(program, UV, "uv");
    f.bindAttribLocation(program, COLOUR, "colour");
    f.bindAttribLocation(program, EDGE, "edge");
    f.bindFragDataLocation(program, 0, "fragColour");
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
    f.uniform1i(f.getUniformLocation(program, "atlas"), 0);
    g_gl.scaleAt = f.getUniformLocation(program, "scale");
    g_gl.sizeAt = f.getUniformLocation(program, "size");
    g_gl.program = program;

    f.genVertexArrays(1, &g_gl.vao);
    f.bindVertexArray(g_gl.vao);
    f.genBuffers(1, &g_gl.vbo);
    f.bindBuffer(KGL_ARRAY_BUFFER, g_gl.vbo);
    auto stride = static_cast<GLsizei>(sizeof(Vertex));
    f.vertexAttribPointer(POSITION, 2, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<const void *>(offsetof(Vertex, x)));
    f.vertexAttribPointer(OFFSET, 2, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<const void *>(offsetof(Vertex, offsetX)));
    f.vertexAttribPointer(UV, 2, GL_FLOAT, GL_FALSE, stride, reinterpret_cast<const void *>(offsetof(Vertex, u)));
    f.vertexAttribPointer(COLOUR, 4, GL_UNSIGNED_BYTE, GL_TRUE, stride,
                          reinterpret_cast<const void *>(offsetof(Vertex, colour)));
    f.vertexAttribPointer(EDGE, 2, GL_FLOAT, GL_FALSE, stride,
                          reinterpret_cast<const void *>(offsetof(Vertex, distance)));
    for (GLuint attribute : {POSITION, OFFSET, UV, COLOUR, EDGE}) {
        f.enableVertexAttribArray(attribute);
    }

    glGenTextures(1, &g_gl.atlas);
    glBindTexture(GL_TEXTURE_2D, g_gl.atlas);
    glTexImage2D(GL_TEXTURE_2D, 0, KGL_RGBA8, ATLAS_SIDE, ATLAS_SIDE, 0, GL_BGRA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, KGL_TEXTURE_MAX_LEVEL, 0);
}

/**
 * Uploads the atlas rows that changed since the last upload (every row into a new texture), unless
 * the reader thread is writing the atlas right now. Leaves the atlas texture bound to unit 0.
 */
void uploadAtlas() {
    glBindTexture(GL_TEXTURE_2D, g_gl.atlas);
    uint32_t top = 0;
    uint32_t bottom = 0;
    uint32_t version = 0;
    if (!drawlist::copyAtlas(!g_gl.atlasValid, g_rows, top, bottom, version)) {
        return;
    }
    if (bottom > top) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, static_cast<GLint>(top), ATLAS_SIDE,
                        static_cast<GLsizei>(bottom - top), GL_BGRA, GL_UNSIGNED_BYTE, g_rows.data());
    }
    g_gl.atlasValid = true;
    g_gl.haveVersion = true;
    g_gl.atlasVersion = version;
}

} // namespace

void reset() {
    g_gl = BatchGl();
}

bool take(uint64_t frame) {
    if (!drawlist::takeBatch(frame, g_batch)) {
        g_batch.vertices.clear();
        return false;
    }
    return !g_batch.vertices.empty() && g_batch.screenWidth > 0 && g_batch.screenHeight > 0;
}

void draw(const GlFunctions &f, int major, int minor, uint32_t width, uint32_t height) {
    if (!g_gl.built) {
        build(f, major, minor);
    }
    if (g_gl.broken) {
        return;
    }
    uploadAtlas();
    size_t count = g_batch.vertices.size();
    bool spritesReady = g_gl.haveVersion && static_cast<int32_t>(g_gl.atlasVersion - g_batch.atlasVersion) >= 0;
    if (!spritesReady) {
        count = g_batch.spriteStart; // the atlas doesn't hold the sprites yet: geometry only this frame
    }
    if (count == 0) {
        return;
    }
    f.useProgram(g_gl.program);
    f.uniform2f(g_gl.scaleAt, static_cast<float>(width) / static_cast<float>(g_batch.screenWidth),
                static_cast<float>(height) / static_cast<float>(g_batch.screenHeight));
    f.uniform2f(g_gl.sizeAt, static_cast<float>(width), static_cast<float>(height));
    f.bindVertexArray(g_gl.vao);
    f.bindBuffer(KGL_ARRAY_BUFFER, g_gl.vbo);
    f.bufferData(KGL_ARRAY_BUFFER, static_cast<KGLsizeiptr>(count * sizeof(Vertex)), g_batch.vertices.data(),
                 KGL_STREAM_DRAW);
    glDrawArrays(GL_TRIANGLES, 0, static_cast<GLsizei>(count));
}

} // namespace kraken::overlay::batch
