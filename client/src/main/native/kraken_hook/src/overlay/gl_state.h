// The client's GL state the overlay changes, saved before it draws and restored after, so the client
// never sees the overlay's state.
#pragma once

#include "overlay/gl.h"

namespace kraken::overlay {

/**
 * The client's GL state the overlay changes, saved before and restored after.
 */
struct GlSaved {
    GLint program;
    GLint vao;
    GLint activeTexture;
    GLint texture0;
    GLint sampler0;
    GLint drawFramebuffer;
    GLint readFramebuffer;
    GLint drawBuffer;
    GLint viewport[4];
    GLboolean scissor;
    GLboolean depth;
    GLboolean stencil;
    GLboolean cull;
    GLboolean blend;
    GLboolean srgb;
    GLboolean discard;
    GLboolean alphaToCoverage;
    GLboolean logicOp;
    GLint blendSrcRgb;
    GLint blendDstRgb;
    GLint blendSrcAlpha;
    GLint blendDstAlpha;
    GLint blendEqRgb;
    GLint blendEqAlpha;
    GLboolean colourMask[4];
    GLint polygonMode[2];
    GLint unpackAlignment;
    GLint unpackRowLength;
    GLint unpackSkipRows;
    GLint unpackSkipPixels;
    GLint unpackBuffer;
    GLint arrayBuffer;
};

/**
 * Saves the client's GL state the overlay changes, and binds texture unit 0.
 *
 * @param f the context's entry points
 * @param s where to save it
 */
void saveGl(const GlFunctions &f, GlSaved &s);

/**
 * Restores the client's GL state saved by saveGl.
 *
 * @param f the context's entry points
 * @param s the saved state
 */
void restoreGl(const GlFunctions &f, const GlSaved &s);

} // namespace kraken::overlay
