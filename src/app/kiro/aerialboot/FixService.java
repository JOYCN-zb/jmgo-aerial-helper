package app.kiro.aerialboot;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 坚果 S621 固件的两个问题，这里在开机、待机唤醒后各处理一轮：
 * 1. 按组件启动的 Aerial 落进 standard 任务，图层一直不显示、反复 ANR；以 HOME 方式启动则正常。
 * 2. 待机时 hippo 强制结束 Aerial，守护服务虽仍在启用列表里却不再绑定，Aerial 自己写设置会被固件拒绝；
 *    通过 hippo 的私有广播关一下再开，守护就会重新绑定。
 * 只在前台是原桌面或 Aerial 时拉起 Aerial，用户自己打开的应用不打扰。
 */
public class FixService extends Service {
    static final String TAG = "AerialBootFix";
    private static final String AERIAL = "app.orchard.tv";
    private static final String GUARD = AERIAL + "/" + AERIAL + ".guard.HomeGuardService";
    /** 每轮检查的时间点（毫秒）。 */
    private static final long[] DELAYS = {1000, 5000, 12000, 25000, 45000, 75000};
    /** 视为「停在桌面」的前台包名。 */
    private static final Set<String> HOMES = new HashSet<>(Arrays.asList(
            AERIAL, "com.jmgo.launcher", "com.jmgo.fallbackhome", "com.jmgo.tv", "android"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long roundStartedAt = -1;
    /** 本轮是否已经拉起过 Aerial */
    private boolean launchedThisRound = false;
    /** 前台已是 Aerial 时，只在一轮开始后这段时间内允许拉起（毫秒） */
    private static final long AERIAL_RELAUNCH_WINDOW = 12000;

    static void start(Context context, String reason) {
        try {
            context.startService(new Intent(context, FixService.class).putExtra("reason", reason));
        } catch (Exception e) {
            Log.w(TAG, "启动修复服务失败（" + reason + "）", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long now = SystemClock.uptimeMillis();
        // 一轮进行中（3 秒内重复触发）就不再叠加
        if (roundStartedAt >= 0 && now - roundStartedAt < 3000) return START_NOT_STICKY;
        roundStartedAt = now;
        launchedThisRound = false;
        handler.removeCallbacksAndMessages(null);
        Runnable checkTask = new Runnable() {
            @Override
            public void run() {
                check();
            }
        };
        for (long d : DELAYS) handler.postDelayed(checkTask, d);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                roundStartedAt = -1;
                stopSelf();
            }
        }, DELAYS[DELAYS.length - 1] + 2000);
        return START_NOT_STICKY;
    }

    private void check() {
        ensureGuardBound();
        String top = topPackage();
        if (top != null && !HOMES.contains(top)) {
            Log.i(TAG, "前台是 " + top + "，不打扰");
            return;
        }
        // 前台已是 Aerial 时，HOME 方式重新拉起会让 Aerial 关掉面板回到首页，打断用户操作。
        // 开机/亮屏卡死只出现在一轮开始后的头几秒，所以只在这段时间内、且本轮还没拉过时拉一次。
        if (AERIAL.equals(top)) {
            long elapsed = SystemClock.uptimeMillis() - roundStartedAt;
            if (launchedThisRound || elapsed > AERIAL_RELAUNCH_WINDOW) {
                Log.i(TAG, "前台已是 Aerial，不重复拉起");
                return;
            }
        }
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(AERIAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
            launchedThisRound = true;
            Log.i(TAG, "前台 " + top + "，已以 HOME 方式拉起 Aerial");
        } catch (Exception e) {
            Log.w(TAG, "拉起 Aerial 失败", e);
        }
    }

    /** 守护没绑定时，通过 hippo 私有广播关一下再开，让系统重新绑定。 */
    private void ensureGuardBound() {
        if (guardBound()) return;
        Log.i(TAG, "守护未绑定，通过 hippo 广播重新启用");
        sendHippo(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendHippo(true);
            }
        }, 800);
    }

    private boolean guardBound() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                // getId() 可能是缩写形式（app.orchard.tv/.guard.HomeGuardService），按包名和类名比较
                android.content.pm.ServiceInfo si = info.getResolveInfo() == null ? null : info.getResolveInfo().serviceInfo;
                if (si != null && AERIAL.equals(si.packageName) && (AERIAL + ".guard.HomeGuardService").equals(si.name)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void sendHippo(boolean enabled) {
        // 字段名 compontentNameStr 是坚果接口的原样拼写
        Intent i = new Intent("action.jmgo.request.accessibility.service")
                .setPackage("com.jmgo.hippo")
                .putExtra("compontentNameStr", GUARD)
                .putExtra("enabled", enabled);
        sendBroadcast(i);
    }

    /** 最近一次进入前台的应用包名；读不到时返回 null。 */
    private String topPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 10 * 60 * 1000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName();
            }
            return last;
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
