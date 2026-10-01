/*
 * The 3x3 pattern grid of a locked clone ("pattern lock").
 *
 * Drawn with plain framework calls, so no resource of the cloned app is touched. When the finger is
 * lifted the sequence of the dots that were drawn is handed over as "0,1,4,7" - the very same string the
 * setup screen hashes in App Cloner.
 */
package com.appcloner.runtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

final class PatternView extends View {

    interface Listener {
        /** [sequence] is the comma separated dot order, e.g. "0,1,4,7". */
        void onPattern(String sequence);
    }

    private static final int DOTS = 9;
    private static final float DOT_RADIUS_DP = 10f;
    private static final float TOUCH_RADIUS_DP = 46f;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Integer> selected = new ArrayList<Integer>();
    private final float[] centersX = new float[DOTS];
    private final float[] centersY = new float[DOTS];
    private final float density;

    private Listener listener;
    private int activeDot = -1;
    private float touchX;
    private float touchY;

    PatternView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        dotPaint.setColor(Color.parseColor("#FF3D3D4A"));
        dotPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(Color.parseColor("#FF7C6CFF"));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3f * density);
        linePaint.setColor(Color.parseColor("#FF7C6CFF"));
        linePaint.setStrokeWidth(6f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void clear() {
        selected.clear();
        activeDot = -1;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float stepX = width / 4f;
        float stepY = height / 4f;
        for (int index = 0; index < DOTS; index++) {
            centersX[index] = stepX * ((index % 3) + 1);
            centersY[index] = stepY * ((index / 3) + 1);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = DOT_RADIUS_DP * density;
        float ring = radius * 1.9f;

        // Lines first, so the dots stay on top of them.
        for (int index = 1; index < selected.size(); index++) {
            int from = selected.get(index - 1);
            int to = selected.get(index);
            canvas.drawLine(centersX[from], centersY[from], centersX[to], centersY[to], linePaint);
        }
        if (activeDot >= 0 && !selected.isEmpty()) {
            int from = selected.get(selected.size() - 1);
            canvas.drawLine(centersX[from], centersY[from], touchX, touchY, linePaint);
        }

        for (int index = 0; index < DOTS; index++) {
            canvas.drawCircle(centersX[index], centersY[index], radius, dotPaint);
            if (selected.contains(Integer.valueOf(index))) {
                canvas.drawCircle(centersX[index], centersY[index], ring, ringPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;
        touchX = event.getX();
        touchY = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clear();
                activeDot = dotAt(touchX, touchY);
                if (activeDot >= 0) {
                    selected.add(Integer.valueOf(activeDot));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                int dot = dotAt(touchX, touchY);
                if (dot >= 0 && !selected.contains(Integer.valueOf(dot))) {
                    selected.add(Integer.valueOf(dot));
                    activeDot = dot;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                activeDot = -1;
                invalidate();
                finishPattern();
                return true;
            case MotionEvent.ACTION_CANCEL:
                clear();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void finishPattern() {
        if (selected.size() < 4) {
            // A pattern needs at least four dots; anything shorter is not a pattern.
            clear();
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < selected.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append(selected.get(index));
        }
        if (listener != null) listener.onPattern(builder.toString());
    }

    private int dotAt(float x, float y) {
        float limit = TOUCH_RADIUS_DP * density;
        float best = limit * limit;
        int found = -1;
        for (int index = 0; index < DOTS; index++) {
            float dx = x - centersX[index];
            float dy = y - centersY[index];
            float distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                found = index;
            }
        }
        return found;
    }

    @Override
    public boolean performClick() {
        // Keeps accessibility services happy; the pattern itself is drawn with touch events.
        return super.performClick();
    }
}
