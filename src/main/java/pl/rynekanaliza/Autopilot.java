package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Autopilot (od 0.5.0 bez własnego ustalania cen - ceny liczy wzór, patrz Pricing; od 0.5.1 bez obniżki skupu za zapas):
 * dzienny budżet - gdy auto rynek wyda apBudget $ w ciągu dnia, kupowanie wszystkich przedmiotów jest wstrzymane do
 * następnego dnia (0 = bez limitu).
 * Zawsze (także przy wyłączonym autopilocie): historia zakupów i sprzedaży (TradeLedger), rozpoznawanie sprzedaży,
 * kolejka analizy auto rynku (plan) i ponowna analiza po apRescanBuys zakupach w ciągu apRescanMin minut. Pasek przedmiotu:
 * Auto: TAK/NIE (NIE = stała cena sprzedaży), "Min. sprzedaż", "Max skup".
 *
 * Stan: config/rynek-analiza-autopilot.json.
 */
public final class Autopilot {
    static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
    static final long STALE_LOT = 48 * HOUR;
    static final int LOG_CAP = 40;

    // ---------- stan zapisywany ----------
    /** Zakupiona partia (koszt za sztukę) - niesprzedane zakupy (zakładka Autopilot) i zysk przy sprzedaży. */
    public static final class Lot {
        public long t;
        public double costPP;
        public int count;
        public boolean virtual; // tylko stare pliki (tryb próbny do 0.4.1) - takie partie są usuwane przy wczytaniu
    }

    /** Wpis w dzienniku przedmiotu. */
    public static final class Decision {
        public long t;
        public String why = "";
        public boolean applied; // zmiana na liście (skup)
        public boolean problem; // skan się nie udał
    }

    public static final class ItemState {
        public boolean auto = true;    // false = analiza i monitorowanie nie zmieniają ceny sprzedaży (skup i tak ze wzoru)
        public double manualMinSell;   // za sztukę, 0 = brak
        public double manualMaxBuy;    // za sztukę, 0 = brak
        public long lastScanAt, lastBuyAt;
        public String lastWhy = "";
        public List<Lot> lots = new ArrayList<>();
        public List<Decision> log = new ArrayList<>();
        public int buysSinceScan;      // zakupy snajpera (osobne transakcje) od ostatniego skanu
        public boolean rescan;         // po apRescanBuys zakupach w krótkim czasie: czeka na ponowną analizę (od razu)
        public transient boolean sellCheck; // sprzedaż kupionych dałaby stratę - analiza tego przedmiotu przed decyzją o wystawieniu
        transient List<Long> buyTimes; // czasy zakupów od ostatniego skanu (okno apRescanMin minut), nie zapisywane
    }

    /** Przedmiot w kolejce analizy i powód. */
    public static final class Pick {
        public static final int RESCAN = 0, NEW = 1, OLD = 2;
        public final BkBridge.BkTarget t;
        public final int kind;
        public final String why;

        Pick(BkBridge.BkTarget t, int kind, String why) {
            this.t = t;
            this.kind = kind;
            this.why = why;
        }
    }

    public static final class DayStats {
        public String day = "";
        public double spentAll, revenue, realized;
        public int boughtAll, sold, soldKnown;
    }

    public static final class Data {
        public Map<String, ItemState> items = new LinkedHashMap<>();
        public Map<String, DayStats> days = new LinkedHashMap<>();
        public List<String> pausedByBudget = new ArrayList<>();
        public List<String> pausedByStock = new ArrayList<>(); // wstrzymane za zapas do 0.4.0 - do zwolnienia
        public List<String> pausedBySlow = new ArrayList<>();  // wstrzymane hamulcem z 0.2.8 - do zwolnienia
        public String pausedDay = "";
        public int closedReal, profitableReal;
    }

    /** Rozpoznana sprzedaż: przedmiot (klucz, nazwa; t = przedmiot z listy albo null), ile sztuk, kwota przed i po podatku. */
    public static final class SaleMatch {
        public final BkBridge.BkTarget t;
        public final String key, name;
        public final int pieces;
        public final double gross, net;

        SaleMatch(BkBridge.BkTarget t, String key, String name, int pieces, double gross, double net) {
            this.t = t;
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
            this.pieces = Math.max(1, pieces);
            this.gross = gross;
            this.net = net;
        }

