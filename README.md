# AutoBot – automatyzacja Androida (MVP)

Aplikacja-bot dla Androida: nagrywa kliknięcia i przesunięcia, rozpoznaje elementy ekranu
po obrazie i pozwala składać z tego algorytmy z bloków (jak w Scratchu).
Interfejs odwzorowuje prototyp Figma `Auto_bot.fig`: zakładki **BOT**, **Skrypty**, **Wzorce**, **Ustawienia**
(z nich otwierane są **Edycja algorytmu** i **Logi**), dolne menu oraz pływający panel START/STOP.

## Jak zbudować
1. Otwórz folder `AutoBot` w **Android Studio** (Koala 2024.1+ / JDK 17). Studio samo
   wygeneruje Gradle Wrapper i pobierze zależności.
2. Podłącz telefon z **Androidem 11+** (API 30+) lub uruchom emulator → **Run**.

## Pierwsze uruchomienie
1. Ekran **BOT** pokaże czerwony baner → **WŁĄCZ USŁUGĘ** → Ustawienia → Dostępność → *AutoBot* → włącz.
   * Android 13+, instalacja z pliku APK: jeśli przełącznik jest wyszarzony – *Informacje o aplikacji → ⋮ → Zezwól na ustawienia z ograniczeniami*.
2. Żadne inne uprawnienia nie są potrzebne (nakładki są rysowane jako okna usługi dostępności).

## Jak używać
| Krok | Gdzie | Co zrobić |
|---|---|---|
| Nagraj makro | **Skrypty → NAGRAJ MAKRO** | Telefon wraca na ekran główny. Wykonaj czynności w dowolnej aplikacji (każdy gest jest od razu odtwarzany), zakończ **■** w pływającym panelu. |
| Przeglądaj skrypty | **Skrypty** | Wszystkie makra i algorytmy na jednej liście (oznaczone **MAKRO** / **ALGORYTM**), wyszukiwanie po nazwie, ikona ✎ otwiera edycję. |
| Utwórz wzorzec | **Wzorce → + NOWY WZORZEC Z TELEFONU** | *Z galerii* albo *Zrzut ekranu (pływający przycisk)*: aplikacja się minimalizuje, przechodzisz na właściwy ekran i naciskasz **📷 ZRÓB ZRZUT**. Potem zaznacz palcem fragment i zapisz. |
| Zbuduj algorytm | **BOT → + NOWY ALGORYTM** (lub Skrypty) | **+ DODAJ BLOK** → paleta bloków. Zapis jest automatyczny. **KOPIUJ** (w edytorze i ikona ⧉ na liście BOT) duplikuje algorytm. |
| Wczytaj do BOT-a | **BOT → WCZYTAJ ALGORYTMY** | Zakładka BOT pokazuje tylko algorytmy wczytane tym przyciskiem (odznaczenie je ukrywa, nie usuwa). Algorytmy istniejące przed aktualizacją zostają w BOT. |
| Uruchom | **BOT → zaznacz algorytmy → URUCHOM BOTA** | Uruchamiane są zaznaczone algorytmy widoczne w BOT. **⏸** pauza, **■** natychmiastowy stop. |
| Ustawienia | **Ustawienia** | Logi, limity bezpieczeństwa (z przełącznikiem), odliczanie, losowość kliknięć i czasu, kopia zapasowa. |

### Test algorytmu i tryb krokowy
**TESTUJ** w edytorze (lub **URUCHOM BOTA** przy jednym zaznaczonym algorytmie) pokazuje pływający panel:
`✥` przesuń · `⏮` poprzedni krok · `▶/⏸` start/pauza · `⏭` następny krok · `■` stop / `✕` zamknij · `☰` menu · `‹` zwiń.

* **Miejsca kliknięć** – podczas testu bot rysuje na ekranie pulsujący znacznik z numerem i współrzędnymi tam, gdzie klika
  (dla przesunięcia: ścieżka ze strzałką). Znacznik nie przechwytuje dotyku i znika przed każdym zrzutem ekranu.
  Zwykła praca bota (bez testu) nie rysuje znaczników. Wyłączysz je w **Ustawienia → Test algorytmu**.
* **Tryb krokowy** – algorytm jest rozwijany do listy kroków (każdy blok to jeden krok; zawartość pętli, JEŻELI itd. idzie tuż po
  bloku-kontenerze). Kursor wskazuje krok, który wykona się jako następny:
  * `⏭` – wykonuje krok z kursora i przesuwa kursor dalej (tylko gdy krok się udał),
  * `⏮` – cofa kursor o jeden krok **bez wykonywania** (np. by powtórzyć krok po korekcie; stanu aplikacji docelowej nie da się cofnąć),
  * pierwsze naciśnięcie `⏮`/`⏭`/`☰` tylko włącza tryb krokowy i pokazuje bieżący krok. Jeśli w tym czasie trwa zwykły test (`▶`),
    zostanie wstrzymany, a kursor stanie na kroku, na którym się zatrzymał,
  * `▶` uruchamia cały test od początku (i kończy tryb krokowy).
  * Kontenery nie są wykonywane jako całość: JEŻELI tylko sprawdza warunek (wynik w logach) i tryb krokowy przechodzi przez
    **obie** gałęzie; PĘTLA, DLA KAŻDEGO, O DACIE, POWTARZAJ – jedno przejście przez zawartość; PRZERWIJ/POMIŃ/ZATRZYMAJ są pomijane.
