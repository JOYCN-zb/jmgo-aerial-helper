package app.kiro.aerialboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机完成（或本应用升级）后启动修复服务。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(FixService.TAG, "收到 " + intent.getAction() + "，启动修复服务");
        FixService.start(context, "boot");
    }
}
