package ss.colytitse.setappfull.ui;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import ss.colytitse.setappfull.core.DynamicStatusBarLog;

/**
 * Applies temporary vertical translation to an Activity content view
 * during dynamic status bar gestures.
 *
 * This class does not show or hide system bars.
 */
public final class StatusBarGestureAnimator {

    private static final long DEFAULT_DURATION_MS = 180L;

    private final Map<Activity, AnimationState> states = new WeakHashMap<>();

    /**
     * Prepares the content view for a gesture.
     *
     * @param activity owner Activity
     * @param contentRoot Activity content root
     * @param statusBarHeight status bar inset height in pixels
     * @param initialOffset initial additional translation in pixels
     * @return true if the gesture animation can be started
     */
    public boolean prepare(
            Activity activity,
            View contentRoot,
            int statusBarHeight,
            float initialOffset) {

        checkMainThread();

        if (!isActivityValid(activity)
                || contentRoot == null
                || statusBarHeight <= 0
                || contentRoot.getWindowToken() == null) {
            return false;
        }

        clear(activity);

        AnimationState state = new AnimationState(
                contentRoot,
                statusBarHeight,
                0);
        Drawable background = contentRoot.getBackground();
        activity.getWindow().setBackgroundDrawable(background);

        states.put(activity, state);
        setOffsetInternal(state, initialOffset);

        DynamicStatusBarLog.d("Gesture animation prepared"
                + ", contentRoot.getTranslationY(): " + contentRoot.getTranslationY()

        );
        return true;
    }

    /**
     * Updates the additional offset directly from finger movement.
     *
     * @param activity owner Activity
     * @param offset additional translation in pixels
     */
    public void setOffset(Activity activity, float offset) {
        checkMainThread();

        AnimationState state = getState(activity);
        if (state == null) return;

        cancelAnimator(state);
        setOffsetInternal(state, offset);
    }

    /**
     * Animates the additional offset to a target value.
     *
     * The completion callback is invoked after the animation ends.
     */
    public void animateTo(
            Activity activity,
            float targetOffset,
            long durationMs,
            Runnable onEnd) {

        checkMainThread();

        AnimationState state = getState(activity);
        if (state == null) {
            runCallback(onEnd);
            return;
        }

        cancelAnimator(state);

        float target = clamp(targetOffset, 0f, state.statusBarHeight);
        float start = state.currentOffset;

        if (Math.abs(target - start) < 0.5f || durationMs <= 0L) {
            setOffsetInternal(state, target);
            runCallback(onEnd);
            return;
        }

        ValueAnimator animator = ValueAnimator.ofFloat(start, target);
        state.animator = animator;

        animator.setDuration(durationMs);
        animator.setInterpolator(new DecelerateInterpolator());

        animator.addUpdateListener(animation -> {
            if (state.animator != animator) return;

            float value = (float) animation.getAnimatedValue();
            setOffsetInternal(state, value);
        });

        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (state.animator != animator) return;

                state.animator = null;

                if (!cancelled) {
                    setOffsetInternal(state, target);
                    runCallback(onEnd);
                }
            }
        });

        animator.start();
    }

    /**
     * Cancels the current animation and restores the original translation.
     */
    public void cancel(Activity activity) {
        checkMainThread();

        AnimationState state = getState(activity);
        if (state == null) return;

        cancelAnimator(state);
        restoreOriginalTranslation(state);

        DynamicStatusBarLog.d("Gesture animation cancelled");
    }

    /**
     * Removes all animation state and restores the original translation.
     */
    public void clear(Activity activity) {
        checkMainThread();

        if (activity == null) return;

        AnimationState state = states.remove(activity);
        if (state == null) return;

        cancelAnimator(state);
        restoreOriginalTranslation(state);

        DynamicStatusBarLog.d("Gesture animation state cleared");
    }

    /**
     * Clears all tracked animations.
     */
    public void clearAll() {
        checkMainThread();

        for (AnimationState state : states.values()) {
            cancelAnimator(state);
            restoreOriginalTranslation(state);
        }

        states.clear();
        DynamicStatusBarLog.d("All gesture animations cleared");
    }

    private AnimationState getState(Activity activity) {
        if (!isActivityValid(activity)) return null;

        AnimationState state = states.get(activity);
        if (state == null || state.contentRoot.get() == null) {
            if (state != null) {
                cancelAnimator(state);
                states.remove(activity);
            }
            return null;
        }

        return state;
    }

    private void setOffsetInternal(AnimationState state, float offset) {
        View view = state.contentRoot.get();
        if (view == null) return;

        float bounded = clamp(offset, 0f, state.statusBarHeight);
        state.currentOffset = bounded;
        view.setTranslationY(state.originalTranslationY + bounded);
    }

    private void restoreOriginalTranslation(AnimationState state) {
        View view = state.contentRoot.get();
        if (view == null) return;

        view.setTranslationY(state.originalTranslationY);
        state.currentOffset = 0f;
    }

    private void cancelAnimator(AnimationState state) {
        ValueAnimator animator = state.animator;
        state.animator = null;

        if (animator != null) {
            animator.cancel();
        }
    }

    private static boolean isActivityValid(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void runCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private static void checkMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "StatusBarGestureAnimator must run on the main thread");
        }
    }

    private static final class AnimationState {
        final WeakReference<View> contentRoot;
        final int statusBarHeight;
        final float originalTranslationY;

        float currentOffset;
        ValueAnimator animator;

        AnimationState(
                View contentRoot,
                int statusBarHeight,
                float originalTranslationY) {

            this.contentRoot = new WeakReference<>(contentRoot);
            this.statusBarHeight = statusBarHeight;
            this.originalTranslationY = originalTranslationY;
            this.currentOffset = 0f;
        }
    }
}