        SaleMatch(BkBridge.BkTarget t, double gross, double net) {
            this(t, t.key(), t.displayName(), Math.max(1, t.sellCount()), gross, net);
        }
    }

    /** Kwota sprzedaży pasuje do dwóch przedmiotów o różnych nazwach z dokładnością do tylu $ = nie wiadomo, który się sprzedał. */
    static final double SALE_TIE = 1.0;
    /** Kwota sprzedaży a cena wystawki: dokładność 2$, przy drogich 0,1%. */
    static final double SALE_TOL = 0.001;

    /** Oferta zauważona w oknie rynku (monitorowanie rynku). */
    public static final class Offer {
        public final double pp;
        public final int count;
        public final boolean own;
        public final String id;

        public Offer(double pp, int count, boolean own, String id) {
            this.pp = pp;
            this.count = count;
            this.own = own;
            this.id = id;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final AnalizaConfig cfg;
    private final Path file;
    Data data = new Data();
    /** Historia zakupów i sprzedaży (zakładka Historia); null = bez historii (np. w testach). */
    TradeLedger ledger;
    /** Zatwierdzenie zmian na liście (podmieniane w testach). */
    Runnable committer = BkBridge::commit;
    /** Komunikaty do czatu (podmieniane w testach). */
    Consumer<String> chat = m -> MarketAnalyzer.log.accept(m);
    /** Strefa czasowa dnia budżetu. */
    ZoneId zone = ZoneId.systemDefault();

    private boolean dirty;          // zmiany na liście do zatwierdzenia
    private boolean changed;        // zmiany w stanie do zapisu
    private long lastTick, lastSave;

    public Autopilot(AnalizaConfig cfg, Path file) {
        this.cfg = cfg;
        this.file = file;
    }

    public static Autopilot load(AnalizaConfig cfg) {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-autopilot.json");
        Autopilot a = new Autopilot(cfg, f);
        try {
            if (Files.exists(f)) {
                Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
                if (d != null) a.data = fix(d);
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać autopilota: " + e);
        }
        return a;
    }

    static Data fix(Data d) {
        if (d.items == null) d.items = new LinkedHashMap<>();
        if (d.days == null) d.days = new LinkedHashMap<>();
        if (d.pausedByBudget == null) d.pausedByBudget = new ArrayList<>();
        if (d.pausedByStock == null) d.pausedByStock = new ArrayList<>();
        if (d.pausedBySlow == null) d.pausedBySlow = new ArrayList<>();
        if (d.pausedDay == null) d.pausedDay = "";
        d.items.values().removeIf(s -> s == null);
        for (ItemState s : d.items.values()) {
            if (s.lots == null) s.lots = new ArrayList<>();
            if (s.log == null) s.log = new ArrayList<>();
            if (s.lastWhy == null) s.lastWhy = "";
            s.lots.removeIf(l -> l == null || l.virtual || l.count <= 0);
            s.log.removeIf(x -> x == null);
            for (Decision x : s.log) if (x.why == null) x.why = "";
        }
        return d;
    }

    public void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            changed = false;
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać autopilota: " + e);
        }
    }

    // ---------- ustawienia ----------
    /** Autopilot włączony: dzienny budżet. */
    public boolean enabled() { return cfg.autopilot; }

    public ItemState state(String key) {
        return data.items.computeIfAbsent(key, k -> new ItemState());
    }

    /** Stan przedmiotu bez tworzenia nowego (do wyświetlania). */
    public ItemState peek(String key) {
        return data.items.get(key);
    }

    // ---------- analiza ----------
    /** Udany skan przedmiotu (analiza): ponowna analiza po zakupach załatwiona, kolejka zaczyna od innych przedmiotów. */
    public void onScan(BkBridge.BkTarget t, long now) {
        clearRescan(t);
        ItemState s = state(t.key());
        s.lastScanAt = now;
        changed = true;
    }

    /** Skan przedmiotu się nie udał (np. rynek się nie otworzył): wpis w dzienniku, ceny bez zmian. */
    public void onScanFailed(BkBridge.BkTarget t, String reason, long now) {
        clearRescan(t); // bez zapętlania ponownej analizy
        ItemState s = state(t.key());
        s.lastScanAt = now; // następny skan zacznie od innych przedmiotów
        s.lastWhy = "skan nieudany: " + reason;
        log(s, now, s.lastWhy, false);
        s.log.get(s.log.size() - 1).problem = true;
    }

