package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Historia zakupów i sprzedaży (zakładka Historia): każdy zakup zrobiony przez BK z ceną, a sprzedaż dopiero wtedy, gdy
 * naprawdę nastąpiła (licznik sprzedaży BK + rozpoznany przedmiot). Bez sprzedaży wpis zostaje "jeszcze się nie sprzedał".
 * Zysk = to, co zostało po podatku ze sprzedaży, minus koszt sprzedanych sztuk (strata jest ujemna).
 * Sprzedaż trafia do NAJTAŃSZYCH niesprzedanych zakupów tego przedmiotu (przy równej cenie - najstarszych; od 0.5.3, wcześniej
 * zawsze najstarszych): ten sam wpis listy, potem ta sama nazwa, a na końcu ta sama nazwa bez dopisku w nawiasie, np.
 * "Diamentowe buty [Ochrona IV]" -> "Diamentowe buty". Tak samo liczy wystawianie bez straty (cheapestOpen): wystawka to
 * najtańsze sztuki, które jeszcze nie leżą na rynku - więc sprzedaż zamyka dokładnie te zakupy, które sprawdzono przed
 * wystawieniem, a droższe czekają na wyższą cenę.
 * Wystawki zrobione przez auto rynek są zapamiętane (cena, ilość, przedmiot) - po nich rozpoznajemy, co się sprzedało,
 * także po restarcie gry i po zmianie ceny na liście.
 *
 * Plik: config/rynek-analiza-historia.json.
 */
public final class TradeLedger {
    /** Jeden zakup BK i jego sprzedaż. */
    public static final class Entry {
        public long t;            // kiedy kupione
        public String key = "";   // klucz przedmiotu (wpis BK); pusty, gdy zakup nie pasował do żadnego wpisu
        public String name = "";  // nazwa przedmiotu
        public int count;         // ile sztuk kupiono
        public double cost;       // ile zapłacono za całość
        public int sold;          // ile sztuk już sprzedano
        public double soldGross;  // za ile sprzedano (cena wystawki, przed podatkiem)
        public double soldNet;    // ile dostałeś po podatku
        public long soldAt;       // kiedy ostatnia sprzedaż

        public boolean fullySold() { return sold >= count; }

        public double costPP() { return count > 0 ? cost / count : cost; }

        /** Zysk (+) albo strata (-) ze sprzedanych sztuk (po podatku); 0, gdy nic się nie sprzedało. */
        public double profit() { return sold <= 0 ? 0 : soldNet - costPP() * sold; }
    }

    /** Wystawka zrobiona przez auto rynek (jeszcze nie rozpoznana jako sprzedana). */
    public static final class Listing {
        public long t;            // kiedy wystawiona
        public String key = "";   // klucz przedmiotu (wpis listy)
        public String name = "";  // nazwa przedmiotu
        public int count;         // ile sztuk
        public double total;      // cena całej wystawki
    }

    public static final class Data {
        public List<Entry> entries = new ArrayList<>();
        public List<Listing> listings = new ArrayList<>();
    }

    /** Wystawki starsze niż tyle są zapominane (rynek je zdjął albo wróciły do skrzynki). */
    static final long LISTING_TTL = 8L * 24 * 3600_000L;
    static final int MAX_LISTINGS = 400;

    /** Podsumowanie całej historii. */
    public static final class Totals {
        public double spent;       // ile mod wydał na wszystkie zakupy
        public double soldGross;   // za ile sprzedano (przed podatkiem)
        public double soldNet;     // ile wróciło po podatku
        public double profit;      // łączny zysk ze sprzedanych (po podatku; straty odjęte)
        public double unsoldCost;  // ile kosztowały jeszcze niesprzedane sztuki
        public int bought, soldPieces, unsoldPieces, purchases;
    }

