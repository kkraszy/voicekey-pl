# Tłumacz ekranowy

Aplikacja rysuje na wierzchu ekranu polskie plakietki w miejscu napisów, które oprogramowanie pokazuje po chińsku lub po angielsku. Teksty pochodzą z drzewa ułatwień dostępu, podmiana odbywa się według słownika `assets/translate/<pakiet>.json`:

```json
{"exact": {"声音模式": "Tryb dźwięku"},
 "patterns": [["^结果「(.+?)」：(.+?)$", "Wyniki dla „\\1”: \\2"]]}
```

Słowniki leżą w repozytorium i wchodzą do APK. Ich podstawą jest rosyjskie tłumaczenie tekstów oprogramowania z VIDDA Nova (jego autor zezwolił na wykorzystanie tego tłumaczenia), przełożone na polski. Braki uzupełniono ręcznie i roboczym tłumaczeniem lokalnej sieci neuronowej, krótkie słowa do wąskich menu — w `tools/overrides/`.

Narzędzia w `tools/`:

- `build-dict.py` — buduje słownik pakietu: bierze domyślne teksty z jego APK na projektorze (potrzebne `adb` i `aapt2`) i dopasowuje je po nazwie zasobu do tłumaczenia tych samych zasobów (źródłem jest rosyjskie tłumaczenie VIDDA Nova — wynik trzeba potem przełożyć na polski);
- `untranslated.py` — co w słownikach zostało bez tłumaczenia;
- `match-dicts.py`, `dict-specs.txt` — dopasowanie plików tłumaczeń do pakietów;
- `overrides/` — krótkie polskie słowa do wąskich menu (poprawia długie miejsca ręcznie);
- `qwen-translate.py` — roboczy przekład pozostałych tekstów lokalnym modelem.
