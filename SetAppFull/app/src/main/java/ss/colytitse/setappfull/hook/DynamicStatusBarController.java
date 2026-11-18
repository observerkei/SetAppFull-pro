
package ss.colytitse.setappfull.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ss.colytitse.setappfull.core.RuleCodec;
import ss.colytitse.setappfull.core.DynamicStatusBarLog;
import ss.colytitse.setappfull.ui.StatusBarGestureAnimator;


/**
 * 动态状态栏控制器。
 *
 * 只检测目标 Activity 正常内容区域的上下滑动。
 * 向上滑隐藏状态栏，向下滑显示状态栏。
 * 不修改导航栏，也不拦截 Activity 原本的触摸事件。
 */
public final class DynamicStatusBarController {

    private final FullscreenModule module;

    private final StatusBarGestureAnimator gestureAnimator =
            new StatusBarGestureAnimator();

    /** Activity 使用弱引用键，避免控制器延长 Activity 生命周期。 */
    private final Map<Activity, State> states = new WeakHashMap<>();

    /** 使用对象身份区分弹窗，避免多个弹窗互相覆盖。 */
    private final Map<Object, Activity> popups = new IdentityHashMap<>();

    public DynamicStatusBarController(FullscreenModule module) {
        this.module = module;
    }

    /**
     * Activity 恢复时同步动态模式状态。
     */
    public void onActivityResumed(Activity activity) {
        if (!isValidActivity(activity)) return;

        State state = states.computeIfAbsent(activity, key -> new State());
        syncEnabled(activity, state);
    }

    /**
     * Activity 销毁时清理手势和弹窗记录。
     */
    public void onActivityDestroyed(Activity activity) {
        if (activity == null) return;

        gestureAnimator.clear(activity);
        states.remove(activity);

        popups.values().removeIf(owner -> owner == activity);

        DynamicStatusBarLog.d("Activity state cleared");
    }

    /**
     * 配置变化后重新读取动态模式开关。
     */
    public void refresh() {
        for (Map.Entry<Activity, State> entry :
                new ArrayList<>(states.entrySet())) {
            Activity activity = entry.getKey();

            if (isValidActivity(activity)) {
                syncEnabled(activity, entry.getValue());
            }
        }
    }

