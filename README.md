# Rynek Analiza 0.5.3 - auto rynek z analizą cen (Minecraft 1.21.4, Fabric)

Samodzielny auto rynek dla Anarchii (BK-Rynek nie jest potrzebny) z wbudowaną analizą cen. **Jeden start / stop** (klawisz **F9**):
1. **analiza cen** wszystkich przedmiotów z listy - wpisuje **tylko cenę sprzedaży** = średnia 3 najtańszych ofert (można ją pominąć:
   **F8** albo przycisk **"Pomiń analizę"** obok rynku),
2. **skup liczy się sam ze wzoru**: `skup za sztukę = sprzedaż za sztukę x 0,97 - Twoja minimalna marża (za sztukę)`,
3. **snajper** - kupuje oferty z zakładki **Najnowsze** taniej niż skup i podświetla je na **zielono**,
4. **monitorowanie rynku** w trakcie pracy - nowe tańsze oferty obniżają cenę sprzedaży, wykupione podnoszą ją z powrotem (skup idzie
   za nią ze wzoru),
5. **restart rynku co 60 s** - przy każdym restarcie **wystawia** kupione przedmioty, **od najtańszych do najdroższych**, ale tylko
   te, na których **nie stracisz** (sprzedaż po podatku >= cena zakupu) - reszta czeka w ekwipunku na wyższą cenę,
6. **anty-AFK** co 4 minuty, **ponowna analiza co 12 minut** i od razu po 3 zakupach tego samego przedmiotu w ciągu 10 minut,
7. **praca w tle** - menu gry (Esc, albo samo po przełączeniu na inną aplikację) jest zamykane "Powrót do gry", gra nie pauzuje.
Nowe przedmioty dodasz na rynku: **najedź myszką na przedmiot i naciśnij L** - trafi na listę, a cenę ustali następna analiza.
Zamiast snajpera można włączyć tryb **"Tylko sprzedaż"** - bot nie kupuje, tylko wystawia (cały czas, gdy jest miejsce na rynku).

**Uczciwie:** 0.3.0 działała u Ciebie w grze. Zmiany od 0.4.0 (także cała 0.5.x) były kompilowane i testowane tylko na atrapach API
Minecrafta i na symulowanym serwerze - w grze jeszcze nie były uruchomione. Prawdziwy build zrobi GitHub Actions.

**Ważne po aktualizacji:** progi skupu zniknęły, a **marża domyślna jest 0 = przedmiot bez własnej marży nie jest kupowany**. Zaraz po
wgraniu 0.5.0 snajper nic nie kupi, dopóki nie wpiszesz marż (kolumna **Marża ($)** w zakładce Przedmioty) albo marży domyślnej
(Ustawienia, "Marża domyślna ($/szt., 0 = nie kupuj bez marży)"). Wystawianie działa bez marż.

## Zmiany w 0.5.3
- **Wystawianie bez straty.** Mod pamięta, za ile kupił każdą sztukę (historia zakupów). Przed każdą wystawką liczy: cena sprzedaży
  za sztukę x 0,97 (podatek) vs cena zakupu. Gdy wyszłaby strata, sztuka **zostaje w ekwipunku** i czeka - przy każdym kolejnym
  wystawianiu (co restart rynku) sprawdzana jest od nowa, więc wystawi się sama, gdy cena sprzedaży wzrośnie (analiza co 12 min,
  monitorowanie rynku). Na czacie raz: "Czeka na wyższą cenę: Anarchiczny hełm - zakup 17500$/szt., sprzedaż teraz po podatku
  16490$/szt.". W panelu: pasek pod wykresem ("czeka na wyższą cenę: zakup ..., teraz po podatku ...") i zakładka Auto rynek
  ("czeka na wyższą cenę: n przedm.").
  - **Najpierw analiza:** gdy cena sprzedaży tego przedmiotu jest starsza niż 5 min (a auto rynek ma włączoną analizę), mod zleca
    jego analizę ("sprawdzam cenę analizą") i decyduje po niej ("Po analizie dalej strata - czeka..." albo wystawia).
  - **Różne ceny zakupu tego samego przedmiotu** (np. hełm kupiony za 17 500$, a potem po spadku ceny drugi za 15 000$): wystawia
    najpierw sztuki kupione najtaniej - ta za 15 000$ idzie od razu z zyskiem, droga czeka. Dlatego historia **przypisuje teraz
    sprzedaż do najtańszego niesprzedanego zakupu** (wcześniej - do najstarszego), żeby zysk w Historii zgadzał się z decyzją.
  - Ustawienia: "Wystawianie: tylko bez straty" (WŁ), "zysk co najmniej ($/szt., 0 = bez straty)" (0), "czekaj na cenę najwyżej
    (h, 0 = bez limitu)" (0 = czeka, aż cena wzrośnie; np. 48 = po dobie i 2 dniach od zakupu wystawia mimo straty).
  - Sztuki bez zakupu w historii (kupione ręcznie, sprzed włączenia historii) - koszt nieznany, wystawiane jak dotąd.
  - Uwaga: pełny ekwipunek samych czekających przedmiotów = bot nie kupuje i nie wystawia, dopóki cena nie wzrośnie (albo ustaw
    limit czekania). Ochrona opiera się na rozpoznanych sprzedażach - gdy komunikat o sprzedaży nie zostanie rozpoznany, mod myśli,
    że sztuka dalej leży na rynku, i ostrożnie liczy następne sztuki.

