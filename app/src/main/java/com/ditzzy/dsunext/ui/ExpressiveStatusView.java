package com.ditzzy.dsunext.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;

import com.ditzzy.dsunext.R;
import com.google.android.material.color.MaterialColors;

public final class ExpressiveStatusView extends View {

    private static final int BACK_LOBES = 12;
    private static final float BACK_DEPTH = 0.06f;
    private static final int FRONT_LOBES = 9;
    private static final float FRONT_DEPTH = 0.08f;
    private static final int PATH_STEPS = 360;

    private static final long ENTER_MS = 900L;
    private static final long SPIN_MS = 30_000L;
    private static final long RIPPLE_MS = 2_800L;
    private static final int RIPPLE_COUNT = 2;

    private static final float DEFAULT_SIZE_DP = 240f;
    private static final float BACK_RADIUS = 0.88f;
    private static final float FRONT_RADIUS = 0.60f;
    private static final float ICON_RADIUS = 0.31f;

    private final Path backShape = createScallopedShape(BACK_LOBES, BACK_DEPTH);
    private final Path frontShape = createScallopedShape(FRONT_LOBES, FRONT_DEPTH);

    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Interpolator overshoot = new OvershootInterpolator(1.6f);
    private final Interpolator decelerate = new DecelerateInterpolator(1.5f);

    @Nullable
    private Drawable icon;
    private int rippleColor;
    private boolean hasStatus;

    // 0..1 progress of each animation, read while drawing
    private float enter = 1f;
    private float spin;
    private float ripple;

    @Nullable
    private ValueAnimator enterAnimator;
    @Nullable
    private ValueAnimator spinAnimator;
    @Nullable
    private ValueAnimator rippleAnimator;

    public ExpressiveStatusView(Context context) {
        this(context, null);
    }

    public ExpressiveStatusView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ExpressiveStatusView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        backPaint.setStyle(Paint.Style.FILL);
        frontPaint.setStyle(Paint.Style.FILL);
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
    }

    /** Shows the result and plays the entrance animation. */
    public void setStatus(boolean success) {
        int container = resolve(success
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorErrorContainer);
        int accent = resolve(success
                ? androidx.appcompat.R.attr.colorPrimary
                : androidx.appcompat.R.attr.colorError);
        int onAccent = resolve(success
                ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnError);

        backPaint.setColor(container);
        frontPaint.setColor(accent);
        rippleColor = accent;

        Drawable drawable = AppCompatResources.getDrawable(
                getContext(), success ? R.drawable.ic_check : R.drawable.ic_close);
        icon = drawable != null ? drawable.mutate() : null;
        if (icon != null) {
            icon.setTint(onAccent);
        }

        hasStatus = true;
        restartAnimations();
        invalidate();
    }

    private int resolve(int attr) {
        return MaterialColors.getColor(this, attr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(getResources().getDisplayMetrics().density * DEFAULT_SIZE_DP);
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!hasStatus) {
            return;
        }

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = Math.min(getWidth(), getHeight()) / 2f;

        float backScale = overshoot.getInterpolation(clamp01(enter / 0.8f));
        float frontScale = overshoot.getInterpolation(clamp01((enter - 0.15f) / 0.85f));
        float iconScale = overshoot.getInterpolation(clamp01((enter - 0.4f) / 0.6f));

        drawRipples(canvas, cx, cy, half);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(spin * 360f);
        canvas.scale(half * BACK_RADIUS * backScale, half * BACK_RADIUS * backScale);
        canvas.drawPath(backShape, backPaint);
        canvas.restore();

        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(-spin * 360f);
        canvas.scale(half * FRONT_RADIUS * frontScale, half * FRONT_RADIUS * frontScale);
        canvas.drawPath(frontShape, frontPaint);
        canvas.restore();

        if (icon != null) {
            int iconHalf = Math.round(half * ICON_RADIUS);
            icon.setBounds(-iconHalf, -iconHalf, iconHalf, iconHalf);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(iconScale, iconScale);
            icon.draw(canvas);
            canvas.restore();
        }
    }

    private void drawRipples(Canvas canvas, float cx, float cy, float half) {
        // Ripples appear once the badge has mostly popped in
        float visibility = clamp01((enter - 0.6f) / 0.4f);
        if (visibility <= 0f || rippleAnimator == null) {
            return;
        }
        float from = half * FRONT_RADIUS;
        for (int i = 0; i < RIPPLE_COUNT; i++) {
            float t = (ripple + (float) i / RIPPLE_COUNT) % 1f;
            float radius = from + (half - from) * decelerate.getInterpolation(t);
            float alpha = (1f - t) * 0.5f * visibility;
            ripplePaint.setColor(ColorUtils.setAlphaComponent(rippleColor, Math.round(alpha * 255f)));
            canvas.drawCircle(cx, cy, radius, ripplePaint);
        }
    }

    private void restartAnimations() {
        cancelAnimations();
        if (!hasStatus) {
            return;
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Animations are off in system settings, show the final state as is
            enter = 1f;
            spin = 0f;
            ripple = 0f;
            return;
        }

        enter = 0f;
        enterAnimator = ValueAnimator.ofFloat(0f, 1f);
        enterAnimator.setDuration(ENTER_MS);
        enterAnimator.setInterpolator(new LinearInterpolator());
        enterAnimator.addUpdateListener(a -> {
            enter = (float) a.getAnimatedValue();
            invalidate();
        });
        enterAnimator.start();

        spinAnimator = endless(SPIN_MS, value -> spin = value);
        rippleAnimator = endless(RIPPLE_MS, value -> ripple = value);
    }

    private ValueAnimator endless(long duration, FloatConsumer consumer) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(duration);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            consumer.accept((float) a.getAnimatedValue());
            invalidate();
        });
        animator.start();
        return animator;
    }

    private void cancelAnimations() {
        for (ValueAnimator animator : new ValueAnimator[]{enterAnimator, spinAnimator, rippleAnimator}) {
            if (animator != null) {
                animator.cancel();
            }
        }
        enterAnimator = null;
        spinAnimator = null;
        rippleAnimator = null;
    }

    private void setAmbientPaused(boolean paused) {
        for (ValueAnimator animator : new ValueAnimator[]{spinAnimator, rippleAnimator}) {
            if (animator == null) {
                continue;
            }
            if (paused) {
                animator.pause();
            } else if (animator.isPaused()) {
                animator.resume();
            }
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setAmbientPaused(!isVisible);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        setAmbientPaused(visibility != VISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimations();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (hasStatus && enterAnimator == null && spinAnimator == null) {
            restartAnimations();
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * A closed shape of unit radius whose edge swells and dips {@code lobes} times, which gives the
     * rounded "cookie" and "sunny" silhouettes of Material 3 Expressive.
     */
    private static Path createScallopedShape(int lobes, float depth) {
        Path path = new Path();
        for (int i = 0; i <= PATH_STEPS; i++) {
            double angle = 2.0 * Math.PI * i / PATH_STEPS;
            double radius = (1.0 + depth * Math.cos(lobes * angle)) / (1.0 + depth);
            float x = (float) (radius * Math.cos(angle));
            float y = (float) (radius * Math.sin(angle));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        return path;
    }

    private interface FloatConsumer {
        void accept(float value);
    }
}
