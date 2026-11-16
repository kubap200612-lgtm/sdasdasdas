package pl.rynekanaliza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Podpowiedź marży z historii (od 0.5.2) - liczona dla jednego przedmiotu z ostatnich 14 dni:
 * <ol>
 *   <li><b>czas sprzedaży</b> - mediana czasu od zakupu do sprzedaży całego zakupu (historia zakupów i sprzedaży);
 *       bez sprzedaży przyjmuje 6 h, a gdy zakupy sprzed doby wciąż leżą - "ponad dobę",</li>
 *   <li><b>ryzyko spadku</b> - o ile cena (średnia 3 najtańszych z analiz) zwykle spada w takim czasie: dla każdej analizy
 *       najniższa cena w kolejnych H godzinach, z tych spadków 75. percentyl (3 na 4 razy spadek był mniejszy),</li>
 *   <li><b>zysk docelowy</b> - im wolniej się sprzedaje, tym więcej: do 1 h 2%, do 6 h 3%, do doby 5%, dłużej 8%;
 *       +2%, gdy mniej niż połowa zakupów się sprzedała, +1%, gdy danych jest mało.</li>
 * </ol>
 * Marża = sprzedaż za sztukę x (0,97 x spadek + zysk docelowy), zaokrąglona w górę do "okrągłej" kwoty. Po zakupie po skupie
 * (sprzedaż x 0,97 - marża) i spadku ceny o ten typowy spadek zostaje mniej więcej zysk docelowy.
 */
final class MarginHint {
    static final long HOUR = 3600_000L;
    static final long DAY = 24 * HOUR;
    static final long WINDOW = 14 * DAY;       // brane pod uwagę ostatnie 14 dni
    static final long DEFAULT_HOLD = 6 * HOUR; // czas sprzedaży, gdy nic się jeszcze nie sprzedało
    static final int MIN_WINDOWS = 5;          // tyle okien historii cen, żeby ryzyko było "pewne"

    private MarginHint() {}

    /** Wynik podpowiedzi; margin = NaN, gdy podpowiedzi nie ma (problem mówi dlaczego). */
    static final class Hint {
        double margin = Double.NaN;  // proponowana marża za sztukę
        double pct = Double.NaN;     // marża jako % ceny sprzedaży
        double sellPP;               // cena sprzedaży za sztukę, od której liczona
        long holdMs;                 // czas sprzedaży przyjęty do liczenia
        int holdSource;              // 0 = z historii sprzedaży, 1 = brak sprzedaży (przyjęte 6 h), 2 = nic się nie sprzedało od doby
        int lots, lotsSold;          // zakupy starsze niż 1 h / z nich sprzedane w całości
        int unsoldOld;               // zakupy sprzed doby, które się jeszcze nie sprzedały (w całości)
        int soldPieces;              // sprzedane sztuki
        double profitPP = Double.NaN; // dotychczasowy zysk na sztukę ze sprzedanych
        double riskPct = Double.NaN;  // typowy spadek ceny w czasie sprzedaży (%)
        int windows;                 // z ilu okien historii liczony spadek
        boolean shortHistory;        // historia krótsza niż czas sprzedaży - spadek liczony z całej dostępnej
        double basePct, extraPct;    // zysk docelowy i dopłaty (%)
        boolean fewData;
        String problem = "";

        boolean ok() { return !Double.isNaN(margin); }
    }

    static Hint compute(MarketItem it, String key, TradeLedger ledger, PriceHistory hist, long now) {
        Hint h = new Hint();
        h.sellPP = it == null ? 0 : Pricing.sellPP(it);
        if (!(h.sellPP > 0)) {
            h.problem = "brak ceny sprzedaży - podpowiedź po pierwszej analizie";
            return h;
        }
        // 1. historia zakupów i sprzedaży
        List<Long> holds = new ArrayList<>();
        double profit = 0;
        if (ledger != null && key != null) {
            for (TradeLedger.Entry e : ledger.data.entries) {
                if (!key.equals(e.key) || now - e.t > WINDOW || e.t > now) continue;
                if (e.sold > 0) {
                    h.soldPieces += e.sold;
                    profit += e.profit();
                }
                if (e.fullySold() && e.soldAt >= e.t) holds.add(e.soldAt - e.t);
                if (now - e.t >= HOUR) {
                    h.lots++;
                    if (e.fullySold()) h.lotsSold++;
                }
                if (!e.fullySold() && now - e.t >= DAY) h.unsoldOld++;
            }
        }
        if (h.soldPieces > 0) h.profitPP = profit / h.soldPieces;
        if (!holds.isEmpty()) {
            Collections.sort(holds);
            int n = holds.size();
            h.holdMs = n % 2 == 1 ? holds.get(n / 2) : (holds.get(n / 2 - 1) + holds.get(n / 2)) / 2;
            h.holdSource = 0;
        } else if (h.unsoldOld > 0) {
            h.holdMs = DAY + 1;
            h.holdSource = 2;
        } else {
            h.holdMs = DEFAULT_HOLD;
            h.holdSource = 1;
        }
        long horizon = Math.max(HOUR / 4, h.holdMs);

        // 2. ryzyko spadku ceny w czasie sprzedaży (z analiz)
        List<PriceHistory.Point> pts = new ArrayList<>();
        if (hist != null && key != null) {
            for (PriceHistory.Point p : hist.lastScans(key, false, PriceHistory.MAX_POINTS)) {
                if (p.a3 > 0 && p.t <= now && now - p.t <= WINDOW) pts.add(p);
            }
        }
        List<Double> drops = drops(pts, horizon, false);
        if (drops.size() < MIN_WINDOWS && pts.size() >= 3) {
            drops = drops(pts, horizon, true);
            h.shortHistory = !drops.isEmpty();
        }
        h.windows = drops.size();
        if (!drops.isEmpty()) h.riskPct = percentile75(drops) * 100;

        // 3. zysk docelowy i dopłaty
        h.basePct = h.holdMs <= HOUR ? 2 : h.holdMs <= 6 * HOUR ? 3 : h.holdMs <= DAY ? 5 : 8;
        h.fewData = h.lots < 3 || h.shortHistory || Double.isNaN(h.riskPct);
        double extra = 0;
        if (h.lots >= 3 && h.lotsSold * 2 < h.lots) extra += 2;
        if (h.fewData) extra += 1;
        h.extraPct = extra;
        double risk = Double.isNaN(h.riskPct) ? 3 : h.riskPct; // bez historii cen: zakładany spadek 3%
        h.pct = Math.max(1, Math.min(30, 0.97 * risk + h.basePct + extra));
        h.margin = roundUp(h.sellPP * h.pct / 100);
        return h;
    }