## Zmiany w 0.5.2
- **Podpowiedź marży z historii.** Pod wykresem przedmiotu (pasek z "Auto: TAK") linia "Podpowiedź: marża 1200$ (3.4%) | sprzedaż
  zwykle w 39 min (sprzedane 5/6) | ..." i przycisk **Zastosuj** - wpisuje tę marżę (skup przeliczy wzór). Po najechaniu na przycisk
  dymek z pełnym uzasadnieniem. Na niskim ekranie (np. 480x270) zamiast osobnej linii jest przycisk "Podp. 1200$" obok wzoru skupu.
  Liczona z ostatnich 14 dni:
  1. **czas sprzedaży** - mediana czasu od zakupu do sprzedaży (historia zakupów i sprzedaży); bez sprzedaży przyjmuje 6 h, a gdy
     zakupy sprzed doby wciąż leżą - "ponad dobę",
  2. **typowy spadek ceny w tym czasie** - z analiz: dla każdej analizy najniższa cena w kolejnych (czas sprzedaży); bierze spadek,
     którego 3 na 4 razy nie przekroczono (75. percentyl); bez historii cen zakłada 3%,
  3. **zysk docelowy** - im wolniej schodzi, tym więcej: do 1 h 2%, do 6 h 3%, do doby 5%, dłużej 8%; +2%, gdy mniej niż połowa
     zakupów się sprzedała; +1%, gdy danych jest mało (mniej niż 3 zakupy, krótka historia cen).
  **Marża = cena sprzedaży za sztukę x (0,97 x spadek + zysk docelowy)**, najwyżej 30%, zaokrąglona w górę (do 10$, 50$, 100$...).
  Sens: kupujesz po skupie, cena w czasie sprzedaży spada o typowy spadek - i dalej zostaje zysk docelowy.
  Przykład: sprzedaż 10 000$, sprzedaje się w 40 min, spadek 2% -> 0,97 x 2% + 2% = 3,94% -> **400$**. Przedmiot dodany L, bez
  historii: 0,97 x 3% + 3% + 1% = 6,91% -> 700$ (przy cenie 10 000$). Podpowiedź pojawia się, gdy przedmiot ma cenę sprzedaży;
  nic nie zmienia sama - tylko po kliknięciu "Zastosuj". W dymku także "zarabiałeś X$/szt." (albo "traciłeś") z dotychczasowych
  sprzedaży.

## Zmiany w 0.5.1
- **Dodawanie przedmiotów klawiszem L**: w oknie rynku najedź myszką na przedmiot (oferta albo Twój ekwipunek) i naciśnij **L**.
  Na czacie: "Dodano do listy: Totem Anarchii (totem_of_undying). Cenę sprzedaży ustali następna analiza, skup - po wpisaniu marży".
  Zapisuje się:
  - id przedmiotu i **nazwa z kolorem i stylem** (podróbka z inną nazwą albo kolorem nie pasuje), CustomModelData,
  - przy **zaklętej księdze** - jej enchanty (księga z innym enchantem to osobny przedmiot),
  - ilość sprzedaży = ilość w najechanym stacku (np. 64), gdy większa niż 1,
  - "Kup: TAK", "Wyst.: kupione" (nie wystawia Twoich własnych przedmiotów z ekwipunku), ceny 0 i pusta marża.
  Gdy coś z listy już pasuje: "... już jest na liście.". Dolny rząd przycisków rynku i puste sloty się nie liczą. Cenę sprzedaży wpisze
  najbliższa analiza (start, co 12 min); skup policzy wzór, gdy przedmiot ma marżę (albo jest marża domyślna). Opis ofert (cena,
  sprzedawca) nie jest kopiowany. Klawisz zmienisz w Ustawieniach ("Klawisz: dodaj przedmiot z rynku"; Delete = brak). To nie jest
  klawisz ze Sterowania Minecrafta, więc **L dalej otwiera Postępy** w grze - mod łapie go tylko w oknie rynku.
- **Usunięta obniżka skupu przy zapasie** (3 niesprzedane zakupy = skup -10%). Skup jest zawsze ze wzoru; ceny po zakupach poprawia
  ponowna analiza po 3 zakupach w 10 minut. Skup obniżony przez 0.5.0 wraca do wzoru sam po wczytaniu. Autopilot pilnuje już tylko
  budżetu dziennego. Ustawienia "obniżka skupu od zapasu / za zapas" zniknęły.
- W dzienniku autopilota **zielone = sprzedaż**. Pasek pod wykresem przy "Max skup" pokazuje "na liście X$ (Max skup)".

## Zmiany w 0.5.0
- **Marża zamiast progów skupu.** Każdy przedmiot ma swoją **minimalną marżę za sztukę** (kolumna **Marża ($)**, zapis ok. 1 s po
  ostatnim znaku). Puste pole = marża domyślna z Ustawień (podpowiedź w polu: "dom. 500" albo "brak"). Kolumna **Skup ($)** jest teraz
  tylko do odczytu - pokazuje skup ze wzoru. Przykład: sprzedaż 34 333$, marża 500$ -> skup = 34 333 x 0,97 - 500 = **32 803$**.
  Wzór dający 0 albo mniej = skup 0 = **nigdy** nie kupuje. Podatek (0,97) zmienisz w Ustawieniach.
