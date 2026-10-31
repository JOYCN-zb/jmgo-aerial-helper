package app.kiro.aerialboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 坚果待机时 com.jmgo.hippo 会强制结束所有第三方应用，普通广播接收器随之失效；
 * 通知监听服务会被系统重新绑定。这里在绑定时和每次亮屏时触发修复。
 */
public class KeeperService extends NotificationListenerService {
    private BroadcastReceiver screenOn;

    @Override
    public void onListenerConnected() {
        Log.i(FixService.TAG, "通知监听已绑定，触发修复");
        FixService.start(this, "listener");
        BrightnessBridge.start(this);
        if (screenOn == null) {
            screenOn = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(FixService.TAG, "收到 " + intent.getAction() + "，触发修复");
                    FixService.start(context, "screen_on");
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenOn, f);
        }
    }

    @Override
    public void onListenerDisconnected() {
        Log.i(FixService.TAG, "通知监听断开，请求重新绑定");
        try {
            requestRebind(new android.content.ComponentName(this, KeeperService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        BrightnessBridge.stop(this);
        if (screenOn != null) {
            try {
                unregisterReceiver(screenOn);
            } catch (Exception ignored) {
            }
            screenOn = null;
        }
        super.onDestroy();
    }
}