    /**
     * Spadki ceny: dla każdej analizy - o ile (ułamek) najniższa cena w następnym oknie horizon była niższa (0, gdy nie
     * spadła). Bez partial tylko okna, które już w całości minęły; z partial - do końca dostępnej historii.
     */
    static List<Double> drops(List<PriceHistory.Point> pts, long horizon, boolean partial) {
        List<Double> out = new ArrayList<>();
        if (pts.size() < 2) return out;
        long last = pts.get(pts.size() - 1).t;
        for (int i = 0; i < pts.size() - 1; i++) {
            PriceHistory.Point p = pts.get(i);
            if (!partial && p.t + horizon > last) break;
            double min = Double.MAX_VALUE;
            for (int j = i + 1; j < pts.size() && pts.get(j).t <= p.t + horizon; j++) min = Math.min(min, pts.get(j).a3);
            if (min == Double.MAX_VALUE) continue; // brak analizy w tym oknie
            out.add(Math.max(0, (p.a3 - min) / p.a3));
        }
        return out;
    }

    static double percentile75(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int idx = (int) Math.ceil(0.75 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }

    /** W górę do "okrągłej" kwoty: 0,01 / 0,1 / 1 / 10 / 50 / 100 / 500 zależnie od wielkości. */
    static double roundUp(double v) {
        if (!(v > 0)) return 0;
        double step = v < 1 ? 0.01 : v < 10 ? 0.1 : v < 100 ? 1 : v < 1000 ? 10 : v < 10_000 ? 50 : v < 100_000 ? 100 : 500;
        double r = Math.ceil(v / step - 1e-9) * step;
        return Math.round(r * 100) / 100.0;
    }

    static String dur(long ms) {
        if (ms > DAY) return "ponad dobę";
        long min = Math.max(1, Math.round(ms / 60_000.0));
        if (min < 60) return min + " min";
        long hrs = Math.round(min / 60.0);
        return hrs + " h";
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, v < 10 ? "%.1f%%" : "%.0f%%", v);
    }

    /** Krótko: "marża 1250$ (3.6%)". */
    static String headline(Hint h) {
        return h.ok() ? "marża " + AnalizaScreen.fmt(h.margin) + "$ (" + pct(h.pct) + ")" : h.problem;
    }

    /** Uzasadnienie w kawałkach (pasek pod wykresem pokazuje tyle, ile się zmieści). */
    static String[] reasons(Hint h) {
        if (!h.ok()) return new String[0];
        String hold = switch (h.holdSource) {
            case 0 -> "sprzedaż zwykle w " + dur(h.holdMs) + " (sprzedane " + h.lotsSold + "/" + h.lots + ")";
            case 2 -> "nic się nie sprzedało od ponad doby (niesprzedane: " + h.unsoldOld + ")";
            default -> "bez sprzedaży w historii - przyjmuję 6 h";
        };
        String risk = Double.isNaN(h.riskPct) ? "brak historii cen - przyjmuję spadek 3%"
                : "cena w tym czasie spada zwykle do " + pct(h.riskPct) + (h.shortHistory ? " (krótka historia)" : "");
        List<String> why = new ArrayList<>();
        if (h.lots >= 3 && h.lotsSold * 2 < h.lots) why.add("słabo się sprzedaje");
        if (h.fewData) why.add("mało danych");
        String gain = "zysk docelowy " + pct(h.basePct) + (h.extraPct > 0 ? " + " + pct(h.extraPct) + " (" + String.join(", ", why) + ")" : "");
        String earned = Double.isNaN(h.profitPP) ? ""
                : (h.profitPP >= 0 ? "zarabiałeś " : "traciłeś ") + AnalizaScreen.fmtR(Math.abs(h.profitPP)) + "$/szt.";
        return new String[]{hold, risk, gain, earned};
    }
}