- **Analiza wpisuje tylko cenę sprzedaży** (średnia 3 najtańszych cudzych ofert za sztukę x ilość sprzedaży). Skup przelicza się sam po
  każdej zmianie sprzedaży, marży, marży domyślnej albo podatku. Przedmiot z "Auto: NIE" ma stałą cenę sprzedaży (Twoją), a skup i tak
  liczy się z niej ze wzoru. "Max skup" (pasek pod wykresem) ogranicza skup, "Min. sprzedaż" - cenę sprzedaży.
- **Monitorowanie rynku** (gdy działa auto rynek): mod pamięta 8 najtańszych cen z ostatniej analizy każdego przedmiotu. W oknach,
  które widzi snajper:
  - nowa cudza oferta tańsza od 3. najtańszej, której snajper nie kupi (nie poniżej skupu), wchodzi do średniej - **sprzedaż w dół**,
  - oferta kupiona przez snajpera schodzi z rynku - gdy była wśród najtańszych, średnia liczy się bez niej - **sprzedaż w górę**.
  Skup idzie za sprzedażą ze wzoru. Na czacie / w dzienniku: "Hełm: monitorowanie rynku (nowa oferta 33000$/szt.) - sprzedaż
  34333$ -> 33666$, skup 32656$/szt. (33666$/szt. x 0.97 - marża 500$)". Pełna analiza co 12 minut i tak ustawia wszystko od nowa.
- **Szybkość analizy** znowu w trybach: **Bezpieczna / Szybka / Maksymalna** (Ustawienia, przycisk "Szybkość analizy"); tempo wg pingu
  usunięte. Ping zostaje tylko do ponownego kliknięcia potwierdzenia zakupu.
- **Wystawianie od najtańszych**: przy każdym wystawianiu przedmioty idą w kolejności od najniższej ceny wystawki do najwyższej -
  tanie (szybciej schodzą) zajmują sloty rynku pierwsze, drogie nie blokują miejsca.
- **Praca w tle**: gdy auto rynek działa, mod wyłącza "pauzę po utracie fokusu" (po stopie wraca Twoje ustawienie) i zamyka menu gry jak
  przycisk "Powrót do gry": od razu, gdy okno gry nie jest aktywne (grasz w coś innego), a po 5 s, gdy sam otworzysz Esc przy aktywnym
  oknie (żeby zdążyć wejść w opcje albo wyjść). Komunikat: "Menu gry zamknięte (Powrót do gry) - auto rynek działa w tle." Inne okna
  (czat, ekwipunek, panel) - jak dotąd: auto rynek czeka, aż je zamkniesz. Lepiej nie minimalizować okna gry, tylko przełączyć się na
  inne - zminimalizowany Minecraft ogranicza klatki (Ustawienia grafiki, limit FPS przy bezczynności), a potwierdzanie zakupu i odczyt
  okna rynku działają w klatkach, więc snajper byłby wolniejszy.
- **Autopilot uproszczony** (obniżka przy zapasie usunięta w 0.5.1): **budżet dzienny** (0 = bez limitu) i **obniżka skupu przy zapasie** (od 3 niesprzedanych zakupów
  skup x 0,9 za każdy). Ceny zawsze liczy wzór. Usunięte: tryb próbny, "kupuj też przy zysku od 200$" (zastąpiła go marża), żółte ramki.
- **Historia sprzedaży naprawiona** - przyczyny, przez które część sprzedaży nie miała ceny:
  - kilka sprzedaży w tej samej sekundzie liczyło się jako jedna (teraz każdy komunikat to osobna sprzedaż; dwa komunikaty o tej samej
    sprzedaży, np. "Twój przedmiot ... został kupiony" i "+... dodane", w ciągu 2 s = jedna),
  - sprzedaż szukała przedmiotu tylko po obecnej cenie z listy, a po zmianie ceny przez analizę stare wystawki już nie pasowały - teraz
    **każda wystawka auto rynku jest zapamiętana** (cena, sztuki, przedmiot; do 8 dni, zapisywane na dysk) i sprzedaż dopasowuje się do
    niej (cena x 0,97 albo sama cena),
  - przedmiot z dopiskiem w nazwie (np. "Klucz [Epicki]") nie trafiał do zakupu zapisanego pod nazwą bez dopisku - teraz trafia,
  - nazwa przedmiotu z komunikatu o sprzedaży (gdy serwer ją podaje) rozstrzyga, który to przedmiot.
- **% salda na zakup** działa znowu przy snajperze: oferta droższa niż "Snajper: najwyżej % salda na 1 zakup" (70%) jest pomijana
  ("Pomijam ... - więcej niż 70% salda"); 0 = tylko limit salda.

## Jak zbudować i zainstalować
1. Repo z plikami budowania (`build.gradle`, `gradle.properties`, `settings.gradle`, `.github/workflows/build.yml`) i folderem `src/`
   z tej paczki. Build leci w zakładce Actions, jar jest w artefakcie "rynek-analiza".
   - Na GitHubie (Add file -> Upload files) **przeciągnij cały folder `src`**, nie pojedyncze pliki. W repo musi być
     `src/main/java/pl/rynekanaliza/` z **20 plikami `.java`** i `src/main/resources/fabric.mod.json`. W 0.5.2 doszedł
     `MarginHint.java` (w 0.5.3 bez nowych plików), w 0.5.0 `Pricing.java`, a `PingTiming.java` nie jest już potrzebny - usuń go z repo (gdy zostanie, build i tak przejdzie, plik jest nieużywany).
   - Wgraj też `gradle.properties` (wersja 0.5.3). `build.gradle` się nie zmienił.
   - Dobry jar `rynek-analiza-0.5.3.jar` ma ok. 210-250 KB. Jar ok. 1 KB = build bez plików moda.
