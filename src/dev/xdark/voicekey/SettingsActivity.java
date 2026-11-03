package dev.xdark.voicekey;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoiceKey's settings, made for the remote: up and down pick a row, OK or right moves its value
 * on, left moves it back. What the mic button does on a press and on a hold, how speech is
 * recognized, the on-screen translation, sounds. Opening it also puts the service back if the
 * system dropped it.
 */
public class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private volatile boolean downloading;
    private boolean deleteArmed;
    /** What the update row says while it works or after it failed; null when idle. */
    private volatile String updating;

    /** One line of the list: what it is, its value now, how the value moves. */
    private abstract class Row {
        final String title;
        final String hint;
        LinearLayout view;
        TextView titleView;
        TextView hintView;
        TextView valueView;

        Row(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }

        abstract String value();

        /** [step] +1 on OK or right, -1 on left. */
        abstract void change(int step);

        String hintNow() {
            return hint;
        }

        /** Setup rows disappear once what they set up is done. */
        boolean hidden() {
            return false;
        }
    }

    /**
     * A step of the first setup: shows while the thing is not done. OK does it, or opens the system
     * screen for it; where the firmware has no such screen, the row says which adb command does it.
     */
    private abstract class Setup extends Row {
        /** What the row says after OK found no way but adb. */
        String failed;

        Setup(String title, String hint) {
            super(title, hint);
        }

        abstract boolean done();

        abstract String adbHint();

        /** Does it from inside the app; false if that is not possible. */
        abstract boolean fix();

        @Override boolean hidden() {
            return done();
        }

        @Override String value() {
            return failed != null ? failed : "OK — skonfiguruj";
        }

        @Override String hintNow() {
            return failed != null ? adbHint() : hint;
        }

        @Override void change(int step) {
            failed = null;
            if (!fix()) failed = "Brak ekranu — potrzebny adb";
        }
    }

    /** The hidden diagnostics section: closed until opened by its row. */
    private boolean debugOpen;
    private String debugLang = "pl";

    /** A diagnostics step: runs [job] off the main thread and keeps its text as the row's hint. */
    private class DebugAction extends Row {
        private final java.util.concurrent.Callable<String> job;
        private volatile String result;
        private volatile boolean working;

        DebugAction(String title, String hint, java.util.concurrent.Callable<String> job) {
            super(title, hint);
            this.job = job;
        }

        @Override boolean hidden() {
            return !debugOpen;
        }

        @Override String value() {
            return working ? "Wykonuję…" : "OK — wykonaj";
        }

        @Override String hintNow() {
            return result != null ? result : hint;
        }

        @Override void change(int step) {
            if (working) return;
            working = true;
            new Thread(() -> {
                try {
                    result = job.call();
                } catch (Exception e) {
                    result = "Błąd: " + e.getMessage();
                }
                Journal.log(SettingsActivity.this, "debug \"" + title + "\": " + result.replace('\n', ' '));
                working = false;
                handler.post(SettingsActivity.this::refresh);
            }, "debug-row").start();
            refresh();
        }
    }

    private class Toggle extends Row {
        private final java.util.function.Supplier<Boolean> get;
        private final java.util.function.Consumer<Boolean> set;

        Toggle(String title, String hint, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
            super(title, hint);
            this.get = get;
            this.set = set;
        }

        @Override String value() {
            return get.get() ? "Wł." : "Wył.";
        }

        @Override void change(int step) {
            set.accept(!get.get());
        }
    }

    private class Choice<T> extends Row {
        private final T[] options;
        private final java.util.function.Supplier<T> get;
        private final java.util.function.Consumer<T> set;
        private final java.util.function.Function<T, String> name;

        Choice(String title, String hint, T[] options, java.util.function.Supplier<T> get,
               java.util.function.Consumer<T> set, java.util.function.Function<T, String> name) {
            super(title, hint);
            this.options = options;
            this.get = get;
            this.set = set;
            this.name = name;
        }

        @Override String value() {
            return name.apply(get.get());
        }

        @Override void change(int step) {
            int i = 0;
            T now = get.get();
            for (int k = 0; k < options.length; k++) if (options[k].equals(now)) i = k;
            set.accept(options[(i + step + options.length) % options.length]);
        }
    }

    /** Listed as enabled, but not bound by the system. */
    private String crashedServices() {
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(), android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return "brak";
        java.util.List<String> bound = new java.util.ArrayList<>();
        for (android.accessibilityservice.AccessibilityServiceInfo info :
                getSystemService(android.view.accessibility.AccessibilityManager.class)
                        .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            bound.add(info.getId());
        }
        StringBuilder out = new StringBuilder();
        for (String s : enabled.split(":")) {
            if (s.isEmpty()) continue;
            String id = android.content.ComponentName.unflattenFromString(s) == null
                    ? s : android.content.ComponentName.unflattenFromString(s).flattenToShortString();
            if (!bound.contains(s) && !bound.contains(id)) out.append(out.length() > 0 ? ", " : "").append(id);
        }
        return out.length() == 0 ? "brak" : out.toString();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            Journal.log(this, "settings opened: " + BootReceiver.heal(this));
            // The system binds the service a moment after the list changes.
            handler.postDelayed(this::refresh, 2_500);
        }, "heal").start();

        addSetupRows();
        rows.add(new Toggle("Przycisk mikrofonu", "Wył. — przycisk zostaje dla oprogramowania lub innej aplikacji",
                () -> Prefs.micOn(this), on -> Prefs.setMicOn(this, on)));
        rows.add(new Choice<>("Naciśnięcie", "Co robi krótkie naciśnięcie przycisku mikrofonu",
                Prefs.Action.values(), () -> Prefs.tap(this), a -> Prefs.setTap(this, a), a -> a.title));
        rows.add(new Choice<>("Przytrzymanie", "Co robi przycisk, gdy go przytrzymasz",
                Prefs.Action.values(), () -> Prefs.hold(this), a -> Prefs.setHold(this, a), a -> a.title));
        Long[] holds = new Long[Prefs.HOLD_CHOICES.length];
        for (int i = 0; i < holds.length; i++) holds[i] = Prefs.HOLD_CHOICES[i];
        rows.add(new Choice<>("Czas przytrzymania", "Jak długo trzymać przycisk, by liczyło się jako przytrzymanie",
                holds, () -> Prefs.holdMillis(this), ms -> Prefs.setHoldMillis(this, ms),
                ms -> String.valueOf(ms / 1000.0).replace('.', ',').replaceAll(",0$", "") + " s"));
        rows.add(new Choice<Prefs.Engine>("Rozpoznawanie mowy", null, Prefs.Engine.values(),
                () -> Prefs.engine(this), e -> Prefs.setEngine(this, e), e -> e.title) {
            @Override String hintNow() {
                return Prefs.engine(SettingsActivity.this).hint;
            }
        });
        rows.add(new Row("Model offline", null) {
            @Override String value() {
                if (downloading) return "Pobieram…";
                if (ModelStore.ready(SettingsActivity.this)) return deleteArmed ? "OK — usuń" : "Pobrany";
                return "OK — pobierz";
            }

            @Override String hintNow() {
                return ModelStore.ready(SettingsActivity.this)
                        ? "Polski model Vosk. Aby usunąć: OK dwa razy"
                        : "Polski model Vosk ze strony jego autorów, 53 MB";
            }

            @Override void change(int step) {
                if (downloading) return;
                if (ModelStore.ready(SettingsActivity.this)) {
                    if (!deleteArmed) {
                        deleteArmed = true;
                        handler.postDelayed(() -> {
                            deleteArmed = false;
                            refresh();
                        }, 4_000);
                    } else {
                        deleteArmed = false;
                        ModelStore.delete(SettingsActivity.this);
                    }
                    return;
                }
                download(this);
            }
        });
        rows.add(new Toggle("Tłumaczenie interfejsu", "Polskie napisy na ustawieniach i aplikacjach Hisense",
                () -> Prefs.translate(this), on -> Prefs.setTranslate(this, on)));
        rows.add(new Row("Język systemu", null) {
            private volatile boolean working;
            private volatile String status;
            /** The language set by this row; it shows in the running configuration only after a restart. */
            private volatile String chosen;

            private String running() {
                return getResources().getConfiguration().getLocales().get(0).getLanguage();
            }

            private String target() {
                return "pl".equals(chosen != null ? chosen : running()) ? "en" : "pl";
            }

            private String lastLine(String text) {
                String[] lines = text.trim().split("\\n");
                return lines[lines.length - 1].trim();
            }

            private String name(String tag) {
                return "pl".equals(tag) ? "Polski" : "en".equals(tag) ? "English" : tag;
            }

            @Override String value() {
                if (working) return "Potwierdź prośbę na ekranie…";
                if (status != null) return status;
                if (chosen != null && !chosen.equals(running())) return "Teraz: " + name(running()) + " → " + name(chosen);
                return "Teraz: " + name(running());
            }

            @Override String hintNow() {
                if (status != null && status.startsWith("Nie udało się")) {
                    return "Z komputera: adb shell setprop persist.sys.locale pl (lub en) — i restart";
                }
                String pending = chosen != null && !chosen.equals(running())
                        ? "Wybrano: " + name(chosen) + ", zadziała po restarcie projektora. " : "";
                return pending + "OK lub strzałki — przełącz na " + name(target())
                        + ". Wymaga włączonego debugowania (jak w „Konfiguracja: nadaj uprawnienia”), potem restart";
            }

            @Override void change(int step) {
                if (working) return;
                String target = target();
                working = true;
                status = null;
                new Thread(() -> {
                    try {
                        // Each method is judged by reading the value back, not by its exit status.
                        String got = SelfAdb.shell(SettingsActivity.this,
                                "setprop persist.sys.locale " + target + " 2>&1; getprop persist.sys.locale");
                        String how = "setprop";
                        if (!lastLine(got).equals(target)) {
                            Journal.log(SettingsActivity.this, "setprop did not stick: " + got);
                            // The way locale-changer apps do it: the activity manager updates and saves the configuration.
                            got = SelfAdb.shell(SettingsActivity.this, "CLASSPATH=" + getPackageCodePath()
                                    + " app_process /system/bin dev.xdark.voicekey.LocaleTool " + target
                                    + " 2>&1; getprop persist.sys.locale");
                            how = "przez ustawienia systemu";
                        }
                        if (lastLine(got).equals(target)) {
                            chosen = target;
                            Prefs.setLocale(SettingsActivity.this, target);
                            status = "Gotowe (" + how + "): " + name(target) + ", sprawdzone";
                        } else {
                            Journal.log(SettingsActivity.this, "locale fallback failed: " + got);
                            status = "Nie udało się: system zostawił „" + lastLine(got) + "”";
                        }
                    } catch (SelfAdb.Failure | RuntimeException e) {
                        Journal.log(SettingsActivity.this, "set locale failed: " + e);
                        status = "Nie udało się: " + e.getMessage();
                    }
                    working = false;
                    handler.post(SettingsActivity.this::refresh);
                }, "set-locale").start();
                refresh();
            }
        });
        rows.add(new Toggle("Sygnały dźwiękowe", "Krótki dźwięk, gdy można mówić, i po",
                () -> Prefs.sounds(this), on -> Prefs.setSounds(this, on)));
        rows.add(new Row("Aktualizacja", null) {
            @Override String value() {
                if (updating != null) return updating;
                String[] found = Updater.pending(SettingsActivity.this);
                return found != null ? "Jest " + found[0] + " — OK, zainstaluj"
                        : "Wersja " + Updater.currentVersion(SettingsActivity.this) + " — OK, sprawdź";
            }

            @Override String hintNow() {
                return "Nowe wersje pochodzą ze strony projektu na GitHub; system poprosi o potwierdzenie";
            }

            @Override void change(int step) {
                if (updating != null && !updating.startsWith("Nie ") && !updating.startsWith("Brak")) return;
                update(Updater.pending(SettingsActivity.this) != null);
            }
        });
        rows.add(new Toggle("Ochrona pilota", "Wyłącza usługę Lumbina, przez którą pilot powtarza naciśnięcia, oraz fabryczny launcher Hisense, jeśli jest inny",
                () -> Prefs.remoteGuard(this), on -> {
                    Prefs.setRemoteGuard(this, on);
                    if (on) RemoteGuard.checkAsync(this, "settings"); else RemoteGuard.release(this);
                }));
        rows.add(new Toggle("Sprawdzaj aktualizacje", "Co pół dnia, gdy projektor jest włączony; wynik pokaże napis u góry",
                () -> Prefs.autoUpdate(this), on -> Prefs.setAutoUpdate(this, on)));

        rows.add(new Row("Debugowanie", "Do ustalenia, czemu nie zmienia się język: próby i stan systemu") {
            @Override String value() {
                return debugOpen ? "Otwarte" : "Ukryte";
            }

            @Override void change(int step) {
                debugOpen = !debugOpen;
                refresh();
            }
        });
        rows.add(new Row("Język prób", "Jaki język ustawiają dwie próby poniżej") {
            @Override boolean hidden() {
                return !debugOpen;
            }

            @Override String value() {
                return debugLang;
            }

            @Override void change(int step) {
                debugLang = "pl".equals(debugLang) ? "en" : "pl";
                refresh();
            }
        });
        rows.add(new DebugAction("Stan języka",
                "Co mówią system i oprogramowanie. Wyślij ten tekst deweloperowi", () -> {
            String props = SelfAdb.shell(this, "echo persist.sys.locale=$(getprop persist.sys.locale);"
                    + "echo persist.sys.language=$(getprop persist.sys.language);"
                    + "echo ro.product.locale=$(getprop ro.product.locale);"
                    + "echo system_locales=$(settings get system system_locales)");
            return "Wybrano w VoiceKey: " + Prefs.locale(this) + "\nKonfiguracja teraz: "
                    + getResources().getConfiguration().getLocales().toLanguageTags() + "\n" + props;
        }));
        rows.add(new DebugAction("Próba 1: setprop",
                "Ustawia persist.sys.locale i po 3 sekundach odczytuje", () -> {
            String out = SelfAdb.shell(this, "setprop persist.sys.locale " + debugLang
                    + " 2>&1; sleep 3; getprop persist.sys.locale");
            return "setprop " + debugLang + " → w systemie: " + out;
        }));
        rows.add(new DebugAction("Próba 2: przez system",
                "Prosi system o zmianę języka (jak aplikacje-przełączniki), odczytuje po 3 sekundach", () -> {
            String out = SelfAdb.shell(this, "CLASSPATH=" + getPackageCodePath()
                    + " app_process /system/bin dev.xdark.voicekey.LocaleTool " + debugLang
                    + " 2>&1; sleep 3; getprop persist.sys.locale");
            return "LocaleTool " + debugLang + " → " + out.replace('\n', ' ');
        }));
        rows.add(new DebugAction("Raport o zawieszeniu",
                "Gdy projektor sam się zrestartował: co system zapisał o przyczynie. Pełny tekst zapisuje się do pliku", () -> {
            StringBuilder full = new StringBuilder();
            StringBuilder summary = new StringBuilder();
            try {
                summary.append("VoiceKey ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append('\n');
            } catch (Exception ignored) {
            }
            String latestWatchdog = "";
            for (String tag : new String[] {"system_server_watchdog", "SYSTEM_RESTART", "SYSTEM_LAST_KMSG", "system_server_anr"}) {
                String text = SelfAdb.shell(this, "dumpsys dropbox --print " + tag + " 2>&1");
                full.append("==== ").append(tag).append('\n').append(text).append('\n');
                // Only the newest entry: the dropbox keeps older crashes too.
                String[] lines = text.split("\n");
                int start = 0, count = 0;
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].matches("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d .*")) {
                        start = i;
                        count++;
                    }
                }
                StringBuilder last = new StringBuilder();
                for (int i = start; i < lines.length; i++) last.append(lines[i]).append('\n');
                if (tag.equals("system_server_watchdog")) latestWatchdog = last.toString();
                summary.append(tag).append(": wpisów ").append(count).append(", ostatni ")
                        .append(lines.length > start ? lines[start] : "—").append('\n');
                int shown = 0;
                for (String line : last.toString().split("\n")) {
                    if (shown < 4 && line.matches("(?i).*(voicekey|waiting to lock|held by|Blocked in|Watchdog:).*")) {
                        summary.append("  ").append(line.length() > 100 ? line.substring(0, 100) + "…" : line).append('\n');
                        shown++;
                    }
                }
            }
            // Who holds the lock: the stack of system_server's main thread in the newest watchdog dump.
            String[] all = latestWatchdog.split("\n");
            for (int i = 0; i < all.length; i++) {
                if (all[i].startsWith("\"main\" ") && all[i].contains(" tid=1 ")) {
                    summary.append("Wątek główny:\n");
                    for (int j = i + 1, shown = 0; j < all.length && shown < 14; j++) {
                        String line = all[j].trim();
                        if (line.startsWith("at ") || line.startsWith("- ")) {
                            summary.append(' ').append(line.length() > 100 ? line.substring(0, 100) + "…" : line).append('\n');
                            shown++;
                        }
                        if (line.isEmpty()) break;
                    }
                    break;
                }
            }
            summary.append("Usługi, które padły: ").append(crashedServices()).append('\n');
            java.io.StringWriter journal = new java.io.StringWriter();
            Journal.tail(this, new java.io.PrintWriter(journal), 200);
            String filterLine = "";
            for (String line : journal.toString().split("\n")) if (line.contains("key filter")) filterLine = line;
            summary.append("Filtr klawiszy, ostatni wpis: ").append(filterLine.isEmpty() ? "brak" : filterLine).append('\n');
            java.io.File dir = getExternalFilesDir(null);
            if (dir != null) {
                java.io.File file = new java.io.File(dir, "watchdog.txt");
                java.nio.file.Files.write(file.toPath(), full.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                summary.append("Pełny tekst: ").append(file.getPath());
            }
            return summary.toString().trim();
        }));
        rows.add(new DebugAction("Dziennik", "Ostatnie wpisy VoiceKey, także o języku", () -> {
            java.io.StringWriter text = new java.io.StringWriter();
            Journal.tail(this, new java.io.PrintWriter(text), 12);
            StringBuilder out = new StringBuilder();
            for (String line : text.toString().split("\n")) {
                out.append(line.length() > 110 ? line.substring(0, 110) + "…" : line).append('\n');
            }
            return out.toString().trim();
        }));

        setContentView(build());
        refresh();
        for (Row row : rows) {
            if (!row.hidden()) {
                row.view.requestFocus();
                break;
            }
        }
    }

    /** Back from a system screen: the step may be done now. */
    @Override
    protected void onResume() {
        super.onResume();
        if (!rows.isEmpty() && rows.get(0).view != null) refresh();
    }

    private boolean canWriteSecure() {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static final String ADB_GRANT =
            "adb shell pm grant dev.xdark.voicekey android.permission.WRITE_SECURE_SETTINGS";

    /** Opens a system screen; false where the firmware does not have it. */
    private boolean open(android.content.Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            Journal.log(this, "no system screen for " + intent.getAction() + ": " + e);
            return false;
        }
    }

    private static final String VOICE_SERVICE_URL =
            "https://github.com/xDarkOne/voicekey/releases/download/voice-service/Vidda-Voice-Service.apk";
    private static final String VOICE_SERVICE_SHA256 = "8baddc4007bdd7b40a04a86fba7f51ec828c51a90255d4b57d19751fb49ee668";

    private void addSetupRows() {
        rows.add(new Setup("Konfiguracja: nadaj uprawnienia",
                "Włącz w opcjach programisty „USB debugging” i „Wi-Fi debugging”. VoiceKey sam nada sobie wszystko, co trzeba; na ekranie potwierdź prośbę i zaznacz „Zawsze zezwalaj”") {
            private boolean working;
            private String status;

            @Override boolean done() {
                return canWriteSecure();
            }

            @Override String adbHint() {
                return "Z komputera jeden raz: " + ADB_GRANT + " — lub włącz debugowanie i powtórz";
            }

            @Override boolean hidden() {
                return done() && !working;
            }

            @Override String value() {
                if (working) return "Potwierdź prośbę na ekranie…";
                return status != null ? status : "OK — nadaj";
            }

            @Override String hintNow() {
                return status != null && !working ? adbHint() : hint;
            }

            @Override boolean fix() {
                if (working) return true;
                working = true;
                status = null;
                String pkg = getPackageName();
                String listener = new android.content.ComponentName(SettingsActivity.this, WakeListener.class).flattenToString();
                new Thread(() -> {
                    try {
                        SelfAdb.shell(SettingsActivity.this, "appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow;"
                                + "cmd notification allow_listener " + listener + ";"
                                + "pm grant " + pkg + " android.permission.RECORD_AUDIO;"
                                + "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
                        if (canWriteSecure()) Journal.log(SettingsActivity.this, "self-adb: " + BootReceiver.heal(SettingsActivity.this));
                    } catch (SelfAdb.Failure | RuntimeException e) {
                        Journal.log(SettingsActivity.this, "self-adb failed: " + e);
                        status = "Nie udało się: " + e.getMessage();
                    }
                    working = false;
                    handler.post(SettingsActivity.this::refresh);
                }, "self-adb").start();
                return true;
            }
        });
        rows.add(new Setup("Konfiguracja: mikrofon", "Uprawnienie do nagrywania dźwięku — potrzebne do rozpoznawania mowy") {
            @Override boolean done() {
                return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }

            @Override String adbHint() {
                return "adb shell pm grant dev.xdark.voicekey android.permission.RECORD_AUDIO";
            }

            @Override boolean fix() {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
                return true;
            }
        });
        rows.add(new Setup("Konfiguracja: usługa przycisku mikrofonu",
                "Włącz „VoiceKey: przycisk mikrofonu” w ułatwieniach dostępu") {
            @Override boolean done() {
                return BootReceiver.isRunning(SettingsActivity.this);
            }

            @Override String adbHint() {
                return ADB_GRANT + " — i otwórz VoiceKey jeszcze raz";
            }

            @Override boolean fix() {
                if (canWriteSecure()) {
                    new Thread(() -> {
                        Journal.log(SettingsActivity.this, "setup: " + BootReceiver.heal(SettingsActivity.this));
                        handler.post(SettingsActivity.this::refresh);
                    }, "heal").start();
                    return true;
                }
                return open(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        rows.add(new Setup("Konfiguracja: powrót po uśpieniu",
                "Dostęp do powiadomień: dzięki niemu system znów uruchamia VoiceKey po uśpieniu. Powiadomień nie czyta") {
            @Override boolean done() {
                return getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(
                        new android.content.ComponentName(SettingsActivity.this, WakeListener.class));
            }

            @Override String adbHint() {
                return "adb shell cmd notification allow_listener dev.xdark.voicekey/dev.xdark.voicekey.WakeListener";
            }

            @Override boolean fix() {
                String me = new android.content.ComponentName(SettingsActivity.this, WakeListener.class).flattenToString();
                if (canWriteSecure()) {
                    String now = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
                    android.provider.Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners",
                            now == null || now.isEmpty() ? me : now + ":" + me);
                    return true;
                }
                return open(new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });
        rows.add(new Setup("Konfiguracja: usługa głosowa Google",
                "Potrzebna do rozpoznawania online: pobierz Vidda-Voice-Service (28 MB) z wydania VoiceKey i zainstaluj. Na ekranie systemu naciśnij „Zainstaluj”") {
            private volatile String status;
            private volatile boolean working;

            private boolean installed() {
                try {
                    getPackageManager().getPackageInfo(KeyService.SEARCH_PACKAGE, 0);
                    return true;
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    return false;
                }
            }

            @Override boolean done() {
                return Prefs.engine(SettingsActivity.this) == Prefs.Engine.OFFLINE
                        || VoiceActivity.voiceServiceReady(SettingsActivity.this);
            }

            @Override boolean hidden() {
                return done() && !working;
            }

            @Override String adbHint() {
                return "Zainstaluj Vidda-Voice-Service.apk z wydania voice-service na GitHub, potem: adb shell pm grant "
                        + KeyService.SEARCH_PACKAGE + " android.permission.RECORD_AUDIO";
            }

            @Override String value() {
                if (status != null) return status;
                return installed() ? "OK — nadaj mikrofon" : "OK — pobierz i zainstaluj";
            }

            @Override String hintNow() {
                return failed != null ? adbHint() : hint;
            }

            @Override boolean fix() {
                if (working) return true;
                working = true;
                status = installed() ? "Nadaję mikrofon…" : "Pobieram…";
                new Thread(() -> {
                    try {
                        if (!installed()) {
                            Updater.installOther(SettingsActivity.this, VOICE_SERVICE_URL, VOICE_SERVICE_SHA256,
                                    KeyService.SEARCH_PACKAGE, text -> {
                                        status = text.equals("Gotowe") ? "Zainstalowano" : text;
                                        handler.post(SettingsActivity.this::refresh);
                                    });
                            // The system's screen decides; wait for it, then give the service the microphone.
                            for (int i = 0; i < 120 && !installed(); i++) Thread.sleep(1000);
                        }
                        if (installed()) {
                            SelfAdb.shell(SettingsActivity.this, "pm grant " + KeyService.SEARCH_PACKAGE
                                    + " android.permission.RECORD_AUDIO");
                            Journal.log(SettingsActivity.this, "voice service: "
                                    + (VoiceActivity.voiceServiceReady(SettingsActivity.this) ? "ready" : "installed, no microphone"));
                            status = VoiceActivity.voiceServiceReady(SettingsActivity.this) ? null
                                    : "Usługa nie ma mikrofonu — potrzebny adb";
                        } else {
                            status = "Nie zainstalowano — OK, jeszcze raz";
                        }
                    } catch (Exception e) {
                        Journal.log(SettingsActivity.this, "voice service failed: " + e);
                        status = "Nie udało się (" + e.getClass().getSimpleName() + ") — OK, jeszcze raz";
                    }
                    working = false;
                    handler.post(SettingsActivity.this::refresh);
                }, "voice-service").start();
                return true;
            }
        });
        rows.add(new Setup("Konfiguracja: aktualizacje z aplikacji",
                "Zezwól VoiceKey na instalowanie aktualizacji („nieznane źródła”)") {
            @Override boolean done() {
                return getPackageManager().canRequestPackageInstalls();
            }

            @Override String adbHint() {
                return "adb shell appops set dev.xdark.voicekey REQUEST_INSTALL_PACKAGES allow";
            }

            @Override boolean fix() {
                return open(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        refresh();
    }

    private View build() {
        float dp = Look.dp(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (96 * dp);
        list.setPadding(side, (int) (56 * dp), side, (int) (48 * dp));

        TextView title = Look.text(this, 38, Look.TEXT);
        title.setText("VoiceKey");
        TextView sub = Look.text(this, 18, Look.MUTED);
        sub.setText("Głos z przycisku pilota i polski interfejs");
        sub.setPadding(0, (int) (12 * dp), 0, (int) (28 * dp));
        list.addView(title);
        list.addView(sub);

        for (Row row : rows) {
            LinearLayout v = new LinearLayout(this);
            v.setGravity(Gravity.CENTER_VERTICAL);
            int px = (int) (24 * dp), py = (int) (18 * dp);
            v.setPadding(px, py, px, py);
            v.setFocusable(true);
            v.setClickable(true);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            row.titleView = Look.text(this, 22, Look.TEXT);
            row.titleView.setText(row.title);
            row.hintView = Look.text(this, 15, Look.MUTED);
            row.hintView.setPadding(0, (int) (6 * dp), 0, 0);
            texts.addView(row.titleView);
            texts.addView(row.hintView);
            row.valueView = Look.text(this, 20, Look.MUTED);
            row.valueView.setGravity(Gravity.END);
            v.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            v.addView(row.valueView);
            v.setOnFocusChangeListener((view, focused) -> paint(row, focused));
            v.setOnClickListener(view -> {
                row.change(+1);
                refresh();
            });
            v.setOnKeyListener((view, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    row.change(code == KeyEvent.KEYCODE_DPAD_RIGHT ? +1 : -1);
                    refresh();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * dp);
            list.addView(v, lp);
            row.view = v;
            paint(row, false);
        }

        TextView foot = Look.text(this, 15, Look.MUTED);
        foot.setText("Głosem: „otwórz Lampę”, „znajdź na YouTube …”, „znajdź w Kinopoisku …”, „wyłącz tłumaczenie”, „oddaj mikrofon”");
        foot.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), 0);
        list.addView(foot);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Look.SCREEN);
        scroll.setFillViewport(true);
        scroll.addView(list);
        return scroll;
    }

    private void paint(Row row, boolean focused) {
        row.view.setBackground(focused ? Look.pill(this, Look.FOCUS) : null);
        row.titleView.setTextColor(focused ? Look.ON_FOCUS : Look.TEXT);
        row.hintView.setTextColor(focused ? 0xFF5F6368 : Look.MUTED);
        row.valueView.setTextColor(focused ? Look.ON_FOCUS : Look.ACCENT);
    }

    private void refresh() {
        for (Row row : rows) {
            row.view.setVisibility(row.hidden() ? View.GONE : View.VISIBLE);
            row.valueView.setText(row.value());
            String h = row.hintNow();
            row.hintView.setText(h == null ? "" : h);
            row.hintView.setVisibility(h == null ? View.GONE : View.VISIBLE);
        }
    }

    private void download(Row row) {
        downloading = true;
        refresh();
        new Thread(() -> {
            try {
                ModelStore.download(this, (done, total) -> handler.post(() -> row.valueView.setText(done < 0
                        ? "Rozpakowuję…" : String.format(Locale.ROOT, "Pobieram %d%%", done * 100 / total))));
                Journal.log(this, "offline model downloaded");
            } catch (Exception e) {
                Journal.log(this, "offline model download failed: " + e);
                handler.post(() -> row.valueView.setText("Nie pobrano — OK, jeszcze raz"));
                downloading = false;
                return;
            }
            downloading = false;
            handler.post(this::refresh);
        }, "model-download").start();
    }

    /** Checks for a newer release, or, if one is known, downloads it and hands it to the installer. */
    private void update(boolean install) {
        updating = install ? "Pobieram…" : "Sprawdzam…";
        refresh();
        new Thread(() -> {
            try {
                if (!install) {
                    updating = Updater.check(this) ? null : "Brak nowej wersji — OK, sprawdź";
                } else {
                    Updater.install(this, text -> {
                        updating = text.equals("Gotowe") ? null : text;
                        handler.post(this::refresh);
                    });
                }
            } catch (Exception e) {
                Journal.log(this, "update failed: " + e);
                updating = "Nie udało się (" + e.getClass().getSimpleName() + ") — OK, jeszcze raz";
            }
            handler.post(this::refresh);
        }, "update").start();
    }
}
