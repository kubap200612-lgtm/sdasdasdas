package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ustawienia w config/rynek-analiza.json (własne frazy wyszukiwania, ostatnie wyniki analizy, opcje).
 * Pola ze starszych wersji (np. dawne marże "margins" i "defaultMargin", progi skupu) są przy wczytywaniu pomijane.
 */
public final class AnalizaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Klucz = "nazwa bez kolorów|material" (małe litery; przy powtórzonych nazwach z dopiskiem), wartość = fraza po "/ah search ". */
    public Map<String, String> search = new LinkedHashMap<>();
    /** Ostatni pomiar z analizy każdego przedmiotu (klucz jak wyżej). */
    public Map<String, ScanRecord> lastScan = new LinkedHashMap<>();
    /** Wykresy: oprócz skanów z analizy pokazuj też zaufane pomiary z obserwacji rynku (gdy pracuje auto rynek). */
    public boolean chartObserved = false;
    /** Podatek od sprzedaży (mnożnik) - we wzorze skupu: skup = sprzedaż x taxFactor - marża. */
    public double taxFactor = 0.97;
    /**
     * Marża domyślna ($ za sztukę) dla przedmiotów bez własnej marży (pole "Marża" w liście). 0 = takich przedmiotów
     * snajper nie kupuje (skup 0).
     */
    public double defaultMinMargin = 0;
    /**
     * Klawisz "dodaj przedmiot" w oknie rynku (kod GLFW; domyślnie L = 76; -1 = brak). Celowo nie KeyBinding Minecrafta:
     * L to też klawisz Postępów, a dwa przypisania do jednego klawisza psułyby jedno z nich.
     */
    public int addItemKey = 76;
    public int listingsUsed = 3;
    /** Minimalna liczba pasujących ofert, żeby policzyć cenę (1-3). */
    public int minListings = 3;
    public int maxPages = 20;
    /** Szybkość analizy: 0 = bezpieczna, 1 = szybka (domyślnie), 2 = maksymalna. */
    public int speed = 1;
    /** Wymagaj tej samej liczby komponentów co we wzorcu z listy (BK tego nie sprawdzał; włącz tylko, gdy coś się myli). */
    public boolean strictComponents = false;
    /**
     * Analiza i autopilot pomijają wymagane linie lore przedmiotu z wartością % (np. "Dodatkowe obrażenia 14%",
     * "Odporność na miecze 2%"): liczą najtańsze przedmioty tej nazwy niezależnie od ulepszeń. Snajper kupuje dalej
     * dokładnie wg listy.
     */
    public boolean ignorePercentLore = true;
    /** Zapisuj historię cen (z analiz i z obserwacji rynku) do wykresów. */
    public boolean historyEnabled = true;
    /** Obserwuj okna rynku otwierane przez auto rynek (bez klikania) i zapisuj z nich ceny. */
    public boolean passiveObserve = true;
    /** Minimalny odstęp między zapisami z obserwacji dla jednego przedmiotu (sekundy). */
    public int historyIntervalSec = 60;
    /** Czy po analizie włączyć z powrotem auto rynek, jeśli działał przed startem. */
    public boolean resumeBk = true;
    /** Przerwa (s) po zatrzymaniu auto rynku przed analizą i po analizie przed jego wznowieniem - żeby się nie nakładały (0-15). */
    public double bkPauseSec = 3;
    /** Żółte podświetlenie najtańszych ofert + pasek z nazwą i ikoną nad rynkiem. */
    public boolean showOverlay = true;
    /** Znaczek "Rynek Analiza: działa" obok okna rynku (i na ekranie gry, gdy pracuje auto rynek). */
    public boolean showBadge = true;
    /** Ile ticków (20 = 1 s) trzymać stronę z podświetlonymi ofertami, żeby dało się je obejrzeć. 0 = bez pauzy. */
    public int highlightPauseTicks = 10;

    // ----- autopilot cen (patrz Autopilot) -----
    /** Autopilot włączony: dzienny budżet zakupów (ceny liczy wzór). */
    public boolean autopilot = false;
    /** Co ile minut analiza auto rynku sprawdza wszystkie przedmioty z listy (auto rynek zatrzymuje się na czas skanu). */
    public int apIntervalMin = 12;
    /** Tyle zakupów (osobnych transakcji) tego samego przedmiotu w ciągu apRescanMin minut = od razu ponowna analiza (0 = nigdy). */
    public int apRescanBuys = 3;
    /** Okno czasu (min) do liczenia zakupów przed ponowną analizą (1-60). */
    public int apRescanMin = 10;
    /**
     * Snajper nie kupuje oferty droższej niż tyle % salda konta (saldo z tablicy wyników), żeby jeden zakup nie zabrał
     * prawie wszystkich pieniędzy. 0 = bez limitu (tylko saldo). 0-100.
     */
    public double buyBalancePct = 70;

    // ----- auto rynek (AutoMarket): snajper, wystawianie, anty-AFK -----
    /** Komenda rynku bez "/" (z profilu BK: market_command). */
    public String amCommand = "ah";
    /** Slot odświeżania listy ofert (PPM; na Anarchii filtr w lewym górnym rogu). */
    public int amRefreshSlot = 0;
    /** Slot przycisku sortowania (na Anarchii 53) i słowo opcji, którą snajper ustawia ("najnowsz" = Najnowsze). */
    public int amSortSlot = 53;
    public String amSortKeyword = "najnowsz";
    /** Slot przycisku "Potwierdź" w oknie zakupu, gdy nie da się go znaleźć po nazwie (z profilu BK: confirm_slot). */
    public int amConfirmSlot = 11;
    /** Nazwa przycisku potwierdzenia (pusta = szukanie po słowach "potwierdź", "kup", "confirm"). */
    public String amConfirmName = "";
    /** Komenda wystawiania ({quantity} = ilość, {price} = cena całej wystawki). */
    public String amSellCommand = "wystaw {quantity} {price}";
    /** Wzór ceny w lore oferty (grupa 1 = liczba) i wzór linii sprzedawcy - z profilu BK (lore_regex, lore_seller_regex). */
    public String priceRegex = ItemMatcher.DEFAULT_PRICE_REGEX;
    public String sellerRegex = "";
    /** Tryb "Unit" (cena skupu za sztukę) dla całej listy; false = "Sum" (cena całej oferty o ilości kupna). */
    public boolean unitModeGlobal = true;
    /**
     * Tryb auto rynku: true = snajper (kupuje, wystawia przy każdym restarcie rynku), false = "Tylko sprzedaż" (rynek
     * zamknięty, co restartSec wystawia przedmioty z ekwipunku). Wystawianie zakupionych i skakanie przeciw AFK - osobno.
     */
    public boolean amBuy = true;
    public boolean amSell = true;
    /**
     * Wystawianie bez straty (od 0.5.3): przed wystawką mod sprawdza, za ile kupił te sztuki (historia zakupów). Gdy cena
     * sprzedaży po podatku dałaby mniej niż koszt + amSellMinProfit za sztukę, sztuki czekają w ekwipunku na wyższą cenę.
     */
    public boolean amSellNoLoss = true;
    /** Minimalny zysk za sztukę przy wystawianiu bez straty ($; 0 = wystarczy nie stracić). */
    public double amSellMinProfit = 0;
    /** Po tylu godzinach od zakupu wystawia mimo straty (0 = czeka bez limitu, aż cena wzrośnie). */
    public double amSellMaxWaitH = 0;
    public boolean amAntiAfk = true;
    /** Analiza cen na starcie auto rynku i potem co apIntervalMin minut (oraz ponowna po kilku zakupach tego samego). */
    public boolean amAnalysis = true;
    /** Podświetlanie ofert, które snajper kupi (cena poniżej skupu) na zielono. */
    public boolean amHighlight = true;
    /** Co ile ms snajper odświeża listę ofert (100-5000; BK robił to jeszcze częściej). */
    public int amRefreshMs = 200;
    /** Anty-AFK: co ile minut przerwa na skoki (1-30) i ile skoków (1-10). */
    public int amAfkMin = 4;
    public int amAfkJumps = 3;
    /** Co ile sekund rynek jest zamykany i otwierany od nowa w trakcie snajpienia (0 = nigdy; 15-3600). */
    public int amRestartSec = 60;
    /** Po tylu sekundach bez żadnej zmiany w oknie rynku - restart (zacięcie; 5-60). */
    public int amStuckSec = 10;
    /** Przerwa między zamknięciem a ponownym otwarciem rynku przy restarcie (ms; z profilu BK: restart_delay). */
    public int amRestartPauseMs = 1500;
    /** Lista przedmiotów została już zaimportowana z BK (albo nie było czego importować) - bez ponownego importu przy starcie. */
    public boolean bkImportDone = false;

    /** Wersja ustawień (jednorazowe poprawki starych wartości przy wczytaniu). */
    public int configVersion = 0;
    static final int CONFIG_VERSION = 3;

    /** Ile autopilot może wydać na zakupy w ciągu dnia ($). */
    public double apBudget = 25000;

    /** Pomiar z analizy: średnia 3 najtańszych (za sztukę), najtańsza oferta, liczba ofert, czas. */
    public static final class ScanRecord {
        public double avg3;
        public double cheapest;
        public int offers;
        public long t;

        public ScanRecord() {}

        public ScanRecord(double avg3, double cheapest, int offers, long t) {
            this.avg3 = avg3;
            this.cheapest = cheapest;
            this.offers = offers;
            this.t = t;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza.json");
    }

    public static AnalizaConfig load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                AnalizaConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), AnalizaConfig.class);
                if (c != null) {
                    if (c.search == null) c.search = new LinkedHashMap<>();
                    if (c.lastScan == null) c.lastScan = new LinkedHashMap<>();
                    c.fix();
                    if (c.migrate()) c.save();
                    return c;
                }
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać configu: " + e);
        }
        AnalizaConfig c = new AnalizaConfig();
        c.configVersion = CONFIG_VERSION;
        c.save();
        return c;
    }

    /**
     * Jednorazowe poprawki starych ustawień. Wersja 3: autopilot skanuje wszystkie przedmioty co 12 min (dawne ustawienia
     * kolejki i marż autopilota znikają). Zwraca true, gdy coś zmieniono.
     */
    boolean migrate() {
        if (configVersion >= CONFIG_VERSION) return false;
        if (configVersion < 3) apIntervalMin = 12;
        configVersion = CONFIG_VERSION;
        return true;
    }

    /** Wartości z pliku: brakujące albo uszkodzone zastępowane domyślnymi, liczby przycięte do zakresów. */
    void fix() {
        taxFactor = taxFactor > 0 && taxFactor <= 1 ? taxFactor : 0.97;
        defaultMinMargin = clamp(defaultMinMargin, 0, 1e12, 0);
        speed = (int) clamp(speed, 0, 2, 1);
        buyBalancePct = clamp(buyBalancePct, 0, 100, 70);
        amSellMinProfit = clamp(amSellMinProfit, 0, 1e12, 0);
        amSellMaxWaitH = clamp(amSellMaxWaitH, 0, 24 * 30, 0);
        if (amCommand == null || amCommand.isBlank()) amCommand = "ah";
        amCommand = amCommand.trim().startsWith("/") ? amCommand.trim().substring(1) : amCommand.trim();
        if (amSortKeyword == null || amSortKeyword.isBlank()) amSortKeyword = "najnowsz";
        if (amConfirmName == null) amConfirmName = "";
        if (amSellCommand == null || !amSellCommand.contains("{price}")) amSellCommand = "wystaw {quantity} {price}";
        if (priceRegex == null || priceRegex.isBlank()) priceRegex = ItemMatcher.DEFAULT_PRICE_REGEX;
        if (sellerRegex == null) sellerRegex = "";
        amRefreshSlot = (int) clamp(amRefreshSlot, 0, 53, 0);
        amSortSlot = (int) clamp(amSortSlot, 0, 53, 53);
        amConfirmSlot = (int) clamp(amConfirmSlot, 0, 53, 11);
        amRefreshMs = (int) clamp(amRefreshMs, 100, 5000, 200);
        apRescanMin = (int) clamp(apRescanMin, 1, 60, 10);
        amAfkMin = (int) clamp(amAfkMin, 1, 30, 4);
        amAfkJumps = (int) clamp(amAfkJumps, 1, 10, 3);
        amRestartSec = amRestartSec <= 0 ? 0 : (int) clamp(amRestartSec, 15, 3600, 60);
        amStuckSec = (int) clamp(amStuckSec, 5, 60, 10);
        amRestartPauseMs = (int) clamp(amRestartPauseMs, 200, 10_000, 1500);
    }

    /** Wartość z pliku albo z pola przycięta do min-max; nie liczba = domyślna. */
    static double clamp(double v, double min, double max, double def) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return def;
        return Math.max(min, Math.min(max, v));
    }

    public void save() {
        try {
            Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać configu: " + e);
        }
    }

    /** Przenosi ustawienia do silnika analizy. */
    public void applyToAnalyzer() {
        MarketAnalyzer.TAX_FACTOR = taxFactor;
        MarketAnalyzer.LISTINGS_USED = Math.max(1, listingsUsed);
        MarketAnalyzer.MIN_LISTINGS = Math.max(1, Math.min(Math.max(1, listingsUsed), minListings));
        MarketAnalyzer.MAX_PAGES = Math.max(1, maxPages);
        switch (speed) {
            case 0 -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 4; MarketAnalyzer.STABLE_TICKS = 3; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 20; }
            case 2 -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 1; MarketAnalyzer.STABLE_TICKS = 1; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 2; }
            default -> { MarketAnalyzer.MIN_CLICK_GAP_TICKS = 2; MarketAnalyzer.STABLE_TICKS = 2; MarketAnalyzer.BETWEEN_TARGETS_TICKS = 6; }
        }
        MarketAnalyzer.HIGHLIGHT_PAUSE_TICKS = Math.max(0, highlightPauseTicks);
        PriceHistory.ENABLED = historyEnabled;
        PriceHistory.PASSIVE_INTERVAL_SEC = Math.max(5, historyIntervalSec);
        AnalysisOverlay.enabled = showOverlay;
    }
}