2. Jar wrzuć do `mods/` (stary jar tego moda usuń). Ustawienia, lista przedmiotów i historia z 0.3.0 / 0.4.x zostają (progi skupu,
   tryb próbny i "zysk od 200$" są pomijane). **Potem wpisz marże** (patrz "Ważne po aktualizacji" wyżej).
   Jeśli to pierwsza instalacja: jar BK-Rynku z `mods/` usuń, a **folder z configiem BK zostaw** (`config/BK-Mods/BK-Rynek` albo
   `config/bkrynek-config`) - przy pierwszym uruchomieniu mod sam zaimportuje z niego listę (ponownie: "Importuj listę z BK").

## Auto rynek
**Start / stop:** klawisz **F9** (działa też przy otwartym rynku), przycisk w zakładce Auto rynek albo `/analiza auto`.
Panel zamyka się przy starcie. Na czacie: "[Auto rynek] Start: najpierw analiza cen, potem snajper (N przedm. z ceną skupu), wystawianie
przy każdym restarcie rynku, anty-AFK co 4 min, restart rynku co 60 s, analiza co 12 min. Stop: F9." i "Analiza cen: 25 przedm. Pomiń: F8
albo przycisk "Pomiń analizę" obok rynku." Stop w trakcie analizy zatrzymuje też analizę.

**Pętla (tryb snajper):**
1. **Analiza na starcie** (gdy włączona "Analiza: na starcie i co 12 min") - wszystkie przedmioty, bez przerwy przed nią. Po niej (albo
   po "Pomiń analizę"): "[Auto rynek] Analiza zakończona (zmienione ceny - sprzedaż: 7, skup: 5). Auto rynek wraca."
2. Wystawia przedmioty do wystawienia (punkt 7), otwiera rynek komendą z ustawień (domyślnie `/ah`) i ustawia sortowanie **Najnowsze**:
   PPM na przycisku sortowania (slot 53), aż w jego opisie zaznaczona będzie opcja "Najnowsze" (po 6 kliknięciach bez skutku - komunikat
   i snajpienie przy obecnym sortowaniu).
3. Przy każdej zmianie okna (od razu w klatce, w której przyszła nowa lista) sprawdza wszystkie oferty. Kupuje ofertę, gdy:
   - pasuje do przedmiotu z listy z **"Kup: TAK"** i skupem ze wzoru **większym niż 0** (brak marży, brak ceny sprzedaży albo wzór
     <= 0 = skup 0 = **nigdy**),
   - jest **taniej** niż skup (oferta równa skupowi - nie),
   - tryb **Unit** (domyślnie): cena za sztukę, oferta ma co najmniej "ilość kupna" sztuk; tryb **Sum**: cena całej oferty, która ma
     dokładnie "ilość kupna" sztuk (skup na liście = skup za sztukę x ilość kupna),
   - nie jest Twoja (nick w opisie oferty), nie jest droższa niż saldo konta i niż "% salda na 1 zakup" (70%; gdy saldo jest znane),
   - pet/kostium: ma co najmniej "minimum dni" z listy.
   Przy kilku pasujących przedmiotach liczy się ten z najniższym skupem.
   W tym samym oknie działa **monitorowanie rynku** (cena sprzedaży wg nowych tańszych ofert - patrz "Ceny: wzór i monitorowanie").
4. **Podświetlenie**: **zieloną** ramkę mają dokładnie te oferty, które snajper kupiłby w tej chwili (poniżej aktualnego skupu).
   Pozostałe oferty, podróbki, własne oferty i przedmioty spoza listy - bez ramki. Wyłączenie: Ustawienia.
5. Zakup: LPM na ofercie, a w oknie potwierdzenia mod **sprawdza, że to ten sam przedmiot i nie drożej** (inny przedmiot albo wyższa
   cena - "Anuluję zakup ...", okno zamknięte). Przycisk potwierdzenia: po nazwie ("potwierdź", "kup"/"zakup", "akceptuj", "confirm",
   "buy", "tak"; pomija sam przedmiot i "anuluj"/"nie"/"wróć"), po **zielonym barwniku** (lime/green dye), zapasowo slot z ustawień (11).
   Klik **w tej samej klatce, w której przyszło okno**, jeśli jest w nim już przycisk i kupowany przedmiot; gdy serwer dosyła zawartość
   po kawałku - czeka na nią najwyżej 0,25 s. Brak odpowiedzi serwera = ponowny klik po 4 x ping (min. 0,6 s), najwyżej 3 razy.
   Wynik z czatu ("Pomyślnie zakupiłeś...", "już sprzedany", "brak środków"...) albo z ekwipunku (przedmiot przybył).
   Brak środków = minuta przerwy w kupowaniu. Nieudana oferta jest pomijana przez 30 s, kupiona - przez minutę.
