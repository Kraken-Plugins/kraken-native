#include "overlay/gl.h"

#include <cstdint>

namespace kraken::overlay {
namespace {

/**
 * Looks up one GL entry point, treating the values some drivers return for a missing one as null.
 *
 * @param name the function's name
 * @return its address, or nullptr
 */
void *glProc(const char *name) {
    void *p = reinterpret_cast<void *>(wglGetProcAddress(name));
    intptr_t v = reinterpret_cast<intptr_t>(p);
    if (v == 0 || v == 1 || v == 2 || v == 3 || v == -1) {
        return nullptr;
    }
    return p;
}

} // namespace

bool lookupGlFunctions(GlFunctions &f, bool samplers) {
    f.createShader = reinterpret_cast<PFN_CreateShader>(glProc("glCreateShader"));
    f.shaderSource = reinterpret_cast<PFN_ShaderSource>(glProc("glShaderSource"));
    f.compileShader = reinterpret_cast<PFN_CompileShader>(glProc("glCompileShader"));
    f.getShaderiv = reinterpret_cast<PFN_GetShaderiv>(glProc("glGetShaderiv"));
    f.deleteShader = reinterpret_cast<PFN_DeleteShader>(glProc("glDeleteShader"));
    f.createProgram = reinterpret_cast<PFN_CreateProgram>(glProc("glCreateProgram"));
    f.attachShader = reinterpret_cast<PFN_AttachShader>(glProc("glAttachShader"));
    f.bindFragDataLocation = reinterpret_cast<PFN_BindFragDataLocation>(glProc("glBindFragDataLocation"));
    f.linkProgram = reinterpret_cast<PFN_LinkProgram>(glProc("glLinkProgram"));
    f.getProgramiv = reinterpret_cast<PFN_GetProgramiv>(glProc("glGetProgramiv"));
    f.deleteProgram = reinterpret_cast<PFN_DeleteProgram>(glProc("glDeleteProgram"));
    f.useProgram = reinterpret_cast<PFN_UseProgram>(glProc("glUseProgram"));
    f.getUniformLocation = reinterpret_cast<PFN_GetUniformLocation>(glProc("glGetUniformLocation"));
    f.uniform1i = reinterpret_cast<PFN_Uniform1i>(glProc("glUniform1i"));
    f.genVertexArrays = reinterpret_cast<PFN_GenVertexArrays>(glProc("glGenVertexArrays"));
    f.bindVertexArray = reinterpret_cast<PFN_BindVertexArray>(glProc("glBindVertexArray"));
    f.bindBuffer = reinterpret_cast<PFN_BindBuffer>(glProc("glBindBuffer"));
    f.activeTexture = reinterpret_cast<PFN_ActiveTexture>(glProc("glActiveTexture"));
    f.bindFramebuffer = reinterpret_cast<PFN_BindFramebuffer>(glProc("glBindFramebuffer"));
    f.bindSampler = samplers ? reinterpret_cast<PFN_BindSampler>(glProc("glBindSampler")) : nullptr;
    f.blendFuncSeparate = reinterpret_cast<PFN_BlendFuncSeparate>(glProc("glBlendFuncSeparate"));
    f.blendEquationSeparate = reinterpret_cast<PFN_BlendEquationSeparate>(glProc("glBlendEquationSeparate"));
    return f.createShader && f.shaderSource && f.compileShader && f.getShaderiv && f.deleteShader
           && f.createProgram && f.attachShader && f.bindFragDataLocation && f.linkProgram && f.getProgramiv
           && f.deleteProgram && f.useProgram && f.getUniformLocation && f.uniform1i && f.genVertexArrays
           && f.bindVertexArray && f.bindBuffer && f.activeTexture && f.bindFramebuffer
           && f.blendFuncSeparate && f.blendEquationSeparate && (!samplers || f.bindSampler);
}

} // namespace kraken::overlay
