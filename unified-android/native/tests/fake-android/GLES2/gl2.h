#ifndef LUCENT_FAKE_GLES2_H
#define LUCENT_FAKE_GLES2_H
typedef unsigned char GLubyte;
#define GL_VERSION 0x1F02
const GLubyte *glGetString(unsigned name);
#endif
