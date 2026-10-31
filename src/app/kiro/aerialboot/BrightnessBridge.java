package app.kiro.aerialboot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 亮度转接：Aerial 的亮度条只写 Settings.Global.picture_backlight（液晶背光值），
 * 坚果投影的光源不读这个值。这里监听它的变化，换算后通过 Binder 调用坚果光机服务
 * set(512, 值)，与坚果设置「光源亮度」（JmGODlpManager.set(512, 值)）走同一条路。
 *
 * 坚果光源亮度共 10 档：第 1 到 9 档对应 0、11 ... 88，第 10 档对应 100。
 */
public final class BrightnessBridge {
    static final String KEY = "picture_backlight";
    /** 坚果光机接口里「光源亮度」的命令号 */
    static final int CMD_LIGHT = 512;

    private static HandlerThread thread;
    private static ContentObserver observer;
    private static int lastSent = -1;

    private BrightnessBridge() {
    }

    /** 注册监听，可重复调用 */
    public static synchronized void start(Context context) {
        if (observer != null) {
            return;
        }
        final Context app = context.getApplicationContext();
        thread = new HandlerThread("brightness-bridge");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        observer = new ContentObserver(h) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                apply(app);
            }
        };
        ContentResolver cr = app.getContentResolver();
        cr.registerContentObserver(Settings.Global.getUriFor(KEY), false, observer);
        // 同时监听 System 表，兼容 Aerial 以后改写到 System 表的情况
        cr.registerContentObserver(Settings.System.getUriFor(KEY), false, observer);
        Log.i(FixService.TAG, "亮度转接已启动");
    }

    public static synchronized void stop(Context context) {
        if (observer == null) {
            return;
        }
        try {
            context.getApplicationContext().getContentResolver().unregisterContentObserver(observer);
        } catch (Exception ignored) {
        }
        observer = null;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    /** 读取 picture_backlight（0 到 100）并下发到光机 */
    static void apply(Context context) {
        ContentResolver cr = context.getContentResolver();
        int v = Settings.Global.getInt(cr, KEY, -1);
        if (v < 0) {
            v = Settings.System.getInt(cr, KEY, -1);
        }
        if (v < 0) {
            return;
        }
        int target = toJmgo(v);
        if (target == lastSent) {
            return;
        }
        int r = setLight(context, target);
        Log.i(FixService.TAG, "picture_backlight=" + v + " -> 光源亮度 " + target + "，返回 " + r);
        if (r >= 0) {
            lastSent = target;
        }
    }

    /** 0 到 100 换算成坚果的 10 档取值 */
    static int toJmgo(int v) {
        if (v > 100) {
            v = 100;
        }
        int level = Math.round(v * 9 / 100f); // 0..9
        return level >= 9 ? 100 : level * 11;
    }

    /** 坚果中间件系统服务名及 AIDL 描述符、事务号（取自 com.jmgo.middleware.jar） */
    static final String SVC = "jmgomiddle-01";
    static final String DESC_SYS = "com.jmgo.middleware.service.IJmGOSystemService";
    static final int TX_GET_DLP = 5;
    static final String DESC_DLP = "com.jmgo.middleware.dlp.IJmGODlpManagerService";
    static final int TX_SET = 1;
    static final int TX_GET = 2;

    private static IBinder dlp;

    /**
     * 直接用 Binder 调用坚果光机服务（中间件 jar 的方法被隐藏接口限制挡住，反射拿不到），
     * 失败返回 -1。
     */
    static synchronized int setLight(Context context, int value) {
        return call(TX_SET, value, true);
    }

    /** 读取当前光源亮度（get(512)），失败返回 -1 */
    static synchronized int getLight() {
        return call(TX_GET, 0, false);
    }

    private static int call(int tx, int value, boolean withValue) {
        for (int i = 0; i < 2; i++) {
            try {
                IBinder b = dlpBinder();
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESC_DLP);
                    data.writeInt(CMD_LIGHT);
                    if (withValue) {
                        data.writeInt(value);
                    }
                    b.transact(tx, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            } catch (Throwable t) {
                // 服务重启后旧 Binder 失效，清掉重取一次
                dlp = null;
                if (i == 1) {
                    Log.w(FixService.TAG, "调用坚果光机接口失败（事务 " + tx + "）", t);
                }
            }
        }
        return -1;
    }

    private static IBinder dlpBinder() throws Exception {
        if (dlp != null && dlp.isBinderAlive()) {
            return dlp;
        }
        Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
        IBinder sys = (IBinder) get.invoke(null, SVC);
        if (sys == null) {
            throw new IllegalStateException("找不到服务 " + SVC);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_SYS);
            sys.transact(TX_GET_DLP, data, reply, 0);
            reply.readException();
            dlp = reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (dlp == null) {
            throw new IllegalStateException("光机服务为空");
        }
        return dlp;
    }
}