    // ---------- zakupy ----------
    /** Zakup zrobiony przez auto rynek: historia, ponowna analiza, monitorowanie rynku, budżet. */
    public void onPurchase(List<BkBridge.BkTarget> ts, ItemStack stack, double total, int count, long now) {
        if (total <= 0) return;
        BkBridge.BkTarget t = findTarget(ts, stack);
        if (ledger != null) {
            String name = t != null ? t.displayName() : stack == null ? "" : TradeLedger.clean(stack.getName().getString());
            ledger.onPurchase(t == null ? "" : t.key(), name, count, total, now);
        }
        DayStats d = today(now);
        d.boughtAll++;
        d.spentAll += total;
        changed = true;
        if (t == null) return;
        // kilka zakupów tego samego przedmiotu w krótkim czasie: rynek mógł się zmienić - ponowna analiza od razu
        noteRescan(t, now);
        // kupiona oferta zeszła z rynku: średnia 3 najtańszych bez niej (monitorowanie rynku)
        String mon = Pricing.onPurchase(t, total / Math.max(1, count), cfg, this);
        if (mon != null) AutoMarket.note(mon);
        ItemState s = state(t.key());
        Lot l = new Lot();
        l.t = now;
        l.count = Math.max(1, count);
        l.costPP = total / l.count;
        s.lots.add(l);
        s.lastBuyAt = now;
        log(s, now, "kupione przez snajpera: " + l.count + " szt. za " + AutoPricing.money(total), false);
        enforceLimits(ts, now);
    }

    /**
     * Ponowna analiza po zakupach: apRescanBuys zakupów (osobnych transakcji) tego samego przedmiotu w ciągu apRescanMin
     * minut (i od jego ostatniego skanu) = analiza tego jednego przedmiotu od razu. Przedmiot z Auto: NIE - bez ponownej
     * analizy (jego cena sprzedaży się nie zmienia).
     */
    private void noteRescan(BkBridge.BkTarget t, long now) {
        ItemState s = state(t.key());
        if (!s.auto) return;
        s.buysSinceScan++;
        if (s.buyTimes == null) s.buyTimes = new ArrayList<>();
        s.buyTimes.add(now);
        long window = Math.max(1, cfg.apRescanMin) * 60_000L;
        s.buyTimes.removeIf(x -> now - x > window);
        int need = cfg.apRescanBuys;
        if (need > 0 && s.buyTimes.size() >= need && !s.rescan) {
            s.rescan = true;
            String m = zakupy(s.buyTimes.size()) + " w ciągu " + Math.max(1, cfg.apRescanMin) + " min - ponowna analiza";
            log(s, now, m, false);
            chat.accept(t.displayName() + ": " + m + ".");
        }
        changed = true;
    }

    /** Skan przedmiotu (udany albo nie) załatwia ponowną analizę po zakupach - liczenie od nowa. */
    private void clearRescan(BkBridge.BkTarget t) {
        ItemState s = data.items.get(t.key());
        if (s == null) return;
        s.buysSinceScan = 0;
        s.rescan = false;
        s.sellCheck = false;
        if (s.buyTimes != null) s.buyTimes.clear();
    }

