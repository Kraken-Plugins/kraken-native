// The OpenGL the overlay needs beyond GL 1.1's gl.h: the constants, and the entry points it looks up
// per context through wglGetProcAddress.
#pragma once

#include <windows.h>

#include <GL/gl.h>

// gl.h stops at GL 1.1, but a newer one may define these two.
#ifndef GL_BGRA
#define GL_BGRA 0x80E1
#endif
#ifndef GL_CLAMP_TO_EDGE
#define GL_CLAMP_TO_EDGE 0x812F
#endif

namespace kraken::overlay {

inline constexpr GLenum KGL_RGBA8 = 0x8058;
inline constexpr GLenum KGL_TEXTURE0 = 0x84C0;
inline constexpr GLenum KGL_ACTIVE_TEXTURE = 0x84E0;
inline constexpr GLenum KGL_TEXTURE_BINDING_2D = 0x8069;
inline constexpr GLenum KGL_TEXTURE_BASE_LEVEL = 0x813C;
inline constexpr GLenum KGL_TEXTURE_MAX_LEVEL = 0x813D;
inline constexpr GLenum KGL_SAMPLER_BINDING = 0x8919;
inline constexpr GLenum KGL_CURRENT_PROGRAM = 0x8B8D;
inline constexpr GLenum KGL_VERTEX_ARRAY_BINDING = 0x85B5;
inline constexpr GLenum KGL_DRAW_FRAMEBUFFER = 0x8CA9;
inline constexpr GLenum KGL_READ_FRAMEBUFFER = 0x8CA8;
inline constexpr GLenum KGL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
inline constexpr GLenum KGL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
inline constexpr GLenum KGL_FRAMEBUFFER_SRGB = 0x8DB9;
inline constexpr GLenum KGL_RASTERIZER_DISCARD = 0x8C89;
inline constexpr GLenum KGL_BLEND_DST_RGB = 0x80C8;
inline constexpr GLenum KGL_BLEND_SRC_RGB = 0x80C9;
inline constexpr GLenum KGL_BLEND_DST_ALPHA = 0x80CA;
inline constexpr GLenum KGL_BLEND_SRC_ALPHA = 0x80CB;
inline constexpr GLenum KGL_BLEND_EQUATION_RGB = 0x8009;
inline constexpr GLenum KGL_BLEND_EQUATION_ALPHA = 0x883D;
inline constexpr GLenum KGL_FUNC_ADD = 0x8006;
inline constexpr GLenum KGL_PIXEL_UNPACK_BUFFER = 0x88EC;
inline constexpr GLenum KGL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
inline constexpr GLenum KGL_CONTEXT_PROFILE_MASK = 0x9126;
inline constexpr GLint KGL_CONTEXT_CORE_PROFILE_BIT = 0x1;
inline constexpr GLenum KGL_VERTEX_SHADER = 0x8B31;
inline constexpr GLenum KGL_FRAGMENT_SHADER = 0x8B30;
inline constexpr GLenum KGL_COMPILE_STATUS = 0x8B81;
inline constexpr GLenum KGL_LINK_STATUS = 0x8B82;
inline constexpr GLenum KGL_SAMPLE_ALPHA_TO_COVERAGE = 0x809E;

using KGLchar = char;
using PFN_CreateShader = GLuint (APIENTRY *)(GLenum);
using PFN_ShaderSource = void (APIENTRY *)(GLuint, GLsizei, const KGLchar *const *, const GLint *);
using PFN_CompileShader = void (APIENTRY *)(GLuint);
using PFN_GetShaderiv = void (APIENTRY *)(GLuint, GLenum, GLint *);
using PFN_DeleteShader = void (APIENTRY *)(GLuint);
using PFN_CreateProgram = GLuint (APIENTRY *)();
using PFN_AttachShader = void (APIENTRY *)(GLuint, GLuint);
using PFN_BindFragDataLocation = void (APIENTRY *)(GLuint, GLuint, const KGLchar *);
using PFN_LinkProgram = void (APIENTRY *)(GLuint);
using PFN_GetProgramiv = void (APIENTRY *)(GLuint, GLenum, GLint *);
using PFN_DeleteProgram = void (APIENTRY *)(GLuint);
using PFN_UseProgram = void (APIENTRY *)(GLuint);
using PFN_GetUniformLocation = GLint (APIENTRY *)(GLuint, const KGLchar *);
using PFN_Uniform1i = void (APIENTRY *)(GLint, GLint);
using PFN_GenVertexArrays = void (APIENTRY *)(GLsizei, GLuint *);
using PFN_BindVertexArray = void (APIENTRY *)(GLuint);
using PFN_BindBuffer = void (APIENTRY *)(GLenum, GLuint);
using PFN_ActiveTexture = void (APIENTRY *)(GLenum);
using PFN_BindFramebuffer = void (APIENTRY *)(GLenum, GLuint);
using PFN_BindSampler = void (APIENTRY *)(GLuint, GLuint);
using PFN_BlendFuncSeparate = void (APIENTRY *)(GLenum, GLenum, GLenum, GLenum);
using PFN_BlendEquationSeparate = void (APIENTRY *)(GLenum, GLenum);

/**
 * The GL entry points beyond GL 1.1, loaded per context through wglGetProcAddress.
 */
struct GlFunctions {
    PFN_CreateShader createShader;
    PFN_ShaderSource shaderSource;
    PFN_CompileShader compileShader;
    PFN_GetShaderiv getShaderiv;
    PFN_DeleteShader deleteShader;
    PFN_CreateProgram createProgram;
    PFN_AttachShader attachShader;
    PFN_BindFragDataLocation bindFragDataLocation;
    PFN_LinkProgram linkProgram;
    PFN_GetProgramiv getProgramiv;
    PFN_DeleteProgram deleteProgram;
    PFN_UseProgram useProgram;
    PFN_GetUniformLocation getUniformLocation;
    PFN_Uniform1i uniform1i;
    PFN_GenVertexArrays genVertexArrays;
    PFN_BindVertexArray bindVertexArray;
    PFN_BindBuffer bindBuffer;
    PFN_ActiveTexture activeTexture;
    PFN_BindFramebuffer bindFramebuffer;
    PFN_BindSampler bindSampler;         // GL 3.3; null below
    PFN_BlendFuncSeparate blendFuncSeparate;
    PFN_BlendEquationSeparate blendEquationSeparate;
};

/**
 * Looks up the entry points beyond GL 1.1 for the current context. Changes no GL state.
 *
 * @param f        receives the entry points
 * @param samplers whether the context is GL 3.3 or later, so glBindSampler is looked up too
 * @return false if one is missing
 */
bool lookupGlFunctions(GlFunctions &f, bool samplers);

} // namespace kraken::overlay
