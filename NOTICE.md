# Komponenty zewnętrzne

VoiceKey jest budowany razem z dwiema bibliotekami; pobiera je `tools/fetch-libs.sh`, w repozytorium ich nie ma.

| Komponent | Licencja |
|---|---|
| [Vosk](https://github.com/alphacep/vosk-api) (`vosk-android` 0.3.75) | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) 5.18.1 | Apache-2.0 lub LGPL-2.1+ |

Polski model rozpoznawania `vosk-model-small-pl` (Apache-2.0, autorzy — Alpha Cephei) aplikacja
pobiera sama z alphacephei.com, gdy włączysz rozpoznawanie offline.

Aplikacja `org.vidda.googlevoicesearch` (Vidda-Voice-Service, cudza kompilacja xxivanoi, 4pda, oparta na
aplikacji Google), przez którą działa rozpoznawanie online, nie wchodzi w skład kodu VoiceKey. Jej niezmieniony
APK leży jako osobny plik w wydaniu `voice-service`, tylko po to, by VoiceKey mógł ją zainstalować na prośbę
użytkownika; prawa do niej należą do jej autorów.

Słowniki tłumacza ekranowego (`assets/translate/`) wywodzą się z rosyjskiego tłumaczenia tekstów oprogramowania
z aplikacji VIDDA Nova (jego autor zezwolił na wykorzystanie tego tłumaczenia), przełożonego na polski.
