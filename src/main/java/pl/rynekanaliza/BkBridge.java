package pl.rynekanaliza;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lista przedmiotów moda (ItemStore, config/rynek-analiza-przedmioty.json) jako cele analizy, autopilota, obserwacji
 * i auto rynku. Dawniej most do BK-Rynku - nazwa została, ale BK nie jest już potrzebny: lista jest własna (pierwszy raz
 * importowana z BK), dopasowanie i odczyt ceny robi ItemMatcher (przeniesione z BK), kupuje i wystawia AutoMarket.
 *
 * Pola przedmiotu jak w BK: maxPrice = cena skupu (za sztukę; w trybie "Sum" za całą ofertę o ilości kupna),
 * sellPrice = cena sprzedaży CAŁEJ wystawki, sellCount = ilość sztuk w wystawce ("/wystaw {quantity} {price}").
 */
public final class BkBridge {
    private BkBridge() {}

    private static ItemStore store;

    /** Lista przedmiotów (wczytywana przy pierwszym użyciu). */
    public static synchronized ItemStore store() {
        if (store == null) store = ItemStore.load();
        return store;
    }

    /** Tylko start moda i testy: podmiana listy. */
    static synchronized void setStore(ItemStore s) {
        store = s;
    }

    /** Lista jest zawsze dostępna (własna). */
    public static boolean available() {
        return store() != null;
    }

    public static String error() {
        return "";
    }

    // ---------- ulepszenia % (np. "Dodatkowe obrażenia 14%") ----------
    /** Linia z wartością procentową: liczba i zaraz po niej % (np. "Dodatkowe obrażenia 14%", "Odporność na miecze +2 %"). */
    static final Pattern PERCENT_VALUE = Pattern.compile("\\d\\s*%");

    /**
     * Wymagane lore przedmiotu bez linii z ulepszeniami % (części rozdzielone ";" albo nową linią, jak w BK).
     * Bez takich linii zwraca ten sam tekst.
     */
    static String withoutPercentLore(String lore) {
        if (lore == null) return "";
        StringBuilder out = new StringBuilder();
        boolean cut = false;
        for (String part : lore.split("[;\\n]+")) {
            if (part.isBlank()) continue;
            if (PERCENT_VALUE.matcher(strip(part)).find()) {
                cut = true;
                continue;
            }
            if (out.length() > 0) out.append(';');
            out.append(part);
        }
        return cut ? out.toString() : lore;
    }

    /** Cena całej oferty z lore (jak odczytywał ją BK; pomija m.in. linie "kosztuje ... sztuk"); NaN, gdy brak. */
    static double extractPrice(ItemStack st) {
        double v = ItemMatcher.extractPrice(ItemMatcher.loreLines(st));
        return v > 0 ? v : Double.NaN;
    }

    /** To samo (obserwacja rynku). */
    static double extractPriceObserver(ItemStack st) {
        return extractPrice(st);
    }

    // ---------- sterowanie auto rynkiem (dawniej BK) ----------
    /** Czy auto rynek pracuje (włączony i nie wstrzymany na analizę). */
    public static boolean isRunning() {
        return AutoMarket.running();
    }

    /** Wstrzymanie auto rynku na czas analizy. */
    public static void stopBk() {
        AutoMarket.pause();
    }

    /** Wznowienie auto rynku po analizie. */
    public static void startBk() {
        AutoMarket.resume();
    }

    /** Zapis cen i listy na dysk (snajper i obserwacja od razu biorą nowe ceny). */
    public static void commit() {
        ItemStore s = store();
        if (s != null) s.save();
    }

    /** Wersja listy (zmienia się po każdej zmianie listy albo cen). */
    public static long priceVersion() {
        ItemStore s = store();
        return s == null ? -1 : s.version();
    }

