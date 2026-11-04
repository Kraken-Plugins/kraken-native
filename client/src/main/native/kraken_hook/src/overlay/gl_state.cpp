#include "overlay/gl_state.h"

namespace kraken::overlay {
namespace {

/**
 * Sets one capability on or off.
 *
 * @param cap the capability
 * @param on  whether it should be enabled
 */
void setCap(GLenum cap, GLboolean on) {
    if (on) {
        glEnable(cap);
    } else {
        glDisable(cap);
    }
}

} // namespace

void saveGl(const GlFunctions &f, GlSaved &s) {
    glGetIntegerv(KGL_CURRENT_PROGRAM, &s.program);
    glGetIntegerv(KGL_VERTEX_ARRAY_BINDING, &s.vao);
    glGetIntegerv(KGL_ACTIVE_TEXTURE, &s.activeTexture);
    f.activeTexture(KGL_TEXTURE0);
    glGetIntegerv(KGL_TEXTURE_BINDING_2D, &s.texture0);
    s.sampler0 = 0;
    if (f.bindSampler) {
        glGetIntegerv(KGL_SAMPLER_BINDING, &s.sampler0);
    }
    glGetIntegerv(KGL_DRAW_FRAMEBUFFER_BINDING, &s.drawFramebuffer);
    glGetIntegerv(KGL_READ_FRAMEBUFFER_BINDING, &s.readFramebuffer);
    glGetIntegerv(GL_VIEWPORT, s.viewport);
    s.scissor = glIsEnabled(GL_SCISSOR_TEST);
    s.depth = glIsEnabled(GL_DEPTH_TEST);
    s.stencil = glIsEnabled(GL_STENCIL_TEST);
    s.cull = glIsEnabled(GL_CULL_FACE);
    s.blend = glIsEnabled(GL_BLEND);
    s.srgb = glIsEnabled(KGL_FRAMEBUFFER_SRGB);
    s.discard = glIsEnabled(KGL_RASTERIZER_DISCARD);
    s.alphaToCoverage = glIsEnabled(KGL_SAMPLE_ALPHA_TO_COVERAGE);
    s.logicOp = glIsEnabled(GL_COLOR_LOGIC_OP);
    glGetIntegerv(KGL_BLEND_SRC_RGB, &s.blendSrcRgb);
    glGetIntegerv(KGL_BLEND_DST_RGB, &s.blendDstRgb);
    glGetIntegerv(KGL_BLEND_SRC_ALPHA, &s.blendSrcAlpha);
    glGetIntegerv(KGL_BLEND_DST_ALPHA, &s.blendDstAlpha);
    glGetIntegerv(KGL_BLEND_EQUATION_RGB, &s.blendEqRgb);
    glGetIntegerv(KGL_BLEND_EQUATION_ALPHA, &s.blendEqAlpha);
    glGetBooleanv(GL_COLOR_WRITEMASK, s.colourMask);
    s.polygonMode[0] = GL_FILL;
    s.polygonMode[1] = GL_FILL;
    glGetIntegerv(GL_POLYGON_MODE, s.polygonMode);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &s.unpackAlignment);
    glGetIntegerv(GL_UNPACK_ROW_LENGTH, &s.unpackRowLength);
    glGetIntegerv(GL_UNPACK_SKIP_ROWS, &s.unpackSkipRows);
    glGetIntegerv(GL_UNPACK_SKIP_PIXELS, &s.unpackSkipPixels);
    glGetIntegerv(KGL_PIXEL_UNPACK_BUFFER_BINDING, &s.unpackBuffer);
    glGetIntegerv(KGL_ARRAY_BUFFER_BINDING, &s.arrayBuffer);
    // The draw buffer is per framebuffer: the draw reads framebuffer 0's once it binds it
    s.drawBuffer = -1;
}

void restoreGl(const GlFunctions &f, const GlSaved &s) {
    if (s.drawBuffer >= 0) {
        glDrawBuffer(static_cast<GLenum>(s.drawBuffer));
    }
    f.bindFramebuffer(KGL_DRAW_FRAMEBUFFER, static_cast<GLuint>(s.drawFramebuffer));
    f.bindFramebuffer(KGL_READ_FRAMEBUFFER, static_cast<GLuint>(s.readFramebuffer));
    glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
    setCap(GL_SCISSOR_TEST, s.scissor);
    setCap(GL_DEPTH_TEST, s.depth);
    setCap(GL_STENCIL_TEST, s.stencil);
    setCap(GL_CULL_FACE, s.cull);
    setCap(GL_BLEND, s.blend);
    setCap(KGL_FRAMEBUFFER_SRGB, s.srgb);
    setCap(KGL_RASTERIZER_DISCARD, s.discard);
    setCap(KGL_SAMPLE_ALPHA_TO_COVERAGE, s.alphaToCoverage);
    setCap(GL_COLOR_LOGIC_OP, s.logicOp);
    f.blendFuncSeparate(s.blendSrcRgb, s.blendDstRgb, s.blendSrcAlpha, s.blendDstAlpha);
    f.blendEquationSeparate(s.blendEqRgb, s.blendEqAlpha);
    glColorMask(s.colourMask[0], s.colourMask[1], s.colourMask[2], s.colourMask[3]);
    if (s.polygonMode[0] != GL_FILL) {
        glPolygonMode(GL_FRONT_AND_BACK, s.polygonMode[0]);
    }
    glPixelStorei(GL_UNPACK_ALIGNMENT, s.unpackAlignment);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, s.unpackRowLength);
    glPixelStorei(GL_UNPACK_SKIP_ROWS, s.unpackSkipRows);
    glPixelStorei(GL_UNPACK_SKIP_PIXELS, s.unpackSkipPixels);
    f.bindBuffer(KGL_PIXEL_UNPACK_BUFFER, static_cast<GLuint>(s.unpackBuffer));
    f.bindBuffer(KGL_ARRAY_BUFFER, static_cast<GLuint>(s.arrayBuffer));
    if (f.bindSampler) {
        f.bindSampler(0, static_cast<GLuint>(s.sampler0));
    }
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(s.texture0));
    f.activeTexture(static_cast<GLenum>(s.activeTexture));
    f.bindVertexArray(static_cast<GLuint>(s.vao));
    f.useProgram(static_cast<GLuint>(s.program));
}

} // namespace kraken::overlay
