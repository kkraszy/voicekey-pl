package dev.xdark.voicekey;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the remote usable. Lumbina's accessibility service intercepts Home, crashes and then makes
 * one key press act many times; restarting it does not help, only turning it off does. Without it
 * Home falls to the stock Hisense launcher, so that one is put aside while another launcher is
 * installed, and given back when none is left.
 */
final class RemoteGuard {
    static final String LUMBINA = "dev.lumbina.tv";
    static final String STOCK_LAUNCHER = "com.oversea.aslauncher";
    private static final String MEETING_ROOM = "com.meetingroom";

    private static boolean running;

    private RemoteGuard() {}

    /** In the background; one check at a time. */
    static void checkAsync(Context context, String reason) {
        Context app = context.getApplicationContext();
        synchronized (RemoteGuard.class) {
            if (running) return;
            running = true;
        }
        new Thread(() -> {
            try {
                check(app, reason);
            } catch (RuntimeException e) {
                Journal.log(app, "remote guard failed: " + e);
            } finally {
                synchronized (RemoteGuard.class) {
                    running = false;
                }
            }
        }, "remote-guard").start();
    }

    private static void check(Context context, String reason) {
        if (!Prefs.remoteGuard(context)) return;
        if (dropLumbinaService(context)) {
            Journal.log(context, "remote guard (" + reason + "): Lumbina service turned off");
            KeyService.flash("Wyłączyłem usługę Lumbina: przez nią pilot powtarzał naciśnięcia", 6_000);
        }
        launcher(context, reason);
    }

    /** Takes only Lumbina's entries out of the list; every other service stays. */
    private static boolean dropLumbinaService(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || !enabled.contains(LUMBINA + "/")) return false;
            List<String> keep = new ArrayList<>();
            for (String s : enabled.split(":")) {
                if (s.isEmpty() || s.startsWith(LUMBINA + "/") || keep.contains(s)) continue;
                keep.add(s);
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", keep));
            return true;
        } catch (SecurityException e) {
            Journal.log(context, "remote guard: no WRITE_SECURE_SETTINGS");
            return false;
        }
    }

    /** Third-party launchers: not system, not the stock one, not the hub shown at boot. */
    private static List<String> thirdPartyLaunchers(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<String> found = new ArrayList<>();
        for (ResolveInfo r : context.getPackageManager().queryIntentActivities(home, 0)) {
            ApplicationInfo app = r.activityInfo.applicationInfo;
            String pkg = r.activityInfo.packageName;
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (pkg.equals(STOCK_LAUNCHER) || pkg.equals(MEETING_ROOM) || pkg.equals(context.getPackageName())) continue;
            if (!found.contains(pkg)) found.add(pkg);
        }
        return found;
    }

    private static boolean stockEnabled(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(STOCK_LAUNCHER, PackageManager.MATCH_DISABLED_COMPONENTS).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean stockInstalled(Context context) {
        try {
            context.getPackageManager().getApplicationInfo(STOCK_LAUNCHER, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void launcher(Context context, String reason) {
        if (!stockInstalled(context)) return;
        List<String> others = thirdPartyLaunchers(context);
        boolean enabled = stockEnabled(context);
        try {
            if (!others.isEmpty() && enabled) {
                String pick = others.contains(LUMBINA) ? LUMBINA : others.get(0);
                String home = homeActivity(context, pick);
                if (home != null) {
                    SelfAdb.shell(context, "cmd package set-home-activity " + home);
                }
                SelfAdb.shell(context, "pm disable-user --user 0 " + STOCK_LAUNCHER);
                Prefs.setLauncherDisabledByUs(context, true);
                Journal.log(context, "remote guard (" + reason + "): stock launcher disabled, home -> " + pick);
            } else if (others.isEmpty() && !enabled && Prefs.launcherDisabledByUs(context)) {
                SelfAdb.shell(context, "pm enable " + STOCK_LAUNCHER);
                Prefs.setLauncherDisabledByUs(context, false);
                Journal.log(context, "remote guard (" + reason + "): no other launcher left, stock one enabled");
            }
        } catch (SelfAdb.Failure e) {
            Journal.log(context, "remote guard: launcher step needs adb: " + e.getMessage());
        }
    }

    private static String homeActivity(Context context, String pkg) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        ResolveInfo r = context.getPackageManager().resolveActivity(home, 0);
        return r == null ? null : r.activityInfo.packageName + "/" + r.activityInfo.name;
    }

    /** Turned off in the settings: the stock launcher goes back if we were the ones to put it aside. */
    static void release(Context context) {
        if (!Prefs.launcherDisabledByUs(context)) return;
        new Thread(() -> {
            try {
                SelfAdb.shell(context, "pm enable " + STOCK_LAUNCHER);
                Prefs.setLauncherDisabledByUs(context, false);
                Journal.log(context, "remote guard off: stock launcher enabled");
            } catch (SelfAdb.Failure e) {
                Journal.log(context, "remote guard off: needs adb: " + e.getMessage());
            }
        }, "remote-guard-release").start();
    }
}
