package com.thorium.preview.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import com.thorium.lucent.input.CanonicalControl;

import java.util.EnumSet;

/** Minimal Lucent-owned phone fallback; never shown when a physical pad exists. */
final class TouchControlsView extends View {
    interface Listener { void onControl(CanonicalControl control, boolean pressed); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final EnumSet<CanonicalControl> pressed = EnumSet.noneOf(CanonicalControl.class);
    private Listener listener;

    TouchControlsView(Context context) {
        super(context);
        setFocusable(false);
        fill.setColor(Color.argb(72, 255, 255, 255));
        text.setColor(Color.argb(190, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
    }

    void setListener(Listener listener) { this.listener = listener; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = Math.min(getWidth(), getHeight());
        float radius = unit * 0.052f;
        float leftX = getWidth() * 0.18f, centerY = getHeight() * 0.73f;
        drawButton(canvas, leftX, centerY - radius * 1.65f, radius, "▲");
        drawButton(canvas, leftX, centerY + radius * 1.65f, radius, "▼");
        drawButton(canvas, leftX - radius * 1.65f, centerY, radius, "◀");
        drawButton(canvas, leftX + radius * 1.65f, centerY, radius, "▶");

        float rightX = getWidth() * 0.82f;
        drawButton(canvas, rightX, centerY - radius * 1.65f, radius, "X");
        drawButton(canvas, rightX, centerY + radius * 1.65f, radius, "B");
        drawButton(canvas, rightX - radius * 1.65f, centerY, radius, "Y");
        drawButton(canvas, rightX + radius * 1.65f, centerY, radius, "A");

        float small = radius * 0.72f;
        drawButton(canvas, getWidth() * 0.08f, getHeight() * 0.12f, small, "L");
        drawButton(canvas, getWidth() * 0.92f, getHeight() * 0.12f, small, "R");
        drawButton(canvas, getWidth() * 0.46f, getHeight() * 0.88f, small, "−");
        drawButton(canvas, getWidth() * 0.54f, getHeight() * 0.88f, small, "+");
    }

    private void drawButton(Canvas canvas, float x, float y, float radius, String label) {
        canvas.drawCircle(x, y, radius, fill);
        text.setTextSize(radius * 0.72f);
        canvas.drawText(label, x, y - (text.ascent() + text.descent()) / 2f, text);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        EnumSet<CanonicalControl> next = EnumSet.noneOf(CanonicalControl.class);
        int lifted = event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                ? event.getActionIndex() : -1;
        if (event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                if (index == lifted) continue;
                CanonicalControl control = controlAt(event.getX(index), event.getY(index));
                if (control != null) next.add(control);
            }
        }
        for (CanonicalControl control : CanonicalControl.values()) {
            boolean was = pressed.contains(control), is = next.contains(control);
            if (was != is && listener != null) listener.onControl(control, is);
        }
        pressed.clear();
        pressed.addAll(next);
        return true;
    }

    private CanonicalControl controlAt(float x, float y) {
        float unit = Math.min(getWidth(), getHeight());
        float radius = unit * 0.069f;
        float leftX = getWidth() * 0.18f, centerY = getHeight() * 0.73f;
        CanonicalControl found = nearest(x, y, radius,
                leftX, centerY - unit * 0.086f, CanonicalControl.DPAD_UP,
                leftX, centerY + unit * 0.086f, CanonicalControl.DPAD_DOWN,
                leftX - unit * 0.086f, centerY, CanonicalControl.DPAD_LEFT,
                leftX + unit * 0.086f, centerY, CanonicalControl.DPAD_RIGHT);
        if (found != null) return found;
        float rightX = getWidth() * 0.82f;
        found = nearest(x, y, radius,
                rightX, centerY - unit * 0.086f, CanonicalControl.NORTH,
                rightX, centerY + unit * 0.086f, CanonicalControl.SOUTH,
                rightX - unit * 0.086f, centerY, CanonicalControl.WEST,
                rightX + unit * 0.086f, centerY, CanonicalControl.EAST);
        if (found != null) return found;
        if (distance(x, y, getWidth() * 0.08f, getHeight() * 0.12f) < radius)
            return CanonicalControl.L1;
        if (distance(x, y, getWidth() * 0.92f, getHeight() * 0.12f) < radius)
            return CanonicalControl.R1;
        if (distance(x, y, getWidth() * 0.46f, getHeight() * 0.88f) < radius)
            return CanonicalControl.SELECT;
        if (distance(x, y, getWidth() * 0.54f, getHeight() * 0.88f) < radius)
            return CanonicalControl.START;
        return null;
    }

    private static CanonicalControl nearest(float x, float y, float radius, Object... values) {
        for (int index = 0; index < values.length; index += 3)
            if (distance(x, y, (Float) values[index], (Float) values[index + 1]) < radius)
                return (CanonicalControl) values[index + 2];
        return null;
    }

    private static float distance(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }
}
