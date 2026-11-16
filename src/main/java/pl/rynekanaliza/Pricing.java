package pl.rynekanaliza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ceny wg wzoru (od 0.5.0):
 * <ul>
 *   <li>sprzedaż = średnia 3 najtańszych cudzych ofert (wpisuje ją analiza; między analizami zmienia ją monitorowanie
 *       rynku - patrz onObserved / onPurchase),</li>
 *   <li>skup (za sztukę) = sprzedaż za sztukę x 0,97 (podatek serwera) - minimalna marża przedmiotu (za sztukę; bez
 *       własnej marży - marża domyślna z Ustawień, a gdy ta jest 0 - przedmiot nie jest kupowany).</li>
 * </ul>
 * Skup na liście liczy się sam z ceny sprzedaży i marży (sync) - po każdej zmianie ceny sprzedaży, marży albo podatku.
 * Skup <= 0 = snajper tego nie kupuje. "Max skup" z paska przedmiotu ogranicza skup,
 * "Min. sprzedaż" - cenę sprzedaży; przedmiot z "Auto: NIE" ma stałą cenę sprzedaży (skup i tak ze wzoru).
 */
final class Pricing {
    private Pricing() {}

    /** Ile najtańszych cen (za sztukę) z analizy pamiętamy do monitorowania rynku. */
    static final int KEEP = 8;
    /** Ta sama oferta przy kolejnych odświeżeniach okna liczy się raz przez tyle ms. */
    static final long SEEN_TTL = 30 * 60_000L;

    /** Najtańsze cudze ceny za sztukę z ostatniej analizy, zmieniane przez monitorowanie (rosnąco). Nie zapisywane. */
    private static final Map<String, List<Double>> known = new HashMap<>();
    /** Oferty już policzone przez monitorowanie: klucz przedmiotu -> (id oferty -> kiedy). */
    private static final Map<String, Map<String, Long>> seen = new HashMap<>();

    // ---------- wzór ----------
    static double tax(AnalizaConfig cfg) {
        return cfg.taxFactor > 0 && cfg.taxFactor <= 1 ? cfg.taxFactor : 0.97;
    }

    /** Minimalna marża przedmiotu (za sztukę): własna, a bez niej domyślna; NaN = brak marży (przedmiot nie jest kupowany). */
    static double margin(MarketItem it, AnalizaConfig cfg) {
        if (it.margin != null && it.margin >= 0 && !it.margin.isInfinite()) return it.margin;
        return cfg.defaultMinMargin > 0 ? cfg.defaultMinMargin : Double.NaN;
    }

    /** Cena sprzedaży za sztukę (cena wystawki / ilość sprzedaży); 0 = brak. */
    static double sellPP(MarketItem it) {
        return it.sellPrice > 0 ? it.sellPrice / Math.max(1, it.sellUnits()) : 0;
    }

    /** Skup za sztukę ze wzoru (bez "Max skup"); 0 = nie kupuje (brak sprzedaży, brak marży, wynik <= 0). */
    static double formulaPP(MarketItem it, AnalizaConfig cfg) {
        double sell = sellPP(it);
        double m = margin(it, cfg);
        if (!(sell > 0) || Double.isNaN(m)) return 0;
        double v = AutoPricing.cents(sell * tax(cfg) - m);
        return v > 0 ? v : 0;
    }

    /**
     * Skup na liście: wzór, nie wyżej niż "Max skup"; za sztukę, a bez trybu "Unit" za całą ofertę o ilości kupna.
     * 0 = nie kupuje.
     */
    static double target(BkBridge.BkTarget t, AnalizaConfig cfg, Autopilot ap) {
        double pp = formulaPP(t.item(), cfg);
        if (!(pp > 0)) return 0;
        Autopilot.ItemState s = ap == null ? null : ap.peek(t.key());
        if (s != null && s.manualMaxBuy > 0) pp = Math.min(pp, s.manualMaxBuy);
        double v = AutoPricing.cents(pp * Math.max(1, t.buyFactor()));
        return v > 0 ? v : 0;
    }