6. Co **200 ms** odświeża listę (PPM na slocie 0), ale nie częściej niż serwer odpowiada.
7. **Restart rynku co 60 s = wystawianie**: zamyka rynek, wystawia
   - przedmioty **kupione przez snajpera** (licznik "do wyst." przy przedmiocie),
   - wszystkie pasujące z ekwipunku, gdy przedmiot ma **"Wyst.: TAK"**,
   **od najtańszej wystawki do najdroższej** i otwiera rynek od nowa (gdy nie ma nic do wystawienia - zwykły restart z przerwą 1,5 s).
   Cena = cena sprzedaży z listy za "ilość
   sprzedaży" sztuk; mniejszy stack proporcjonalnie (48 jabłek przy 3000$ za 64 = 2250$). Komenda z ustawień (domyślnie
   `/wystaw {quantity} {price}`); przedmiot z plecaka jest na chwilę zamieniany z wybranym slotem paska. Przedmiot bez ceny sprzedaży
   nie jest wystawiany. Przy pełnym ekwipunku - chwilowy tryb sprzedaży (punkt 10).
   **Brak miejsca na rynku** (komunikat o limicie wystawionych): "Brak miejsca na rynku (limit wystawionych) - snajpię dalej; wystawię
   znowu po sprzedaży albo za 2 min." - snajper pracuje dalej, a wystawianie wraca, gdy serwer napisze o sprzedaży Twojej oferty
   (zwolniło się miejsce) albo po 2 minutach.
   Restart od razu także, gdy okno nie zmienia się przez 10 s, rynek się nie otworzy (5 razy z rzędu = stop) albo zatnie się okno
   potwierdzenia - po takim restarcie też najpierw wystawianie.
8. **Anty-AFK** co **4 minuty**: zamyka rynek, **3 skoki**, wraca na rynek.
9. **Analiza co 12 minut** (wszystkie przedmioty) i **ponowna analiza po zakupach** (niżej) - auto rynek stoi na czas analizy i sam wraca.
10. **Menu gry** (Esc) jest zamykane jak "Powrót do gry": od razu, gdy okno gry nie jest aktywne, a po 5 s, gdy otworzysz je sam przy
    aktywnym oknie. Gra nie pauzuje po przełączeniu na inną aplikację, póki auto rynek działa (po stopie wraca Twoje ustawienie).
    Gdy otworzysz inne okno (czat, ekwipunek, panel) - auto rynek czeka i wraca po jego zamknięciu. Prawy Shift przy otwartym rynku
    zamyka rynek i otwiera panel. Rozłączenie = stop. **Pełny ekwipunek** = "Pełny ekwipunek - przełączam na wystawianie": rynek
    zamknięty, wystawianie jak w trybie "Tylko sprzedaż", a przy 3 wolnych miejscach (albo 1, gdy nic więcej się nie wystawi, np.
    rynek pełny) - "Jest miejsce w ekwipunku - wracam do snajpera.". Z wyłączonym "Wystawianie" tylko czeka na miejsce.

**Tryb "Tylko sprzedaż"** (przycisk "Tryb: snajper" -> "Tryb: tylko sprzedaż"; działa też w trakcie pracy): bot nie kupuje i nie
otwiera rynku. Wystawia cały czas, gdy jest miejsce na rynku - kupione przez snajpera i wszystkie z "Wyst.: TAK" (przełącznik
"Wystawianie" jest wtedy zawsze WŁ): po każdej rundzie zaraz kolejna, nowe przedmioty w ekwipunku - w ciągu 10 s. Brak miejsca na
rynku - wystawia od razu po sprzedaży Twojej oferty, bez sprzedaży próbuje co 2 min. Anty-AFK i analiza co 12 minut działają dalej.
Rynek otwarty przez Ciebie nie jest zamykany - bot czeka, aż go zamkniesz.

**Ponowna analiza po zakupach:** gdy snajper kupi ten sam przedmiot **3 razy w ciągu 10 minut** (od jego ostatniej analizy), ten jeden
przedmiot jest analizowany od razu (najwcześniej 30 s po poprzedniej analizie): "[Analiza] Anarchiczny hełm: 3 zakupy w ciągu 10 min -
ponowna analiza." Działa z autopilotem i bez niego (przedmioty z "Auto: NIE" - bez ponownej analizy). Liczba zakupów i okno czasu:
Ustawienia ("Ponowna analiza po tylu zakupach", "Ponowna analiza: zakupy w ciągu (min)").