    /** Wzór ceny z ustawień (lore_regex z profilu BK; grupa 1 = liczba) albo null. */
    public static Pattern priceRegex() {
        AnalizaConfig cfg = RynekAnalizaMod.config();
        String rx = cfg == null ? null : cfg.priceRegex;
        if (rx == null || rx.isBlank()) return null;
        try {
            return Pattern.compile(rx);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- cele analizy ----------
    public static List<BkTarget> targets(String filter, AnalizaConfig cfg) {
        ItemStore s = store();
        List<BkTarget> all = new ArrayList<>();
        if (s != null) for (MarketItem it : new ArrayList<>(s.items())) all.add(new BkTarget(it, cfg));
        assignKeys(all); // klucze liczone na całej liście, żeby filtr nie zmieniał kluczy
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<BkTarget> out = new ArrayList<>();
        for (BkTarget t : all) {
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) out.add(t);
        }
        return out;
    }

    /**
     * Rozróżnia przedmioty o tej samej nazwie i id (np. 5 x "Zaklęta książka", 2 x "Diamentowy miecz" różniące się lore/CMD).
     * Przedmiot z unikalną nazwą zostaje przy kluczu "nazwa|material" (jego historia i wykres się nie zmieniają);
     * przedmioty z powtórzoną nazwą dostają klucz z dopiskiem (lore, CMD, enchanty) i etykietę do pokazania przy nazwie.
     */
    static void assignKeys(List<BkTarget> all) {
        Map<String, List<BkTarget>> groups = new java.util.LinkedHashMap<>();
        for (BkTarget t : all) groups.computeIfAbsent(t.baseKey(), k -> new ArrayList<>()).add(t);
        for (List<BkTarget> g : groups.values()) {
            if (g.size() < 2) continue;
            java.util.Set<String> usedKeys = new java.util.HashSet<>(), usedLabels = new java.util.HashSet<>();
            int n = 0;
            for (BkTarget t : g) {
                n++;
                String lore = t.firstLoreLine();
                Integer cmd = t.item.customModelData;
                String ench = t.item.enchants == null ? "" : t.item.enchants.trim();
                String key = (t.baseKey() + "|" + lore + "|cmd" + (cmd == null ? "" : cmd) + "|" + ench).toLowerCase(Locale.ROOT);
                if (!usedKeys.add(key)) { key = key + "#" + n; usedKeys.add(key); }
                String label = !lore.isEmpty() ? lore : !ench.isEmpty() ? ench : cmd != null ? "CMD " + cmd : "#" + n;
                if (!usedLabels.add(label)) { label = label + " #" + n; usedLabels.add(label); }
                t.key = key;
                t.label = label;
            }
        }
    }

    /** Dopisuje raport diagnostyczny do config/rynek-analiza-diagnostyka.txt (ostatnie raporty na końcu). */
    static java.nio.file.Path writeDiagnostics(String title, List<String> lines) {
        try {
            java.nio.file.Path f = FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-diagnostyka.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("==== ").append(java.time.LocalDateTime.now().withNano(0)).append(" | ").append(title).append(" ====\n");
            for (String l : lines) sb.append(l).append('\n');
            sb.append('\n');
            java.nio.file.Files.writeString(f, sb.toString(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Cele do obserwacji rynku (okna otwierane przez auto rynek). */
    public static List<BkTarget> observerTargets(AnalizaConfig cfg) {
        List<BkTarget> l = targets("", cfg);
        for (BkTarget t : l) t.passive = true;
        return l;
    }

    public static List<String> describe(AnalizaConfig cfg) {
        List<String> lines = new ArrayList<>();
        for (BkTarget t : targets("", cfg)) {
            lines.add(String.format("%s | skup %s$ | sprzedaż %s$ za %d szt.", t.displayName(),
                    AnalizaScreen.fmt(t.currentBuyPrice()), AnalizaScreen.fmt(t.currentSellPrice()), t.sellCount()));
        }
        return lines;
    }

    // ---------- zakupy auto rynku, liczniki sprzedaży z czatu, saldo ----------
    /** Zakup zrobiony przez auto rynek: kupiony stack (kopia), cena całej oferty, liczba sztuk. */
    public static final class Purchase {
        public final ItemStack stack;
        public final double total;
        public final int count;

        Purchase(ItemStack stack, double total, int count) {
            this.stack = stack;
            this.total = total;
            this.count = count;
        }
    }

    private static final List<Purchase> purchases = new ArrayList<>();
    private static int soldCount;
    private static double soldRevenue;

    /** Auto rynek kupił ofertę - trafi do historii i autopilota przy najbliższym pollPurchases(). */
    static synchronized void recordPurchase(ItemStack stack, double total, int count) {
        if (stack == null || stack.isEmpty() || !(total > 0)) return;
        purchases.add(new Purchase(stack.copy(), total, Math.max(1, count)));
        while (purchases.size() > 50) purchases.remove(0);
    }

    /** Nowe zakupy auto rynku od ostatniego wywołania (najstarszy pierwszy). */
    public static synchronized List<Purchase> pollPurchases() {
        if (purchases.isEmpty()) return List.of();
        List<Purchase> out = new ArrayList<>(purchases);
        purchases.clear();
        return out;
    }

    /** Komunikat serwera o sprzedaży naszej wystawki: kwota, tekst (małe litery, bez kolorów - z nazwą przedmiotu), czas. */
    public static final class SaleEvent {
        public final long t;
        public final double amount;
        public String text;

        SaleEvent(long t, double amount, String text) {
            this.t = t;
            this.amount = amount;
            this.text = text;
        }
    }

    /** Drugi komunikat o tej samej sprzedaży (inna treść, ta sama kwota albo kwota po podatku) przychodzi w tylu ms. */
    static final long SALE_DUP_MS = 2000;
    private static final List<SaleEvent> sales = new ArrayList<>();
    private static SaleEvent lastSale;

    /** Komunikat serwera o sprzedaży naszej wystawki (czat): każda sprzedaż osobno (historia), liczniki jak dawniej w BK. */
    static void noteSaleMessage(String text) {
        noteSaleMessage(text, System.currentTimeMillis());
    }

    static void noteSaleMessage(String text, long now) {
        double v = ItemMatcher.saleAmount(text);
        if (!(v > 0)) return;
        String low = BkChatGuard.strip(text).toLowerCase(Locale.ROOT);
        synchronized (BkBridge.class) {
            // ta sama sprzedaż w dwóch komunikatach różnego rodzaju ("Twój przedmiot ... został kupiony" i np. "+... dodane"):
            // jedna sprzedaż, a nazwa z obu (podpowiedź, co się sprzedało); dwa komunikaty "został kupiony" = dwie sprzedaże
            if (lastSale != null && now - lastSale.t <= SALE_DUP_MS && sameAmount(lastSale.amount, v) && sellerNotice(lastSale.text) != sellerNotice(low)) {
                lastSale.text = lastSale.text + " | " + low;
                return;
            }
            soldCount++;
            soldRevenue += v;
            lastSale = new SaleEvent(now, v, low);
            sales.add(lastSale);
            while (sales.size() > 50) sales.remove(0);
        }
        AutoMarket.onSold(); // zwolniło się miejsce na rynku (limit wystawionych)
    }

    /** Ta sama kwota albo jedna to druga po podatku (co do 1$ / 0,1%). */
    static boolean sameAmount(double a, double b) {
        double tax = Pricing.tax(RynekAnalizaMod.config() == null ? new AnalizaConfig() : RynekAnalizaMod.config());
        double tol = Math.max(1, Math.max(a, b) * 0.001);
        return Math.abs(a - b) <= tol || Math.abs(a - b * tax) <= tol || Math.abs(b - a * tax) <= tol;
    }

    /** Komunikat do sprzedającego ("Twój przedmiot ... został kupiony") - inny rodzaj niż np. "+X$ dodane do konta". */
    static boolean sellerNotice(String low) {
        String p = BkChatGuard.plain(low);
        return p.contains("twoj przedmiot") || p.contains("zostal kupiony");
    }

    /** Sprzedaże gotowe do rozliczenia (starsze niż SALE_DUP_MS - drugi komunikat już był), najstarsza pierwsza. */
    public static synchronized List<SaleEvent> pollSales(long now) {
        List<SaleEvent> out = new ArrayList<>();
        for (java.util.Iterator<SaleEvent> it = sales.iterator(); it.hasNext(); ) {
            SaleEvent e = it.next();
            if (now - e.t < SALE_DUP_MS) break;
            out.add(e);
            it.remove();
        }
        return out;
    }

    /** Tylko testy. */
    static synchronized void resetSalesForTests() {
        sales.clear();
        lastSale = null;
        soldCount = 0;
        soldRevenue = 0;
    }

    // zmiany cen na liście (podsumowanie analizy auto rynku)
    private static int sellChanges, buyChanges;

    /** Liczniki zmian cen od resetPriceChanges(): {sprzedaż, skup}. */
    static synchronized int[] priceChanges() {
        return new int[]{sellChanges, buyChanges};
    }

    static synchronized void resetPriceChanges() {
        sellChanges = buyChanges = 0;
    }

    private static synchronized void countChange(boolean sell) {
        if (sell) sellChanges++;
        else buyChanges++;
    }

    /** {liczba sprzedanych, suma przychodu} od startu gry (z komunikatów serwera). */
    public static synchronized double[] salesCounters() {
        return new double[]{soldCount, soldRevenue};
    }

    /** Tylko testy: udawane saldo konta (null = odczyt z tablicy wyników). */
    static Double balanceOverride;

    /** Saldo konta (tablica wyników "Stan konta" albo ostatni komunikat o saldzie); -1, gdy nieznane. */
    public static double walletBalance() {
        if (balanceOverride != null) return balanceOverride;
        return Wallet.balance();
    }

    /** Wcześniejsze ceny wystawek każdego przedmiotu: {czas zmiany ceny, cena całej wystawki}, najwyżej 10 na przedmiot. */
    private static final Map<String, java.util.ArrayDeque<double[]>> oldSellTotals = new java.util.HashMap<>();

    /** Cena wystawki zmieniła się: wystawki po starej cenie mogą jeszcze wisieć na rynku i się sprzedać. */
    static synchronized void noteOldSellTotal(String key, double total, long now) {
        if (key == null || !(total > 0)) return;
        java.util.ArrayDeque<double[]> q = oldSellTotals.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        q.addLast(new double[]{now, total});
        while (q.size() > 10) q.removeFirst();
    }

    /** Wcześniejsze ceny wystawek tego przedmiotu z ostatniej doby (do rozpoznania, co się sprzedało). */
    static synchronized List<Double> recentSellTotals(String key, long now) {
        List<Double> out = new ArrayList<>();
        java.util.ArrayDeque<double[]> q = oldSellTotals.get(key);
        if (q != null) for (double[] x : q) if (now - (long) x[0] < 24 * 3600_000L) out.add(x[1]);
        return out;
    }


    /** Tylko testy: udawany tryb "Unit" całej listy (null = z ustawień). */
    static Boolean unitModeOverride;

    /** Tryb "Unit" całej listy (cena skupu za sztukę); null = nie wiadomo. */
    static Boolean globalUnitMode() {
        if (unitModeOverride != null) return unitModeOverride;
        AnalizaConfig cfg = RynekAnalizaMod.config();
        return cfg == null ? null : cfg.unitModeGlobal;
    }

    /** Tekst bez kodów kolorów - tak jak w poprzednich wersjach (z tego są klucze historii i ustawień przedmiotów). */
    private static String strip(String s) {
        String r = Formatting.strip(s == null ? "" : s);
        return r == null ? "" : r.trim();
    }

    /** Przedmiot z listy jako cel analizy, autopilota i obserwacji. */
    public static final class BkTarget implements MarketAnalyzer.Target {
        final MarketItem item;
        private final AnalizaConfig cfg;
        boolean passive;
        /** Klucz w configu i historii; zwykle "nazwa|material", przy powtórzonych nazwach z dopiskiem (patrz assignKeys). */
        String key;
        /** Dopisek do nazwy, gdy kilka przedmiotów ma tę samą nazwę (np. pierwsza linia lore). */
        String label = "";

        BkTarget(MarketItem item, AnalizaConfig cfg) {
            this.item = item;
            this.cfg = cfg;
            this.key = baseKey();
        }

        /** Przedmiot z listy (do edycji w panelu i do auto rynku). */
        public MarketItem item() {
            return item;
        }

        /** Nazwa bez kolorów (albo id, gdy przedmiot nie ma nazwy). */
        public String baseName() {
            String n = strip(item.name);
            return n.isEmpty() ? (item.material == null ? "" : item.material) : n;
        }

        @Override
        public String displayName() {
            return label.isEmpty() ? baseName() : baseName() + " [" + label + "]";
        }

        String baseKey() {
            return (strip(item.name) + "|" + (item.material == null ? "" : item.material)).toLowerCase(Locale.ROOT);
        }

        /** Pierwsza niepusta linia wymaganego lore (bez kolorów). */
        String firstLoreLine() {
            if (item.lore == null) return "";
            for (String part : item.lore.split("[;\\n]+")) {
                String p = strip(part);
                if (!p.isEmpty()) return p;
            }
            return "";
        }

        @Override
        public ItemStack icon() {
            String id = item.material == null ? "" : item.material.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) return ItemStack.EMPTY;
            Identifier ident = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
            if (ident == null) return ItemStack.EMPTY;
            return new ItemStack(Registries.ITEM.get(ident));
        }

        public String key() {
            return key;
        }

        /** Cena skupu (maxPrice, za sztukę; w trybie "Sum" za całą ofertę). */
        public double currentBuyPrice() {
            return item.maxPrice;
        }

        /** Cena sprzedaży (sellPrice) - za całą wystawkę sellCount() sztuk. */
        public double currentSellPrice() {
            return item.sellPrice;
        }

        /** Czy przedmiot jest wystawiany na rynek. */
        public boolean sellEnabled() {
            return Boolean.TRUE.equals(item.sellEnabled);
        }

        /** Czy przedmiot jest kupowany (brak pola = tak). */
        public boolean buyEnabled() {
            return !Boolean.FALSE.equals(item.buyEnabled);
        }

        /** Włącza / wyłącza kupowanie (autopilot: budżet i limit zakupów, panel). Zapis na dysk robi commit(). */
        public void setBuyEnabled(boolean on) {
            item.buyEnabled = on;
            ItemStore s = store();
            if (s != null) s.touch();
        }

        /** Włącza / wyłącza wystawianie (panel). Zapis na dysk robi commit(). */
        public void setSellEnabled(boolean on) {
            item.sellEnabled = on;
            ItemStore s = store();
            if (s != null) s.touch();
        }

        /**
         * Mnożnik ceny skupu: w trybie "Unit" (przedmiot albo cała lista) cena skupu jest za sztukę - 1;
         * bez niego to cena całej oferty o dokładnie "Ilość kupna" sztukach.
         */
        public int buyFactor() {
            if (Boolean.TRUE.equals(item.unitMode)) return 1;
            Boolean g = globalUnitMode();
            if (g == null || g) return 1;
            return item.buyCount();
        }

        /** Wynik pełnego skanu: najtańsze ceny do monitorowania rynku, autopilot (ponowna analiza po zakupach załatwiona). */
        @Override
        public void onScanned(List<Double> othersPP, int ownOffers) {
            Pricing.onAnalysis(key(), othersPP);
            Autopilot ap = RynekAnalizaMod.autopilot();
            if (ap != null) ap.onScan(this, System.currentTimeMillis());
        }

        /** Auto: NIE (pasek przedmiotu) = analiza i monitorowanie nie zmieniają ceny sprzedaży. */
        @Override
        public boolean autoSell() {
            return Pricing.autoSell(this, RynekAnalizaMod.autopilot());
        }

        /** Cena wystawki ze średniej, nie niżej niż "Min. sprzedaż" z paska przedmiotu. */
        @Override
        public double sellFromAverage(double avgPerPiece) {
            return Pricing.sellTotal(this, avgPerPiece, RynekAnalizaMod.autopilot());
        }

        /** Po wpisaniu ceny sprzedaży: skup ze wzoru (sprzedaż x 0,97 - marża) i opis do logu. */
        @Override
        public String afterSell() {
            AnalizaConfig c = RynekAnalizaMod.config() != null ? RynekAnalizaMod.config() : cfg;
            Autopilot ap = RynekAnalizaMod.autopilot();
            Pricing.sync(this, c, ap);
            return Pricing.describe(this, c, ap);
        }

        @Override
        public void onFailed(String reason) {
            Autopilot ap = RynekAnalizaMod.autopilot();
            if (ap != null) ap.onScanFailed(this, reason, System.currentTimeMillis());
        }

        /** Ile sztuk w jednej wystawce ("Ilość sprzedaży"; brak = 1). */
        @Override
        public int sellCount() {
            return item.sellUnits();
        }

        @Override
        public String searchQuery() {
            String custom = cfg.search.get(key());
            if (custom != null && !custom.isBlank()) return custom;
            String n = strip(item.name);
            if (!n.isEmpty()) return n;
            return (item.material == null ? "" : item.material).replace("minecraft:", "").replace('_', ' ');
        }

        /**
         * Zapasowe frazy wyszukiwania, gdy po pierwszej nie było na rynku ŻADNEGO przedmiotu o tym id
         * (np. "Anarchiczny hełm II" -> "Anarchiczny hełm" -> "Anarchiczny"). Dopasowanie i tak wybiera właściwy przedmiot.
         */
        @Override
        public List<String> fallbackQueries() {
            String custom = cfg.search.get(key());
            if (custom != null && !custom.isBlank()) return List.of();
            String q = searchQuery().trim();
            String[] w = q.split("\\s+");
            List<String> out = new ArrayList<>();
            if (w.length >= 2 && w[w.length - 1].matches("(?i)[ivxl]+|\\d+|\\[?[ivxl]+]?")) {
                out.add(String.join(" ", java.util.Arrays.copyOf(w, w.length - 1)));
            }
            if (w.length >= 2 && !out.contains(w[0]) && w[0].length() >= 3) out.add(w[0]);
            out.remove(q);
            return out;
        }

        /** Id przedmiotu: małe litery, bez "minecraft:". */
        private String wantedMaterial() {
            return ItemMatcher.material(item);
        }

        // liczniki do diagnostyki (dlaczego nic nie pasuje)
        private int seenSameId, rejectedByMatcher, rejectedByComponents;
        private String exampleRejected = "", exampleReason = "";
        private ItemStack firstRejected;
        private final java.util.TreeSet<Integer> componentCountsSeen = new java.util.TreeSet<>();

        @Override
        public void resetDiagnostics() {
            seenSameId = rejectedByMatcher = rejectedByComponents = 0;
            exampleRejected = exampleReason = "";
            firstRejected = null;
            componentCountsSeen.clear();
        }

        @Override
        public int candidatesSeen() {
            return seenSameId;
        }

        private MarketItem relaxed;
        private String relaxedFrom;

        /**
         * Przedmiot do analizy rynku: z ustawieniem "Pomijaj ulepszenia %" bez wymaganych linii lore z wartością % (np.
         * "Dodatkowe obrażenia 14%") - liczą się wtedy najtańsze przedmioty tej nazwy niezależnie od ulepszeń. Reszta
         * dopasowania (id, nazwa, enchanty, inne lore, CMD) bez zmian.
         */
        MarketItem analysisItem() {
            if (!cfg.ignorePercentLore) return item;
            String lore = item.lore == null ? "" : item.lore;
            if (!lore.equals(relaxedFrom)) {
                relaxedFrom = lore;
                String cut = withoutPercentLore(lore);
                relaxed = cut.equals(lore) ? null : item.copy();
                if (relaxed != null) relaxed.lore = cut;
            }
            return relaxed == null ? item : relaxed;
        }

        /** Dopasowanie do analizy rynku (z ustawieniem "Pomijaj ulepszenia %" bez linii lore z wartością %). */
        @Override
        public boolean matches(ItemStack stack) {
            return matchesItem(stack, analysisItem(), true);
        }

        /** Dopasowanie dokładnie wg listy (pełne wymagane lore) - snajper i przypisanie zakupów do przedmiotu. */
        public boolean matchesStrict(ItemStack stack) {
            return matchesItem(stack, item, false);
        }

        private boolean matchesItem(ItemStack stack, MarketItem it, boolean diag) {
            if (stack.isEmpty()) return false;
            String wantId = wantedMaterial();
            if (!wantId.isEmpty() && !wantId.equals(ItemMatcher.itemId(stack))) return false;
            if (diag) seenSameId++;
            String why = ItemMatcher.reject(stack, it);
            if (why != null) {
                if (diag) rejected(stack, why);
                return false;
            }
            // liczba komponentów - tylko na życzenie (ustawienie), gdy trzeba odróżnić przedmioty o tej samej nazwie
            if (cfg.strictComponents) {
                int want = it.componentCount;
                int have = stack.getComponents().getTypes().size();
                if (diag) componentCountsSeen.add(have);
                if (want > 0 && have != want) {
                    if (diag) rejectedByComponents++;
                    return false;
                }
            }
            return true;
        }

        private void rejected(ItemStack stack, String why) {
            rejectedByMatcher++;
            if (exampleRejected.isEmpty()) {
                exampleRejected = strip(stack.getName().getString());
                exampleReason = why;
            }
            if (firstRejected == null && !passive) firstRejected = stack.copy();
        }

        @Override
        public void onMeasured(double cheapest, double avg3, int offers) {
            long now = System.currentTimeMillis();
            cfg.lastScan.put(key(), new AnalizaConfig.ScanRecord(avg3, cheapest, offers, now));
            PriceHistory h = RynekAnalizaMod.history();
            if (h != null) h.record(key(), now, cheapest, avg3, offers, PriceHistory.SRC_ACTIVE);
        }

        /** Ostatnia analiza tego przedmiotu: z configu, a gdy jej tam nie ma (starsza wersja moda) - z historii cen. */
        public AnalizaConfig.ScanRecord lastScan() {
            AnalizaConfig.ScanRecord r = cfg.lastScan.get(key());
            if (r != null && r.avg3 > 0) return r;
            PriceHistory h = RynekAnalizaMod.history();
            PriceHistory.Point p = h == null ? null : h.lastActive(key(), Math.max(1, MarketAnalyzer.MIN_LISTINGS));
            return p == null ? null : new AnalizaConfig.ScanRecord(p.a3, p.p1, p.n, p.t);
        }

        @Override
        public double extractPrice(ItemStack stack) {
            return BkBridge.extractPrice(stack);
        }

        @Override
        public String diagnose() {
            if (seenSameId == 0) {
                return String.format("na rynku nie było żadnego przedmiotu o id '%s' (wyszukiwanie nic takiego nie pokazało albo nikt tego nie wystawia)", wantedMaterial());
            }
            return String.format("w oknie było %d przedm. o id '%s'; odrzucone przez dopasowanie: %d%s; odrzucone przez liczbę komponentów: %d%s; oczekiwana nazwa: '%s'",
                    seenSameId, wantedMaterial(), rejectedByMatcher,
                    exampleRejected.isEmpty() ? "" : " (np. \"" + exampleRejected + "\": " + exampleReason + ")",
                    rejectedByComponents, componentCountsSeen.isEmpty() ? "" : " (widziane liczby komponentów: " + componentCountsSeen + ")",
                    strip(item.name));
        }

        /**
         * Gdy dopasowanie odrzuciło oferty tego id: raport dla pierwszej odrzuconej oferty (opis oferty i powód) zapisany
         * do pliku, a do czatu powód i ścieżka pliku. Pusta lista, gdy nie ma czego raportować.
         */
        @Override
        public List<String> bkReport() {
            if (firstRejected == null) return List.of();
            List<String> rep = new ArrayList<>();
            rep.add("Przedmiot z listy: " + item.name + " | id " + item.material + " | lore: " + item.lore + " | enchanty: " + item.requiredEnchants()
                    + " | CMD: " + item.customModelData);
            rep.add("Oferta: " + ItemMatcher.toLegacy(firstRejected.getName()) + " | id " + ItemMatcher.itemId(firstRejected)
                    + " | CMD: " + ItemMatcher.customModelData(firstRejected));
            for (String l : ItemMatcher.tooltip(firstRejected)) rep.add("  " + l);
            String why = ItemMatcher.reject(firstRejected, analysisItem());
            rep.add("Powód: " + (why == null ? "(teraz pasuje)" : why));
            java.nio.file.Path f = writeDiagnostics(displayName(), rep);
            List<String> chat = new ArrayList<>();
            chat.add("odrzucona oferta: " + (why == null ? "(teraz pasuje)" : why));
            if (f != null) chat.add("pełny raport: config/" + f.getFileName());
            return chat;
        }

        /** Wpisuje cenę sprzedaży CAŁEJ wystawki (sellCount() sztuk). Zapis na dysk robi commit(). */
        @Override
        public void applySellPrice(double total) {
            double old = currentSellPrice();
            item.sellPrice = total;
            if (Math.abs(old - total) > 0.001) countChange(true);
            // wystawki po poprzedniej cenie zostają na rynku - ich sprzedaż też trzeba rozpoznać (historia)
            if (old > 0 && Math.abs(old - total) > 0.001) noteOldSellTotal(key(), old, System.currentTimeMillis());
            ItemStore s = store();
            if (s != null) s.touch();
        }

        /** Wpisuje cenę skupu (pole "Skup" w panelu albo autopilot na żywo). */
        public boolean applyBuyPrice(double pricePerPiece) {
            if (Double.isNaN(pricePerPiece) || Double.isInfinite(pricePerPiece)) return false;
            if (Math.abs(item.maxPrice - pricePerPiece) > 0.001) countChange(false);
            item.maxPrice = pricePerPiece;
            ItemStore s = store();
            if (s != null) s.touch();
            return item.maxPrice == pricePerPiece;
        }

        /** Cena sprzedaży z ostatniej analizy przy obecnej ilości sprzedaży; NaN, gdy brak analizy. */
        public double sellFromLastScan() {
            AnalizaConfig.ScanRecord r = lastScan();
            return r == null ? Double.NaN : MarketAnalyzer.sellFromAverage(r.avg3, sellCount());
        }
    }
}