    /** Wpisuje skup ze wzoru, gdy na liście jest inny. true = zmieniony (zapis na dysk robi BkBridge.commit()). */
    static boolean sync(BkBridge.BkTarget t, AnalizaConfig cfg, Autopilot ap) {
        double want = target(t, cfg, ap);
        if (Math.abs(t.currentBuyPrice() - want) < 0.005) return false;
        return t.applyBuyPrice(want);
    }

    /** Skup ze wzoru dla całej listy; zwraca liczbę zmian (i od razu zapisuje listę, gdy coś się zmieniło). */
    static int syncAll(List<BkBridge.BkTarget> ts, AnalizaConfig cfg, Autopilot ap) {
        int n = 0;
        for (BkBridge.BkTarget t : ts) {
            try {
                if (sync(t, cfg, ap)) n++;
            } catch (Throwable ignored) {}
        }
        if (n > 0) BkBridge.commit();
        return n;
    }

    /** Opis skupu do logu analizy: "skup 9344$/szt. (14498$ x 0,97 - marża 500$)". */
    static String describe(BkBridge.BkTarget t, AnalizaConfig cfg, Autopilot ap) {
        MarketItem it = t.item();
        double m = margin(it, cfg);
        if (Double.isNaN(m)) return "skup 0 (brak marży - wpisz marżę albo marżę domyślną w Ustawieniach)";
        double sell = sellPP(it);
        double pp = formulaPP(it, cfg);
        int factor = Math.max(1, t.buyFactor());
        double listed = target(t, cfg, ap);
        String calc = AnalizaScreen.fmtR(sell) + "$/szt. x " + AnalizaScreen.fmt(tax(cfg)) + " - marża " + AnalizaScreen.fmtR(m) + "$";
        if (!(pp > 0)) return "skup 0 (" + calc + " <= 0)";
        String out = "skup " + AnalizaScreen.fmtR(pp) + "$/szt. (" + calc + ")";
        if (Math.abs(listed - pp * factor) > 0.005) out += ", na liście " + AnalizaScreen.fmtR(listed) + "$" + (factor > 1 ? " za " + factor + " szt." : "") + " (Max skup)";
        else if (factor > 1) out += " = " + AnalizaScreen.fmtR(listed) + "$ za " + factor + " szt.";
        return out;
    }

    // ---------- cena sprzedaży ----------
    /** Czy analiza i monitorowanie zmieniają cenę sprzedaży przedmiotu (Auto: NIE = stała cena gracza). */
    static boolean autoSell(BkBridge.BkTarget t, Autopilot ap) {
        Autopilot.ItemState s = ap == null ? null : ap.peek(t.key());
        return s == null || s.auto;
    }

    /** Cena wystawki ze średniej za sztukę: floor(średnia x ilość), nie niżej niż "Min. sprzedaż"; NaN = brak. */
    static double sellTotal(BkBridge.BkTarget t, double avgPP, Autopilot ap) {
        int count = Math.max(1, t.sellCount());
        double total = MarketAnalyzer.sellFromAverage(avgPP, count);
        if (Double.isNaN(total)) return total;
        Autopilot.ItemState s = ap == null ? null : ap.peek(t.key());
        if (s != null && s.manualMinSell > 0) total = Math.max(total, Math.ceil(s.manualMinSell * count - 1e-6));
        return total;
    }

    // ---------- monitorowanie rynku ----------
    /** Analiza przedmiotu: najtańsze cudze ceny (za sztukę) - od nich liczy monitorowanie. */
    static void onAnalysis(String key, List<Double> pricesPP) {
        List<Double> l = new ArrayList<>(pricesPP);
        Collections.sort(l);
        if (l.size() > KEEP) l = new ArrayList<>(l.subList(0, KEEP));
        known.put(key, l);
        seen.remove(key);
    }

    /** Średnia 3 najtańszych z listy; NaN, gdy za mało cen. */
    static double avg3(List<Double> l) {
        int need = Math.max(1, Math.min(Math.max(1, MarketAnalyzer.LISTINGS_USED), Math.max(1, MarketAnalyzer.MIN_LISTINGS)));
        if (l == null || l.size() < need) return Double.NaN;
        int n = Math.min(Math.max(1, MarketAnalyzer.LISTINGS_USED), l.size());
        double sum = 0;
        for (int i = 0; i < n; i++) sum += l.get(i);
        return sum / n;
    }

