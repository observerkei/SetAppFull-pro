
package ss.colytitse.setappfull.hook;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.MotionEvent;
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


/**
 * 动态状态栏控制器。
 *
 * 只检测目标 Activity 正常内容区域的上下滑动。
 * 向上滑隐藏状态栏，向下滑显示状态栏。
 * 不修改导航栏，也不拦截 Activity 原本的触摸事件。
 */
public final class DynamicStatusBarController {

    private final FullscreenModule module;

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

        states.remove(activity);

        // 清除属于该 Activity 的所有弹窗，防止弹窗计数残留。
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

        // 动态模式关闭或弹窗存在时，不识别页面手势。
        if (!state.enabled || state.popupCount > 0) {
            state.startY = Float.NaN;
            return;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state.startY = event.getRawY();
                break;

            case MotionEvent.ACTION_UP:
                if (Float.isNaN(state.startY)) return;

                float deltaY = event.getRawY() - state.startY;
                state.startY = Float.NaN;

                // 使用系统最小触摸距离过滤点击和轻微抖动。
                int touchSlop =
                        ViewConfiguration.get(activity).getScaledTouchSlop();

                if (Math.abs(deltaY) <= touchSlop) return;

                boolean visible = deltaY > 0;

                DynamicStatusBarLog.d(
                        visible ? "Swipe down: show status bar"
                                : "Swipe up: hide status bar"
                );

                setStatusBarVisible(activity, visible);
                break;

            case MotionEvent.ACTION_CANCEL:
                state.startY = Float.NaN;
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

        DynamicStatusBarLog.i(
                enabled ? "Dynamic status bar enabled"
                        : "Dynamic status bar disabled"
        );

        if (enabled) {
            setStatusBarVisible(activity, true);
        }
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

    private static final class State {
        boolean enabled;
        float startY = Float.NaN;
        int popupCount;
    }
}
