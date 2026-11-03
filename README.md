<p align="center"><img src="docs/icon-256.png" width="128" alt="VoiceKey"></p>

<h1 align="center">VoiceKey</h1>

<p align="center"><b>Głos z przycisku mikrofonu na projektorze Hisense / Vidda.</b><br>Powiedziałeś — otworzyło się. Bez chmury aplikacji i bez Google TV.</p>

VoiceKey to aplikacja dla oprogramowania Android TV od Hisense (sprawdzona na Vidda C2 Ultra, VIDAA na Androidzie 11). Przejmuje przycisk mikrofonu na pilocie i robi z nim to, czego nie potrafi fabryczny asystent.

- **Polecenia głosowe.** Naciśnij przycisk i powiedz: „otwórz Lampę”, „znajdź na YouTube zwiastun Diuny”, „znajdź w Kinopoisku Interstellar” albo po prostu tytuł. Działa z TizenTube, Lampa, Kinopoiskiem, VK Video, Rutube.
- **Wyszukiwanie w otwartej aplikacji.** Przytrzymanie przycisku — wyszukiwanie tam, gdzie właśnie jesteś.
- **Dwa silniki rozpoznawania.** Online (Google) lub offline (Vosk, polski model ~53 MB, pobierany raz).
- **„Kontynuuj oglądanie”.** Niedokończone filmy z TizenTube i Lampy trafiają do rzędu launchera.
- **Tłumacz ekranowy.** Chińskie i angielskie napisy w ustawieniach systemowych są pokazywane po polsku na wierzchu ekranu (słowniki — w [docs/translator.md](docs/translator.md)).
- **Wraca po uśpieniu.** Oprogramowanie przy usypianiu zabija prawie wszystkie aplikacje razem z usługą ułatwień dostępu; VoiceKey sam włącza ją z powrotem.
- **Aktualizuje się sam.** Co pół dnia sprawdza nowe wersje na GitHubie; gdy wyjdzie nowa, powie o tym napisem u góry, a zainstalujesz ją w ustawieniach, „Aktualizacja” (system poprosi o potwierdzenie).
- **Ustawienia na ekranie.** Co robi naciśnięcie i przytrzymanie, czas przytrzymania, silnik rozpoznawania, dźwięki, tłumacz.

<p align="center">
  <img src="docs/screenshots/settings.png" width="48%" alt="Ustawienia VoiceKey">
  <img src="docs/screenshots/settings-update.png" width="48%" alt="Aktualizacja w ustawieniach">
</p>

<p align="center">
  <img src="docs/screenshots/voice.png" width="96%" alt="Polecenie głosowe: plakietka „Powiedz polecenie” i wynik">
</p>

<p align="center">
  <img src="docs/screenshots/voice-youtube.png" width="96%" alt="Wyszukiwanie zwiastuna na YouTube: plakietka „Szukam w YouTube”">
</p>

<p align="center">
  <img src="docs/screenshots/google-panel.png" width="60%" alt="Panel wyszukiwania Google: film „Diuna”, dostępny w Lampie">
</p>

<p align="center">
  <img src="docs/screenshots/translation.png" width="40%" alt="Przetłumaczone napisy na ustawieniach systemowych">
</p>

## Instalacja

