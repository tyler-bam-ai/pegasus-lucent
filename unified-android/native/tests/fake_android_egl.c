#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <string.h>

static int display_token, config_token, context_token, surface_token;
static EGLContext current_context;
static EGLint last_error = EGL_SUCCESS;
static unsigned swap_count, acquire_count, release_count;
static unsigned blit_count;
static unsigned opaque_alpha_clear_count;
static GLint last_blit_source_x1;
static GLint last_blit_source_y1;
static GLuint bound_framebuffer;
static int lose_next_swap;
static GLfloat clear_color[4] = {0, 0, 0, 0};
static GLboolean color_mask[4] = {GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE};
static GLboolean scissor_enabled;
static GLint viewport[4] = {0, 0, 0, 0};
static void fake_symbol(void) {}

EGLDisplay eglGetDisplay(void *unused) { (void)unused; return &display_token; }
EGLBoolean eglInitialize(EGLDisplay display, EGLint *major, EGLint *minor) {
    (void)major; (void)minor; return display == &display_token;
}
EGLBoolean eglBindAPI(EGLint api) { return api == EGL_OPENGL_ES_API; }
EGLBoolean eglChooseConfig(EGLDisplay display, const EGLint *attributes,
                           EGLConfig *configs, EGLint size, EGLint *count) {
    (void)attributes;
    if (display != &display_token || !configs || size < 1 || !count) return EGL_FALSE;
    configs[0] = &config_token; *count = 1; return EGL_TRUE;
}
EGLContext eglCreateContext(EGLDisplay display, EGLConfig config,
                            EGLContext shared, const EGLint *attributes) {
    (void)shared; (void)attributes;
    return display == &display_token && config == &config_token ?
            &context_token : EGL_NO_CONTEXT;
}
EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config,
                                   const EGLint *attributes) {
    (void)attributes;
    return display == &display_token && config == &config_token ?
            &surface_token : EGL_NO_SURFACE;
}
EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config,
                                  void *window, const EGLint *attributes) {
    (void)attributes;
    return display == &display_token && config == &config_token && window ?
            &surface_token : EGL_NO_SURFACE;
}
EGLBoolean eglMakeCurrent(EGLDisplay display, EGLSurface draw,
                          EGLSurface read, EGLContext context) {
    (void)draw; (void)read;
    if (display != &display_token) return EGL_FALSE;
    current_context = context; return EGL_TRUE;
}
EGLContext eglGetCurrentContext(void) { return current_context; }
EGLBoolean eglDestroySurface(EGLDisplay d, EGLSurface s) {
    return d == &display_token && s == &surface_token;
}
EGLBoolean eglDestroyContext(EGLDisplay d, EGLContext c) {
    return d == &display_token && c == &context_token;
}
EGLBoolean eglGetConfigAttrib(EGLDisplay d, EGLConfig c, EGLint attribute,
                              EGLint *value) {
    if (d != &display_token || c != &config_token ||
            attribute != EGL_NATIVE_VISUAL_ID || !value) return EGL_FALSE;
    *value = 1; return EGL_TRUE;
}
__eglMustCastToProperFunctionPointerType eglGetProcAddress(const char *name) {
    return name && strcmp(name, "lucent_mock_symbol") == 0 ? fake_symbol : NULL;
}
EGLBoolean eglSwapBuffers(EGLDisplay d, EGLSurface s) {
    if (d != &display_token || s != &surface_token) return EGL_FALSE;
    if (lose_next_swap) {
        lose_next_swap = 0; last_error = EGL_CONTEXT_LOST; return EGL_FALSE;
    }
    swap_count++; return EGL_TRUE;
}
EGLBoolean eglTerminate(EGLDisplay d) { return d == &display_token; }
EGLint eglGetError(void) { EGLint result = last_error; last_error = EGL_SUCCESS; return result; }
const GLubyte *glGetString(unsigned name) {
    static const GLubyte version[] = "OpenGL ES 3.2 Lucent Fake";
    return name == GL_VERSION ? version : NULL;
}
void glGenTextures(GLsizei count, GLuint *textures) {
    if (count > 0 && textures) textures[0] = 41;
}
void glBindTexture(GLenum target, GLuint texture) { (void)target; (void)texture; }
void glTexParameteri(GLenum target, GLenum name, GLint value) {
    (void)target; (void)name; (void)value;
}
void glTexImage2D(GLenum target, GLint level, GLint internal_format,
                  GLsizei width, GLsizei height, GLint border,
                  GLenum format, GLenum type, const void *pixels) {
    (void)target; (void)level; (void)internal_format; (void)width;
    (void)height; (void)border; (void)format; (void)type; (void)pixels;
}
void glViewport(GLint x, GLint y, GLsizei width, GLsizei height) {
    viewport[0] = x; viewport[1] = y;
    viewport[2] = width; viewport[3] = height;
}
void glClearColor(GLfloat red, GLfloat green, GLfloat blue, GLfloat alpha) {
    clear_color[0] = red; clear_color[1] = green;
    clear_color[2] = blue; clear_color[3] = alpha;
}
void glClear(GLbitfield mask) {
    if ((mask & GL_COLOR_BUFFER_BIT) && !color_mask[0] && !color_mask[1] &&
            !color_mask[2] && color_mask[3] && clear_color[3] == 1.0f &&
            !scissor_enabled) opaque_alpha_clear_count++;
}
void glGetFloatv(GLenum name, GLfloat *values) {
    if (name == GL_COLOR_CLEAR_VALUE && values)
        memcpy(values, clear_color, sizeof(clear_color));
}
void glGetBooleanv(GLenum name, GLboolean *values) {
    if (name == GL_COLOR_WRITEMASK && values)
        memcpy(values, color_mask, sizeof(color_mask));
}
void glGetIntegerv(GLenum name, GLint *values) {
    if (name == GL_VIEWPORT && values)
        memcpy(values, viewport, sizeof(viewport));
}
GLboolean glIsEnabled(GLenum capability) {
    return capability == GL_SCISSOR_TEST ? scissor_enabled : GL_FALSE;
}
void glDisable(GLenum capability) {
    if (capability == GL_SCISSOR_TEST) scissor_enabled = GL_FALSE;
}
void glEnable(GLenum capability) {
    if (capability == GL_SCISSOR_TEST) scissor_enabled = GL_TRUE;
}
void glColorMask(GLboolean red, GLboolean green, GLboolean blue,
                 GLboolean alpha) {
    color_mask[0] = red; color_mask[1] = green;
    color_mask[2] = blue; color_mask[3] = alpha;
}
void glReadPixels(GLint x, GLint y, GLsizei width, GLsizei height,
                  GLenum format, GLenum type, void *pixels) {
    GLubyte *value = (GLubyte *)pixels;
    (void)x; (void)y; (void)width; (void)height; (void)format; (void)type;
    value[0] = value[1] = value[2] = 0;
    value[3] = 255;
}
void glDeleteTextures(GLsizei count, const GLuint *textures) {
    (void)count; (void)textures;
}
void glGenFramebuffers(GLsizei count, GLuint *framebuffers) {
    if (count > 0 && framebuffers) framebuffers[0] = 42;
}
void glBindFramebuffer(GLenum target, GLuint framebuffer) {
    (void)target; bound_framebuffer = framebuffer;
}
void glFramebufferTexture2D(GLenum target, GLenum attachment,
                            GLenum texture_target, GLuint texture,
                            GLint level) {
    (void)target; (void)attachment; (void)texture_target;
    (void)texture; (void)level;
}
GLenum glCheckFramebufferStatus(GLenum target) {
    (void)target; return GL_FRAMEBUFFER_COMPLETE;
}
GLenum glGetError(void) { return GL_NO_ERROR; }
void glDeleteFramebuffers(GLsizei count, const GLuint *framebuffers) {
    (void)count; (void)framebuffers;
}
void glGenRenderbuffers(GLsizei count, GLuint *renderbuffers) {
    if (count > 0 && renderbuffers) renderbuffers[0] = 43;
}
void glBindRenderbuffer(GLenum target, GLuint renderbuffer) {
    (void)target; (void)renderbuffer;
}
void glRenderbufferStorage(GLenum target, GLenum format,
                           GLsizei width, GLsizei height) {
    (void)target; (void)format; (void)width; (void)height;
}
void glFramebufferRenderbuffer(GLenum target, GLenum attachment,
                               GLenum renderbuffer_target,
                               GLuint renderbuffer) {
    (void)target; (void)attachment; (void)renderbuffer_target;
    (void)renderbuffer;
}
void glDeleteRenderbuffers(GLsizei count, const GLuint *renderbuffers) {
    (void)count; (void)renderbuffers;
}
void glBlitFramebuffer(GLint source_x0, GLint source_y0,
                       GLint source_x1, GLint source_y1,
                       GLint destination_x0, GLint destination_y0,
                       GLint destination_x1, GLint destination_y1,
                       unsigned mask, GLenum filter) {
    (void)source_x0; (void)source_y0;
    last_blit_source_x1 = source_x1;
    last_blit_source_y1 = source_y1;
    (void)destination_x0; (void)destination_y0;
    (void)destination_x1; (void)destination_y1;
    (void)mask; (void)filter; blit_count++;
}
void ANativeWindow_acquire(ANativeWindow *window) { if (window) acquire_count++; }
void ANativeWindow_release(ANativeWindow *window) { if (window) release_count++; }
int ANativeWindow_setBuffersGeometry(ANativeWindow *window, int width,
                                     int height, int format) {
    (void)width; (void)height; (void)format; return window ? 0 : -1;
}
int32_t ANativeWindow_getWidth(ANativeWindow *window) {
    return window ? 1920 : 0;
}
int32_t ANativeWindow_getHeight(ANativeWindow *window) {
    return window ? 1080 : 0;
}
void lucent_fake_egl_lose_next_swap(void) { lose_next_swap = 1; }
unsigned lucent_fake_egl_swap_count(void) { return swap_count; }
unsigned lucent_fake_gles_blit_count(void) { return blit_count; }
unsigned lucent_fake_gles_opaque_alpha_clear_count(void) {
    return opaque_alpha_clear_count;
}
int lucent_fake_gles_last_blit_source_x1(void) { return last_blit_source_x1; }
int lucent_fake_gles_last_blit_source_y1(void) { return last_blit_source_y1; }
void lucent_fake_gles_set_scissor_enabled(int enabled) {
    scissor_enabled = enabled ? GL_TRUE : GL_FALSE;
}
int lucent_fake_gles_scissor_enabled(void) { return scissor_enabled == GL_TRUE; }
unsigned lucent_fake_gles_bound_framebuffer(void) { return bound_framebuffer; }
void lucent_fake_gles_set_viewport(GLint x, GLint y, GLsizei width,
                                   GLsizei height) {
    glViewport(x, y, width, height);
}
unsigned lucent_fake_window_acquire_count(void) { return acquire_count; }
unsigned lucent_fake_window_release_count(void) { return release_count; }
