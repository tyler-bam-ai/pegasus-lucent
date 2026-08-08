package com.thorium.preview.game;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

/** Engine-neutral, Lucent-owned video surface. */
public final class GameSurface extends TextureView
        implements TextureView.SurfaceTextureListener {
    public interface Listener {
        void onSurfaceAvailable(Surface surface, int width, int height);
        void onSurfaceSizeChanged(int width, int height);
        void onSurfaceDestroyed();
    }

    private Listener listener;
    private Surface renderSurface;

    public GameSurface(Context context) {
        super(context);
        setFocusable(false);
        // A TextureView marked opaque is composited with SkBlendMode.SRC, so
        // the engine buffer's alpha channel becomes the gameplay window's
        // alpha and SurfaceFlinger blends the Lucent library through it.
        // Hardware cores legitimately publish RGB with non-opaque alpha (the
        // PS2 GS marks a fully opaque pixel 0x80, and untouched swapchain rows
        // are zero), so an opaque layer published the library at 50% over
        // ARMSX2 gameplay. Compositing over the gameplay root's opaque black
        // background instead keeps premultiplied RGB and forces the published
        // window alpha to one, which is exactly what the GLES backend's
        // force_opaque_surface_alpha() does inside its own context.
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null && isAvailable() && getSurfaceTexture() != null) {
            ensureSurface(getSurfaceTexture());
            listener.onSurfaceAvailable(renderSurface, getWidth(), getHeight());
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,
                                                     int width, int height) {
        ensureSurface(texture);
        if (listener != null) listener.onSurfaceAvailable(renderSurface, width, height);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,
                                                       int width, int height) {
        if (listener != null) listener.onSurfaceSizeChanged(width, height);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        // The listener must finish (or abandon within a short bound) any
        // render-thread detach before this returns: the Surface below is
        // released immediately, and a swap still queued against it raises
        // EGL_BAD_SURFACE on GLES sessions.
        if (listener != null) listener.onSurfaceDestroyed();
        if (renderSurface != null) {
            renderSurface.release();
            renderSurface = null;
        }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}

    private void ensureSurface(SurfaceTexture texture) {
        if (renderSurface == null || !renderSurface.isValid()) {
            if (renderSurface != null) renderSurface.release();
            renderSurface = new Surface(texture);
        }
    }
}