* **Menu ☰** (wysuwa się pod przyciskami panelu):
  * **✎ Koryguj pozycję i czas** – patrz niżej,
  * **＋ Dodaj następny krok** – KLIKNIJ, PRZESUŃ, CZEKAJ 1 s lub ZNAJDŹ WZORZEC I KLIKNIJ (wybór wzorca z listy); nowy krok trafia tuż po
    aktualnym (po bloku-kontenerze – na początek jego zawartości). Pętle i warunki dodasz w edytorze,
  * **🗑 Usuń aktualny krok** (z potwierdzeniem; usunięcie kontenera usuwa jego zawartość),
  * ↺ wróć do pierwszego kroku, zakończ tryb krokowy, zamknij menu.
* **Korekta pozycji i czasu** (działa na bieżącym kroku, zmiany zapisują się od razu w algorytmie):
  * w trybie krokowym na ekranie pojawiają się **przeciągalne celowniki** w miejscu kliknięcia (dla przesunięcia: 1 = początek, 2 = koniec) –
    przeciągnij je palcem; dokładnie ustawisz strzałkami ◀▲▼▶ w menu (skok 1 / 5 / 20 px),
  * **czas między kliknięciami** – „Przerwa przed tym krokiem” zmienia blok CZEKAJ stojący tuż przed krokiem (±100 ms / ±1 s;
    brakujący blok jest tworzony, a przy zejściu do 0 usuwany). Dla kroku CZEKAJ zmieniasz jego czas, dla ZNAJDŹ I KLIKNIJ – maks. czas szukania.
  * pozycje zapisane w algorytmie są pozycjami zadanymi – realne kliknięcie nadal podlega losowemu przesunięciu z Ustawień (znacznik pokazuje
    miejsce faktycznego kliknięcia). Czasy z bloków CZEKAJ również dostają losowy dodatek.
  * Przed wykonaniem kroku celowniki chowają się (leżą dokładnie w miejscu kliknięcia i przechwyciłyby dotyk).

### Bloki
JEŻELI wzorzec istnieje / nie istnieje · ZNAJDŹ WZORZEC I KLIKNIJ GO · MAKRO · CZEKAJ · PRZESUŃ · KLIKNIJ · PĘTLA · PRZERWIJ PĘTLĘ · POMIŃ ITERACJĘ ·
PRZERWIJ AKTUALNY ALGORYTM · ZATRZYMAJ BOTA oraz nowe:
* **ALGORYTM** – wywołuje inny zapisany algorytm (× powtórzeń). Rekurencja (A→B→A) jest zablokowana w edytorze i w silniku.
* **DLA KAŻDEGO WZORCA Z ZESTAWU** – zestaw wzorców (np. jabłko, gruszka, śliwka). Dopóki na ekranie jest którykolwiek: bierze ten położony najwyżej, opcjonalnie go klika, wykonuje bloki z kieszeni (np. makro) i skanuje ekran od nowa. Kończy, gdy nie ma żadnego.
* **O OKREŚLONEJ DACIE I GODZINIE** – czeka do wskazanego terminu i wykonuje bloki z kieszeni; ustawiasz, co zrobić, gdy termin już minął (pomiń / wykonaj od razu).
* **POWTARZAJ CO…** – cyklicznie co N minut / godzin / dni / tygodni (rytm liczony od startu poprzedniego uruchomienia), z opcją „pierwszy raz od razu” i limitem uruchomień (0 = bez końca).
* **KLIKNIJ / PRZESUŃ → POBIERZ Z EKRANU** – współrzędne można wpisać albo pobrać dotknięciem: aplikacja się minimalizuje, pojawia się pływający przycisk; po jego naciśnięciu dotykasz miejsca (lub przesuwasz palcem), a wynik trafia do edytowanego bloku.

Oczekiwanie na datę / interwał nie wlicza się do limitu czasu działania. Podczas pracy bota pływający panel utrzymuje ekran włączony – telefon musi pozostać włączony i odblokowany.

## Ustawienia
* **Limity bezpieczeństwa** (przełącznik): maks. czas działania (30 min), liczba akcji (5000), iteracje pętli (500), pojedyncze oczekiwanie (60 s). Po wyłączeniu wszystkie są ignorowane (także „maks. iteracji” w pętlach do skutku i w bloku DLA KAŻDEGO) – zatrzymać można tylko ręcznie przyciskiem ■.
* **Test algorytmu**: przełącznik „Pokazuj miejsca kliknięć” (domyślnie włączony) – znaczniki tylko podczas testu.
* **Losowość**: każde kliknięcie/przesunięcie jest losowo przesuwane o −5…+5 px (ścieżka przesunięcia – o ten sam wektor; kliknięcie we wzorzec nigdy poza jego obrys), do każdego oczekiwania (CZEKAJ i opóźnienia w makrach) dodawane jest losowo 0…500 ms. Obie wartości edytowalne, 0 = wyłączone.
* **Kopia zapasowa**: *ZAPISZ KOPIĘ* tworzy jeden plik ZIP (`backup.json` + `patterns/<id>.png`) z makrami, algorytmami, wzorcami, folderami i ustawieniami – bez logów. *WCZYTAJ KOPIĘ*: **dołącz/zaktualizuj** (po id) albo **zastąp wszystko**. Identyfikatory są zachowane, więc bloki dalej wskazują właściwe makra i wzorce.
* **Logi**: przycisk otwiera ekran logów (kopiowanie, czyszczenie).