1. Pobierz `VoiceKey.apk` ze strony [Releases](../../releases/latest) (albo zbuduj sam, patrz niżej) i zainstaluj na projektorze dowolnym sposobem: z pendrive’a przez menedżer plików, przez sieć, przez `adb install`.
2. Włącz na projektorze w opcjach programisty **„USB debugging”** i **„Wi-Fi debugging”** (sekcja „Debugowanie”). Jeśli oprogramowanie ukrywa ustawienia, otwórz je przez menedżer plików: aplikacja systemowa Settings → „otwórz w systemie”.
3. Otwórz VoiceKey z launchera. Na górze listy pojawią się kroki **„Konfiguracja: …”** — widać je, dopóki nie są zrobione, potem znikają same. Naciśnij OK na **„Konfiguracja: nadaj uprawnienia”**: na ekranie pojawi się prośba „Allow USB debugging?” — zaznacz „Always allow” i naciśnij Allow. VoiceKey sam nada sobie mikrofon, aktualizacje, dostęp do powiadomień i `WRITE_SECURE_SETTINGS`, a potem włączy swoją usługę. Komputer nie jest potrzebny. Debugowanie można potem wyłączyć.
4. Jeśli to nie zadziała, jedno polecenie z komputera w tej samej sieci załatwia to samo:

   ```sh
   adb connect <adres-projektora>:5555
   adb shell pm grant dev.xdark.voicekey android.permission.WRITE_SECURE_SETTINGS
   ```

   Otwórz VoiceKey jeszcze raz — pozostałe kroki zamkną się same.
5. Rozpoznawanie online wymaga usługi głosowej `org.vidda.googlevoicesearch` (Vidda-Voice-Service, kompilacja xxivanoi z 4pda). Jeśli jej nie ma, na górze ustawień pojawi się krok **„Konfiguracja: usługa głosowa Google”**: OK pobierze ją z wydania [voice-service](../../releases/tag/voice-service) (suma kontrolna jest sprawdzana), na ekranie systemu naciśnij „Zainstaluj”, a mikrofon nada jej VoiceKey sam. Bez tej usługi panel mikrofonu sam przechodzi na model offline (jeśli pobrany) albo podpowiada, co zrobić, i nie wisi na „Łączę…”; można też od razu wybrać silnik offline.

Co daje każde uprawnienie:

| Krok | Po co | Bez debugowania |
|---|---|---|
| Mikrofon | rozpoznawanie mowy wymaga uprawnienia od wywołującego | tak, okno dialogowe |
| Aktualizacje z aplikacji | instalacja nowej wersji | tak, okno oprogramowania |
| Usługa przycisku mikrofonu | przechwytywanie przycisku | tylko jeśli oprogramowanie ma ekran ułatwień dostępu |
| Powrót po uśpieniu | oprogramowanie przy usypianiu zabija usługę; dzięki dostępowi do powiadomień system znów uruchamia VoiceKey | tylko jeśli jest ekran dostępu do powiadomień |
| `WRITE_SECURE_SETTINGS` | włącza dwa poprzednie kroki sam, także po uśpieniu | nie: przez „Konfiguracja: nadaj uprawnienia” albo adb |

**Język systemu.** W ustawieniach VoiceKey jest wiersz „Język systemu”: pokazuje bieżący język, a OK lub strzałki przełączają polski / English (zmienia się `persist.sys.locale`, potrzebny restart projektora). Wartość jest odczytywana z powrotem i wiersz uczciwie pisze, co wyszło; jeśli `setprop` nie zadziałał, VoiceKey próbuje drugiego sposobu — prosi system o zmianę języka tak, jak robią to aplikacje do przełączania języka. Jeśli oprogramowanie przy starcie przywraca swój język, VoiceKey po włączeniu projektora sam ustawia wybrany ponownie. Na samym dole ustawień jest ukryta sekcja „Debugowanie” (otwiera się przez OK): stan języka, dwie próby i dziennik — żeby ustalić, co robi oprogramowanie. Działa przy włączonym debugowaniu, tak jak „Konfiguracja: nadaj uprawnienia”. Przydaje się tam, gdzie oprogramowanie nie ma polskiego: aplikacje z własnymi polskimi tekstami zaczną mówić po polsku.

Test: `adb shell am start -n dev.xdark.voicekey/.TestPhrase --es phrase 'otwórz lampę'` — wykona frazę tak, jakby została powiedziana.

## Aktualizacja

