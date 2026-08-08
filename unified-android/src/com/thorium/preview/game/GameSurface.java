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
        setOpaque(true);
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
