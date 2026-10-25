package com.hatchslide.core.render.quality

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30

/** Reads the GPU renderer string for the first-launch quality choice. */
object GpuInfo {
    /**
     * GL_RENDERER from a throwaway 1x1 pbuffer context on the calling thread, or "" if EGL fails.
     * The default display is not terminated: Filament shares it in this process.
     */
    fun renderer(): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return ""
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return ""
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return ""
        val config = configs[0] ?: return ""
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return ""
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        return try {
            if (surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            } else {
                ""
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
        }
    }
}