    /**
     * Oferty przedmiotu zauważone w oknie rynku (snajper). Nowa cudza oferta, której snajper nie kupi (nie poniżej skupu),
     * tańsza od 3 najtańszych z analizy - wchodzi do średniej (cena sprzedaży w dół, skup za nią ze wzoru). Oferty poniżej
     * skupu się nie liczą (zaraz znikną - kupi je snajper albo ktoś inny).
     * @return opis zmiany ceny do logu albo null (bez zmian)
     */
    static String onObserved(BkBridge.BkTarget t, List<Autopilot.Offer> offers, AnalizaConfig cfg, Autopilot ap, long now) {
        List<Double> l = known.get(t.key());
        if (l == null || offers == null || offers.isEmpty()) return null;
        Map<String, Long> ids = seen.computeIfAbsent(t.key(), k -> new LinkedHashMap<>());
        for (Iterator<Map.Entry<String, Long>> it = ids.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > SEEN_TTL) it.remove();
        }
        double buyPP = t.buyEnabled() && t.currentBuyPrice() > 0 ? t.currentBuyPrice() / Math.max(1, t.buyFactor()) : 0;
        boolean added = false;
        double newest = 0;
        for (Autopilot.Offer o : offers) {
            if (o.own || !(o.pp > 0) || ids.containsKey(o.id)) continue;
            ids.put(o.id, now);
            if (buyPP > 0 && o.pp < buyPP) continue; // snajper to kupi - nie zostanie na rynku
            if (l.size() >= KEEP && o.pp >= l.get(l.size() - 1)) continue; // nie wśród 8 najtańszych znanych
            l.add(o.pp);
            Collections.sort(l);
            while (l.size() > KEEP) l.remove(l.size() - 1);
            added = true;
            newest = o.pp;
        }
        while (ids.size() > 300) ids.remove(ids.keySet().iterator().next());
        if (!added) return null;
        return applyAvg(t, l, cfg, ap, "nowa oferta " + AnalizaScreen.fmtR(newest) + "$/szt.");
    }

    /**
     * Snajper kupił ofertę (za sztukę pp): znika z rynku - gdy była wśród najtańszych z analizy, średnia liczy się bez
     * niej (cena sprzedaży w górę, skup za nią).
     */
    static String onPurchase(BkBridge.BkTarget t, double pp, AnalizaConfig cfg, Autopilot ap) {
        List<Double> l = known.get(t.key());
        if (l == null || l.isEmpty() || !(pp > 0)) return null;
        int best = -1;
        double err = Double.MAX_VALUE;
        for (int i = 0; i < l.size(); i++) {
            double e = Math.abs(l.get(i) - pp);
            if (e < err) { err = e; best = i; }
        }
        if (best < 0 || err > Math.max(0.01, pp * 0.001)) return null;
        l.remove(best);
        return applyAvg(t, l, cfg, ap, "kupiona oferta " + AnalizaScreen.fmtR(pp) + "$/szt. zeszła z rynku");
    }

    /** Nowa średnia 3 najtańszych -> cena sprzedaży (gdy przedmiot ma Auto: TAK) i skup ze wzoru. */
    private static String applyAvg(BkBridge.BkTarget t, List<Double> l, AnalizaConfig cfg, Autopilot ap, String why) {
        if (!autoSell(t, ap)) return null;
        double total = sellTotal(t, avg3(l), ap);
        if (Double.isNaN(total)) return null;
        double old = t.currentSellPrice();
        if (Math.abs(old - total) < 0.5) return null;
        t.applySellPrice(total);
        sync(t, cfg, ap);
        BkBridge.commit();
        int count = Math.max(1, t.sellCount());
        return t.displayName() + ": monitorowanie rynku (" + why + ") - sprzedaż " + AnalizaScreen.fmtR(old) + "$ -> "
                + AnalizaScreen.fmtR(total) + "$" + (count > 1 ? " za " + count + " szt." : "") + ", " + describe(t, cfg, ap);
    }

    /** Najtańsze znane ceny przedmiotu (kopia; do testów i panelu). */
    static List<Double> knownPrices(String key) {
        List<Double> l = known.get(key);
        return l == null ? List.of() : new ArrayList<>(l);
    }

    static void resetForTests() {
        known.clear();
        seen.clear();
    }
}