    /** Wyniki jednego przedmiotu (widok "Wg przedmiotów"). */
    public static final class ItemStats {
        public String key = "";   // wpis BK (albo "?nazwa", gdy zakupy nie pasowały do żadnego wpisu)
        public String name = "";  // nazwa z najnowszego zakupu
        public int purchases;     // ile zakupów
        public int bought, sold;  // ile sztuk kupiono i ile z nich sprzedano
        public double spent;      // ile wydano na ten przedmiot
        public double soldNet;    // ile wróciło ze sprzedaży (po podatku)
        public double profit;     // zysk (+) albo strata (-) ze sprzedanych sztuk
        public int soldOut;       // ile zakupów sprzedało się w całości
        public long avgSellMs;    // średni czas od zakupu do sprzedaży całości (gdy soldOut > 0)
        long lastBuy, sellMsSum;

        public int unsold() { return bought - sold; }
    }

    static int MAX_ENTRIES = 2000; // nie final - testy zmniejszają
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    Data data = new Data();

    public TradeLedger(Path file) {
        this.file = file;
    }

    public static TradeLedger load() {
        TradeLedger l = new TradeLedger(FabricLoader.getInstance().getConfigDir().resolve("rynek-analiza-historia.json"));
        l.read();
        return l;
    }

    void read() {
        try {
            if (file != null && Files.exists(file)) {
                Data d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (d != null && d.entries != null) {
                    if (d.listings == null) d.listings = new ArrayList<>();
                    d.listings.removeIf(x -> x == null || !(x.total > 0));
                    for (Listing x : d.listings) {
                        if (x.key == null) x.key = "";
                        if (x.name == null) x.name = "";
                        x.count = Math.max(1, x.count);
                    }
                    d.entries.removeIf(e -> e == null);
                    for (Entry e : d.entries) {
                        if (e.key == null) e.key = "";
                        if (e.name == null) e.name = "";
                        e.count = Math.max(1, e.count);
                        e.sold = Math.max(0, Math.min(e.count, e.sold));
                    }
                    data = d;
                }
            }
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się wczytać historii zakupów: " + e);
        }
    }