    private void updateGesture(
            Activity activity,
            State state,
            float currentY) {
        float offset = 0;
        int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        // first in
        // init gesture
        if (Float.isNaN(state.gesture.startY)) {
//            float deltaY = currentY - state.startY;
//            if (Math.abs(deltaY) <= touchSlop) return;

            Window window = activity.getWindow();
            if (window == null) return;

            View decor = window.getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            state.statusBarsHeight = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;

            if (state.statusBarVisible) {
                state.gesture.lowerYHideAllStatusBar = currentY - state.statusBarsHeight;
                state.gesture.highYShowALLStatusBar = currentY;
                offset = state.statusBarsHeight;
            } else {
                state.gesture.lowerYHideAllStatusBar = currentY;
                state.gesture.highYShowALLStatusBar = currentY + state.statusBarsHeight;
                offset = 0;
            }
            View contentRoot = activity.findViewById(android.R.id.content);
            if (!gestureAnimator.prepare(
                    activity, contentRoot, state.statusBarsHeight,
                    state.statusBarsHeight - offset)) {
                return;
            }
            state.gesture.startY = currentY;
            DynamicStatusBarLog.d("updateGesture:: data init, statusBarsHeight: " + state.statusBarsHeight);
        }

        if (currentY < state.gesture.lowerYHideAllStatusBar) {
            if (!state.statusBarVisible) {
                return;
            }
            // TODO Hide Status bar
            offset = state.statusBarsHeight;
            state.statusBarVisible = false;
            setStatusBarVisible(activity, false);
            DynamicStatusBarLog.d("updateGesture:: <= top, Hide StatusBar");
            gestureAnimator.setOffset(activity, offset);
        } else if (currentY >= state.gesture.highYShowALLStatusBar + touchSlop) {
            if (state.statusBarVisible) {
                return;
            }
            // TODO Show Status bar
            offset = 0;
            state.statusBarVisible = true;
            setStatusBarVisible(activity, true);
            DynamicStatusBarLog.d("updateGesture:: >= low, Show StatusBar");
        } else {
            // TODO Hide Status bar
            offset = currentY - state.gesture.lowerYHideAllStatusBar;
            offset = Math.max(0, Math.min(offset, state.statusBarsHeight));
            if (state.statusBarVisible) {
                state.statusBarVisible = false;
                setStatusBarVisible(activity, false);
                DynamicStatusBarLog.d("updateGesture:: move activity, hide StatusBar");
            }
            gestureAnimator.setOffset(activity, offset);
        }
        gestureAnimator.setOffset(activity, offset);

        DynamicStatusBarLog.d(
                "updateGesture:: currentY: " + currentY
                + ", offset: " + offset
                + ", highY: " + state.gesture.highYShowALLStatusBar
                + ", lowerY: " + state.gesture.lowerYHideAllStatusBar
                + ", touchSlop: " + touchSlop
                + ", statusBarVisible: " + state.statusBarVisible
        );
    }


//    private void updateGesture(
//            Activity activity,
//            State state,
//            float currentY) {
//
//        float deltaY = currentY - state.startY;
//
//        int touchSlop =
//                ViewConfiguration.get(activity).getScaledTouchSlop();
//
//        if (!state.gestureActive) {
//            if (Math.abs(deltaY) <= touchSlop) return;
//
//            boolean targetVisible = deltaY > 0;
//
//            // Ignore gestures that do not change the current bar state.
//            if (targetVisible == state.statusBarVisible) return;
//
//            Window window = activity.getWindow();
//            if (window == null) return;
//
//            View decor = window.getDecorView();
//            View contentRoot = activity.findViewById(android.R.id.content);
//            WindowInsets insets = decor.getRootWindowInsets();
//
//            if (contentRoot == null || insets == null) return;
//
//            int height = insets.getInsetsIgnoringVisibility(
//                    WindowInsets.Type.statusBars()).top;
//
//            if (height <= 0) {
//                DynamicStatusBarLog.d(
//                        "Gesture ignored: status bar height unavailable");
//                return;
//            }
//
//            float initialOffset = targetVisible ? 0f : height;
//
//            if (!gestureAnimator.prepare(
//                    activity, contentRoot, height, initialOffset)) {
//                return;
//            }
//
//            state.gestureActive = true;
//            state.gestureTargetVisible = targetVisible;
//            state.gestureHeight = height;
//
//            if (!targetVisible) {
//                // Preserve the content position before hiding the system bar.
//                setStatusBarVisible(activity, false);
//                state.statusBarVisible = false;
//            }
//
//            DynamicStatusBarLog.d(
//                    targetVisible
//                            ? "Down gesture started"
//                            : "Up gesture started");
//        }
//
//        float distance = Math.abs(deltaY) - touchSlop;
//        distance = Math.max(0f, distance);
//
//        float offset = state.gestureTargetVisible
//                ? distance
//                : state.gestureHeight - distance;
//
//        gestureAnimator.setOffset(activity, offset);
//
//        // Hand over to the system status bar as soon as the
//        // manually expanded area reaches the full status bar height.
//        if (state.gestureTargetVisible
//                && distance >= state.gestureHeight) {
//            DynamicStatusBarLog.d(
//                    "Status bar handoff threshold reached");
//
//            // finishGesture(activity, state);
//            gestureAnimator.clear(activity);
//        }
//    }
private void finishGesture(Activity activity, State state) {
    if (Float.isNaN(state.gesture.startY)) return;

    int height = state.statusBarVisible ? state.statusBarsHeight : 0;

    state.gesture.lowerYHideAllStatusBar = 0;
    state.gesture.highYShowALLStatusBar = 0;
    gestureAnimator.clear(activity);

    state.gesture.startY = Float.NaN;
}

//    private void finishGesture(Activity activity, State state) {
//        if (!state.gestureActive) return;
//
//        boolean targetVisible = state.gestureTargetVisible;
//        int height = state.gestureHeight;
//
//        state.gestureActive = false;
//        state.gestureHeight = 0;
//
//        if (targetVisible) {
//            gestureAnimator.animateTo(
//                    activity,
//                    height,
//                    180L,
//                    () -> {
//                        if (!isValidActivity(activity) || !state.enabled) {
//                            gestureAnimator.clear(activity);
//                            return;
//                        }
//
//                        setStatusBarVisible(activity, true);
//                        state.statusBarVisible = true;
//                        gestureAnimator.clear(activity);
//                    });
//        } else {
//            gestureAnimator.animateTo(
//                    activity,
//                    0f,
//                    180L,
//                    () -> gestureAnimator.clear(activity));
//        }
//    }

    private void abortGesture(Activity activity, State state) {
        state.gesture.startY = Float.NaN;
        state.gesture.highYShowALLStatusBar = 0;
        state.gesture.lowerYHideAllStatusBar = 0;

//        state.startY = Float.NaN;
//
//        if (!state.gestureActive) {
//            gestureAnimator.clear(activity);
//            return;
//        }
//
//        // An upward gesture hides a bar that was originally visible.
//        if (!state.gestureTargetVisible && state.enabled) {
//            setStatusBarVisible(activity, true);
//            state.statusBarVisible = true;
//        }
//
//        state.gestureActive = false;
//        state.gestureHeight = 0;
//
        gestureAnimator.clear(activity);
        DynamicStatusBarLog.d("Gesture aborted");
    }