**Zakładka Auto rynek:** Start/Stop, "Tryb: snajper / tylko sprzedaż", Wystawianie, Anty-AFK, "Analiza: na starcie i co 12 min" i import
z BK. Pod spodem stan ("otwiera rynek", "snajpi", "kupuje", "wystawia", "tylko sprzedaż", "wystawia (pełny ekwipunek)", "anty-AFK
(skoki)", "restart rynku",
"czeka (otwarte inne okno)", "wstrzymany (analiza)"), liczniki, za ile wystawianie (restart) / skoki / analiza ("brak miejsca na rynku -
wystawianie po sprzedaży albo za X"), szybkość analizy, ile przedmiotów jest kupowanych i ile **bez marży** (nie kupuje), lista
przedmiotów z cenami i przełącznikami **"Kup: TAK/NIE"** i **"Wyst.: TAK / kupione"** oraz dziennik.

**Ustawienia (grupa "Auto rynek"):** komenda rynku, odświeżanie (ms), restart co (s, 0 = nie; wtedy wystawianie co minutę), restart po
zacięciu (s), przerwa przy restarcie (ms), anty-AFK co (min) i liczba skoków, skup za sztukę (tryb Unit), "Snajper: najwyżej % salda na
1 zakup" (70), podświetlanie ofert do kupienia (WŁ/WYŁ), sloty odświeżania / sortowania / potwierdzenia, nazwa przycisku potwierdzenia,
komenda wystawiania. Do tego "Analiza wszystkich co (min)" (12) i ustawienia ponownej analizy.

## Import z BK-Rynku
Mod szuka profili BK w `config/BK-Mods/BK-Rynek/auto_market_configs/*.json` (BK 3.0) i listy `servers[].prices` w głównym configu
(`config/BK-Mods/BK-Rynek/bkrynek-config.json` albo plik z `selected.txt`), a także w starym miejscu `config/bkrynek-config/`.
Bierze **aktywny profil** (`defaultProfile`; gdy jest w kilku plikach - najnowszy plik), a bez niego profil z Anarchii albo najnowszy.
Z profilu przechodzą też ustawienia: komenda rynku, sloty odświeżania / sortowania / potwierdzenia, nazwa potwierdzenia, wzór ceny
(`lore_regex`, `lore_seller_regex`), tryb Unit/Sum, komenda wystawiania i przerwa przy restarcie. Pola przedmiotów jak w BK.

## Panel (GUI)
- **Prawy Shift** otwiera/zamyka panel, **F9** = start/stop (analiza + auto rynek), **F8** = pomiń analizę, **L** w oknie rynku =
  dodaj przedmiot spod myszy do listy. Klawisze zmienisz
  w zakładce **Ustawienia** (kliknij przycisk i naciśnij klawisz; Delete/Backspace = brak, Esc = anuluj) albo w Sterowaniu Minecrafta.
  W trakcie analizy panel się nie otwiera - najpierw ją pomiń (F8).
- Zakładki: **Przedmioty**, **Auto rynek**, **Autopilot**, **Historia**, **Ustawienia**.
- **Przedmioty**: lista przedmiotów, wyszukiwarka, **"Analizuj"** (ręczna analiza - z wpisaną frazą tylko pasujące; bez snajpera)
  i "Stop". W wierszu: nazwa, trend, **Sprzedaż ($)**, **Skup ($)** (ze wzoru, tylko odczyt), pole **Marża ($)** (za sztukę; puste =
  domyślna) i **Wykres**. Pod wykresem pasek przedmiotu: "Auto: TAK/NIE" (stała cena sprzedaży), "Min. sprzedaż", "Max skup", wzór
  skupu tego przedmiotu, np. "skup 32803$/szt. (34333$/szt. x 0.97 - marża 500$)" albo "skup 0 (brak marży - ...)", i podpowiedź
  marży z przyciskiem "Zastosuj".
- **Autopilot**: "Włącz/Wyłącz autopilota" (budżet dzienny), następna analiza, zakupy dnia, niesprzedane zakupy, saldo i dziennik
  (zielone = sprzedaż, czerwone = analiza nieudana).
- **Historia**: zakupy i sprzedaże ("Zakupy" / "Wg przedmiotów"), "Wyczyść historię".
- **Ustawienia**: klawisze, **szybkość analizy** (Bezpieczna / Szybka / Maksymalna), min. ofert do ceny, **marża domyślna**, **podatek**,
  % salda na zakup, ulepszenia %, komponenty, historia cen, obserwacja rynku, analiza co / ponowna analiza, autopilot (budżet),
  auto rynek.
- Pliki: ustawienia `config/rynek-analiza.json`, lista `config/rynek-analiza-przedmioty.json`, autopilot `config/rynek-analiza-autopilot.json`,
  historia zakupów `config/rynek-analiza-historia.json`, historia cen `config/rynek-analiza-history.json`,
  diagnostyka `config/rynek-analiza-diagnostyka.txt`.

## Ceny: wzór i monitorowanie
- **Sprzedaż** (za całą wystawkę "ilość sprzedaży" sztuk) = `floor( średnia 3 najtańszych cudzych ofert za sztukę x ilość sprzedaży )`,
  nie niżej niż "Min. sprzedaż". Wpisuje ją analiza (start, co 12 min, po zakupach) i monitorowanie rynku. "Auto: NIE" = stała cena.
- **Skup** (za sztukę) = `sprzedaż za sztukę x 0,97 - marża`, zaokrąglony w dół do 0,01$. Marża = własna przedmiotu, a bez niej marża
  domyślna; bez żadnej marży - skup 0. Wynik <= 0 = skup 0. Do tego "Max skup" (górna granica z paska przedmiotu).
- **Monitorowanie rynku** (tylko gdy działa auto rynek): z ostatniej analizy mod pamięta 8 najtańszych cen za sztukę. Nowa cudza oferta
  w oknie snajpera, tańsza od 3. z nich i nie do kupienia (nie poniżej skupu), wchodzi do średniej - sprzedaż w dół. Każda oferta liczy
  się raz (pamięć 30 min). Kupiona przez snajpera oferta z tej listy wypada - sprzedaż w górę. Twoje oferty się nie liczą. Ceny
  z monitorowania nie są zapisywane - każda analiza zaczyna od nowa.

**Autopilot** (domyślnie wyłączony; zakładka Autopilot): cen nie liczy - pilnuje tylko **budżetu dziennego** ("Autopilot: budżet na
dzień ($, 0 = bez limitu)", 25 000$): po wydaniu budżetu kupowanie wstrzymane do północy ("Kup: NIE" na liście; wraca samo następnego
dnia albo od razu po podniesieniu budżetu albo wyłączeniu autopilota). Niesprzedane zakupy nie obniżają skupu (od 0.5.1) - ceny po
zakupach poprawia ponowna analiza (3 zakupy w 10 min).

## Historia zakupów i sprzedaży
Każdy zakup snajpera (cena, sztuki) zapisuje się zawsze. **Sprzedaż** - gdy serwer napisze o sprzedaży Twojej oferty. Każdy komunikat
o sprzedaży to osobna sprzedaż (dwa komunikaty o tej samej sprzedaży z tą samą kwotą, np. "został kupiony" i "dodane do konta",
w ciągu 2 s = jedna). Przedmiot rozpoznawany po kolei:
1. po **wystawkach auto rynku** (każda zapamiętana z ceną i sztukami na 8 dni): kwota = cena wystawki x 0,97 albo sama cena,
   najstarsza pasująca wystawka; gdy serwer podał nazwę - tylko wystawki tego przedmiotu,
2. po obecnej i niedawnych cenach sprzedaży z listy,
3. po nazwie przedmiotu w komunikacie (gdy pasuje jeden przedmiot).
Gdy kwota pasuje do dwóch różnych przedmiotów i nic tego nie rozstrzyga - sprzedaż bez przypisania (nie zgaduje). Sprzedaż trafia do
najtańszego niesprzedanego zakupu tego przedmiotu (przy równej cenie - najstarszego; od 0.5.3, patrz wystawianie bez straty):
wpis z listy, potem ta sama nazwa, potem nazwa bez dopisku w [ ]. Zysk po podatku
3%. Widok "Wg przedmiotów": sprzedane z kupionych, wydane, zysk, średni czas do sprzedaży. "Wyczyść historię" z kopią.

## Cena sprzedaży (analiza)
Dla każdego przedmiotu: `/ah search <nazwa>` -> sortowanie "Najniższej ceny" -> cena za sztukę z każdej pasującej oferty ->
`sprzedaż = floor( średnia 3 najtańszych za sztukę x ilość sprzedaży )`. Mniej ofert niż "Min. ofert do ceny" (3) = bez zmian.
Zapasowe frazy, gdy po pełnej nazwie nie ma żadnego przedmiotu tego id ("Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny").
Przedmioty niestackowalne - skan do 3 ofert; stackowalne - wszystkie strony.

## Co dzieje się podczas analizy
1. Auto rynek wstrzymany, jego okno rynku zamknięte, **przerwa** (domyślnie 3 s), żeby spóźnione odpowiedzi serwera nie mieszały się
   z analizą. Analiza na starcie rusza bez tej przerwy (auto rynek jeszcze nic nie kliknął).
2. Analiza przedmiotów (wyżej) - po cichu, komunikaty w logu gry. Nad rynkiem pasek z przedmiotem, obok okna rynku przycisk
   **"Pomiń analizę"**.
3. Rynek zamknięty, **przerwa** (3 s; po "Pomiń analizę" najwyżej 1 s), lista zapisana, podsumowanie na czacie i auto rynek rusza od
   nowa. Długość przerwy: Ustawienia, "Przerwa przed i po analizie, gdy działa auto rynek (s)" (0-15 s).

**Przedmioty o tej samej nazwie** (np. 5 x "Zaklęta książka") są rozróżniane po pierwszej linii lore (potem CMD / enchanty).
Własna fraza wyszukiwania (`search` w `config/rynek-analiza.json`) wyłącza zapasowe frazy dla tego przedmiotu.

## Cena skupu - przykłady
`skup za sztukę = sprzedaż za sztukę x 0,97 - marża`

| przedmiot | sprzedaż | marża | skup |
|---|---|---|---|
| Anarchiczny hełm II | 34 333$ | 500$ | 34 333 x 0,97 - 500 = **32 803$** |
| złote jabłko (64 szt. za 3 000$) | 46,875$/szt. | 5$ | 46,875 x 0,97 - 5 = **40,46$/szt.** |
| tani przedmiot | 100$ | 120$ | 97 - 120 < 0 = **0 (nie kupuje)** |
| bez marży (marża domyślna 0) | 20 000$ | - | **0 (nie kupuje)** |

W trybie Sum skup na liście = skup za sztukę x "ilość kupna". Marżę wpisujesz w polu **Marża ($)** (zapis ok. 1 s po ostatnim znaku;
status "Zapisano - Hełm: marża 500$ -> skup 32803$"). Puste pole = marża domyślna.

## Szybkość analizy
Ustawienia, przycisk "Szybkość analizy". Bot zawsze czeka na odpowiedź serwera (okno przestało się zmieniać); tryb ustala, jak długo:

| tryb | strona wczytana po (ticki bez zmian) | przerwa między kliknięciami | przed kolejnym "/ah search" |
|---|---|---|---|
| Bezpieczna | 3 | 4 ticki | 1 s |
| Szybka (domyślna) | 2 | 2 ticki | 0,3 s |
| Maksymalna | 1 | 1 tick | 0,1 s |

Przy dużym pingu albo lagach serwera wybierz Bezpieczną.

## Dopasowanie i cena - jak w BK
- Najpierw id przedmiotu, potem zakazane lore, potem nazwa bez kolorów (bez wielkości liter, czcionka "small caps" jak zwykłe litery).
  Gdy nazwa na liście ma kolor (np. `§4Anarchiczny hełm`), pierwszy kolor nazwy oferty i jej pogrubienie/podkreślenie też muszą się
  zgadzać (`§f` = bez wymagania koloru).
- Wymagane lore: każda część (rozdzielone `;` albo nową linią) musi być w opisie przedmiotu; zakres `Dodatkowe obrażenia 0-100%`
  pasuje do liczby z zakresu; linie z ceną, sprzedawcą, "kliknij", wygaśnięciem są pomijane.
- Enchanty z mapy `enchantments` (min. poziom; z przedmiotu albo z opisu), zakazane enchanty, CMD, efekty mikstur. Z opisu liczy się
  tylko pełna nazwa: "Ochrona przed ogniem V" to nie "Ochrona", "Morskie szczęście III" to nie "Szczęście".
- **Ulepszenia %** ("Pomijaj ulepszenia %", domyślnie TAK): analiza liczy 3 najtańsze przedmioty tej nazwy niezależnie od linii z %.
  **Snajper kupuje dokładnie wg listy** (żeby kupował każdy %, wpisz zakres `Dodatkowe obrażenia 0-100%`).
- Cena oferty: wzór z profilu (`lore_regex`), pomija linie "kosztuje ... sztuk" i sprzedawcę; liczby "20 370", "3,289", "1.5k", "2mld".
- Diagnostyka przy braku ofert: ile przedmiotów tego id było, ile odrzuconych i dlaczego, pełny opis w `config/rynek-analiza-diagnostyka.txt`.

## Podgląd na rynku i znaczek
- W trakcie analizy: pasek nad rynkiem (przedmiot, kolejka, strona, liczba ofert), **żółte** ramki na 3 najtańszych ofertach,
  przycisk "Pomiń analizę" obok okna. Przy snajpieniu: **zielone** ramki ofert do kupienia (poniżej skupu).
- Znaczek "Rynek Analiza: działa" przy oknie rynku (stan auto rynku, co mod odczytał z okna, "ceny monitorowane"; w trakcie analizy
  "analiza auto rynku")
  i jedna linia na ekranie gry, gdy auto rynek pracuje, a rynek jest zamknięty ("analiza wszystkich za 8 min (25)"). Chowa się pod F1.
  Wyłączenie: Ustawienia, "Znaczek 'działa'". Podgląd analizy: "Podgląd na rynku".

## Komendy (opcjonalnie)
`/analiza auto` (start/stop), `/analiza pomin` (pomiń analizę), `/analiza start [fragment]` i `/analiza stop` (ręczna analiza),
`/analiza lista`.

## Do sprawdzenia w grze (0.5.3)
1. Wystawianie bez straty: ustaw w Ustawieniach "Wystawianie: zysk co najmniej" na dużą kwotę (np. 100000) - przy wystawianiu
   kupionych: "Czeka na wyższą cenę: ..." i przedmioty zostają w ekwipunku; wróć do 0 - wystawią się przy następnym restarcie rynku.
2. Podpowiedź marży: rozwiń wykres przedmiotu z ceną sprzedaży - pod wzorem skupu "Podpowiedź: marża ..."; najedź na "Zastosuj"
   (dymek z uzasadnieniem), kliknij - pole Marża i Skup się zmieniają, przycisk zmienia się na "Ustawiona".
3. Na rynku najedź na ofertę przedmiotu spoza listy i naciśnij L: "Dodano do listy: ...". W panelu nowy wiersz z ceną 0; po najbliższej
   analizie ma cenę sprzedaży, a po wpisaniu marży - skup. Drugi raz L: "już jest na liście". L poza rynkiem dalej otwiera Postępy.
4. Kilka zakupów tego samego przedmiotu: skup się nie obniża (bez komunikatu "skup obniżony"), po 3 zakupach w 10 min ponowna analiza.
5. Wpisz marżę jednemu przedmiotowi (np. 500) - kolumna Skup od razu pokazuje sprzedaż x 0,97 - 500, a pasek pod wykresem wzór.
   Bez marży (i z marżą domyślną 0) przedmiot ma skup 0 i nie jest kupowany.
6. Po analizie: na liście zmienia się sprzedaż, a skup idzie za nią ze wzoru ("Analiza zakończona (zmienione ceny - sprzedaż: x,
   skup: y)").
7. W trakcie snajpienia: gdy ktoś wystawi tańszą ofertę (nie do kupienia), w dzienniku "monitorowanie rynku (nowa oferta ...) -
   sprzedaż A$ -> B$". Po zakupie najtańszej - cena w górę.
8. Wystawianie kilku różnych przedmiotów: kolejność od najtańszej wystawki.
9. Praca w tle: Esc przy działającym auto rynku - po 5 s menu się zamyka; przełącz się na inną aplikację - gra nie pauzuje, bot
   dalej kupuje i wystawia.
10. Historia: sprzedaż przedmiotu wystawionego przed zmianą ceny przez analizę ma teraz cenę w "Historii". **Nie znam dokładnej treści
   komunikatu o sprzedaży na Anarchii** - gdy sprzedaż dalej nie ma ceny, wklej mi ten komunikat z czatu.
11. Szybkość analizy: Bezpieczna / Szybka / Maksymalna.

## Historia cen
`config/rynek-analiza-history.json`, do ok. 1000 punktów na przedmiot. Źródła: analizy (domyślnie jedyne na wykresie) i obserwacje okien
rynku - zapisywane tylko wtedy, gdy okno pokazuje prawdziwe minimum (jedna strona albo strona 1 od najtańszych), więc okna snajpera
z sortowaniem "Najnowsze" zwykle nie są zapisywane. Obserwacje pokażesz na wykresie przełącznikiem "Wykresy: też obserwacje rynku".