    /**
     * Wpis listy kupionego przedmiotu: najpierw dokładnie (kilka wpisów tej samej nazwy z różnymi ulepszeniami %),
     * potem jak analiza; null = nie pasuje do żadnego.
     */
    static BkBridge.BkTarget findTarget(List<BkBridge.BkTarget> ts, ItemStack stack) {
        if (stack == null) return null;
        for (BkBridge.BkTarget x : ts) {
            try {
                if (x.matchesStrict(stack)) return x;
            } catch (Throwable ignored) {}
        }
        for (BkBridge.BkTarget x : ts) {
            try {
                if (x.matches(stack)) return x;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** "1 zakup", "3 zakupy", "5 zakupów", "22 zakupy". */
    static String zakupy(int n) {
        int d = n % 10, h = n % 100;
        String w = n == 1 ? "zakup" : d >= 2 && d <= 4 && (h < 12 || h > 14) ? "zakupy" : "zakupów";
        return n + " " + w;
    }

    /** "po 1 zakupie", "po 3 zakupach". */
    static String poZakupach(int n) {
        return n == 1 ? "po 1 zakupie" : "po " + n + " zakupach";
    }

    // ---------- sprzedaże ----------
    /** Wystawka zrobiona przez auto rynek - do rozpoznania sprzedaży (historia). */
    public void onListed(BkBridge.BkTarget t, String name, int count, double total, long now) {
        if (ledger == null) return;
        ledger.onListed(t == null ? "" : t.key(), t == null ? name : t.displayName(), count, total, now);
    }

    /**
     * Jedna sprzedaż z komunikatu serwera: kwota (zwykle po podatku) i treść komunikatu (z nazwą przedmiotu, gdy serwer ją
     * podaje). Trafia do historii (najstarsze niesprzedane zakupy), dnia i niesprzedanych zakupów.
     */
    public void onSale(List<BkBridge.BkTarget> ts, double amount, String text, long now) {
        SaleMatch m = amount > 0 ? matchSale(ts, amount, text, now) : null;
        if (m != null && ledger != null) ledger.onSale(m.key, m.name, m.pieces, m.gross, m.net, now);
        DayStats d = today(now);
        d.sold++;
        d.revenue += Math.max(0, amount);
        changed = true;
        if (m == null) return;
        d.soldKnown++;
        if (m.t == null) return;
        ItemState s = state(m.t.key());
        closeRealLots(s, m.pieces, m.net, now);
        enforceLimits(ts, now);
    }

    /** Przedmioty z listy nazwane w komunikacie (nazwa zawarta w dłuższej nazwie z tego samego komunikatu się nie liczy). */
    static List<BkBridge.BkTarget> named(List<BkBridge.BkTarget> ts, String text) {
        List<BkBridge.BkTarget> named = new ArrayList<>();
        if (text == null || text.isEmpty()) return named;
        String h = text.toLowerCase(java.util.Locale.ROOT);
        List<BkBridge.BkTarget> inMsg = new ArrayList<>();
        for (BkBridge.BkTarget t : ts) {
            String n = TradeLedger.norm(t.baseName());
            if (n.length() >= 3 && h.contains(n)) inMsg.add(t);
        }
        for (BkBridge.BkTarget t : inMsg) {
            String n = TradeLedger.norm(t.baseName());
            boolean inLonger = false;
            for (BkBridge.BkTarget o : inMsg) {
                String on = TradeLedger.norm(o.baseName());
                if (on.length() > n.length() && on.contains(n)) { inLonger = true; break; }
            }
            if (!inLonger && !named.contains(t)) named.add(t);
        }
        return named;
    }

    /**
     * Co się sprzedało:
     * <ol>
     *   <li>wystawki auto rynku (zapamiętane z ceną i ilością): kwota = cena x podatek albo sama cena, co do max(2$, 0,1%);
     *       przy nazwie w komunikacie - tylko wystawki tego przedmiotu; najstarsza pasująca,</li>
     *   <li>ceny sprzedaży z listy (obecna i wcześniejsze z ostatniej doby) - jak BK,</li>
     *   <li>sama nazwa z komunikatu (gdy wskazuje jeden przedmiot) - kwota jak po podatku.</li>
     * </ol>
     * Kwota pasująca tak samo dobrze do przedmiotów o różnych nazwach (bez nazwy w komunikacie) = null: lepiej nie wpisać
     * sprzedaży niż wpisać ją złemu przedmiotowi.
     */
    SaleMatch matchSale(List<BkBridge.BkTarget> ts, double amount, String text, long now) {
        double tax = Pricing.tax(cfg);
        List<BkBridge.BkTarget> named = named(ts, text);
        // 1. wystawki auto rynku
        if (ledger != null) {
            List<TradeLedger.Listing> cands = ledger.listingsFor(amount, tax, now);
            if (!named.isEmpty()) {
                List<TradeLedger.Listing> keep = new ArrayList<>();
                for (TradeLedger.Listing x : cands) {
                    for (BkBridge.BkTarget t : named) {
                        if (t.key().equals(x.key) || TradeLedger.baseNorm(x.name).equals(TradeLedger.norm(t.baseName()))) { keep.add(x); break; }
                    }
                }
                cands = keep;
            }
            if (!cands.isEmpty()) {
                TradeLedger.Listing best = cands.get(0);
                double bestErr = TradeLedger.listingError(best, amount, tax);
                boolean ambiguous = false;
                TradeLedger.Listing oldest = best;
                for (TradeLedger.Listing x : cands) {
                    double e = TradeLedger.listingError(x, amount, tax);
                    if (e > bestErr + SALE_TIE) continue;
                    if (!TradeLedger.baseNorm(x.name).equals(TradeLedger.baseNorm(best.name))) ambiguous = true;
                    if (x.t < oldest.t) oldest = x;
                }
                if (!ambiguous) {
                    ledger.removeListing(oldest);
                    BkBridge.BkTarget t = null;
                    for (BkBridge.BkTarget x : ts) if (x.key().equals(oldest.key)) { t = x; break; }
                    boolean net = Math.abs(amount - oldest.total * tax) <= Math.abs(amount - oldest.total);
                    return new SaleMatch(t, oldest.key, oldest.name, oldest.count, net ? amount / tax : amount, net ? amount : amount * tax);
                }
            }
        }
        // 2. ceny sprzedaży z listy
        List<BkBridge.BkTarget> pool = named.isEmpty() ? ts : named;
        BkBridge.BkTarget best = null;
        boolean bestNet = true;
        double bestErr = Double.MAX_VALUE;
        Map<BkBridge.BkTarget, Double> fit = new java.util.IdentityHashMap<>(); // najmniejszy błąd każdego pasującego przedmiotu
        for (BkBridge.BkTarget t : pool) {
            List<Double> prices = new ArrayList<>();
            if (t.currentSellPrice() > 0) prices.add(t.currentSellPrice());
            prices.addAll(BkBridge.recentSellTotals(t.key(), now)); // wcześniejsze ceny: stare wystawki jeszcze wiszą na rynku
            for (double pr : prices) {
                double tol = Math.max(2, pr * SALE_TOL);
                double eNet = Math.abs(amount - pr * tax), eGross = Math.abs(amount - pr);
                if (eNet <= tol && eNet < bestErr) { best = t; bestErr = eNet; bestNet = true; }
                if (eGross <= tol && eGross < bestErr) { best = t; bestErr = eGross; bestNet = false; }
                double e = Math.min(eNet <= tol ? eNet : Double.MAX_VALUE, eGross <= tol ? eGross : Double.MAX_VALUE);
                if (e < Double.MAX_VALUE) fit.merge(t, e, Math::min);
            }
        }
        if (best != null) {
            String bestName = TradeLedger.norm(best.baseName());
            for (Map.Entry<BkBridge.BkTarget, Double> f : fit.entrySet()) {
                if (f.getKey() != best && f.getValue() <= bestErr + SALE_TIE && !TradeLedger.norm(f.getKey().baseName()).equals(bestName)) return null;
            }
            return bestNet ? new SaleMatch(best, amount / tax, amount) : new SaleMatch(best, amount, amount * tax);
        }
        // 3. cena nie pasuje do żadnej wystawki, ale serwer podał nazwę jednego przedmiotu: ten (kwota jak po podatku)
        if (!named.isEmpty()) {
            String n0 = TradeLedger.norm(named.get(0).baseName());
            for (BkBridge.BkTarget t : named) if (!TradeLedger.norm(t.baseName()).equals(n0)) return null;
            return new SaleMatch(named.get(0), amount / tax, amount);
        }
        return null;
    }

    private void closeRealLots(ItemState s, int pieces, double net, long now) {
        int left = pieces;
        double cost = 0;
        int covered = 0;
        for (Iterator<Lot> it = s.lots.iterator(); it.hasNext() && left > 0; ) {
            Lot l = it.next();
            int take = Math.min(left, l.count);
            cost += take * l.costPP;
            covered += take;
            l.count -= take;
            left -= take;
            if (l.count <= 0) it.remove();
        }
        if (covered == 0) {
            log(s, now, "sprzedane: " + AutoPricing.money(net) + " (bez zakupu w księdze)", true);
            return;
        }
        double netCovered = net * covered / pieces;
        double realized = netCovered - cost;
        today(now).realized += realized;
        data.closedReal++;
        if (realized > 0) data.profitableReal++;
        log(s, now, "sprzedane: " + covered + " szt., zysk " + (realized >= 0 ? "+" : "") + AutoPricing.money(realized), true);
    }

    // ---------- okresowo: nowy dzień, stare partie, limity ----------
    public void tick(List<BkBridge.BkTarget> ts, long now) {
        if (now - lastTick < 30_000) return;
        lastTick = now;
        String today = day(now);
        if (!data.pausedByBudget.isEmpty() && !today.equals(data.pausedDay)) {
            int n = unpause(ts, data.pausedByBudget);
            if (n > 0) chat.accept("Autopilot: nowy dzień - kupowanie wznowione (" + n + " przedm.).");
        }
        for (ItemState s : data.items.values()) settle(s, now);
        enforceLimits(ts, now);
        while (data.days.size() > 31) data.days.remove(data.days.keySet().iterator().next());
        if (changed && now - lastSave > 60_000) {
            lastSave = now;
            save();
        }
    }

    /** Zakupy sprzed 48 h bez rozpoznanej sprzedaży znikają z niesprzedanych (pewnie sprzedane bez komunikatu albo zabrane). */
    void settle(ItemState s, long now) {
        for (Iterator<Lot> it = s.lots.iterator(); it.hasNext(); ) {
            Lot l = it.next();
            if (now - l.t < STALE_LOT) continue;
            it.remove();
            changed = true;
            log(s, now, "zakup sprzed 48 h bez rozpoznanej sprzedaży - usunięty z niesprzedanych", false);
        }
    }

    /** Budżet dzienny. */
    void enforceLimits(List<BkBridge.BkTarget> ts, long now) {
        // wstrzymania z dawnych wersji (zapas do 0.4.0, hamulec z 0.2.8): kupowanie wraca
        if (!data.pausedByStock.isEmpty()) {
            int n = unpause(ts, data.pausedByStock);
            if (n > 0) chat.accept("Autopilot: kupowanie wstrzymane za zapas wznowione (" + n + " przedm.).");
        }
        if (!data.pausedBySlow.isEmpty()) {
            int n = unpause(ts, data.pausedBySlow);
            if (n > 0) chat.accept("Autopilot: kupowanie wstrzymane hamulcem wznowione (" + n + " przedm.).");
        }
        if (!enabled()) {
            flush();
            return;
        }
        DayStats d = today(now);
        String today = day(now);
        boolean over = cfg.apBudget > 0 && d.spentAll >= cfg.apBudget - 1e-6;
        if (over) {
            int n = 0;
            for (BkBridge.BkTarget t : ts) {
                if (data.pausedByBudget.contains(t.key()) || !t.buyEnabled()) continue;
                t.setBuyEnabled(false);
                data.pausedByBudget.add(t.key());
                n++;
            }
            if (n > 0) {
                data.pausedDay = today;
                dirty = true;
                changed = true;
                chat.accept("Autopilot: budżet na dziś wykorzystany (" + AutoPricing.money(d.spentAll) + " z " + AutoPricing.money(cfg.apBudget)
                        + ") - kupowanie wstrzymane do jutra.");
            }
        } else if (!data.pausedByBudget.isEmpty() && today.equals(data.pausedDay)) {
            int n = unpause(ts, data.pausedByBudget); // budżet podniesiony w ustawieniach
            if (n > 0) chat.accept("Autopilot: budżet podniesiony - kupowanie wznowione.");
        }
        flush();
    }

    /** Czy kupowanie przedmiotu jest wstrzymane z innego powodu niż lista except. */
    private boolean pausedOtherwise(String key, List<String> except) {
        return (except != data.pausedByBudget && data.pausedByBudget.contains(key))
                || (except != data.pausedByStock && data.pausedByStock.contains(key))
                || (except != data.pausedBySlow && data.pausedBySlow.contains(key));
    }

    private int unpause(List<BkBridge.BkTarget> ts, List<String> keys) {
        int n = 0;
        for (BkBridge.BkTarget t : ts) {
            if (!keys.contains(t.key())) continue;
            if (!pausedOtherwise(t.key(), keys) && !t.buyEnabled()) {
                t.setBuyEnabled(true);
                n++;
            }
        }
        keys.clear();
        dirty = true;
        changed = true;
        return n;
    }

    /** Zmiana stanu z panelu (np. ręczne minimum) - zapis przy najbliższej okazji. */
    public void markChanged() {
        changed = true;
    }

    /** Zatwierdza zmiany na liście (jeden zapis na wiele zmian). */
    public void flush() {
        if (!dirty) return;
        dirty = false;
        try { committer.run(); } catch (Throwable t) { t.printStackTrace(); }
    }

    /** Wyłączenie autopilota: wstrzymane przez niego kupowanie wraca. */
    public void releasePauses(List<BkBridge.BkTarget> ts) {
        List<String> all = new ArrayList<>(data.pausedByBudget);
        all.addAll(data.pausedByStock);
        all.addAll(data.pausedBySlow);
        data.pausedByBudget.clear();
        data.pausedByStock.clear();
        data.pausedBySlow.clear();
        if (!all.isEmpty()) unpause(ts, all);
        for (BkBridge.BkTarget t : ts) if (Pricing.sync(t, cfg, this)) dirty = true;
        flush();
        save();
    }

    // ---------- kolejka analizy ----------
    /**
     * Kolejność analizy: najpierw przedmioty czekające na ponowny skan (po apRescanBuys zakupach), potem jeszcze
     * niesprawdzane, a potem wszystkie pozostałe - najdawniej sprawdzane najpierw.
     */
    public List<Pick> plan(List<BkBridge.BkTarget> ts, long now) {
        List<Pick> rescanL = new ArrayList<>(), fresh = new ArrayList<>(), rest = new ArrayList<>();
        for (BkBridge.BkTarget t : ts) {
            ItemState s = data.items.get(t.key());
            if (s != null && s.auto && (s.rescan || s.sellCheck))
                rescanL.add(new Pick(t, Pick.RESCAN, s.rescan ? poZakupach(s.buysSinceScan) : "przed wystawieniem (sprzedaż teraz = strata?)"));
            else if (s == null || s.lastScanAt <= 0) fresh.add(new Pick(t, Pick.NEW, "jeszcze niesprawdzany"));
            else rest.add(new Pick(t, Pick.OLD, ""));
        }
        rest.sort(Comparator.comparingLong(pk -> data.items.get(pk.t.key()).lastScanAt));
        List<Pick> out = new ArrayList<>(rescanL);
        out.addAll(fresh);
        out.addAll(rest);
        return out;
    }

    /** Koniec analizy: zapis stanu. */
    public void endCycle() {
        save();
    }

    /** Komunikaty cichej analizy nie idą na czat - trafiają do logu gry (latest.log). */
    void scanLine(String line) {
        System.out.println("[rynek-analiza] analiza: " + line);
    }

    // ---------- dziennik i statystyki ----------
    private void log(ItemState s, long now, String why, boolean applied) {
        Decision d = new Decision();
        d.t = now;
        d.why = why;
        d.applied = applied;
        s.log.add(d);
        while (s.log.size() > LOG_CAP) s.log.remove(0);
        changed = true;
    }

    String day(long now) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString();
    }

    public DayStats today(long now) {
        String d = day(now);
        DayStats s = data.days.get(d);
        if (s == null) {
            s = new DayStats();
            s.day = d;
            data.days.put(d, s);
        }
        return s;
    }

    /** Ostatnie wpisy ze wszystkich przedmiotów (najnowsze na początku): {czas, nazwa, opis, zmiana na liście, problem}. */
    public List<String[]> recentLog(int max, Map<String, String> names) {
        List<Object[]> all = new ArrayList<>();
        for (Map.Entry<String, ItemState> e : data.items.entrySet()) {
            for (Decision d : e.getValue().log) all.add(new Object[]{d, e.getKey()});
        }
        all.sort((a, b) -> Long.compare(((Decision) b[0]).t, ((Decision) a[0]).t));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, all.size()); i++) {
            Decision d = (Decision) all.get(i)[0];
            String key = (String) all.get(i)[1];
            out.add(new String[]{String.valueOf(d.t), names.getOrDefault(key, key), d.why, d.applied ? "1" : "0", d.problem ? "1" : "0"});
        }
        return out;
    }

    /** Niesprzedane zakupy (partie) przedmiotu. */
    public int openLots(ItemState s) {
        int n = 0;
        for (Lot l : s.lots) if (l.count > 0) n++;
        return n;
    }
}
