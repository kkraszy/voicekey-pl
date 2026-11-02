package dev.xdark.voicekey;

import android.service.notification.NotificationListenerService;

/**
 * A hook to come back after sleep. Going to sleep, the Hisense firmware force-stops nearly every
 * app; Android then drops the stopped app's accessibility service, and the firmware delivers no
 * broadcast to a third-party app at all, so no receiver can bring it back. A notification
 * listener is bound by the system again once its process is gone, so when it connects, it puts
 * the accessibility service back. It reads no notifications. Enabled over adb:
 * `adb shell cmd notification allow_listener dev.xdark.voicekey/dev.xdark.voicekey.WakeListener`.
 */
public class WakeListener extends NotificationListenerService {
    /** The journal's tail, also when the key service is down: `adb shell dumpsys activity service dev.xdark.voicekey/.WakeListener`. */
    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        Journal.tail(this, out, 30);
    }

    /** Right after a force-stop the system may not bind it yet: checked again a few times. */
    private static final long[] RECHECK_MILLIS = {3_000, 10_000, 30_000};

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Rate limit for [watcher]: never a tug of war with whoever edits the list. */
    private static final long WATCH_MIN_GAP_MILLIS = 10_000;
    private long lastWatchHeal;

    /**
     * Other apps edit the list of enabled accessibility services too (VIDDA Nova did): if ours
     * drops out of it while this listener keeps the process alive, it is put back.
     */
    private final android.database.ContentObserver watcher = new android.database.ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            handler.postDelayed(() -> {
                String list = android.provider.Settings.Secure.getString(getContentResolver(),
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (list != null && list.contains(RemoteGuard.LUMBINA + "/")) RemoteGuard.checkAsync(WakeListener.this, "service listed");
                if (list != null && list.contains(getPackageName() + "/")) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastWatchHeal < WATCH_MIN_GAP_MILLIS) return;
                lastWatchHeal = now;
                Journal.log(WakeListener.this, "dropped from the list: " + BootReceiver.heal(WakeListener.this));
            }, 2_000);
        }
    };

    /** Continue watching for TizenTube: media sessions are readable only from a notification listener. */
    private WatchNext watchNext;

    @Override
    public void onListenerDisconnected() {
        getContentResolver().unregisterContentObserver(watcher);
        if (watchNext != null) watchNext.stop();
        watchNext = null;
        if (packages != null) {
            try {
                unregisterReceiver(packages);
            } catch (RuntimeException ignored) {
            }
            packages = null;
        }
    }

    @Override
    public void onListenerConnected() {
        getContentResolver().unregisterContentObserver(watcher);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, watcher);
        Journal.log(this, "listener connected: " + BootReceiver.heal(this));
        try {
            if (watchNext != null) watchNext.stop();
            watchNext = new WatchNext(this, new android.content.ComponentName(this, WakeListener.class));
            watchNext.start();
        } catch (RuntimeException e) {
            Journal.log(this, "watch next failed to start: " + e);
        }
        handler.postDelayed(() -> Updater.checkInBackground(this), 60_000);
        // Lumbina turns its own service on at boot: give it time, then take it off again.
        handler.postDelayed(() -> RemoteGuard.checkAsync(this, "boot"), 20_000);
        registerPackageWatch();
        handler.postDelayed(this::restoreLocale, 45_000);
        for (long delay : RECHECK_MILLIS) {
            handler.postDelayed(() -> {
                if (!BootReceiver.isRunning(this)) Journal.log(this, "recheck: " + BootReceiver.heal(this));
            }, delay);
        }
    }

    private android.content.BroadcastReceiver packages;

    /** Installing or removing a launcher decides whether the stock one may stay disabled. */
    private void registerPackageWatch() {
        if (packages != null) return;
        packages = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                RemoteGuard.checkAsync(context, "package change");
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packages, filter);
    }

    private static long lastLocaleTry;

    /**
     * The firmware puts its own language back at boot. If one was chosen in the settings and the
     * system runs another, set it again the way the settings row does (needs the debugging the
     * row already used, so no new question). Tried once in a few minutes at most.
     */
    private void restoreLocale() {
        String wanted = Prefs.locale(this);
        if (wanted == null) return;
        String running = getResources().getConfiguration().getLocales().get(0).getLanguage();
        long now = android.os.SystemClock.elapsedRealtime();
        if (running.equals(wanted) || now - lastLocaleTry < 300_000 && lastLocaleTry != 0) return;
        lastLocaleTry = now;
        new Thread(() -> {
            try {
                String before = SelfAdb.shell(this, "getprop persist.sys.locale");
                String got = SelfAdb.shell(this, "CLASSPATH=" + getPackageCodePath()
                        + " app_process /system/bin dev.xdark.voicekey.LocaleTool " + wanted
                        + " 2>&1; getprop persist.sys.locale");
                Journal.log(this, "locale restore: wanted " + wanted + ", system ran " + running
                        + ", prop was '" + before + "', now: " + got.replace('\n', ' '));
            } catch (SelfAdb.Failure | RuntimeException e) {
                Journal.log(this, "locale restore failed: " + e);
            }
        }, "restore-locale").start();
    }
}
