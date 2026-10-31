package app.kiro.aerialboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** adb 手动触发修复；带 --es op get_light 时只读取当前光源亮度并写日志（诊断用）。 */
public class ManualReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if ("get_light".equals(intent.getStringExtra("op"))) {
            Log.i(FixService.TAG, "当前光源亮度 get(512) = " + BrightnessBridge.getLight());
            return;
        }
        Log.i(FixService.TAG, "手动触发修复");
        FixService.start(context, "manual");
    }
}