Z aplikacji: ustawienia VoiceKey → „Aktualizacja” → OK. Aplikacja pobierze `VoiceKey.apk` z najnowszego wydania, sprawdzi sumę kontrolną i przekaże do instalatora; na ekranie systemu trzeba nacisnąć „Zainstaluj”. Cichej aktualizacji Android 11 nie pozwala. Aktualizacja jest przyjmowana tylko wtedy, gdy jest podpisana tym samym kluczem, co zainstalowana aplikacja.
Ręcznie: `adb install -r VoiceKey.apk`.

## Jak używać

- **Naciśnięcie** przycisku mikrofonu — powiedz polecenie.
- **Przytrzymanie** — wyszukiwanie głosowe w aplikacji na ekranie (albo panel Google, jeśli aplikacja tego nie umie).
- Co robi każde z nich i jak długo trzymać — w ustawieniach VoiceKey (ikona w launcherze).
- Aplikacje nazywaj po swojemu: „youtube”, „lampa”, „kinopoisk”, „vk video”, „rutube”.
- Inne polecenia: „wyłącz tłumaczenie” / „włącz tłumaczenie”, „oddaj mikrofon”.

## Gdy coś nie działa

- **Dziennik pracy:** `adb shell dumpsys activity service dev.xdark.voicekey/.KeyService` (oraz `/.WakeListener`, jeśli usługa nie działa).
- **Usługa nie włączyła się po uśpieniu:** uruchom ikonę VoiceKey z launchera — włączy ją ręcznie i powie, jak poszło.
- **Każde naciśnięcie na pilocie „wciska się” kilka razy, a Home otwiera chiński launcher.** Zauważone na Vidda C2 Ultra razem z launcherem [Lumbina](https://github.com/Kasavy/lumbina) 0.0.6–0.0.7: jej „usługa pilota” w ułatwieniach dostępu pada i mnoży naciśnięcia (sprawdzone surowymi zdarzeniami `getevent`: sprzętowo przycisk działa raz; restart usługi nie pomaga). Wiersz **„Ochrona pilota”** w ustawieniach VoiceKey (domyślnie włączony) sam wyłącza tę usługę, także po restarcie, gdy Lumbina włącza ją ponownie. Przy okazji wyłącza fabryczny launcher Hisense (`com.oversea.aslauncher`), dopóki zainstalowany jest dowolny inny launcher, żeby Home do niego nie uciekał; po usunięciu innego launchera fabryczny włącza się z powrotem. Po wyłączeniu tego wiersza fabryczny launcher też wraca. Do wyłączenia launchera potrzebne jest debugowanie sieciowe, tak jak do pozostałych kroków konfiguracji.

## Budowanie

Bez Gradle: `aapt2 → javac → d8 → zipalign → apksigner`.

Potrzebne: JDK 17+ (ścieżka w `JAVA_HOME`, domyślnie `~/.local/opt/jdk`), Android SDK z build-tools 35.0.0 i platformą android-30 (`ANDROID_HOME`, domyślnie `~/.local/opt/android-sdk`), `curl`, `zip`.

```sh
tools/fetch-libs.sh   # Vosk i JNA z Maven Central, ze sprawdzeniem sum kontrolnych
./build.sh            # powstanie VoiceKey.apk
```

Klucz podpisu `voicekey.keystore` tworzy się przy pierwszym budowaniu obok projektu. Przechowuj go: aktualizacje muszą być podpisane tym samym kluczem. Aplikacja jest budowana pod arm64.

## Budowa

Źródła w `src/dev/xdark/voicekey`, każda klasa ma opis na początku pliku. Kluczowe: `KeyService` (przechwytywanie przycisku), `RemoteMic` (dźwięk z mikrofonu pilota), `Command` (analiza frazy), `VoiceActivity` (rozpoznawanie), `WakeListener`/`BootReceiver` (powrót po uśpieniu), `Translator` (tłumacz ekranowy), `WatchNext` (kontynuuj oglądanie).

## Licencja

[GPL-3.0-or-later](LICENSE). Komponenty zewnętrzne — w [NOTICE.md](NOTICE.md).