## Bezpieczeństwo
* Limity (domyślnie włączone) – patrz Ustawienia; każda iteracja pętli ma minimalną przerwę 50 ms.
* Brak wzorca / makra / algorytmu, błąd gestu lub zrzutu → bot zatrzymuje się i zapisuje przyczynę w logach.
* **■** w panelu przerywa natychmiast, **⏸/▶** wstrzymuje i wznawia (pauza nie liczy się do limitu czasu).
* Odliczanie przed startem (domyślnie 3 s), by zdążyć przejść do właściwej aplikacji.

## Architektura
```
app/src/main/java/pl/autobot/app
├── App.kt, MainActivity.kt          start, nawigacja (BOT, Skrypty, Wzorce, Ustawienia + ekrany podrzędne)
├── data/   Models.kt, Repository.kt modele (kotlinx.serialization), zapis lokalny JSON/PNG/log w filesDir
│           StepTree.kt              ścieżki bloków w drzewie: spłaszczanie, wstawianie, usuwanie, podmiana (tryb krokowy)
├── engine/ BotEngine.kt             interpreter bloków, limity, pauza/stop, logi
│           TemplateMatcher.kt       dopasowanie wzorca (SAD, zgrubnie → dokładnie), czysty Kotlin
├── service/BotAccessibilityService  dispatchGesture, takeScreenshot, nagrywanie, panel
│           FloatingPanel.kt         pływający panel ✥ ⏮ ▶/⏸ ⏭ ■ ☰ ‹› (+ wysuwane menu) + FloatingAction (przyciski rejestracji/zrzutu)
│           StepController.kt        tryb krokowy: kursor, wykonanie kroku, korekta pozycji/czasu, dodawanie i usuwanie kroków
│           StepOverlays.kt          znaczniki kliknięć w teście (ClickMarkerOverlay) i przeciągalne celowniki (PositionEditor)
└── ui/     BotScreen, ScriptsScreen, MacroScreens (szczegóły makra), PatternScreens (+kadrowanie), EditorScreen,
            SettingsScreen, LogsScreen, Components
```
Mechanizmy Androida: `AccessibilityService.dispatchGesture()` (kliknięcia/przesunięcia), `takeScreenshot()` (API 30+, bez MediaProjection i bez okienka zgody),
okna `TYPE_ACCESSIBILITY_OVERLAY` (nagrywanie i panel – bez uprawnienia „nad innymi aplikacjami”).

## Ograniczenia i uwagi
* Okna z `FLAG_SECURE` (np. część aplikacji bankowych, DRM) nie dają się sfotografować – bot zgłosi błąd zrzutu.
* Gry z ochroną anty-cheat/anty-bot mogą wykrywać automatyzację lub regulaminowo ją zabraniać – używaj zgodnie z zasadami danej gry.
* Wzorce porównywane są w skali 1:1 – twórz je na tym samym telefonie i w tej samej rozdzielczości/orientacji.
  Dopasowanie jest wrażliwe na zmianę jasności i animacje; przy ruchomym tle obniż dokładność do ok. 80–85%.
* Nagrywanie obsługuje jeden palec (bez multi-touch/pinch). Ścieżka przesunięcia jest odtwarzana ze stałą prędkością.
* Pływający panel jest zwykłym oknem: odsuń go od miejsc, w które bot ma klikać.
* Gest „przytrzymanie + przeciągnięcie” i bardzo szybkie serie dotknięć mogą wymagać korekty czasów w edycji makra.

## Poza zakresem MVP (następne wersje)
Zmienne i liczniki, operatory logiczne/matematyczne, skalowanie wzorców między rozdzielczościami (np. OpenCV), wiele równoległych algorytmów,
harmonogram działający w tle przy wyłączonym ekranie (bloki daty/powtarzania wymagają włączonego, odblokowanego telefonu).


## Budowanie APK przez GitHub Actions

1. Utwórz repozytorium na GitHub i wgraj zawartość katalogu `AutoBot` (nie sam plik ZIP).
2. Upewnij się, że workflow `.github/workflows/android.yml` znajduje się w repozytorium.
3. Po `push` do gałęzi `main`/`master` lub po uruchomieniu z zakładki **Actions → Android APK → Run workflow**, GitHub zbuduje aplikację.
4. APK pobierzesz z sekcji **Artifacts** w szczegółach zakończonego uruchomienia workflow.

Workflow używa JDK 17 i Gradle 8.9 oraz buduje wariant debug (`assembleDebug`).
