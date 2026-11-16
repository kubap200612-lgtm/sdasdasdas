package pl.rynekanaliza;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drobne rachunki na cenach (bez Minecrafta): grosze, mediana, zapis kwoty. Ceny (sprzedaż = średnia 3 najtańszych,
 * skup = sprzedaż x 0,97 - marża) liczy Pricing; dawne reguły autopilota (podcinanie, progi skupu) usunięte w 0.5.0.
 */
public final class AutoPricing {
    private AutoPricing() {}

    /** W dół do pełnych groszy. */
    static double cents(double v) {
        return Math.floor(v * 100 + 1e-6) / 100;
    }

    /** Mediana (0 dla pustej listy). */
    public static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    static String money(double v) {
        return AnalizaScreen.fmtR(v) + "$";
    }
}