    public void save() {
        if (file == null) return;
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[rynek-analiza] nie udało się zapisać historii zakupów: " + e);
        }
    }

    // ---------- zdarzenia ----------
    /** Zakup zrobiony przez BK: count sztuk za total (cena całej oferty). */
    public void onPurchase(String key, String name, int count, double total, long now) {
        if (!(total > 0) || Double.isInfinite(total)) return;
        Entry e = new Entry();
        e.t = now;
        e.key = key == null ? "" : key;
        e.name = clean(name);
        e.count = Math.max(1, count);
        e.cost = total;
        data.entries.add(e);
        trim();
        save();
    }

    /**
     * Sprzedaż pieces sztuk za gross (przed podatkiem; net = po podatku): trafia do najtańszych (przy równej cenie -
     * najstarszych) niesprzedanych zakupów tego przedmiotu - najpierw ten sam wpis listy, potem ta sama nazwa. Sztuki bez
     * zakupu w historii (np. kupione przed włączeniem historii albo nie przez auto rynek) są pomijane.
     * @return ile sztuk przypisano do zakupów
     */
    public int onSale(String key, String name, int pieces, double gross, double net, long now) {
        if (pieces <= 0 || !(net > 0)) return 0;
        int left = pieces;
        left = assign(key == null ? "" : key, null, false, left, pieces, gross, net, now);
        if (left > 0) left = assign(null, norm(name), false, left, pieces, gross, net, now);
        if (left > 0) left = assign(null, baseNorm(name), true, left, pieces, gross, net, now);
        int covered = pieces - left;
        if (covered > 0) save();
        return covered;
    }

    /** Zakupy od najtańszego za sztukę (przy równej cenie od najstarszego). */
    private List<Entry> cheapestFirst() {
        List<Entry> l = new ArrayList<>(data.entries);
        l.sort(Comparator.comparingDouble(Entry::costPP).thenComparingLong(e -> e.t));
        return l;
    }

    private int assign(String key, String nameNorm, boolean base, int left, int pieces, double gross, double net, long now) {
        for (Entry e : cheapestFirst()) {
            if (left <= 0) break;
            if (e.fullySold()) continue;
            if (key != null && (key.isEmpty() || !key.equals(e.key))) continue;
            if (nameNorm != null && (nameNorm.isEmpty() || !nameNorm.equals(base ? baseNorm(e.name) : norm(e.name)))) continue;
            int take = Math.min(left, e.count - e.sold);
            e.sold += take;
            e.soldGross += gross * take / pieces;
            e.soldNet += net * take / pieces;
            e.soldAt = now;
            left -= take;
        }
        return left;
    }

    // ---------- wystawki auto rynku ----------
    /** Auto rynek wystawił przedmiot (count sztuk za total) - zapamiętane do rozpoznania sprzedaży. */
    public void onListed(String key, String name, int count, double total, long now) {
        if (!(total > 0)) return;
        Listing x = new Listing();
        x.t = now;
        x.key = key == null ? "" : key;
        x.name = clean(name);
        x.count = Math.max(1, count);
        x.total = total;
        data.listings.add(x);
        expireListings(now);
        save();
    }

    /** Wystawki pasujące do kwoty sprzedaży (kwota = cena x podatek albo sama cena, co do max(2$, 0,1%)): najstarsze pierwsze. */
    public List<Listing> listingsFor(double amount, double tax, long now) {
        expireListings(now);
        List<Listing> out = new ArrayList<>();
        for (Listing x : data.listings) if (listingError(x, amount, tax) >= 0) out.add(x);
        out.sort((a, b) -> Double.compare(listingError(a, amount, tax), listingError(b, amount, tax)));
        return out;
    }

    /** Błąd dopasowania kwoty do wystawki ($); -1 = nie pasuje. */
    static double listingError(Listing x, double amount, double tax) {
        double tol = Math.max(2, x.total * 0.001);
        double e = Math.min(Math.abs(amount - x.total * tax), Math.abs(amount - x.total));
        return e <= tol ? e : -1;
    }

    /** Sprzedana wystawka - już nie czeka na rynku. */
    public void removeListing(Listing x) {
        if (data.listings.remove(x)) save();
    }

    public List<Listing> listings() {
        return new ArrayList<>(data.listings);
    }

    /** Ile sztuk przedmiotu leży teraz na rynku (wystawki auto rynku jeszcze nierozpoznane jako sprzedane). */
    public int onMarket(String key, long now) {
        expireListings(now);
        int n = 0;
        if (key == null || key.isEmpty()) return 0;
        for (Listing x : data.listings) if (key.equals(x.key)) n += x.count;
        return n;
    }

    /** Koszt sztuk, które poszłyby do następnej wystawki: średni koszt za sztukę, ile sztuk znaleziono, kiedy kupiono najstarszą. */
    public static final class Cost {
        public double costPP;
        public int pieces;
        public long oldestT;
    }

    /**
     * Najtańsze niesprzedane sztuki przedmiotu po pominięciu skip sztuk (tyle leży już na rynku albo idzie we wcześniejszych
     * wystawkach tej rundy): średni koszt qty następnych sztuk. null = nie ma takich sztuk w historii (koszt nieznany).
     */
    public Cost cheapestOpen(String key, int skip, int qty) {
        if (key == null || key.isEmpty() || qty <= 0) return null;
        Cost c = new Cost();
        double sum = 0;
        int toSkip = Math.max(0, skip);
        for (Entry e : cheapestFirst()) {
            if (!key.equals(e.key) || e.fullySold()) continue;
            int open = e.count - e.sold;
            int s = Math.min(open, toSkip);
            toSkip -= s;
            open -= s;
            int take = Math.min(open, qty - c.pieces);
            if (take <= 0) continue;
            sum += e.costPP() * take;
            c.pieces += take;
            c.oldestT = c.oldestT == 0 ? e.t : Math.min(c.oldestT, e.t);
            if (c.pieces >= qty) break;
        }
        if (c.pieces == 0) return null;
        c.costPP = sum / c.pieces;
        return c;
    }

    private void expireListings(long now) {
        data.listings.removeIf(x -> now - x.t > LISTING_TTL);
        while (data.listings.size() > MAX_LISTINGS) data.listings.remove(0);
    }

    /**
     * Czyści całą historię (przycisk "Wyczyść historię"). Poprzednia zostaje w kopii obok pliku
     * (rynek-analiza-historia-kopia.json), na wypadek pomyłki.
     */
    public void clear() {
        if (file != null && Files.exists(file)) {
            try {
                Files.copy(file, backupFile(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                System.err.println("[rynek-analiza] nie udało się zrobić kopii historii: " + e);
            }
        }
        data.entries.clear();
        data.listings.clear();
        save();
    }

    /** Kopia historii sprzed wyczyszczenia: obok pliku historii, z dopiskiem "-kopia". */
    Path backupFile() {
        String n = file.getFileName().toString();
        return file.resolveSibling((n.endsWith(".json") ? n.substring(0, n.length() - 5) : n) + "-kopia.json");
    }

    // ---------- odczyt ----------
    /** Wpisy od najnowszego. */
    public List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>(data.entries);
        out.sort((a, b) -> Long.compare(b.t, a.t));
        return out;
    }

    public Totals totals() {
        Totals s = new Totals();
        for (Entry e : data.entries) {
            s.purchases++;
            s.spent += e.cost;
            s.bought += e.count;
            s.soldPieces += e.sold;
            s.soldGross += e.soldGross;
            s.soldNet += e.soldNet;
            s.profit += e.profit();
            s.unsoldPieces += e.count - e.sold;
            s.unsoldCost += e.costPP() * (e.count - e.sold);
        }
        return s;
    }

    /**
     * Wyniki według przedmiotów: zakupy tego samego wpisu BK razem (bez wpisu - ta sama nazwa). Od największego zysku
     * do największej straty; przy równym zysku wyżej ten, na który wydano więcej.
     */
    public List<ItemStats> byItem() {
        Map<String, ItemStats> m = new LinkedHashMap<>();
        for (Entry e : data.entries) {
            String id = e.key.isEmpty() ? "?" + norm(e.name) : e.key;
            ItemStats s = m.computeIfAbsent(id, k -> new ItemStats());
            s.key = id;
            if (s.purchases == 0 || e.t >= s.lastBuy) {
                s.lastBuy = e.t;
                s.name = e.name;
            }
            s.purchases++;
            s.bought += e.count;
            s.sold += e.sold;
            s.spent += e.cost;
            s.soldNet += e.soldNet;
            s.profit += e.profit();
            if (e.fullySold() && e.soldAt >= e.t) {
                s.soldOut++;
                s.sellMsSum += e.soldAt - e.t;
            }
        }
        List<ItemStats> out = new ArrayList<>(m.values());
        for (ItemStats s : out) s.avgSellMs = s.soldOut > 0 ? s.sellMsSum / s.soldOut : 0;
        out.sort((a, b) -> {
            int c = Double.compare(b.profit, a.profit);
            return c != 0 ? c : Double.compare(b.spent, a.spent);
        });
        return out;
    }

    public int size() {
        return data.entries.size();
    }

    // ---------- pomocnicze ----------
    /** Najstarsze w pełni sprzedane wpisy wypadają pierwsze, gdy historia przekroczy MAX_ENTRIES. */
    private void trim() {
        while (data.entries.size() > MAX_ENTRIES) {
            int idx = -1;
            for (int i = 0; i < data.entries.size(); i++) {
                if (data.entries.get(i).fullySold()) { idx = i; break; }
            }
            data.entries.remove(idx >= 0 ? idx : 0);
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            b.append(c);
        }
        return b.toString().trim();
    }

    static String norm(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }

    /** Nazwa bez dopisku w nawiasie kwadratowym na końcu ("Diamentowe buty [Ochrona IV]" -> "diamentowe buty"). */
    static String baseNorm(String s) {
        String n = norm(s);
        int i = n.indexOf(" [");
        return (i > 0 ? n.substring(0, i) : n).trim();
    }
}