    /**
     * 观察 Activity 的触摸事件。
     *
     * 这里只读取 DOWN 和 UP 的纵向坐标：
     * deltaY > 0：手指向下移动，显示状态栏。
     * deltaY < 0：手指向上移动，隐藏状态栏。
     */
    public void onTouch(Activity activity, MotionEvent event) {
        if (!isValidActivity(activity) || event == null) return;

        State state = states.computeIfAbsent(activity, key -> new State());
        syncEnabled(activity, state);

        if (!state.enabled || state.popupCount > 0) {
            abortGesture(activity, state);
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                abortGesture(activity, state);
                state.startY = event.getRawY();
                break;

            case MotionEvent.ACTION_MOVE:
                if (Float.isNaN(state.startY)) return;
                updateGesture(activity, state, event.getRawY());
                break;

            case MotionEvent.ACTION_UP:
                if (Float.isNaN(state.startY)) return;

                updateGesture(activity, state, event.getRawY());
                state.startY = Float.NaN;

                finishGesture(activity, state);
                break;

            case MotionEvent.ACTION_CANCEL:
                abortGesture(activity, state);
                break;

            default:
                break;
        }
    }

    /**
     * 弹窗显示时暂停 Activity 页面手势检测。
     *
     * 同一个弹窗重复调用不会重复增加计数。
     */
    public void onPopupShown(Activity activity, Object token) {
        if (!isValidActivity(activity) || token == null) return;

        Activity previous = popups.get(token);

        // 同一个弹窗已经登记，不重复计数。
        if (previous == activity) return;

        if (previous != null) {
            State oldState = states.get(previous);
            if (oldState != null) {
                oldState.popupCount = Math.max(0, oldState.popupCount - 1);
            }
        }

        popups.put(token, activity);

        State state = states.computeIfAbsent(activity, key -> new State());

        abortGesture(activity, state);

        state.popupCount++;
        state.startY = Float.NaN;

        DynamicStatusBarLog.d(
                "Popup shown, active count=" + state.popupCount
        );
    }

    /**
     * 弹窗关闭时恢复计数。
     * 只有所有弹窗都关闭后，页面手势检测才会恢复。
     */
    public void onPopupDismissed(Object token) {
        if (token == null) return;

        Activity activity = popups.remove(token);
        if (activity == null) return;

        State state = states.get(activity);
        if (state == null) return;

        state.popupCount = Math.max(0, state.popupCount - 1);
        state.startY = Float.NaN;

        DynamicStatusBarLog.d(
                "Popup dismissed, active count=" + state.popupCount
        );
    }

    /**
     * 从 View 或弹窗 Context 中寻找所属 Activity。
     */
    public static Activity findActivity(Context context) {
        Set<Context> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        while (context != null && visited.add(context)) {
            if (context instanceof Activity) {
                return (Activity) context;
            }

            if (!(context instanceof ContextWrapper)) {
                return null;
            }

            context = ((ContextWrapper) context).getBaseContext();
        }

        return null;
    }

    /**
     * 同步配置状态。
     *
     * 动态模式刚开启时先显示状态栏，避免继承此前全屏状态。
     */
    private void syncEnabled(Activity activity, State state) {
        boolean enabled = (module.ruleFor(activity.getPackageName())
                & RuleCodec.DYNAMIC_STATUS_BAR) != 0;

        if (enabled == state.enabled) return;

        state.enabled = enabled;
        state.startY = Float.NaN;

        if (!enabled) {
            state.gestureActive = false;
            state.gestureHeight = 0;
            gestureAnimator.clear(activity);
        }

        DynamicStatusBarLog.i(
                enabled ? "Dynamic status bar enabled"
                        : "Dynamic status bar disabled"
        );

        if (enabled) {
            state.statusBarVisible = true;
            setStatusBarVisible(activity, true);
            DynamicStatusBarLog.d("syncEnabled:: enable Dynamic cfg, Show StatusBar");        }
    }

    /**
     * 只显示或隐藏状态栏，不操作导航栏。
     */
    private void setStatusBarVisible(Activity activity, boolean visible) {
        Window window = activity.getWindow();
        if (window == null) {
            DynamicStatusBarLog.w("Cannot access Activity window");
            return;
        }

        WindowInsetsController controller = window.getInsetsController();
        if (controller == null) {
            DynamicStatusBarLog.w("WindowInsetsController unavailable");
            return;
        }

        try {
            if (visible) {
                controller.show(WindowInsets.Type.statusBars());
            } else {
                controller.hide(WindowInsets.Type.statusBars());
            }

            DynamicStatusBarLog.d(
                    visible ? "Status bar shown" : "Status bar hidden"
            );
        } catch (RuntimeException error) {
            DynamicStatusBarLog.w("Status bar operation failed", error);
        }
    }

    private boolean isValidActivity(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }


    private static final class GestureState {
        float startY = Float.NaN;
        float lowerYHideAllStatusBar = 0;
        float highYShowALLStatusBar = 0;
    }

    private static final class State {
        boolean enabled;

        // Current status bar state managed by this controller
        boolean statusBarVisible = true;

        int statusBarsHeight;

        // Gesture tracking.
        float startY = Float.NaN;


        // Gesture state.
        final GestureState gesture = new GestureState();

        boolean gestureActive;
        boolean gestureTargetVisible;
        int gestureHeight;
        boolean gestureHeightHideAll;

        boolean gestureHeightShowAll;
        int popupCount;
    }
}
