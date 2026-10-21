# AutoBot

Aplikacja Android do tworzenia algorytmów automatyzacji dotknięć, gestów, makr i wyszukiwania wzorców na ekranie.

## Budowanie APK przez GitHub Actions

1. Wgraj zawartość katalogu `AutoBot` do repozytorium GitHub.
2. Otwórz kartę **Actions** i wybierz workflow **Build Android APK**.
3. Uruchom go przyciskiem **Run workflow** albo wypchnij commit do repozytorium.
4. Po zakończeniu kompilacji pobierz artefakt **AutoBot-debug-apk** z sekcji artefaktów danego uruchomienia.

Workflow konfiguruje JDK 17, Android SDK (w tym `sdkmanager`) oraz Gradle 8.7. Akcja `android-actions/setup-android@v4` instaluje narzędzia SDK i automatycznie akceptuje licencje, dlatego nie trzeba osobno uruchamiać `yes | sdkmanager --licenses`. Workflow doinstalowuje platformę Android 34 i Build Tools 34.0.0, a następnie buduje debug APK z `app/build/outputs/apk/debug/`.

## Uruchamianie bota

Na urządzeniu Android włącz usługę dostępności AutoBot. W zakładce **BOT** zaznacz algorytmy i ustaw ich kolejność przyciskami góra/dół. Przycisk **URUCHOM BOTA** minimalizuje aplikację i pokazuje nakładkę **START BOT**; procedura rozpoczyna się dopiero po naciśnięciu tego przycisku.

Makra i wzorce można zmieniać w odpowiednich zakładkach. Zmiana nazwy makra lub wzorca aktualizuje zapisane odwołania w algorytmach i makrach.
