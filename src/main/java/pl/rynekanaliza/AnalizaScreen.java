package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Panel Rynek Analiza: zakładki "Przedmioty", "Auto rynek", "Autopilot", "Historia" i "Ustawienia".
 *
 * Przedmioty to jedna długa lista przewijana kółkiem myszy albo paskiem z prawej. Wiersz: ikona, nazwa, miniwykres,
 * cena sprzedaży (średnia 3 najtańszych - wpisuje ją analiza i monitorowanie rynku), skup (ze wzoru: sprzedaż x 0,97 -
 * marża), pole minimalnej marży (za sztukę) i przycisk "Wykres", który rozwija pod wierszem pełny wykres wszystkich
 * analiz przedmiotu ze statystykami.
 *
 * Marżę ustawia gracz: wpisana w pole trafia na listę chwilę po ostatnim wpisanym znaku (BUY_DEBOUNCE_MS), żeby przy
 * wpisywaniu "1500" snajper nie dostał pośrednich wartości 1, 15, 150...; zamknięcie panelu zapisuje od razu. Puste
 * pole = marża domyślna z Ustawień.
 *
 * Auto rynek: start/stop, co kupuje / wystawia / anty-AFK, stan i liczniki, lista przedmiotów z przełącznikami
 * "Kup" i "Wyst." oraz dziennik; import listy z BK-Rynku.
 */
public class AnalizaScreen extends Screen {
    private enum Tab { ITEMS, AUTO, AUTOPILOT, HISTORY, SETTINGS }

    private interface RowBuilder { void build(int x, int y, int w); }
    private record SettingRow(String label, String shortLabel, RowBuilder builder) {
        SettingRow(String label, RowBuilder builder) { this(label, label, builder); }
    }

    /** Wiersz listy: przedmiot, jego widżety i położenie w całej (przewijanej) zawartości. */
    private static final class Row {
        final BkBridge.BkTarget t;
        TextFieldWidget field;
        ButtonWidget button;
        TextFieldWidget minField, maxField; // autopilot: ręczna min. cena sprzedaży i max skup (pasek pod wykresem)
        boolean open;
        int top, height;
        ItemStack icon;
        Row(BkBridge.BkTarget t) { this.t = t; }
    }

    /** Zwłoka między ostatnim wpisanym znakiem ceny skupu a zapisem na listę (ms). Pole nie jest final - ustawia je test. */
    static long BUY_DEBOUNCE_MS = 1000;
    /** Ile ostatnich skanów pokazuje wykres i liczą statystyki. */
    static final int CHART_SCANS = Integer.MAX_VALUE; // wszystkie analizy

    private static final int ROW_H = 24;
    private static final int SCROLL_STEP = 24;
    private static final int BLOCK_EXTRA = 88; // wysokość rozwiniętego bloku poza samym wykresem (nagłówek, osie, karty, linia szczegółów, odstęp)
    private static final int STRIP_H = 48;     // pasek na dole bloku (Auto, min. sprzedaż, max skup, wzór skupu, podpowiedź marży)
    private static final int STRIP_H_COMPACT = 34; // niski widok: bez osobnej linii podpowiedzi (przycisk "Podp." obok wzoru)
    private static final int EMPTY_CHART_H = 56;
    private static final int BUY_LINE = 0xFFB58CFF; // linia skupu autopilota na wykresie

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int LIGHT = 0xFFDDDDDD;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    // wykres
    private static final int ACCENT = 0xFF58B8FF;
    private static final int MEAN = 0xFFE0A84A;
    private static final int UP = 0xFF5FD38D;
    private static final int HINT = 0xFFB9E3C6; // podpowiedź marży
    private static final int DOWN = 0xFFE5534B;
    private static final int BLOCK_BG = 0xFF15171D;
    private static final int BLOCK_BORDER = 0xFF2E323D;
    private static final int PLOT_BG = 0xFF0E1015;
    private static final int CARD_BG = 0xFF1C1F27;
    private static final int CARD_BORDER = 0xFF2A2E39;
    private static final int GRID = 0x26FFFFFF;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter F_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);
    private static final DateTimeFormatter F_DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZONE);
    private static final DateTimeFormatter F_FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZONE);
    private static final long STATUS_MS = 15_000L;
    /** Połowa odstępu między przyciskami < i > przy numerze strony ustawień. */
    private static final int PAGER_GAP = 80;

    private final AnalizaConfig cfg;
    private final List<BkBridge.BkTarget> all = new ArrayList<>();
    private List<BkBridge.BkTarget> filtered = new ArrayList<>();
    private String loadError = "";

    private Tab tab = Tab.ITEMS;
    private int settingsPage = 0;
    private String filter = "";
    private KeyBinding listening;
    /** Czeka na klawisz "dodaj przedmiot" (zapisywany w configu, nie w Sterowaniu Minecrafta). */
    private boolean listeningAdd;
    private boolean bgDone;

    // układ panelu
    private int panelX, panelW;
    private int listTop, listBottom, viewportH, listL, listR;
    private int colName, colNameW, colSpark, colSparkW, colAvg, colPrice, colNumW, colBuy, colBuyW, colChart, colChartW;
    private boolean showIcons;
    private TextFieldWidget filterWidget;

    // lista
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> expanded = new LinkedHashSet<>();
    private final Map<String, AnalizaConfig.ScanRecord> scanCache = new HashMap<>();
    private final Map<String, List<PriceHistory.Point>> pointCache = new HashMap<>();
    private int scroll, maxScroll, contentH;
    private int blockPlotH = 80, blockH = 80 + BLOCK_EXTRA + STRIP_H;
    /** Wysokość paska pod wykresem: pełny (z linią podpowiedzi marży) albo niski, gdy blok inaczej nie mieści się w widoku. */
    private int stripH = STRIP_H;
    private int stripPillW, stripMinLabelX, stripMinX, stripMaxLabelX, stripMaxX, stripFieldW;
    private String stripMinLabel = "Min.", stripMaxLabel = "Max";
    private boolean draggingBar;
    private int dragGrab;

    // historia zakupów i sprzedaży
    private int histScroll, histMaxScroll;
    private boolean histByItem;        // widok "Wg przedmiotów" zamiast listy zakupów
    private long confirmClearUntil;    // "Wyczyść historię": drugie kliknięcie przed tą chwilą czyści
    private ButtonWidget histClearButton;

    // ustawienia
    private final List<SettingRow> settingRows = new ArrayList<>();
    private int settingsPerPage = 6, settingsVx;

    // auto rynek: przewijanie listy przedmiotów, klikalne przełączniki {x, y, szer., wys., nr przedmiotu, 0 = Kup / 1 = Wyst.}
    private int amScroll, amMaxScroll;
    private final List<int[]> amPills = new ArrayList<>();
    private int amListTop, amListBottom;
    private long confirmImportUntil;
    private ButtonWidget importButton;

    // ręczna zmiana ceny skupu (zapis na listę po chwili od ostatniego znaku)
    private final Map<String, Long> pendingBuy = new LinkedHashMap<>();        // klucz przedmiotu -> kiedy zapisać
    private final Map<String, Double> pendingBuyValue = new LinkedHashMap<>(); // klucz przedmiotu -> nowa cena skupu
    private String status = "";
    private int statusColor = GRAY;
    private long statusAt;

    public AnalizaScreen(AnalizaConfig cfg) {
        super(Text.literal("Rynek Analiza"));
        this.cfg = cfg;
        try {
            all.addAll(BkBridge.targets("", cfg));
        } catch (Throwable t) {
            loadError = t.toString();
        }
        applyFilter();
    }

    private void applyFilter() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        filtered = new ArrayList<>();
        for (BkBridge.BkTarget t : all) {
            if (f.isEmpty() || t.displayName().toLowerCase(Locale.ROOT).contains(f)) filtered.add(t);
        }
    }

    private BkBridge.BkTarget findTarget(String key) {
        for (BkBridge.BkTarget t : all) if (t.key().equals(key)) return t;
        return null;
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    /** Czy wszystkie napisy mieszczą się w przyciskach tej szerokości. */
    private boolean labelsFit(String[] labels, int w) {
        for (String l : labels) if (this.textRenderer.getWidth(l) + 6 > w) return false;
        return true;
    }

    private void switchTab(Tab t) {
        tab = t;
        listening = null;
        draggingBar = false;
        confirmClearUntil = 0;
        rebuild();
    }

    // ---------- budowanie widżetów ----------
    @Override
    protected void init() {
        panelW = Math.min(540, this.width - 8);
        panelX = (this.width - panelW) / 2;

        // zakładki: pełne nazwy, na wąskim ekranie skróty (a przy bardzo wąskim "X" zamiast "Zamknij")
        String[] names = {"Przedmioty", "Auto rynek", "Autopilot", "Historia", "Ustawienia"};
        String[] brief = {"Przedm.", "Auto", "Autop.", "Historia", "Ustaw."};
        Tab[] tabs = {Tab.ITEMS, Tab.AUTO, Tab.AUTOPILOT, Tab.HISTORY, Tab.SETTINGS};
        int closeW = panelW < 400 ? 56 : 70;
        String closeLabel = "Zamknij";
        int tabW = Math.min(100, (panelW - closeW - 4 - 4 * 4) / 5);
        if (!labelsFit(names, tabW)) {
            names = brief;
            if (!labelsFit(brief, tabW)) {
                closeW = 20;
                closeLabel = "X";
                tabW = Math.min(100, (panelW - closeW - 4 - 4 * 4) / 5);
            }
        }
        for (int i = 0; i < tabs.length; i++) {
            final Tab target = tabs[i];
            ButtonWidget b = ButtonWidget.builder(Text.literal(names[i]), x -> switchTab(target))
                    .dimensions(panelX + i * (tabW + 4), 22, tabW, 20).build();
            b.active = tab != target;
            addDrawableChild(b);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal(closeLabel), b -> close())
                .dimensions(panelX + panelW - closeW, 22, closeW, 20).build());

        histClearButton = null;
        importButton = null;
        switch (tab) {
            case ITEMS -> initItems();
            case AUTO -> initAuto();
            case AUTOPILOT -> initAutopilot();
            case HISTORY -> initHistory();
            case SETTINGS -> initSettings();
        }
    }

    // ----- zakładka Przedmioty -----
    private void initItems() {
        int fieldW = Math.max(90, Math.min(150, (int) (panelW * 0.28)));
        int startX = panelX + 52 + fieldW + 6;
        int startW = panelW < 400 ? 84 : 100;
        TextFieldWidget filterField = new TextFieldWidget(this.textRenderer, panelX + 52, 48, fieldW, 16, Text.empty());
        filterField.setMaxLength(40);
        filterField.setText(filter);
        filterField.setChangedListener(s -> {
            filter = s;
            applyFilter();
            scroll = 0;
            rebuild();
            // po przebudowie widżetów przywracamy fokus na polu wyszukiwania
            setFocused(filterWidget);
            filterWidget.setFocused(true);
        });
        filterWidget = addDrawableChild(filterField);

        // ręczna analiza (bez snajpera); start/stop całego auto rynku jest w zakładce "Auto rynek" i pod klawiszem
        String startLabel = filter.isBlank() ? "Analizuj" : "Analizuj (filtr)";
        addDrawableChild(ButtonWidget.builder(Text.literal(startLabel), b -> {
            String f = filter.trim();
            close();
            RynekAnalizaMod.startAnalysis(f);
        }).dimensions(startX, 46, startW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stop"), b -> MarketAnalyzer.stop())
                .dimensions(startX + startW + 4, 46, 50, 20).build());

        // obszar listy
        listTop = 84;
        listBottom = this.height - 24;
        viewportH = Math.max(ROW_H, listBottom - listTop);
        listL = panelX + 2;
        listR = panelX + panelW - 8; // po prawej zostaje miejsce na pasek przewijania

        // kolumny liczone od prawej: przycisk Wykres, pole marży, Skup (ze wzoru), Sprzedaż, miniwykres, nazwa
        showIcons = panelW >= 400;
        colNumW = Math.max(this.textRenderer.getWidth("000000$") + 4, (int) (panelW * 0.13));
        colChartW = Math.max(54, Math.min(72, (int) (panelW * 0.13)));
        colChart = listR - 2 - colChartW;
        colBuyW = Math.max(52, (int) (panelW * 0.15));
        colBuy = colChart - 6 - colBuyW;
        colPrice = colBuy - 6 - colNumW;
        colAvg = colPrice - 4 - colNumW;
        colSparkW = panelW >= 440 ? 58 : 0;
        colSpark = colAvg - 6 - colSparkW;
        colName = listL + 4 + (showIcons ? 20 : 0);
        colNameW = Math.max(40, (colSparkW > 0 ? colSpark : colAvg) - 6 - colName);

        stripH = viewportH >= BLOCK_EXTRA + STRIP_H + 40 ? STRIP_H : STRIP_H_COMPACT;
        blockPlotH = Math.max(40, Math.min(130, viewportH - ROW_H - BLOCK_EXTRA - stripH - 4));
        blockH = blockPlotH + BLOCK_EXTRA + stripH;

        // pasek autopilota: [Auto: TAK]  Min. [pole]  Max [pole]
        int sbx = listL + 2, sbw = listR - listL - 4;
        stripPillW = this.textRenderer.getWidth("Auto: NIE") + 10;
        boolean wideStrip = sbw >= 360;
        stripMinLabel = wideStrip ? "Min. sprzedaż" : "Min.";
        stripMaxLabel = wideStrip ? "Max skup" : "Max";
        int sx = sbx + 6 + stripPillW + 8;
        int rest = sbx + sbw - 6 - sx - this.textRenderer.getWidth(stripMinLabel) - 4 - this.textRenderer.getWidth(stripMaxLabel) - 4 - 8;
        stripFieldW = Math.max(36, Math.min(80, rest / 2));
        stripMinLabelX = sx;
        stripMinX = sx + this.textRenderer.getWidth(stripMinLabel) + 4;
        stripMaxLabelX = stripMinX + stripFieldW + 8;
        stripMaxX = stripMaxLabelX + this.textRenderer.getWidth(stripMaxLabel) + 4;

        rows.clear();
        scanCache.clear();
        pointCache.clear();
        for (BkBridge.BkTarget t : filtered) {
            Row r = new Row(t);
            r.open = expanded.contains(t.key());
            AnalizaConfig.ScanRecord sc = t.lastScan();
            if (sc != null) scanCache.put(t.key(), sc);
            r.field = addSelectableChild(marginField(t));
            r.button = addSelectableChild(chartButton(r));
            if (r.open) {
                r.minField = addSelectableChild(apField(t, true));
                r.maxField = addSelectableChild(apField(t, false));
            }
            rows.add(r);
        }
        layoutRows();
        positionWidgets();
    }

    /**
     * Pole minimalnej marży (za sztukę) - wpisana wartość trafia na listę po BUY_DEBOUNCE_MS, a skup liczy się od razu ze
     * wzoru (sprzedaż x 0,97 - marża). Puste pole = marża domyślna z Ustawień.
     */
    private TextFieldWidget marginField(BkBridge.BkTarget t) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, colBuy, 0, colBuyW, 16, Text.empty());
        f.setMaxLength(12);
        f.setTextPredicate(s -> s.matches("\\d*[.,]?\\d*"));
        Double m = t.item().margin;
        f.setText(m != null && m >= 0 ? fmt(m) : "");
        // listener dopiero po setText, żeby samo otwarcie panelu niczego nie zapisywało
        f.setChangedListener(s -> {
            Double v = s.isBlank() ? Double.valueOf(Double.NaN) : parse(s);
            if (v == null) return; // np. sam przecinek - czekamy na resztę
            pendingBuyValue.put(t.key(), v);
            pendingBuy.put(t.key(), System.currentTimeMillis() + BUY_DEBOUNCE_MS);
        });
        return f;
    }

    /** Pole autopilota w pasku pod wykresem: ręczna minimalna cena sprzedaży (za wystawkę) albo maksymalny skup (za sztukę). */
    private TextFieldWidget apField(BkBridge.BkTarget t, boolean min) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, min ? stripMinX : stripMaxX, 0, stripFieldW, 14, Text.empty());
        f.setMaxLength(12);
        f.setTextPredicate(s -> s.matches("\\d*[.,]?\\d*"));
        Autopilot ap = RynekAnalizaMod.autopilot();
        Autopilot.ItemState st = ap == null ? null : ap.peek(t.key());
        int count = Math.max(1, t.sellCount());
        double v = st == null ? 0 : (min ? st.manualMinSell * count : st.manualMaxBuy);
        f.setText(v > 0 ? fmt(v) : "");
        f.setChangedListener(s -> {
            if (ap == null) return;
            Double x = parse(s);
            Autopilot.ItemState state = ap.state(t.key());
            double val = x != null && x > 0 ? x : 0;
            if (min) state.manualMinSell = val / count; // w polu cena całej wystawki, w stanie za sztukę
            else state.manualMaxBuy = val;
            ap.markChanged();
        });
        return f;
    }

    private ButtonWidget chartButton(Row r) {
        return ButtonWidget.builder(Text.literal(r.open ? "Ukryj" : "Wykres"), b -> toggleChart(r))
                .dimensions(colChart, 0, colChartW, 20).build();
    }

    /** Rozwija / zwija wykres pod wierszem. Po rozwinięciu przewija listę tak, żeby wiersz i jego wykres były widoczne. */
    private void toggleChart(Row r) {
        String key = r.t.key();
        boolean open = !expanded.contains(key);
        if (open) expanded.add(key); else expanded.remove(key);
        rebuild();
        if (open) {
            for (Row nr : rows) {
                if (!nr.t.key().equals(key)) continue;
                int bottom = nr.top + nr.height;
                if (bottom - scroll > viewportH) scroll = bottom - viewportH; // cały wykres w widoku (gdy wiersz + wykres nie mieszczą się razem, wiersz wyjedzie u góry)
                if (nr.top < scroll && nr.height <= viewportH) scroll = nr.top;
                // nagłówek bloku (z przyciskiem "Ukryj") zawsze w widoku; blok wyższy niż widok (małe okno) zaczyna się
                // od nagłówka, a pasek autopilota na dole widać po przewinięciu
                scroll = Math.min(scroll, nr.top + ROW_H);
                scroll = Math.max(0, Math.min(scroll, maxScroll));
                positionWidgets();
            }
        }
    }

    /** Wysokość części bloku z wykresem (bez paska autopilota). */
    private int chartPart(Row r) {
        return points(r.t.key()).isEmpty() ? EMPTY_CHART_H : blockPlotH + BLOCK_EXTRA;
    }

    /** Górna krawędź paska autopilota rozwiniętego wiersza (na ekranie). */
    private int stripTop(Row r) {
        return listTop + r.top + ROW_H - scroll + chartPart(r) - 4;
    }

    /** Przełącznik "Auto: TAK/NIE" w pasku autopilota: {x, y, szerokość, wysokość}. */
    int[] autoRect(int rowIndex) {
        Row r = rows.get(rowIndex);
        return new int[]{listL + 2 + 6, stripTop(r) + 3, stripPillW, 13};
    }

    /** Przycisk podpowiedzi marży w pasku pod wykresem: {x, y, szerokość, wysokość}; pełny pasek - "Zastosuj" w 3. linii, niski - "Podp. X$" obok wzoru. */
    int[] hintRect(int rowIndex) {
        Row r = rows.get(rowIndex);
        String lbl = hintLabel(r.t, hint(r.t));
        int w = this.textRenderer.getWidth(lbl) + 10;
        return new int[]{listL + 2 + (listR - listL - 4) - 6 - w, stripTop(r) + (stripH == STRIP_H ? 31 : 19), w, 13};
    }

    private String hintLabel(BkBridge.BkTarget t, MarginHint.Hint h) {
        if (hintApplied(t, h)) return "Ustawiona";
        return stripH == STRIP_H || !h.ok() ? "Zastosuj" : "Podp. " + fmtR(h.margin) + "$";
    }

    /** Podpowiedź marży dla przedmiotu (z historii zakupów, sprzedaży i cen). */
    private static MarginHint.Hint hint(BkBridge.BkTarget t) {
        Autopilot ap = RynekAnalizaMod.autopilot();
        return MarginHint.compute(t.item(), t.key(), ap == null ? null : ap.ledger, RynekAnalizaMod.history(), System.currentTimeMillis());
    }

    private static boolean hintApplied(BkBridge.BkTarget t, MarginHint.Hint h) {
        Double m = t.item().margin;
        return h.ok() && m != null && Math.abs(m - h.margin) < 0.005;
    }

    /** "Zastosuj": marża z podpowiedzi trafia do pola Marża i od razu do listy (skup przeliczony ze wzoru). */
    private void applyHint(Row r) {
        MarginHint.Hint h = hint(r.t);
        if (!h.ok()) {
            setStatus(r.t.displayName() + ": " + h.problem, YELLOW);
            return;
        }
        if (hintApplied(r.t, h)) {
            setStatus(r.t.displayName() + ": marża już jest taka jak podpowiedź (" + fmt(h.margin) + "$)", GRAY);
            return;
        }
        r.field.setText(fmt(h.margin));
        flushPending(true);
        setStatus("Zapisano podpowiedź - " + r.t.displayName() + ": marża " + fmt(h.margin) + "$ -> skup " + fmt(r.t.currentBuyPrice()) + "$", GREEN);
    }

    /** Przycisk "Ukryj" w nagłówku rozwiniętego bloku: {x, y, szerokość, wysokość} na ekranie (przy wierszu o danym numerze). */
    int[] closeRect(int rowIndex) {
        Row r = rows.get(rowIndex);
        int bt = listTop + r.top + ROW_H - scroll;
        int w = this.textRenderer.getWidth("Ukryj") + 10;
        return new int[]{listL + 2 + (listR - listL - 4) - 4 - w, bt + 2, w, 13};
    }

    /** Wysokości wierszy i całej zawartości listy; poprawia położenie przewinięcia. */
    private void layoutRows() {
        int y = 0;
        for (Row r : rows) {
            r.top = y;
            r.height = ROW_H + (r.open ? chartPart(r) + stripH : 0);
            y += r.height;
        }
        contentH = y;
        maxScroll = Math.max(0, contentH - viewportH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** Ustawia widżety wierszy według przewinięcia; wiersze poza widokiem są ukryte (nie reagują na kliknięcia). */
    private void positionWidgets() {
        for (Row r : rows) {
            int sy = listTop + r.top - scroll;
            boolean vis = sy + ROW_H > listTop && sy < listBottom;
            place(r.field, sy + 4, vis);
            place(r.button, sy + 2, vis);
            if (r.minField != null) {
                int fy = stripTop(r) + 2;
                boolean fv = fy >= listTop && fy + 14 <= listBottom;
                place(r.minField, fy, fv);
                place(r.maxField, fy, fv);
            }
        }
    }

    private static void place(ClickableWidget w, int y, boolean visible) {
        w.setY(y);
        w.visible = visible;
        if (!visible && w.isFocused()) w.setFocused(false);
    }

    private void setScroll(int v) {
        scroll = Math.max(0, Math.min(v, maxScroll));
    }

    // ----- zapis ręcznie wpisanej marży -----
    /**
     * Zapisuje na listę marże wpisane w polach (gdy minęła zwłoka albo force = true), przelicza skup ze wzoru i raz
     * zatwierdza zmiany. NaN = puste pole (marża domyślna).
     */
    private void flushPending(boolean force) {
        if (pendingBuy.isEmpty()) return;
        long now = System.currentTimeMillis();
        int changed = 0;
        String single = null;
        Autopilot ap = RynekAnalizaMod.autopilot();
        for (Iterator<Map.Entry<String, Long>> it = pendingBuy.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (!force && e.getValue() > now) continue;
            it.remove();
            Double v = pendingBuyValue.remove(e.getKey());
            BkBridge.BkTarget t = findTarget(e.getKey());
            if (t == null || v == null) continue;
            Double want = v.isNaN() ? null : v;
            Double old = t.item().margin;
            if (java.util.Objects.equals(old, want)) continue;
            t.item().margin = want;
            ItemStore st = BkBridge.store();
            if (st != null) st.touch();
            Pricing.sync(t, cfg, ap);
            changed++;
            single = t.displayName() + ": marża " + (want == null ? "domyślna" : fmt(want) + "$") + " -> skup " + fmt(t.currentBuyPrice()) + "$";
        }
        if (changed > 0) BkBridge.commit();
        if (changed == 1) setStatus("Zapisano - " + single, GREEN);
        else if (changed > 0) setStatus("Marża zapisana: " + changed + " przedm. (skup przeliczony ze wzoru)", GREEN);
    }

    private void setStatus(String text, int color) {
        status = text;
        statusColor = color;
        statusAt = System.currentTimeMillis();
    }

    // ----- zakładka Ustawienia -----
    private void initSettings() {
        int vx = panelX + (int) (panelW * 0.56);
        int vw = (int) (panelW * 0.40);
        settingsVx = vx;
        settingRows.clear();
        settingRows.add(new SettingRow("Klawisz: otwórz panel", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.openKey)));
        settingRows.add(new SettingRow("Klawisz: start/stop (auto rynek + analiza)", "Klawisz: start/stop", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.autoKey)));
        settingRows.add(new SettingRow("Klawisz: pomiń analizę", (x, y, w) -> addKeyButton(x, y, w, RynekAnalizaMod.toggleKey)));
        settingRows.add(new SettingRow("Klawisz: dodaj przedmiot z rynku (najedź myszką)", "Klawisz: dodaj przedmiot", (x, y, w) -> addAddKeyButton(x, y, w)));
        settingRows.add(new SettingRow("Szybkość analizy", "Szybkość", this::addSpeedButton));
        settingRows.add(new SettingRow("Min. ofert do ceny (1-3)", "Min. ofert (1-3)", (x, y, w) -> addNumberField(x, y, w, cfg.minListings, true,
                v -> cfg.minListings = (int) Math.max(1, Math.min(3, Math.round(v))))));
        // wzór: skup = sprzedaż x podatek - marża (marża przedmiotu w liście, bez niej - domyślna)
        settingRows.add(new SettingRow("Marża domyślna ($/szt., 0 = nie kupuj bez marży)", "Marża domyślna ($)", (x, y, w) -> addNumberField(x, y, w, cfg.defaultMinMargin, false,
                v -> cfg.defaultMinMargin = AnalizaConfig.clamp(v, 0, 1e12, 0))));
        settingRows.add(new SettingRow("Podatek od sprzedaży (np. 0.97)", "Podatek (np. 0.97)", (x, y, w) -> addNumberField(x, y, w, cfg.taxFactor, false,
                v -> cfg.taxFactor = v > 0 && v <= 1 ? v : 0.97)));
        settingRows.add(new SettingRow("Snajper: najwyżej % salda na 1 zakup (0 = bez limitu)", "Max % salda na zakup", (x, y, w) -> addNumberField(x, y, w,
                cfg.buyBalancePct, false, v -> cfg.buyBalancePct = AnalizaConfig.clamp(v, 0, 100, 70))));
        settingRows.add(new SettingRow("Pauza na ostatniej stronie (ticki)", "Pauza na końcu (ticki)", (x, y, w) -> addNumberField(x, y, w, cfg.highlightPauseTicks, true, v -> cfg.highlightPauseTicks = (int) Math.round(v))));
        settingRows.add(new SettingRow("Podgląd na rynku (żółte oferty)", "Podgląd na rynku", (x, y, w) -> addToggle(x, y, w, cfg.showOverlay, v -> cfg.showOverlay = v)));
        settingRows.add(new SettingRow("Wznów auto rynek po analizie", "Wznów auto rynek", (x, y, w) -> addToggle(x, y, w, cfg.resumeBk, v -> cfg.resumeBk = v)));
        settingRows.add(new SettingRow("Przerwa przed i po analizie, gdy działa auto rynek (s)", "Przerwa przy auto rynku (s)", (x, y, w) -> addNumberField(x, y, w, cfg.bkPauseSec, false,
                v -> cfg.bkPauseSec = Math.max(0, Math.min(15, v)))));
        settingRows.add(new SettingRow("Pomijaj ulepszenia % (np. Dodatkowe obrażenia)", "Pomijaj ulepszenia %", (x, y, w) -> addToggle(x, y, w, cfg.ignorePercentLore,
                v -> cfg.ignorePercentLore = v)));
        settingRows.add(new SettingRow("Ścisłe sprawdzanie komponentów", "Ścisłe komponenty", (x, y, w) -> addToggle(x, y, w, cfg.strictComponents, v -> cfg.strictComponents = v)));
        settingRows.add(new SettingRow("Zapisuj historię cen (wykresy)", (x, y, w) -> addToggle(x, y, w, cfg.historyEnabled, v -> cfg.historyEnabled = v)));
        settingRows.add(new SettingRow("Obserwuj rynek, gdy działa auto rynek", "Obserwuj rynek", (x, y, w) -> addToggle(x, y, w, cfg.passiveObserve, v -> cfg.passiveObserve = v)));
        settingRows.add(new SettingRow("Znaczek 'Rynek Analiza: działa'", "Znaczek 'działa'", (x, y, w) -> addToggle(x, y, w, cfg.showBadge, v -> cfg.showBadge = v)));
        settingRows.add(new SettingRow("Odstęp zapisów z obserwacji (s)", "Odstęp obserwacji (s)", (x, y, w) -> addNumberField(x, y, w, cfg.historyIntervalSec, true, v -> cfg.historyIntervalSec = (int) Math.round(v))));
        settingRows.add(new SettingRow("Wykresy: też obserwacje rynku", (x, y, w) -> addToggle(x, y, w, cfg.chartObserved, v -> cfg.chartObserved = v)));
        // analiza auto rynku (włączanie w zakładce Auto rynek) i autopilot (włączanie i tryb w zakładce Autopilot)
        settingRows.add(new SettingRow("Analiza wszystkich co (min)", "Analiza co (min)", (x, y, w) -> addNumberField(x, y, w, cfg.apIntervalMin, true,
                v -> cfg.apIntervalMin = (int) Math.max(2, Math.round(v)))));
        settingRows.add(new SettingRow("Ponowna analiza po tylu zakupach (0 = nie)", "Ponowna analiza po zakup.", (x, y, w) -> addNumberField(x, y, w, cfg.apRescanBuys, true,
                v -> cfg.apRescanBuys = (int) Math.max(0, Math.round(v)))));
        settingRows.add(new SettingRow("Ponowna analiza: zakupy w ciągu (min)", "Zakupy w ciągu (min)", (x, y, w) -> addNumberField(x, y, w, cfg.apRescanMin, true,
                v -> cfg.apRescanMin = (int) AnalizaConfig.clamp(v, 1, 60, 10))));
        settingRows.add(new SettingRow("Autopilot: budżet na dzień ($, 0 = bez limitu)", "Budżet/dzień ($)", (x, y, w) -> addNumberField(x, y, w, cfg.apBudget, false,
                v -> cfg.apBudget = Math.max(0, v))));
        // wystawianie bez straty
        settingRows.add(new SettingRow("Wystawianie: tylko bez straty (droższe kupione czekają)", "Wystawiaj bez straty", (x, y, w) -> addToggle(x, y, w, cfg.amSellNoLoss, v -> cfg.amSellNoLoss = v)));
        settingRows.add(new SettingRow("Wystawianie: zysk co najmniej ($/szt., 0 = bez straty)", "Zysk wystawki od ($)", (x, y, w) -> addNumberField(x, y, w, cfg.amSellMinProfit, false,
                v -> cfg.amSellMinProfit = Math.max(0, v))));
        settingRows.add(new SettingRow("Wystawianie: czekaj na cenę najwyżej (h, 0 = bez limitu)", "Czekaj najwyżej (h)", (x, y, w) -> addNumberField(x, y, w, cfg.amSellMaxWaitH, false,
                v -> cfg.amSellMaxWaitH = AnalizaConfig.clamp(v, 0, 24 * 30, 0))));
        // auto rynek (start/stop i przełączniki w zakładce Auto rynek)
        settingRows.add(new SettingRow("Auto rynek: komenda rynku (bez /)", "Komenda rynku", (x, y, w) -> addTextField(x, y, w, cfg.amCommand, v -> cfg.amCommand = v)));
        settingRows.add(new SettingRow("Auto rynek: odświeżanie listy co (ms)", "Odświeżanie (ms)", (x, y, w) -> addNumberField(x, y, w, cfg.amRefreshMs, true,
                v -> cfg.amRefreshMs = (int) AnalizaConfig.clamp(v, 100, 5000, 200))));
        settingRows.add(new SettingRow("Auto rynek: restart rynku co (s, 0 = nie)", "Restart co (s)", (x, y, w) -> addNumberField(x, y, w, cfg.amRestartSec, true,
                v -> cfg.amRestartSec = v <= 0 ? 0 : (int) AnalizaConfig.clamp(v, 15, 3600, 60))));
        settingRows.add(new SettingRow("Auto rynek: restart, gdy okno stoi (s)", "Restart po zacięciu (s)", (x, y, w) -> addNumberField(x, y, w, cfg.amStuckSec, true,
                v -> cfg.amStuckSec = (int) AnalizaConfig.clamp(v, 5, 60, 10))));
        settingRows.add(new SettingRow("Auto rynek: przerwa przy restarcie (ms)", "Przerwa restartu (ms)", (x, y, w) -> addNumberField(x, y, w, cfg.amRestartPauseMs, true,
                v -> cfg.amRestartPauseMs = (int) AnalizaConfig.clamp(v, 200, 10_000, 1500))));
        settingRows.add(new SettingRow("Auto rynek: podświetlanie ofert do kupienia", "Podświetlanie ofert", (x, y, w) -> addToggle(x, y, w, cfg.amHighlight, v -> cfg.amHighlight = v)));
        settingRows.add(new SettingRow("Auto rynek: anty-AFK co (min)", "Anty-AFK co (min)", (x, y, w) -> addNumberField(x, y, w, cfg.amAfkMin, true,
                v -> cfg.amAfkMin = (int) AnalizaConfig.clamp(v, 1, 30, 4))));
        settingRows.add(new SettingRow("Auto rynek: anty-AFK - liczba skoków", "Skoków", (x, y, w) -> addNumberField(x, y, w, cfg.amAfkJumps, true,
                v -> cfg.amAfkJumps = (int) AnalizaConfig.clamp(v, 1, 10, 3))));
        settingRows.add(new SettingRow("Auto rynek: skup za sztukę (tryb Unit)", "Skup za sztukę (Unit)", (x, y, w) -> addToggle(x, y, w, cfg.unitModeGlobal, v -> cfg.unitModeGlobal = v)));
        settingRows.add(new SettingRow("Auto rynek: slot odświeżania (PPM)", "Slot odświeżania", (x, y, w) -> addNumberField(x, y, w, cfg.amRefreshSlot, true,
                v -> cfg.amRefreshSlot = (int) AnalizaConfig.clamp(v, 0, 53, 0))));
        settingRows.add(new SettingRow("Auto rynek: slot przycisku sortowania", "Slot sortowania", (x, y, w) -> addNumberField(x, y, w, cfg.amSortSlot, true,
                v -> cfg.amSortSlot = (int) AnalizaConfig.clamp(v, 0, 53, 53))));
        settingRows.add(new SettingRow("Auto rynek: slot potwierdzenia zakupu", "Slot potwierdzenia", (x, y, w) -> addNumberField(x, y, w, cfg.amConfirmSlot, true,
                v -> cfg.amConfirmSlot = (int) AnalizaConfig.clamp(v, 0, 53, 11))));
        settingRows.add(new SettingRow("Auto rynek: nazwa przycisku potwierdzenia", "Nazwa potwierdzenia", (x, y, w) -> addTextField(x, y, w, cfg.amConfirmName, v -> cfg.amConfirmName = v)));
        settingRows.add(new SettingRow("Auto rynek: komenda wystawiania", "Komenda wystawiania", (x, y, w) -> addTextField(x, y, w, cfg.amSellCommand, v -> {
            if (v.contains("{price}")) cfg.amSellCommand = v;
        })));

        int top = 52, step = 22;
        settingsPerPage = Math.max(3, (this.height - top - 58) / step);
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        settingsPage = Math.min(Math.max(0, settingsPage), pages - 1);
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) settingRows.get(i).builder().build(vx, top + (i - from) * step, vw);
        if (pages > 1) addPager(this.height - 40, pages, () -> settingsPage > 0, () -> settingsPage < pages - 1, () -> settingsPage--, () -> settingsPage++);
    }

    private void addPager(int y, int pages, java.util.function.BooleanSupplier hasPrev, java.util.function.BooleanSupplier hasNext,
                          Runnable prevAction, Runnable nextAction) {
        ButtonWidget prev = ButtonWidget.builder(Text.literal("<"), b -> { prevAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 - PAGER_GAP - 24, y, 24, 20).build();
        prev.active = hasPrev.getAsBoolean();
        addDrawableChild(prev);
        ButtonWidget next = ButtonWidget.builder(Text.literal(">"), b -> { nextAction.run(); rebuild(); })
                .dimensions(panelX + panelW / 2 + PAGER_GAP, y, 24, 20).build();
        next.active = hasNext.getAsBoolean();
        addDrawableChild(next);
    }

    static final String[] SPEED_NAMES = {"Bezpieczna", "Szybka", "Maksymalna"};

    /** Szybkość analizy: bezpieczna / szybka / maksymalna (przerwy między kliknięciami i czekanie na stronę). */
    private void addSpeedButton(int x, int y, int w) {
        addDrawableChild(ButtonWidget.builder(Text.literal(SPEED_NAMES[Math.floorMod(cfg.speed, 3)]), b -> {
            cfg.speed = Math.floorMod(cfg.speed + 1, 3);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(SPEED_NAMES[cfg.speed]));
        }).dimensions(x, y - 2, w, 20).build());
    }

    private void addKeyButton(int x, int y, int w, KeyBinding kb) {
        String label = listening == kb ? "> naciśnij klawisz <" : kb.getBoundKeyLocalizedText().getString();
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { listening = kb; listeningAdd = false; rebuild(); })
                .dimensions(x, y - 2, w, 20).build());
    }

    private void addAddKeyButton(int x, int y, int w) {
        String label = listeningAdd ? "> naciśnij klawisz <" : RynekAnalizaMod.addKeyText();
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { listeningAdd = true; listening = null; rebuild(); })
                .dimensions(x, y - 2, w, 20).build());
    }

    private void addNumberField(int x, int y, int w, double value, boolean integer, Consumer<Double> setter) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(10);
        f.setTextPredicate(s -> s.matches(integer ? "\\d*" : "\\d*[.,]?\\d*"));
        f.setText(fmt(value));
        f.setChangedListener(s -> {
            Double v = parse(s);
            if (v != null) {
                setter.accept(v);
                cfg.applyToAnalyzer();
            }
        });
        addDrawableChild(f);
    }

    /** Pole tekstowe ustawienia (np. komenda rynku). */
    private void addTextField(int x, int y, int w, String value, Consumer<String> setter) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.empty());
        f.setMaxLength(80);
        f.setText(value == null ? "" : value);
        f.setChangedListener(v -> setter.accept(v.trim()));
        addDrawableChild(f);
    }

    private void addToggle(int x, int y, int w, boolean value, Consumer<Boolean> setter) {
        boolean[] state = {value};
        addDrawableChild(ButtonWidget.builder(Text.literal(value ? "TAK" : "NIE"), b -> {
            state[0] = !state[0];
            setter.accept(state[0]);
            cfg.applyToAnalyzer();
            b.setMessage(Text.literal(state[0] ? "TAK" : "NIE"));
        }).dimensions(x, y - 2, w, 20).build());
    }

    // ---------- rysowanie ----------
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (bgDone) return; // własne tło, rysowane raz na klatkę (bez rozmycia)
        bgDone = true;
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        flushPending(false);
        bgDone = false;
        renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(panelX - 4, 18, panelX + panelW + 4, this.height - 6, 0xE0202020);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Rynek Analiza"), this.width / 2, 8, WHITE);

        switch (tab) {
            case ITEMS -> drawItems(ctx, mouseX, mouseY, delta);
            case AUTO -> drawAuto(ctx, mouseX, mouseY);
            case AUTOPILOT -> drawAutopilot(ctx);
            case HISTORY -> drawHistory(ctx);
            case SETTINGS -> drawSettingsLabels(ctx);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(s), x, y, color);
    }

    private void drawItems(DrawContext ctx, int mouseX, int mouseY, float delta) {
        TextRenderer tr = this.textRenderer;
        text(ctx, "Szukaj:", panelX + 6, 52, GRAY);

        int hy = listTop - 13;
        text(ctx, "Przedmiot", colName, hy, YELLOW);
        if (colSparkW > 0) text(ctx, "Trend", colSpark, hy, YELLOW);
        text(ctx, tr.trimToWidth(colNumW >= tr.getWidth("Sprzedaż ($)") ? "Sprzedaż ($)" : "Sprzedaż", colPrice - colAvg - 2), colAvg, hy, YELLOW);
        text(ctx, tr.trimToWidth(colNumW >= tr.getWidth("Skup ($)") ? "Skup ($)" : "Skup", colBuy - colPrice - 2), colPrice, hy, YELLOW);
        text(ctx, tr.trimToWidth(colBuyW >= tr.getWidth("Marża ($)") ? "Marża ($)" : "Marża", colChart - colBuy - 2), colBuy, hy, YELLOW);

        if (!loadError.isEmpty()) {
            text(ctx, tr.trimToWidth("Nie mogę wczytać listy przedmiotów: " + loadError, panelW - 12), panelX + 6, listTop + 4, RED);
        } else if (rows.isEmpty()) {
            text(ctx, tr.trimToWidth(all.isEmpty() ? "Lista jest pusta - na rynku najedź na przedmiot i naciśnij " + RynekAnalizaMod.addKeyText() + " albo zaimportuj listę z BK (zakładka Auto rynek)." : "Brak przedmiotów pasujących do wyszukiwania.",
                    panelW - 12), panelX + 6, listTop + 4, GRAY);
        } else {
            drawList(ctx, mouseX, mouseY, delta);
        }

        String count = filtered.size() + (filtered.size() == all.size() ? "" : "/" + all.size()) + " przedm.";
        text(ctx, count, panelX + panelW - 6 - tr.getWidth(count), hy, GRAY);

        boolean showStatus = !status.isEmpty() && System.currentTimeMillis() - statusAt < STATUS_MS;
        String dm = cfg.defaultMinMargin > 0 ? fmt(cfg.defaultMinMargin) + "$" : "brak - nie kupuje";
        String foot = showStatus ? status : firstThatFits(panelW - 12,
                "Sprzedaż = śr. 3 najtańszych (analiza i monitorowanie rynku), skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża/szt. Pusta marża = domyślna (" + dm + ").",
                "Sprzedaż = śr. 3 najtańszych, skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża. Pusta = domyślna (" + dm + ").",
                "Skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża (pusta = domyślna)",
                "Skup = sprzedaż x podatek - marża");
        text(ctx, tr.trimToWidth(foot, panelW - 12), panelX + 6, this.height - 16, showStatus ? statusColor : GRAY);
    }

    private void drawList(DrawContext ctx, int mouseX, int mouseY, float delta) {
        positionWidgets();
        boolean mouseIn = mouseY >= listTop && mouseY < listBottom && mouseX >= panelX && mouseX < panelX + panelW;
        int mx = mouseIn ? mouseX : -10_000, my = mouseIn ? mouseY : -10_000;
        ctx.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        try {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                int sy = listTop + r.top - scroll;
                if (sy >= listBottom) break;
                if (sy + r.height <= listTop) continue;
                drawRow(ctx, r, i, sy, mx, my, delta);
                if (r.open) drawChartBlock(ctx, r, sy + ROW_H, mx, my);
            }
        } finally {
            ctx.disableScissor();
        }
        drawScrollbar(ctx);
    }

    private void drawRow(DrawContext ctx, Row r, int index, int sy, int mx, int my, float delta) {
        TextRenderer tr = this.textRenderer;
        ctx.fill(listL, sy, listR, sy + ROW_H, index % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
        if (r.open) ctx.fill(listL, sy, listL + 2, sy + r.height - 4, ACCENT);

        if (showIcons) {
            if (r.icon == null) r.icon = r.t.icon();
            if (!r.icon.isEmpty()) ctx.drawItem(r.icon, listL + 5, sy + 4);
        }
        text(ctx, fitName(r.t, colNameW), colName, sy + 8, WHITE);
        if (colSparkW > 0) drawSparkline(ctx, points(r.t.key()), colSpark, sy + 5, colSparkW, 14);

        double price = r.t.currentSellPrice();
        int cnt = r.t.sellCount();
        String ps = price > 0 ? fmtR(price) + "$" + (cnt > 1 ? "/" + cnt : "") : "-";
        if (price > 0 && cnt > 1 && tr.getWidth(ps) > colNumW) ps = fmtR(price) + "$";
        text(ctx, tr.trimToWidth(ps, colNumW), colAvg, sy + 8, price > 0 ? GREEN : GRAY);
        double buy = r.t.currentBuyPrice();
        int bf = Math.max(1, r.t.buyFactor());
        String bs = buy > 0 ? fmtR(buy) + "$" + (bf > 1 ? "/" + bf : "") : "0";
        if (buy > 0 && bf > 1 && tr.getWidth(bs) > colNumW) bs = fmtR(buy) + "$";
        text(ctx, tr.trimToWidth(bs, colNumW), colPrice, sy + 8, buy > 0 ? LIGHT : GRAY);

        if (r.field.visible) {
            r.field.render(ctx, mx, my, delta);
            // pusta marża: podpowiedź z marżą domyślną (szara, w polu)
            if (r.field.getText().isEmpty() && !r.field.isFocused()) {
                String hint = cfg.defaultMinMargin > 0 ? "dom. " + fmt(cfg.defaultMinMargin) : "brak";
                text(ctx, tr.trimToWidth(hint, colBuyW - 8), colBuy + 4, r.field.getY() + 4, GRAY);
            }
        }
        if (r.button.visible) r.button.render(ctx, mx, my, delta);
    }

    /**
     * Nazwa w wierszu: gdy z dopiskiem (np. "Zaklęta książka [Regeneracji I]") się nie mieści, skracana jest nazwa,
     * a dopisek zostaje - to on odróżnia wpisy o tej samej nazwie.
     */
    String fitName(BkBridge.BkTarget t, int maxW) {
        TextRenderer tr = this.textRenderer;
        String full = t.displayName();
        if (tr.getWidth(full) <= maxW || t.label.isEmpty()) return tr.trimToWidth(full, maxW);
        int minName = tr.getWidth("Ab..");
        String label = t.label;
        int labelMax = maxW - minName - tr.getWidth(" []");
        if (labelMax < tr.getWidth("..ab")) return tr.trimToWidth(full, maxW);
        if (tr.getWidth(label) > labelMax) {
            // ucinamy dopisek od początku - różnica bywa na końcu (np. "Dodatkowe obrażenia 0%" / "15%")
            int i = 0;
            while (i < label.length() && tr.getWidth(".." + label.substring(i)) > labelMax) i++;
            label = ".." + label.substring(i);
        }
        String tag = " [" + label + "]";
        int left = maxW - tr.getWidth(tag);
        String base = t.baseName();
        String name = tr.getWidth(base) <= left ? base : tr.trimToWidth(base, left - tr.getWidth("..")) + "..";
        return name + tag;
    }

    /** Miniatura wykresu w wierszu: linia ostatnich skanów w skali własnej (od najniższej do najwyższej), ostatnia kropka podświetlona. */
    private void drawSparkline(DrawContext ctx, List<PriceHistory.Point> pts, int x, int y, int w, int h) {
        int n = pts.size();
        if (n < 2) {
            ctx.fill(x, y + h / 2, x + w, y + h / 2 + 1, 0x33FFFFFF);
            return;
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (PriceHistory.Point p : pts) { lo = Math.min(lo, p.a3); hi = Math.max(hi, p.a3); }
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x + 2 + Math.round((float) (w - 4) * i / (n - 1));
            ys[i] = yOf(pts.get(i).a3, lo, hi, y + 2, y + h - 3);
        }
        for (int i = 1; i < n; i++) seg(ctx, xs[i - 1], ys[i - 1], xs[i], ys[i], ACCENT, 1);
        ctx.fill(xs[n - 1] - 1, ys[n - 1] - 1, xs[n - 1] + 2, ys[n - 1] + 2, WHITE);
    }

    private void drawScrollbar(DrawContext ctx) {
        if (maxScroll <= 0) return;
        int x = listR + 2, w = 4;
        ctx.fill(x, listTop, x + w, listBottom, 0x30FFFFFF);
        int hh = barHeight();
        int hy = barTop();
        ctx.fill(x, hy, x + w, hy + hh, draggingBar ? 0xFFDDDDDD : 0xFF9A9A9A);
    }

    private int barHeight() {
        return Math.max(16, (int) ((long) viewportH * viewportH / Math.max(1, contentH)));
    }

    private int barTop() {
        int travel = viewportH - barHeight();
        return listTop + (maxScroll <= 0 ? 0 : (int) ((long) travel * scroll / maxScroll));
    }

    private List<PriceHistory.Point> points(String key) {
        List<PriceHistory.Point> l = pointCache.get(key);
        if (l == null) {
            PriceHistory hist = RynekAnalizaMod.history();
            l = hist == null ? List.of() : hist.lastScans(key, cfg.chartObserved, CHART_SCANS);
            pointCache.put(key, l);
        }
        return l;
    }

    // ----- rozwinięty wykres -----
    private static void box(DrawContext c, int x, int y, int w, int h, int fill, int border) {
        c.fill(x + 1, y, x + w - 1, y + h, border);
        c.fill(x, y + 1, x + w, y + h - 1, border);
        c.fill(x + 2, y + 1, x + w - 2, y + h - 1, fill);
        c.fill(x + 1, y + 2, x + w - 1, y + h - 2, fill);
    }

    private static int alpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private void drawChartBlock(DrawContext ctx, Row r, int bt, int mouseX, int mouseY) {
        TextRenderer tr = this.textRenderer;
        List<PriceHistory.Point> pts = points(r.t.key());
        PriceHistory.Stats st = PriceHistory.stats(pts);
        int n = pts.size();

        int bx = listL + 2, bw = listR - listL - 4, bh = (n == 0 ? EMPTY_CHART_H + stripH : blockH) - 4;
        box(ctx, bx, bt, bw, bh, BLOCK_BG, BLOCK_BORDER);

        // przycisk "Ukryj" (wiersz bywa przewinięty poza widok, a blok nadal trzeba móc zwinąć)
        int rowIndex = rows.indexOf(r);
        int[] cr = closeRect(rowIndex);
        boolean overClose = mouseX >= cr[0] && mouseX < cr[0] + cr[2] && mouseY >= cr[1] && mouseY < cr[1] + cr[3];
        box(ctx, cr[0], cr[1], cr[2], cr[3], overClose ? 0xFF2C3140 : CARD_BG, CARD_BORDER);
        text(ctx, "Ukryj", cr[0] + 5, cr[1] + 3, overClose ? WHITE : GRAY);
        int closeSpace = cr[2] + 10;

        // nagłówek: nazwa (blok bywa przewinięty tak, że wiersz jest już niewidoczny), liczba skanów i legenda
        String head = n == 0 ? "skany: brak" : "skany: " + n;
        int headW = bw - 12 - closeSpace;
        int nameMax = Math.max(30, Math.min(headW - tr.getWidth("  |  " + head) - 4, Math.max(headW / 2, 110)));
        String title = tr.trimToWidth(r.t.displayName(), nameMax) + "  |  " + head;
        text(ctx, title, bx + 6, bt + 5, LIGHT);
        // obecna cena sprzedaży i skupu (za sztukę) - linie przerywane na wykresie
        List<double[]> sellLine = new ArrayList<>(), buyLine = new ArrayList<>();
        if (n > 0) decisionLines(r, pts, sellLine, buyLine);
        if (n > 0) {
            int free = headW - tr.getWidth(title) - 14;
            boolean ap = !sellLine.isEmpty() || !buyLine.isEmpty();
            String[][] variants = ap
                    ? new String[][]{{"śr. 3 najtańszych", "średnia", "sprzedaż", "skup"}, {"śr. 3", "średnia", "sprz.", "skup"}, {"śr. 3", "sprz.", "skup"}, {"śr. 3", "średnia"}}
                    : new String[][]{{"śr. 3 najtańszych", "średnia"}, {"śr. 3", "średnia"}};
            for (String[] lg : variants) {
                int need = 0;
                for (String l : lg) need += 12 + 4 + tr.getWidth(l) + 12;
                if (need - 12 > free) continue;
                int lx = bx + bw - 6 - closeSpace;
                for (int k = lg.length - 1; k >= 0; k--) {
                    String l = lg[k];
                    lx -= tr.getWidth(l);
                    text(ctx, l, lx, bt + 5, GRAY);
                    lx -= 16;
                    int col = l.startsWith("śr. 3") ? ACCENT : l.equals("średnia") ? MEAN : l.startsWith("sprz") ? UP : BUY_LINE;
                    if (l.startsWith("śr. 3")) ctx.fill(lx, bt + 9, lx + 12, bt + 11, col);
                    else for (int d = 0; d < 12; d += 6) ctx.fill(lx + d, bt + 9, lx + d + 3, bt + 10, col);
                    lx -= 12;
                }
                break;
            }
        }

        int plotTop = bt + 18, plotBottom = plotTop + blockPlotH;
        int cardsTop = plotBottom + 26, cardH = 24;

        // skala: od najniższej do najwyższej średniej, z zapasem u góry i u dołu
        double vMin = 0, vMax = 0;
        if (n > 0) {
            vMin = Double.MAX_VALUE;
            vMax = -Double.MAX_VALUE;
            for (PriceHistory.Point p : pts) { vMin = Math.min(vMin, p.a3); vMax = Math.max(vMax, p.a3); }
            for (List<double[]> line : List.of(sellLine, buyLine)) {
                for (double[] v : line) { vMin = Math.min(vMin, v[1]); vMax = Math.max(vMax, v[1]); }
            }
        }
        double range = vMax - vMin;
        double pad = range > 1e-9 ? range * 0.14 : Math.max(Math.abs(vMax) * 0.05, 0.01);
        double lo = Math.max(0, vMin - pad), hi = vMax + pad;
        double step = niceStep((hi - lo) / Math.max(2, Math.min(5, blockPlotH / 26)));
        List<Double> ticks = new ArrayList<>();
        if (n > 0) {
            for (double v = Math.ceil(lo / step - 1e-9) * step; v <= hi + 1e-9 && ticks.size() < 8; v += step) ticks.add(v);
        }
        int dec = decimals(step);
        int gutter = 8;
        for (double v : ticks) gutter = Math.max(gutter, tr.getWidth(fmtTick(v, dec)) + 8);

        int px0 = bx + 4 + gutter, px1 = bx + bw - 6, pw = px1 - px0;
        int top = plotTop + 14, bot = plotBottom - 6; // miejsce na podpisy nad najwyższym i pod najniższym punktem

        if (n == 0) {
            text(ctx, tr.trimToWidth("Brak skanów tego przedmiotu - uruchom analizę (Start / F8), a pojawi się tu wykres.", bw - 12), bx + 6, bt + 19, GRAY);
            text(ctx, fitParts(bw - 12, sellPart(r.t), buyPart(r.t)), bx + 6, bt + 33, GRAY);
            drawStrip(ctx, r, rowIndex, bx, bw, mouseX, mouseY);
            return;
        }
        ctx.fill(px0, plotTop, px1, plotBottom, PLOT_BG);

        // siatka i oś cen
        for (double v : ticks) {
            int ty = yOf(v, lo, hi, top, bot);
            if (ty < plotTop || ty > plotBottom - 1) continue;
            ctx.fill(px0, ty, px1, ty + 1, GRID);
            String lab = fmtTick(v, dec);
            text(ctx, lab, px0 - 4 - tr.getWidth(lab), ty - 4, GRAY);
        }

        int padX = 12, usable = Math.max(1, pw - 2 * padX);
        double spacing = n > 1 ? (double) usable / (n - 1) : pw;
        boolean dense = spacing < 6; // przy wielu analizach: bez prowadnic i kropek przy każdym punkcie, sama linia
        int[] xs = new int[n], ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? px0 + pw / 2 : px0 + padX + Math.round((float) usable * i / (n - 1));
            ys[i] = yOf(pts.get(i).a3, lo, hi, top, bot);
            if (!dense) ctx.fill(xs[i], plotTop, xs[i] + 1, plotBottom, 0x0FFFFFFF); // delikatna pionowa prowadnica
        }

        // wypełnienie pod linią (cztery pasy o malejącej przezroczystości)
        if (n > 1) {
            for (int x = xs[0]; x <= xs[n - 1]; x++) {
                int seg = 1;
                while (seg < n - 1 && x > xs[seg]) seg++;
                int y = ys[seg - 1] + Math.round((float) (ys[seg] - ys[seg - 1]) * (x - xs[seg - 1]) / Math.max(1, xs[seg] - xs[seg - 1]));
                int h = plotBottom - y;
                if (h <= 0) continue;
                int b1 = y + Math.max(1, h / 4), b2 = y + Math.max(1, h / 2), b3 = y + Math.max(1, 3 * h / 4);
                ctx.fill(x, y, x + 1, b1, alpha(ACCENT, 0x48));
                ctx.fill(x, b1, x + 1, b2, alpha(ACCENT, 0x32));
                ctx.fill(x, b2, x + 1, b3, alpha(ACCENT, 0x20));
                ctx.fill(x, b3, x + 1, plotBottom, alpha(ACCENT, 0x10));
            }
        }

        // przerywana linia średniej
        int ym = yOf(st.mean, lo, hi, top, bot);
        for (int x = px0 + 2; x < px1 - 2; x += 6) ctx.fill(x, ym, Math.min(x + 3, px1 - 2), ym + 1, alpha(MEAN, 0xCC));

        // linie autopilota: cena sprzedaży (zielona) i skupu (fioletowa), schodkowo w czasie
        drawStepLine(ctx, sellLine, pts, xs, lo, hi, top, bot, UP);
        drawStepLine(ctx, buyLine, pts, xs, lo, hi, top, bot, BUY_LINE);

        // linia (2 px) i punkty
        for (int i = 1; i < n; i++) seg(ctx, xs[i - 1], ys[i - 1], xs[i], ys[i], ACCENT, 2);
        for (int i = 0; i < n; i++) if (!dense || i == n - 1) marker(ctx, xs[i], ys[i], i == n - 1);

        // podpisy: wartość przy punkcie, czas (i data przy zmianie dnia) pod wykresem; przy ciasnych odstępach co któryś, zawsze ostatni
        String[] vt = new String[n];
        int maxW = tr.getWidth("00:00");
        for (int i = 0; i < n; i++) {
            vt[i] = fmtR(pts.get(i).a3);
            maxW = Math.max(maxW, tr.getWidth(vt[i]));
        }
        int every = Math.max(1, (int) Math.ceil((maxW + 6) / spacing));
        // wartość ostatniego punktu ma pierwszeństwo; pozostałe podpisy, które by na nią (albo na siebie) wchodziły, są pomijane
        List<int[]> taken = new ArrayList<>();
        taken.add(valueRect(tr, vt[n - 1], xs[n - 1], ys[n - 1], px0, px1, plotTop));
        String prevDate = "";
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            if ((n - 1 - i) % every != 0) continue;
            ctx.fill(xs[i], plotBottom, xs[i] + 1, plotBottom + 2, GRAY);
            int[] vr = valueRect(tr, vt[i], xs[i], ys[i], px0, px1, plotTop);
            int vx = vr[0] + 3, vy = vr[1] + 2, vw = vr[2] - 6;
            if (last) {
                box(ctx, vx - 3, vy - 2, vw + 6, 12, ACCENT, ACCENT);
                text(ctx, vt[i], vx, vy, 0xFF0A1220);
            } else if (!overlapsAny(vr, taken)) {
                taken.add(vr);
                ctx.fill(vx - 2, vy - 1, vx + vw + 2, vy + 9, 0xB00E1015);
                text(ctx, vt[i], vx, vy, LIGHT);
            }
            Instant when = Instant.ofEpochMilli(pts.get(i).t);
            String tm = F_TIME.format(when), dt = F_DATE.format(when);
            text(ctx, tm, clamp(xs[i] - tr.getWidth(tm) / 2, bx + 2, bx + bw - tr.getWidth(tm) - 2), plotBottom + 3, LIGHT);
            if (!dt.equals(prevDate)) {
                text(ctx, dt, clamp(xs[i] - tr.getWidth(dt) / 2, bx + 2, bx + bw - tr.getWidth(dt) - 2), plotBottom + 13, GRAY);
                prevDate = dt;
            }
        }

        // karty ze statystykami
        int gap = 4, cardW = (bw - 8 - 3 * gap) / 4;
        String[] labels = {"Średnia", "Najniższa", "Najwyższa", "Ostatni"};
        double[] vals = {st.mean, st.min, st.max, st.last};
        int[] stripe = {MEAN, UP, DOWN, ACCENT};
        double chg = st.changePct();
        for (int k = 0; k < 4; k++) {
            int cx = bx + 4 + k * (cardW + gap);
            box(ctx, cx, cardsTop, cardW, cardH, CARD_BG, CARD_BORDER);
            ctx.fill(cx + 2, cardsTop + 1, cx + cardW - 2, cardsTop + 2, stripe[k]);
            text(ctx, tr.trimToWidth(labels[k], cardW - 6), cx + 4, cardsTop + 4, GRAY);
            text(ctx, tr.trimToWidth(fmtR(vals[k]) + "$", cardW - 6), cx + 4, cardsTop + 14, WHITE);
            if (k == 3 && !Double.isNaN(chg)) {
                String d = String.format(Locale.ROOT, "%+.1f%%", chg);
                int dw = tr.getWidth(d);
                if (tr.getWidth(labels[k]) + dw + 12 <= cardW) text(ctx, d, cx + cardW - 4 - dw, cardsTop + 4, chg >= 0 ? UP : DOWN);
            }
        }

        // linia szczegółów: najpierw ceny z listy i zysk (najważniejsze przy ustalaniu skupu), potem reszta - tyle, ile się zmieści
        List<String> parts = new ArrayList<>();
        parts.add(sellPart(r.t));
        parts.add(buyPart(r.t));
        String profit = profitPart(r.t);
        if (!profit.isEmpty()) parts.add(profit);
        parts.add("rozstęp " + fmtR(st.spread()) + "$");
        parts.add("najtańsza oferta " + fmtR(st.cheapest) + "$");
        parts.add("ostatni skan " + ago(System.currentTimeMillis() - st.lastT));
        String details = fitParts(bw - 12, parts.toArray(new String[0]));
        text(ctx, details, bx + 6, cardsTop + cardH + 4, GRAY);
        drawStrip(ctx, r, rowIndex, bx, bw, mouseX, mouseY);

        // najechanie myszą: najbliższy punkt i jego szczegóły
        if (mouseX >= px0 && mouseX < px1 && mouseY >= plotTop && mouseY < plotBottom) {
            int best = 0, bestDx = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                int dx = Math.abs(xs[i] - mouseX);
                if (dx < bestDx) { bestDx = dx; best = i; }
            }
            PriceHistory.Point p = pts.get(best);
            ctx.fill(xs[best], plotTop, xs[best] + 1, plotBottom, alpha(ACCENT, 0x99));
            marker(ctx, xs[best], ys[best], true);
            String tip1 = F_FULL.format(Instant.ofEpochMilli(p.t)) + (p.s == PriceHistory.SRC_ACTIVE ? "  (skan z analizy)" : "  (obserwacja rynku)");
            String tip2 = "śr. 3: " + fmtR(p.a3) + "$ | najtańsza: " + fmtR(p.p1) + "$ | ofert: " + p.n;
            tip1 = tr.trimToWidth(tip1, pw - 16);
            tip2 = tr.trimToWidth(tip2, pw - 16);
            int tw = Math.max(tr.getWidth(tip1), tr.getWidth(tip2));
            int tx = clamp(xs[best] - tw / 2, px0 + 4, px1 - tw - 8);
            int ty = ys[best] - plotTop < 34 ? plotBottom - 28 : plotTop + 3; // nie zasłaniaj najechanego punktu
            box(ctx, tx - 3, ty - 2, tw + 8, 25, 0xFF0A0C10, alpha(ACCENT, 0xFF));
            text(ctx, tip1, tx + 1, ty + 1, WHITE);
            text(ctx, tip2, tx + 1, ty + 12, LIGHT);
        }
    }

    /** Prostokąt podpisu wartości nad punktem (z ramką): {x, y, szerokość, wysokość}. */
    private static int[] valueRect(TextRenderer tr, String v, int x, int y, int px0, int px1, int plotTop) {
        int vw = tr.getWidth(v);
        int vx = clamp(x - vw / 2, px0 + 3, px1 - vw - 3);
        int vy = y - 14 >= plotTop + 1 ? y - 14 : y + 7;
        return new int[]{vx - 3, vy - 2, vw + 6, 12};
    }

    private static boolean overlapsAny(int[] r, List<int[]> rs) {
        for (int[] o : rs) {
            if (r[0] < o[0] + o[2] + 1 && o[0] < r[0] + r[2] + 1 && r[1] < o[1] + o[3] && o[1] < r[1] + r[3]) return true;
        }
        return false;
    }

    /** Obecna cena sprzedaży i skupu (za sztukę) jako linie przez cały wykres. */
    private void decisionLines(Row r, List<PriceHistory.Point> pts, List<double[]> sell, List<double[]> buy) {
        if (pts.isEmpty()) return;
        long t0 = pts.get(0).t;
        double sellPP = Pricing.sellPP(r.t.item());
        double buyPP = r.t.currentBuyPrice() / Math.max(1, r.t.buyFactor());
        if (sellPP > 0) sell.add(new double[]{t0, sellPP});
        if (buyPP > 0) buy.add(new double[]{t0, buyPP});
    }

    /** x punktu w czasie t (między punktami wykresu - proporcjonalnie). */
    private static int xAtTime(long t, List<PriceHistory.Point> pts, int[] xs) {
        int n = pts.size();
        if (t <= pts.get(0).t) return xs[0];
        if (t >= pts.get(n - 1).t) return xs[n - 1];
        for (int i = 0; i < n - 1; i++) {
            long a = pts.get(i).t, b = pts.get(i + 1).t;
            if (t >= a && t < b) return xs[i] + (int) Math.round((double) (xs[i + 1] - xs[i]) * (t - a) / Math.max(1, b - a));
        }
        return xs[n - 1];
    }

    private void drawStepLine(DrawContext ctx, List<double[]> line, List<PriceHistory.Point> pts, int[] xs, double lo, double hi, int top, int bot, int col) {
        for (int k = 0; k < line.size(); k++) {
            int x0 = xAtTime((long) line.get(k)[0], pts, xs);
            int x1 = k + 1 < line.size() ? xAtTime((long) line.get(k + 1)[0], pts, xs) : xs[xs.length - 1];
            int y = yOf(line.get(k)[1], lo, hi, top, bot);
            for (int x = x0; x <= x1; x += 5) ctx.fill(x, y, Math.min(x + 3, x1 + 1), y + 1, alpha(col, 0xE0));
            if (k + 1 < line.size()) {
                int y2 = yOf(line.get(k + 1)[1], lo, hi, top, bot);
                ctx.fill(x1, Math.min(y, y2), x1 + 1, Math.max(y, y2) + 1, alpha(col, 0x90));
            }
        }
    }

    /** Pasek autopilota pod wykresem: przełącznik Auto, ręczne min. sprzedaży i max skupu, stan i ostatnia decyzja. */
    private void drawStrip(DrawContext ctx, Row r, int rowIndex, int bx, int bw, int mouseX, int mouseY) {
        TextRenderer tr = this.textRenderer;
        int sTop = stripTop(r);
        ctx.fill(bx + 4, sTop, bx + bw - 4, sTop + 1, CARD_BORDER);
        Autopilot ap = RynekAnalizaMod.autopilot();
        Autopilot.ItemState s = ap == null ? null : ap.peek(r.t.key());
        boolean auto = s == null || s.auto;
        int[] ar = autoRect(rowIndex);
        boolean over = mouseX >= ar[0] && mouseX < ar[0] + ar[2] && mouseY >= ar[1] && mouseY < ar[1] + ar[3];
        box(ctx, ar[0], ar[1], ar[2], ar[3], auto ? (over ? 0xFF2F5A3D : 0xFF234A31) : (over ? 0xFF2C3140 : CARD_BG), auto ? alpha(UP, 0xFF) : CARD_BORDER);
        text(ctx, auto ? "Auto: TAK" : "Auto: NIE", ar[0] + 5, ar[1] + 3, auto ? WHITE : GRAY);
        text(ctx, stripMinLabel, stripMinLabelX, sTop + 5, GRAY);
        text(ctx, stripMaxLabel, stripMaxLabelX, sTop + 5, GRAY);
        if (r.minField != null && r.minField.visible) r.minField.render(ctx, mouseX, mouseY, 0f);
        if (r.maxField != null && r.maxField.visible) r.maxField.render(ctx, mouseX, mouseY, 0f);
        // podpowiedź marży z historii: przycisk (pełny pasek - w 3. linii, niski - obok wzoru)
        boolean full = stripH == STRIP_H;
        MarginHint.Hint h = hint(r.t);
        int[] hr = hintRect(rowIndex);
        boolean hov = false;
        if (h.ok()) {
            boolean done = hintApplied(r.t, h);
            hov = mouseX >= hr[0] && mouseX < hr[0] + hr[2] && mouseY >= hr[1] && mouseY < hr[1] + hr[3];
            box(ctx, hr[0], hr[1], hr[2], hr[3], done ? CARD_BG : (hov ? 0xFF2F5A3D : 0xFF234A31), done ? CARD_BORDER : alpha(UP, 0xFF));
            String lbl = hintLabel(r.t, h);
            text(ctx, lbl, hr[0] + (hr[2] - tr.getWidth(lbl)) / 2, hr[1] + 3, done ? GRAY : WHITE);
        }
        // wzór skupu tego przedmiotu, ostatni wpis dziennika
        String formula = Pricing.describe(r.t, cfg, ap);
        int lineW = !full && h.ok() ? hr[0] - (bx + 6) - 6 : bw - 12;
        AutoMarket.Hold hd = AutoMarket.held().get(r.t.item());
        String wait = hd == null ? "" : "czeka na wyższą cenę: zakup " + fmtR(hd.costPP) + "$/szt., teraz po podatku " + fmtR(hd.netPP) + "$/szt."
                + (hd.check ? " (sprawdzam analizą)" : "");
        String line = wait.isEmpty() ? fitParts(lineW, auto ? "" : "stała cena sprzedaży (Auto: NIE)", formula, s == null ? "" : s.lastWhy)
                : fitParts(lineW, wait, formula); // czekająca wystawka ważniejsza niż reszta
        text(ctx, tr.trimToWidth(line, lineW), bx + 6, sTop + 21, auto ? LIGHT : GRAY);
        String[] why = MarginHint.reasons(h);
        if (full) {
            int textW = h.ok() ? hr[0] - (bx + 6) - 6 : bw - 12;
            String[] parts = new String[why.length + 1];
            parts[0] = "Podpowiedź: " + MarginHint.headline(h);
            System.arraycopy(why, 0, parts, 1, why.length);
            text(ctx, tr.trimToWidth(fitParts(textW, parts), textW), bx + 6, sTop + 34, h.ok() ? HINT : GRAY);
        }
        // najechanie na przycisk: pełne uzasadnienie w dymku nad przyciskiem
        if (hov) {
            List<String> tip = new ArrayList<>();
            tip.add("Podpowiedź: " + MarginHint.headline(h));
            for (String w : why) if (!w.isEmpty()) tip.add(w);
            tip.add(hintApplied(r.t, h) ? "marża już taka jak podpowiedź" : "klik = wpisz tę marżę (skup przeliczy wzór)");
            int maxW = Math.min(bw - 12, 300);
            int tw = 0;
            for (int i = 0; i < tip.size(); i++) {
                tip.set(i, ellipsize(tip.get(i), maxW));
                tw = Math.max(tw, tr.getWidth(tip.get(i)));
            }
            int th = tip.size() * 10 + 6;
            int tx = Math.max(bx + 4, hr[0] + hr[2] - tw - 8), ty = hr[1] - th - 2;
            box(ctx, tx, ty, tw + 8, th, 0xF0101216, CARD_BORDER);
            for (int i = 0; i < tip.size(); i++) text(ctx, tip.get(i), tx + 4, ty + 4 + i * 10, i == 0 ? HINT : LIGHT);
        }
    }

    /** Widżety listy (pola i przyciski wierszy, pola paska autopilota). */
    private List<ClickableWidget> listWidgets() {
        List<ClickableWidget> ws = new ArrayList<>();
        for (Row r : rows) {
            ws.add(r.field);
            ws.add(r.button);
            if (r.minField != null) ws.add(r.minField);
            if (r.maxField != null) ws.add(r.maxField);
        }
        return ws;
    }

    /** Punkt wykresu: zaokrąglony znacznik z ciemnym obrysem; ostatni (lub wskazany) jest większy, biały, z poświatą. */
    private static void marker(DrawContext c, int x, int y, boolean big) {
        if (big) {
            int halo = alpha(ACCENT, 0x40);
            c.fill(x - 2, y - 4, x + 3, y + 5, halo);
            c.fill(x - 4, y - 2, x + 5, y + 3, halo);
            c.fill(x - 3, y - 3, x + 4, y + 4, halo);
            c.fill(x - 1, y - 3, x + 2, y + 4, WHITE);
            c.fill(x - 3, y - 1, x + 4, y + 2, WHITE);
            c.fill(x - 2, y - 2, x + 3, y + 3, WHITE);
            c.fill(x - 1, y - 1, x + 2, y + 2, ACCENT);
        } else {
            c.fill(x - 1, y - 2, x + 2, y + 3, 0xFF0E1015);
            c.fill(x - 2, y - 1, x + 3, y + 2, 0xFF0E1015);
            c.fill(x - 1, y - 1, x + 2, y + 2, ACCENT);
        }
    }

    private static int yOf(double v, double lo, double hi, int top, int bot) {
        if (hi - lo < 1e-9) return (top + bot) / 2;
        double f = (v - lo) / (hi - lo);
        f = Math.max(0, Math.min(1, f));
        return bot - (int) Math.round(f * (bot - top));
    }

    /** "Ładny" krok osi: 1, 2, 2.5, 5 albo 10 razy potęga dziesięciu. */
    static double niceStep(double raw) {
        if (!(raw > 0)) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / p;
        double n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
        return n * p;
    }

    /** Ile miejsc po przecinku potrzeba, żeby wypisać wielokrotności kroku bez zaokrągleń. */
    static int decimals(double step) {
        for (int d = 0; d <= 4; d++) {
            double s = step * Math.pow(10, d);
            if (Math.abs(s - Math.rint(s)) < 1e-6) return d;
        }
        return 4;
    }

    static String fmtTick(double v, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String ago(long ms) {
        long m = Math.max(0, ms / 60_000L);
        if (m < 1) return "przed chwilą";
        if (m < 60) return m + " min temu";
        if (m < 48 * 60) return (m / 60) + " h temu";
        return (m / 1440) + " dni temu";
    }

    /** Linia między dwoma punktami o grubości thick: dla każdej kolumny pikseli jeden pionowy pasek (mało wywołań fill). */
    private static void seg(DrawContext c, int x0, int y0, int x1, int y1, int col, int thick) {
        if (x1 < x0) { int tx = x0; x0 = x1; x1 = tx; int ty = y0; y0 = y1; y1 = ty; }
        if (x1 == x0) {
            c.fill(x0, Math.min(y0, y1), x0 + 1, Math.max(y0, y1) + thick, col);
            return;
        }
        int prevY = y0;
        for (int x = x0; x <= x1; x++) {
            int y = y0 + Math.round((float) (y1 - y0) * (x - x0) / (x1 - x0));
            c.fill(x, Math.min(prevY, y), x + 1, Math.max(prevY, y) + thick, col);
            prevY = y;
        }
    }

    private void drawSettingsLabels(DrawContext ctx) {
        int top = 52, step = 22;
        int from = settingsPage * settingsPerPage;
        int to = Math.min(settingRows.size(), from + settingsPerPage);
        for (int i = from; i < to; i++) {
            int avail = settingsVx - (panelX + 8) - 6;
            SettingRow sr = settingRows.get(i);
            text(ctx, this.textRenderer.trimToWidth(firstThatFits(avail, sr.label(), sr.shortLabel()), avail), panelX + 8, top + (i - from) * step + 3, WHITE);
        }
        int pages = Math.max(1, (settingRows.size() + settingsPerPage - 1) / settingsPerPage);
        if (pages > 1) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Ustawienia " + (settingsPage + 1) + "/" + pages),
                    panelX + panelW / 2, this.height - 35, WHITE);
        }
        boolean apPage = from < to && settingRows.get(to - 1).label().startsWith("Autopilot");
        boolean marginPage = false, keyPage = false;
        for (int i = from; i < to; i++) {
            if (settingRows.get(i).label().startsWith("Marża")) marginPage = true;
            if (settingRows.get(i).label().startsWith("Klawisz")) keyPage = true;
        }
        marginPage &= !keyPage; // przy klawiszach ważniejsza podpowiedź, jak je ustawić
        String foot = apPage
                ? firstThatFits(panelW - 16,
                "Autopilot: budżet 0 = bez limitu. Włączanie: zakładka Autopilot.",
                "Budżet 0 = bez limitu. Włączanie: zakładka Autopilot.",
                "Budżet 0 = bez limitu.")
                : marginPage ? firstThatFits(panelW - 16,
                "Skup = sprzedaż x podatek - marża (za sztukę); marżę każdego przedmiotu wpiszesz w liście (pusta = domyślna).",
                "Skup = sprzedaż x podatek - marża/szt. (pusta marża = domyślna).",
                "Skup = sprzedaż x podatek - marża.")
                : firstThatFits(panelW - 16,
                "Klawisz: Delete/Backspace = brak, Esc = anuluj. Komponenty: zwykle zbędne - włącz tylko, gdy coś się myli.",
                "Klawisz: Delete/Backspace = brak, Esc = anuluj.",
                "Klawisz: Delete = brak, Esc = anuluj.");
        text(ctx, this.textRenderer.trimToWidth(foot, panelW - 12), panelX + 8, this.height - 16, GRAY);
    }

    // ----- zakładka Autopilot -----
    private void initAutopilot() {
        Autopilot ap = RynekAnalizaMod.autopilot();
        int bw = Math.max(110, Math.min(170, (panelW - 16) / 2));
        boolean on = cfg.autopilot;
        addDrawableChild(ButtonWidget.builder(Text.literal(on ? "Wyłącz autopilota" : "Włącz autopilota"), b -> {
            cfg.autopilot = !cfg.autopilot;
            if (!cfg.autopilot && ap != null) releaseApPauses(ap);
            cfg.save();
            setStatus(cfg.autopilot ? "Autopilot włączony: budżet na dzień." : "Autopilot wyłączony.",
                    cfg.autopilot ? GREEN : YELLOW);
            rebuild();
        }).dimensions(panelX + 6, 46, bw, 20).build());
    }

    private void releaseApPauses(Autopilot ap) {
        try {
            ap.releasePauses(new ArrayList<>(all));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void drawAutopilot(DrawContext ctx) {
        TextRenderer tr = this.textRenderer;
        Autopilot ap = RynekAnalizaMod.autopilot();
        int x = panelX + 8, w = panelW - 16, y = 74;
        long now = System.currentTimeMillis();
        if (ap == null) {
            text(ctx, "Autopilot niedostępny.", x, y, RED);
            return;
        }
        String st = cfg.autopilot
                ? "Autopilot: WŁĄCZONY - budżet " + (cfg.apBudget > 0 ? fmtR(cfg.apBudget) + "$/dzień" : "bez limitu")
                : "Autopilot: WYŁĄCZONY (bez budżetu dziennego)";
        text(ctx, ellipsize(st, w), x, y, cfg.autopilot ? GREEN : GRAY);
        y += 12;
        text(ctx, ellipsize(firstThatFits(w,
                "Ceny liczy wzór: sprzedaż = śr. 3 najtańszych (analiza i monitorowanie rynku), skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża.",
                "Sprzedaż = śr. 3 najtańszych, skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża.",
                "Skup = sprzedaż x podatek - marża."), w), x, y, GRAY);
        y += 12;
        long ms = RynekAnalizaMod.msToNextCycle();
        List<Autopilot.Pick> plan = ap.plan(all, now);
        String when = !cfg.amAnalysis ? "wyłączona (zakładka Auto rynek)" : !BkBridge.isRunning() ? "ruszy z auto rynkiem"
                : ms < 60_000 ? "zaraz po zamknięciu panelu" : "za " + (ms / 60_000) + " min";
        String next = plan.isEmpty() ? "Brak przedmiotów na liście - nie ma czego analizować."
                : "Analiza wszystkich (" + plan.size() + " przedm.): " + when;
        text(ctx, fitParts(w, next, "co " + cfg.apIntervalMin + " min"
                + (cfg.apRescanBuys > 0 ? ", " + Autopilot.poZakupach(cfg.apRescanBuys) + " w " + cfg.apRescanMin + " min - od razu ponownie" : "")), x, y, GRAY);
        List<String> again = new ArrayList<>();
        for (Autopilot.Pick pk : plan) if (pk.kind == Autopilot.Pick.RESCAN) again.add(pk.t.displayName() + " (" + pk.why + ")");
        if (!again.isEmpty()) {
            y += 10;
            text(ctx, ellipsize("Ponowna analiza po zakupach: " + String.join(", ", again), w), x, y, YELLOW);
        }
        y += 14;
        Autopilot.DayStats d = ap.today(now);
        text(ctx, fitParts(w, "Dziś: kupione " + d.boughtAll + " za " + fmtR(d.spentAll) + "$" + (cfg.autopilot && cfg.apBudget > 0 ? " z " + fmtR(cfg.apBudget) + "$" : ""),
                "sprzedane " + d.sold, "zysk " + (d.realized >= 0 ? "+" : "") + fmtR(d.realized) + "$"), x, y, LIGHT);
        y += 12;
        int lots = 0;
        for (Autopilot.ItemState s : ap.data.items.values()) lots += ap.openLots(s);
        int pausedN = ap.data.pausedByBudget.size();
        String paused = pausedN == 0 ? "" : " | kupowanie wstrzymane (budżet): " + pausedN + " przedm.";
        text(ctx, fitParts(w, "Niesprzedane zakupy: " + lots, paused.isEmpty() ? "" : paused.substring(3),
                "zyskowne sprzedaże: " + ap.data.profitableReal + "/" + ap.data.closedReal), x, y, paused.isEmpty() ? LIGHT : YELLOW);
        y += 12;
        double bal = BkBridge.walletBalance();
        double pct = Math.min(100, cfg.buyBalancePct);
        String balPart = !(pct > 0) || pct >= 100 ? (bal > 0 ? "Saldo " + fmtR(bal) + "$ - snajper kupuje do salda" : "Saldo: nieznane")
                : bal > 0 ? "Saldo " + fmtR(bal) + "$ - 1 zakup najwyżej " + fmt(pct) + "% = " + fmtR(bal * pct / 100) + "$"
                : "Saldo: nieznane (brak \"Stan konta\" na tablicy wyników) - bez limitu";
        text(ctx, ellipsize(balPart, w), x, y, GRAY);
        y += 14;
        text(ctx, "Dziennik:", x, y, YELLOW);
        y += 12;
        Map<String, String> names = new HashMap<>();
        for (BkBridge.BkTarget t : all) names.put(t.key(), t.displayName());
        int maxLines = Math.max(0, (this.height - 22 - y) / 10);
        List<String[]> log = ap.recentLog(maxLines, names);
        if (log.isEmpty()) text(ctx, "Brak - pojawi się po pierwszych zakupach i analizach.", x, y, GRAY);
        String today = F_DATE.format(Instant.ofEpochMilli(now));
        for (String[] e : log) {
            Instant when2 = Instant.ofEpochMilli(Long.parseLong(e[0]));
            String day = F_DATE.format(when2);
            String line = (day.equals(today) ? "" : day + " ") + F_TIME.format(when2) + " " + e[1] + ": " + e[2];
            int c = "1".equals(e[4]) ? RED : "1".equals(e[3]) ? GREEN : LIGHT;
            text(ctx, ellipsize(line, w), x, y, c);
            y += 10;
        }
        boolean showStatus = !status.isEmpty() && now - statusAt < STATUS_MS;
        String foot = showStatus ? status : firstThatFits(panelW - 12,
                "Zielone = sprzedaż, czerwone = analiza nieudana. Budżet dzienny: zakładka Ustawienia.",
                "Zielone = sprzedaż, czerwone = analiza nieudana.",
                "Zielone = sprzedaż, czerwone = błąd.");
        text(ctx, tr.trimToWidth(foot, panelW - 12), panelX + 6, this.height - 16, showStatus ? statusColor : GRAY);
    }

    // ----- zakładka Auto rynek -----
    private void initAuto() {
        int gap = 4, bw = Math.max(70, (panelW - 12 - 3 * gap) / 4);
        int x = panelX + 6;
        boolean running = AutoMarket.enabled();
        addDrawableChild(ButtonWidget.builder(Text.literal(fitLabel(running ? "Stop auto rynku" : "Start auto rynku", running ? "Stop" : "Start", bw)), b -> {
            if (AutoMarket.enabled()) {
                AutoMarket.stop("panel");
                rebuild();
            } else {
                close(); // rynek potrzebuje ekranu - panel się zamyka; najpierw analiza, potem /ah
                AutoMarket.start();
            }
        }).dimensions(x, 46, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(fitLabel(cfg.amBuy ? "Tryb: snajper" : "Tryb: tylko sprzedaż",
                cfg.amBuy ? "Snajper" : "Sprzedaż", bw)), b -> {
            cfg.amBuy = !cfg.amBuy;
            cfg.save();
            setStatus(cfg.amBuy ? "Tryb snajper: kupuje poniżej skupu, wystawia przy każdym restarcie rynku (od najtańszych)."
                    : "Tryb tylko sprzedaż: nie kupuje, rynek zamknięty, wystawia, gdy tylko jest miejsce na rynku.", GREEN);
            rebuild();
        }).dimensions(x + bw + gap, 46, bw, 20).build());
        ButtonWidget sellB = addDrawableChild(ButtonWidget.builder(Text.literal(fitLabel("Wystawianie: " + onOff(cfg.amSell || !cfg.amBuy),
                "Wyst.: " + onOff(cfg.amSell || !cfg.amBuy), bw)), b -> {
            cfg.amSell = !cfg.amSell;
            cfg.save();
            rebuild();
        }).dimensions(x + 2 * (bw + gap), 46, bw, 20).build());
        sellB.active = cfg.amBuy; // w trybie tylko sprzedaż wystawianie jest zawsze włączone
        addDrawableChild(ButtonWidget.builder(Text.literal(fitLabel("Anty-AFK: " + onOff(cfg.amAntiAfk), "AFK: " + onOff(cfg.amAntiAfk), bw)), b -> {
            cfg.amAntiAfk = !cfg.amAntiAfk;
            cfg.save();
            rebuild();
        }).dimensions(x + 3 * (bw + gap), 46, bw, 20).build());
        int bw2 = Math.max(90, (panelW - 12 - gap) / 2);
        addDrawableChild(ButtonWidget.builder(Text.literal(fitLabel("Analiza: na starcie i co " + cfg.apIntervalMin + " min: " + onOff(cfg.amAnalysis),
                "Analiza co " + cfg.apIntervalMin + " min: " + onOff(cfg.amAnalysis), bw2)), b -> {
            cfg.amAnalysis = !cfg.amAnalysis;
            cfg.save();
            setStatus(cfg.amAnalysis ? "Analiza włączona: na starcie auto rynku, co " + cfg.apIntervalMin + " min i po kilku zakupach tego samego."
                    : "Analiza wyłączona - snajper kupuje po cenach, które są na liście.", cfg.amAnalysis ? GREEN : YELLOW);
            rebuild();
        }).dimensions(x, 68, bw2, 20).build());
        importButton = addDrawableChild(ButtonWidget.builder(Text.literal(importLabel(bw2)), b -> {
            long now = System.currentTimeMillis();
            if (now >= confirmImportUntil) {
                confirmImportUntil = now + 5000;
                b.setMessage(Text.literal(importLabel(bw2)));
                return;
            }
            confirmImportUntil = 0;
            String r;
            try {
                r = RynekAnalizaMod.importFromBk();
            } catch (Throwable t) {
                r = "Import nie udał się: " + t;
            }
            all.clear();
            all.addAll(BkBridge.targets("", cfg));
            applyFilter();
            setStatus(r, r.startsWith("Zaimportowano") ? GREEN : RED);
            rebuild();
        }).dimensions(x + bw2 + gap, 68, bw2, 20).build());
    }

    private static String onOff(boolean b) {
        return b ? "WŁ" : "WYŁ";
    }

    private String fitLabel(String full, String brief, int w) {
        return this.textRenderer.getWidth(full) <= w - 8 ? full : this.textRenderer.trimToWidth(brief, w - 8);
    }

    private String importLabel(int w) {
        boolean confirming = System.currentTimeMillis() < confirmImportUntil;
        return fitLabel(confirming ? "Podmienić listę? Kliknij ponownie" : "Importuj listę z BK", confirming ? "Na pewno? Kliknij" : "Import z BK", w);
    }

    private void drawAuto(DrawContext ctx, int mouseX, int mouseY) {
        TextRenderer tr = this.textRenderer;
        long now = System.currentTimeMillis();
        int x = panelX + 8, w = panelW - 16, y = 94;
        if (importButton != null) {
            String lbl = importLabel(importButton.getWidth());
            if (!lbl.equals(importButton.getMessage().getString())) importButton.setMessage(Text.literal(lbl));
        }
        AutoMarket.Stats st = AutoMarket.stats;
        boolean on = AutoMarket.enabled();
        String head = on ? "Auto rynek: " + AutoMarket.stateLabel().toUpperCase(Locale.ROOT) : "Auto rynek: WYŁĄCZONY";
        String keyName = RynekAnalizaMod.autoKey == null ? "F9" : RynekAnalizaMod.autoKey.getBoundKeyLocalizedText().getString();
        text(ctx, ellipsize(fitParts(w, head, on ? "od " + durationText(now - st.startedAt) : "start/stop: " + keyName), w), x, y, on ? GREEN : GRAY);
        y += 11;
        text(ctx, ellipsize(fitParts(w, "Kupiono: " + st.bought + (st.spent > 0 ? " za " + fmtR(st.spent) + "$" : ""),
                "wystawiono: " + st.listed + (st.listedValue > 0 ? " (" + fmtR(st.listedValue) + "$)" : ""),
                "restarty rynku: " + st.restarts, "nieudane: " + st.failed), w), x, y, LIGHT);
        y += 11;
        if (on) {
            List<String> nxt = new ArrayList<>();
            long space = AutoMarket.msToListSpace(now);
            if (cfg.amSell || !cfg.amBuy) {
                nxt.add(space > 0 ? "brak miejsca na rynku - wystawianie po sprzedaży albo " + inText(space)
                        : "wystawianie " + inText(AutoMarket.msToSell(now)) + (AutoMarket.sniping() && cfg.amRestartSec > 0 ? " (restart)" : ""));
            }
            if (cfg.amAntiAfk) nxt.add("skoki " + inText(AutoMarket.msToAfk(now)));
            long rs = AutoMarket.msToRestart(now);
            if (rs >= 0 && !(cfg.amSell && space <= 0)) nxt.add("restart rynku " + inText(rs));
            if (cfg.amAnalysis && BkBridge.isRunning()) nxt.add("analiza " + inText(RynekAnalizaMod.msToNextCycle()));
            text(ctx, ellipsize(nxt.isEmpty() ? "Następne: -" : "Następne: " + String.join(", ", nxt), w), x, y, GRAY);
        } else {
            text(ctx, ellipsize(firstThatFits(w, "Start: analiza cen, potem snajper z zakładki Najnowsze poniżej skupu; przy każdym restarcie rynku (co "
                    + cfg.amRestartSec + " s) wystawia kupione; skoki co " + cfg.amAfkMin + " min; analiza co " + cfg.apIntervalMin + " min.",
                    "Start: analiza, snajper, wystawianie przy restartach, anty-AFK, analiza co " + cfg.apIntervalMin + " min."), w), x, y, GRAY);
        }
        y += 11;
        String priceSrc = "sprzedaż = śr. 3 najtańszych, skup = sprzedaż x " + fmt(Pricing.tax(cfg)) + " - marża";
        String ana = cfg.amAnalysis ? "Analiza na starcie i co " + cfg.apIntervalMin + " min - " + priceSrc : "Analiza WYŁ - ceny na liście się same nie zmieniają";
        text(ctx, ellipsize(fitParts(w, ana, "szybkość: " + SPEED_NAMES[Math.floorMod(cfg.speed, 3)].toLowerCase(java.util.Locale.ROOT)), w), x, y, cfg.amAnalysis ? GREEN : YELLOW);
        y += 13;

        // lista przedmiotów z przełącznikami
        int buyable = 0, toSell = 0;
        for (BkBridge.BkTarget t : all) {
            if (t.buyEnabled() && t.currentBuyPrice() > 0) buyable++;
            toSell += t.item().toSell;
        }
        ItemStore store = BkBridge.store();
        String src = store == null || store.data.importedFrom.isEmpty() ? "" : "import: " + store.data.importedFrom;
        int noMargin = 0;
        for (BkBridge.BkTarget t : all) if (Double.isNaN(Pricing.margin(t.item(), cfg))) noMargin++;
        String nm = noMargin > 0 ? "bez marży: " + noMargin + " (nie kupuje - marża w liście albo domyślna w Ustawieniach)" : "";
        int waiting = AutoMarket.held().size();
        text(ctx, ellipsize(fitParts(w, "Przedmioty: " + all.size() + ", kupowane: " + buyable,
                waiting > 0 ? "czeka na wyższą cenę: " + waiting + " przedm. (sprzedaż teraz = strata)" : "", nm,
                toSell > 0 ? "do wystawienia: " + toSell + " szt." : "", src), w), x, y, YELLOW);
        y += 11;
        amPills.clear();
        // pod listą: licznik przewijania (12), "Dziennik:" (11) i logLines linii dziennika nad stopką (height - 20)
        int logLines = Math.max(2, Math.min(8, (this.height - y - 30) / 3 / 10));
        amListTop = y;
        amListBottom = Math.max(y + 12, this.height - 43 - logLines * 10);
        int rowH = 12, visible = Math.max(1, (amListBottom - amListTop) / rowH);
        amMaxScroll = Math.max(0, all.size() - visible);
        amScroll = Math.max(0, Math.min(amScroll, amMaxScroll));
        int pillBuyW = tr.getWidth("Kup: NIE") + 8, pillSellW = tr.getWidth("Wyst.: kupione") + 8;
        int pillsX = x + w - pillBuyW - 4 - pillSellW;
        if (all.isEmpty()) {
            text(ctx, ellipsize("Lista jest pusta - kliknij \"Importuj listę z BK\".", w), x, y, GRAY);
        }
        for (int i = amScroll; i < all.size() && i < amScroll + visible; i++) {
            BkBridge.BkTarget t = all.get(i);
            int ry = amListTop + (i - amScroll) * rowH;
            String buyTxt = "skup " + (t.currentBuyPrice() > 0 ? fmtR(t.currentBuyPrice()) + "$" : "-");
            String prices = buyTxt + "  sprz. "
                    + (t.currentSellPrice() > 0 ? fmtR(t.currentSellPrice()) + "$" + (t.sellCount() > 1 ? "/" + t.sellCount() : "") : "-")
                    + (t.item().toSell > 0 ? "  do wyst. " + t.item().toSell : "");
            if (pillsX - x - 16 - tr.getWidth(prices) < 70) prices = buyTxt + (t.item().toSell > 0 ? "  wyst. " + t.item().toSell : ""); // wąsko: nazwa ważniejsza
            int pw = tr.getWidth(prices);
            int nameW = Math.max(30, pillsX - x - 8 - pw - 8);
            boolean buyOn = t.buyEnabled();
            text(ctx, ellipsize(t.displayName(), nameW), x, ry + 2, buyOn && t.currentBuyPrice() > 0 ? WHITE : GRAY);
            text(ctx, prices, pillsX - 8 - pw, ry + 2, GRAY);
            boolean overB = mouseX >= pillsX && mouseX < pillsX + pillBuyW && mouseY >= ry && mouseY < ry + rowH - 1;
            box(ctx, pillsX, ry, pillBuyW, rowH - 1, buyOn ? (overB ? 0xFF2F5A3D : 0xFF234A31) : (overB ? 0xFF2C3140 : CARD_BG), buyOn ? alpha(UP, 0xFF) : CARD_BORDER);
            text(ctx, buyOn ? "Kup: TAK" : "Kup: NIE", pillsX + 4, ry + 2, buyOn ? WHITE : GRAY);
            amPills.add(new int[]{pillsX, ry, pillBuyW, rowH - 1, i, 0});
            int sx = pillsX + pillBuyW + 4;
            boolean sellAll = t.sellEnabled();
            boolean overS = mouseX >= sx && mouseX < sx + pillSellW && mouseY >= ry && mouseY < ry + rowH - 1;
            box(ctx, sx, ry, pillSellW, rowH - 1, sellAll ? (overS ? 0xFF2F5A3D : 0xFF234A31) : (overS ? 0xFF2C3140 : CARD_BG), sellAll ? alpha(UP, 0xFF) : CARD_BORDER);
            text(ctx, sellAll ? "Wyst.: TAK" : "Wyst.: kupione", sx + 4, ry + 2, sellAll ? WHITE : LIGHT);
            amPills.add(new int[]{sx, ry, pillSellW, rowH - 1, i, 1});
        }
        if (amMaxScroll > 0) {
            String more = (amScroll + 1) + "-" + Math.min(all.size(), amScroll + visible) + "/" + all.size() + " (kółko myszy)";
            text(ctx, more, x + w - tr.getWidth(more), amListBottom + 1, GRAY);
        }
        // dziennik: tuż pod listą, gdy cała się mieści (więcej linii dziennika), inaczej pod licznikiem przewijania
        int listEnd = amMaxScroll > 0 ? amListBottom : amListTop + Math.max(1, Math.min(visible, all.size())) * rowH;
        int ly = listEnd + 12;
        text(ctx, "Dziennik:", x, ly, YELLOW);
        ly += 11;
        List<String> log = AutoMarket.logLines();
        if (log.isEmpty()) text(ctx, "Pusto - wpisy pojawią się po starcie auto rynku.", x, ly, GRAY);
        for (int i = log.size() - 1, n = 0; i >= 0 && ly + 10 <= this.height - 20; i--, n++) {
            text(ctx, ellipsize(log.get(i), w), x, ly, n == 0 ? LIGHT : GRAY);
            ly += 10;
        }
        boolean showStatus = !status.isEmpty() && now - statusAt < STATUS_MS;
        String foot = showStatus ? status : firstThatFits(panelW - 12,
                "Kup: TAK = snajper kupuje poniżej ceny skupu (0 = nigdy). Wyst.: TAK = wystawia wszystkie z ekwipunku, kupione = tylko kupione przez snajpera. Analizę pominiesz klawiszem "
                        + RynekAnalizaMod.keyText(RynekAnalizaMod.toggleKey, "F8") + ".",
                "Kup: TAK = snajper kupuje poniżej ceny skupu (0 = nigdy). Wyst.: TAK = wystawia wszystkie z ekwipunku, kupione = tylko kupione przez snajpera.",
                "Kup = snajper kupuje poniżej skupu. Wyst. TAK = wszystkie z ekwipunku, kupione = tylko od snajpera.",
                "Kup / Wyst. - kliknij, żeby zmienić.");
        text(ctx, tr.trimToWidth(foot, panelW - 12), panelX + 6, this.height - 16, showStatus ? statusColor : GRAY);
    }

    /** "za 2 min", "za 40 s", "zaraz". */
    private static String inText(long ms) {
        if (ms < 1000) return "zaraz";
        if (ms < 60_000) return "za " + (ms / 1000) + " s";
        return "za " + (ms / 60_000) + " min";
    }

    // ----- zakładka Historia -----
    /** Przełącznik widoku (zakupy / wg przedmiotów) i "Wyczyść historię" (z potwierdzeniem). */
    private void initHistory() {
        TradeLedger led = RynekAnalizaMod.ledger();
        int bw = Math.max(70, Math.min(120, (panelW - 12 - 8) / 3));
        ButtonWidget list = ButtonWidget.builder(Text.literal(this.textRenderer.trimToWidth("Zakupy", bw - 8)), b -> setHistView(false))
                .dimensions(panelX + 6, 46, bw, 20).build();
        list.active = histByItem;
        addDrawableChild(list);
        ButtonWidget items = ButtonWidget.builder(Text.literal(firstThatFits(bw - 8, "Wg przedmiotów", "Przedmioty")), b -> setHistView(true))
                .dimensions(panelX + 6 + bw + 4, 46, bw, 20).build();
        items.active = !histByItem;
        addDrawableChild(items);
        ButtonWidget clear = ButtonWidget.builder(Text.literal(clearLabel(bw)), b -> clearHistoryClicked())
                .dimensions(panelX + panelW - 6 - bw, 46, bw, 20).build();
        clear.active = led != null && led.size() > 0;
        histClearButton = addDrawableChild(clear);
    }

    private void setHistView(boolean byItem) {
        histByItem = byItem;
        histScroll = 0;
        rebuild();
    }

    /** Pierwsze kliknięcie pyta "Na pewno?", drugie (w ciągu 5 s) czyści historię - poprzednia zostaje w kopii. */
    private void clearHistoryClicked() {
        TradeLedger led = RynekAnalizaMod.ledger();
        if (led == null) return;
        long now = System.currentTimeMillis();
        if (now < confirmClearUntil) {
            confirmClearUntil = 0;
            led.clear();
            histScroll = 0;
            setStatus("Historia wyczyszczona. Poprzednia jest w pliku rynek-analiza-historia-kopia.json.", GREEN);
        } else {
            confirmClearUntil = now + 5000;
        }
        rebuild();
    }

    /** Napis przycisku czyszczenia; "Na pewno? Kliknij ponownie" przez 5 s po pierwszym kliknięciu. */
    private String clearLabel(int bw) {
        if (System.currentTimeMillis() < confirmClearUntil) return firstThatFits(bw - 8, "Na pewno? Kliknij ponownie", "Na pewno? Kliknij", "Na pewno?");
        return firstThatFits(bw - 8, "Wyczyść historię", "Wyczyść");
    }

    /**
     * Historia zakupów i sprzedaży: każdy zakup snajpera z ceną; sprzedaż dopiero, gdy naprawdę nastąpiła (inaczej "jeszcze się
     * nie sprzedał"); zysk po podatku (strata na minus); albo wyniki według przedmiotów. Na dole ile wydano i ile łącznie
     * zysku. Kółko myszy przewija.
     */
    private void drawHistory(DrawContext ctx) {
        TextRenderer tr = this.textRenderer;
        int x = panelX + 8, w = panelW - 16, right = x + w;
        long now = System.currentTimeMillis();
        if (histClearButton != null) {
            String lbl = clearLabel(histClearButton.getWidth());
            if (!lbl.equals(histClearButton.getMessage().getString())) histClearButton.setMessage(Text.literal(lbl)); // potwierdzenie wygasło
        }
        TradeLedger led = RynekAnalizaMod.ledger();
        double taxPct = (1 - (cfg.taxFactor > 0 && cfg.taxFactor <= 1 ? cfg.taxFactor : 0.97)) * 100;
        int y = 72;
        String head = histByItem
                ? firstThatFits(w, "Każdy przedmiot: ile sprzedano z kupionych, ile wydano, zysk po podatku " + fmt(taxPct) + "% i średni czas sprzedaży.",
                        "Wyniki przedmiotów: od największego zysku do największej straty", "Wyniki przedmiotów (od największego zysku)", "Wyniki przedmiotów")
                : firstThatFits(w, "Każdy zakup snajpera. Sprzedaż - dopiero gdy naprawdę się sprzeda. Zysk po podatku " + fmt(taxPct) + "%.",
                        "Zakupy i ich sprzedaż (zysk po podatku " + fmt(taxPct) + "%)", "Zakupy i ich sprzedaż");
        text(ctx, ellipsize(head, w), x, y, GRAY);
        if (led == null) {
            text(ctx, "Historia niedostępna.", x, y + 14, RED);
            return;
        }
        TradeLedger.Totals tot = led.totals();
        int hy = y + 13;
        int top = hy + 12, rowH = 11;
        int footTop = this.height - 16 - 25;
        int visible = Math.max(1, (footTop - 5 - top) / rowH);
        int rows = histByItem ? drawHistoryItems(ctx, led, x, right, hy, top, rowH, visible)
                : drawHistoryList(ctx, led, x, right, hy, top, rowH, visible, now);

        // podsumowanie na dole
        ctx.fill(x, footTop - 4, right, footTop - 3, 0x40FFFFFF);
        String spent = "Wydane łącznie: " + fmtR(tot.spent) + "$";
        String prof = "Zysk łącznie: " + (tot.profit >= 0 ? "+" : "") + fmtR(tot.profit) + "$";
        text(ctx, spent, x, footTop, WHITE);
        int px = Math.max(x + tr.getWidth(spent) + 12, right - tr.getWidth(prof));
        text(ctx, prof, px, footTop, tot.profit >= 0 ? GREEN : RED);
        String sold = "Sprzedane: " + tot.soldPieces + " szt. za " + fmtR(tot.soldGross) + "$";
        String unsold = "niesprzedane: " + tot.unsoldPieces + " szt. (koszt " + fmtR(tot.unsoldCost) + "$)";
        text(ctx, fitParts(w, sold, unsold), x, footTop + 11, GRAY);

        boolean showStatus = !status.isEmpty() && now - statusAt < STATUS_MS;
        String what = histByItem ? " przedmiotów)" : " zakupów)";
        String foot = showStatus ? status : rows > visible
                ? firstThatFits(panelW - 12, "Kółko myszy: przewijanie (" + (histScroll + 1) + "-" + Math.min(rows, histScroll + visible)
                        + " z " + rows + what, "Kółko myszy: przewijanie")
                : firstThatFits(panelW - 12, "Zakupy i sprzedaże robi auto rynek - tu jest ich zapis (plik rynek-analiza-historia.json).",
                        "Zakupy i sprzedaże auto rynku - tu jest ich zapis.", "Zapis zakupów i sprzedaży.");
        text(ctx, tr.trimToWidth(foot, panelW - 12), panelX + 6, this.height - 16, showStatus ? statusColor : GRAY);
    }

    /** Wyniki według przedmiotów: przedmiot | sprzedane z kupionych | wydane | zysk | średni czas sprzedaży. Zwraca liczbę wierszy. */
    private int drawHistoryItems(DrawContext ctx, TradeLedger led, int x, int right, int hy, int top, int rowH, int visible) {
        TextRenderer tr = this.textRenderer;
        List<TradeLedger.ItemStats> list = led.byItem();
        boolean narrow = right - x < 380;
        double maxSpent = 0, maxProfit = 0;
        int maxBought = 0;
        for (TradeLedger.ItemStats s : list) {
            maxSpent = Math.max(maxSpent, s.spent);
            if (s.sold > 0) maxProfit = Math.max(maxProfit, Math.abs(s.profit));
            maxBought = Math.max(maxBought, s.bought);
        }
        String cnt0 = "0".repeat(String.valueOf(maxBought).length());
        int wCnt = Math.max(tr.getWidth("Sprzedane"), Math.max(tr.getWidth("000/000"), tr.getWidth(cnt0 + "/" + cnt0)));
        int wMoney = Math.max(tr.getWidth("Wydane"), Math.max(tr.getWidth("000000$"), moneyWidth(maxSpent, false)));
        int wProfit = Math.max(tr.getWidth("Zysk"), Math.max(tr.getWidth("+00000$"), moneyWidth(maxProfit, true)));
        String timeHead = narrow ? "Czas" : "Śr. czas";
        int wTime = Math.max(tr.getWidth(timeHead), tr.getWidth("00 min"));
        int cTimeR = right;
        int cProfitR = cTimeR - wTime - 8;
        int cSpentR = cProfitR - wProfit - 8;             // na wąskim panelu bez kolumny "Wydane"
        int cCntR = narrow ? cSpentR : cSpentR - wMoney - 8;
        int nameW = Math.max(20, cCntR - wCnt - 6 - x);
        text(ctx, "Przedmiot", x, hy, YELLOW);
        textR(ctx, "Sprzedane", cCntR, hy, YELLOW);
        if (!narrow) textR(ctx, "Wydane", cSpentR, hy, YELLOW);
        textR(ctx, "Zysk", cProfitR, hy, YELLOW);
        textR(ctx, timeHead, cTimeR, hy, YELLOW);

        histMaxScroll = Math.max(0, list.size() - visible);
        histScroll = Math.max(0, Math.min(histMaxScroll, histScroll));
        if (list.isEmpty()) {
            text(ctx, ellipsize(firstThatFits(right - x, "Brak zakupów - wyniki przedmiotów pojawią się, gdy snajper coś kupi.",
                    "Brak zakupów - pojawią się, gdy snajper coś kupi.", "Brak zakupów."), right - x), x, top + 2, GRAY);
        }
        int yy = top;
        for (int i = histScroll; i < list.size() && i < histScroll + visible; i++) {
            TradeLedger.ItemStats s = list.get(i);
            text(ctx, ellipsize(s.name.isEmpty() ? "?" : s.name, nameW), x, yy, LIGHT);
            textR(ctx, s.sold + "/" + s.bought, cCntR, yy, s.sold >= s.bought ? WHITE : LIGHT);
            if (!narrow) textR(ctx, fmtR(s.spent) + "$", cSpentR, yy, LIGHT);
            if (s.sold > 0) textR(ctx, (s.profit >= 0 ? "+" : "") + fmtR(s.profit) + "$", cProfitR, yy, s.profit >= 0 ? GREEN : RED);
            else textR(ctx, "-", cProfitR, yy, GRAY);
            textR(ctx, s.soldOut > 0 ? durationText(s.avgSellMs) : "-", cTimeR, yy, s.soldOut > 0 ? LIGHT : GRAY);
            yy += rowH;
        }
        return list.size();
    }

    /** Szerokość kwoty do maxAbs (cyfry jak "0"): duże kwoty, np. ponad milion, nie wchodzą na sąsiednią kolumnę. */
    private int moneyWidth(double maxAbs, boolean signed) {
        long r = Double.isFinite(maxAbs) ? Math.round(Math.abs(maxAbs)) : 0;
        return this.textRenderer.getWidth((signed ? "+" : "") + "0".repeat(String.valueOf(r).length()) + "$");
    }

    /** Czas sprzedaży krótko: "<1 min", "45 min", "3 h", "2 dni". */
    static String durationText(long ms) {
        if (ms < 60_000L) return "<1 min";
        if (ms < 3_600_000L) return (ms / 60_000L) + " min";
        if (ms < 48 * 3_600_000L) return Math.round(ms / 3_600_000.0) + " h";
        return Math.round(ms / 86_400_000.0) + " dni";
    }

    /** Lista zakupów: kiedy | przedmiot | zakup | sprzedaż | zysk (najnowsze na górze). Zwraca liczbę wierszy. */
    private int drawHistoryList(DrawContext ctx, TradeLedger led, int x, int right, int hy, int top, int rowH, int visible, long now) {
        TextRenderer tr = this.textRenderer;
        int w = right - x;
        List<TradeLedger.Entry> list = led.newestFirst();

        // kolumny: kiedy | przedmiot | zakup | sprzedaż | zysk (kwoty do prawej)
        boolean narrow = w < 380;
        int wDate = tr.getWidth(narrow ? "00.00" : "00.00 00:00");
        double maxCost = 0, maxSold = 0, maxProfit = 0;
        for (TradeLedger.Entry e : list) {
            maxCost = Math.max(maxCost, e.cost);
            if (e.sold > 0) {
                maxSold = Math.max(maxSold, e.soldGross);
                maxProfit = Math.max(maxProfit, Math.abs(e.profit()));
            }
        }
        int wMoney = Math.max(tr.getWidth("000000$"), moneyWidth(maxCost, false));
        int wSell = Math.max(tr.getWidth("Sprzedaż"), Math.max(tr.getWidth("000000$"), moneyWidth(maxSold, false)));
        int wProfit = Math.max(tr.getWidth("Zysk"), Math.max(tr.getWidth("+00000$"), moneyWidth(maxProfit, true)));
        int cName = x + wDate + 6;
        // szerszy panel: kolumna sprzedaży tak szeroka, żeby cały napis "jeszcze się nie sprzedał" się zmieścił
        int extra = tr.getWidth("jeszcze się nie sprzedał") - (wSell + 8 + wProfit);
        int baseNameW = right - wProfit - 8 - wSell - 8 - wMoney - 6 - cName;
        if (extra > 0 && baseNameW - extra >= 110) wSell += extra;
        int cProfitR = right;
        int cSellR = cProfitR - wProfit - 8;
        int cBuyR = cSellR - wSell - 8;
        int nameW = Math.max(20, cBuyR - wMoney - 6 - cName);
        text(ctx, narrow ? "Kiedy" : "Kupiono", x, hy, YELLOW);
        text(ctx, "Przedmiot", cName, hy, YELLOW);
        textR(ctx, "Zakup", cBuyR, hy, YELLOW);
        textR(ctx, "Sprzedaż", cSellR, hy, YELLOW);
        textR(ctx, "Zysk", cProfitR, hy, YELLOW);

        histMaxScroll = Math.max(0, list.size() - visible);
        histScroll = Math.max(0, Math.min(histMaxScroll, histScroll));
        if (list.isEmpty()) {
            text(ctx, ellipsize(firstThatFits(w, "Brak zakupów - każdy zakup snajpera pojawi się tu z ceną, a po sprzedaży z ceną sprzedaży i zyskiem.",
                    "Brak zakupów - pojawią się, gdy snajper coś kupi.", "Brak zakupów."), w), x, top + 2, GRAY);
        }
        String today = F_DATE.format(Instant.ofEpochMilli(now));
        int yy = top;
        for (int i = histScroll; i < list.size() && i < histScroll + visible; i++) {
            TradeLedger.Entry e = list.get(i);
            Instant when = Instant.ofEpochMilli(e.t);
            String day = F_DATE.format(when);
            text(ctx, narrow ? (day.equals(today) ? F_TIME.format(when) : day) : day + " " + F_TIME.format(when), x, yy, GRAY);
            String name = (e.name.isEmpty() ? "?" : e.name) + (e.count > 1 ? " x" + e.count : "");
            text(ctx, ellipsize(name, nameW), cName, yy, LIGHT);
            textR(ctx, fmtR(e.cost) + "$", cBuyR, yy, LIGHT);
            if (e.sold <= 0) {
                int avail = cProfitR - (cSellR - wSell);
                textR(ctx, tr.trimToWidth(firstThatFits(avail, "jeszcze się nie sprzedał", "nie sprzedał się", "niesprzedany"), avail), cProfitR, yy, GRAY);
            } else {
                String part = e.fullySold() ? "" : " (" + e.sold + "/" + e.count + ")";
                String sell = fmtR(e.soldGross) + "$";
                if (!part.isEmpty() && tr.getWidth(sell + part) <= wSell + 6) sell += part;
                textR(ctx, sell, cSellR, yy, e.fullySold() ? WHITE : LIGHT);
                double pr = e.profit();
                textR(ctx, (pr >= 0 ? "+" : "") + fmtR(pr) + "$", cProfitR, yy, pr >= 0 ? GREEN : RED);
            }
            yy += rowH;
        }
        return list.size();
    }

    /** Tekst wyrównany do prawej krawędzi rightX. */
    private void textR(DrawContext ctx, String s, int rightX, int y, int color) {
        text(ctx, s, rightX - this.textRenderer.getWidth(s), y, color);
    }

    // ---------- zdarzenia ----------
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listeningAdd) {
            listeningAdd = false;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                cfg.addItemKey = keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE ? -1 : keyCode;
                cfg.save();
            }
            rebuild();
            return true;
        }
        if (listening != null) {
            KeyBinding kb = listening;
            listening = null;
            if (keyCode != GLFW.GLFW_KEY_ESCAPE) {
                if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) kb.setBoundKey(InputUtil.UNKNOWN_KEY);
                else kb.setBoundKey(InputUtil.fromKeyCode(keyCode, scanCode));
                KeyBinding.updateKeysByCode();
                MinecraftClient.getInstance().options.write();
            }
            rebuild();
            return true;
        }
        boolean typing = getFocused() instanceof TextFieldWidget tf && tf.isFocused();
        if (!typing && RynekAnalizaMod.openKey != null && RynekAnalizaMod.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        if (!typing && tab == Tab.ITEMS) {
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) { setScroll(scroll + viewportH - ROW_H); return true; }
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP) { setScroll(scroll - viewportH + ROW_H); return true; }
            if (keyCode == GLFW.GLFW_KEY_HOME) { setScroll(0); return true; }
            if (keyCode == GLFW.GLFW_KEY_END) { setScroll(maxScroll); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (listening != null) { // klik anuluje ustawianie klawisza
            listening = null;
            rebuild();
            return true;
        }
        if (tab == Tab.AUTO && button == 0) {
            for (int[] pr : amPills) {
                if (mouseX < pr[0] || mouseX >= pr[0] + pr[2] || mouseY < pr[1] || mouseY >= pr[1] + pr[3]) continue;
                if (pr[4] < 0 || pr[4] >= all.size()) break;
                BkBridge.BkTarget t = all.get(pr[4]);
                if (pr[5] == 0) {
                    t.setBuyEnabled(!t.buyEnabled());
                    setStatus(t.displayName() + ": kupowanie " + (t.buyEnabled() ? "włączone" : "wyłączone"), t.buyEnabled() ? GREEN : YELLOW);
                } else {
                    t.setSellEnabled(!t.sellEnabled());
                    setStatus(t.displayName() + ": " + (t.sellEnabled() ? "wystawia wszystkie z ekwipunku" : "wystawia tylko to, co kupił snajper"), GREEN);
                }
                BkBridge.commit();
                return true;
            }
        }
        if (tab != Tab.ITEMS) return super.mouseClicked(mouseX, mouseY, button);

        // pasek przewijania: kliknięcie w uchwyt zaczyna przeciąganie, w tor - przeskok
        if (button == 0 && maxScroll > 0 && mouseX >= listR && mouseX < panelX + panelW && mouseY >= listTop && mouseY < listBottom) {
            int hy = barTop(), hh = barHeight();
            if (mouseY >= hy && mouseY < hy + hh) {
                dragGrab = (int) mouseY - hy;
            } else {
                dragGrab = hh / 2;
                dragBarTo(mouseY);
            }
            draggingBar = true;
            return true;
        }
        // przycisk "Ukryj" w nagłówku rozwiniętego wykresu
        if (button == 0 && mouseY >= listTop && mouseY < listBottom) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (!r.open) continue;
                int[] cr = closeRect(i);
                if (mouseX >= cr[0] && mouseX < cr[0] + cr[2] && mouseY >= cr[1] && mouseY < cr[1] + cr[3]) {
                    toggleChart(r);
                    return true;
                }
                int[] hr = hintRect(i);
                if (mouseX >= hr[0] && mouseX < hr[0] + hr[2] && mouseY >= hr[1] && mouseY < hr[1] + hr[3] && hint(r.t).ok()) {
                    applyHint(r);
                    return true;
                }
                int[] ar = autoRect(i);
                Autopilot ap = RynekAnalizaMod.autopilot();
                if (ap != null && mouseX >= ar[0] && mouseX < ar[0] + ar[2] && mouseY >= ar[1] && mouseY < ar[1] + ar[3]) {
                    Autopilot.ItemState st = ap.state(r.t.key());
                    st.auto = !st.auto;
                    ap.markChanged();
                    setStatus(r.t.displayName() + (st.auto ? ": cena sprzedaży z analizy (automat włączony)" : ": stała cena sprzedaży (automat wyłączony)"),
                            st.auto ? GREEN : YELLOW);
                    return true;
                }
            }
        }
        // wiersze przewinięte pod nagłówek są tylko przycięte, nie ukryte - klik poza obszarem listy nie może ich trafić
        if (mouseY < listTop || mouseY >= listBottom) {
            List<Boolean> was = new ArrayList<>();
            List<ClickableWidget> ws = listWidgets();
            for (ClickableWidget w : ws) { was.add(w.visible); w.visible = false; }
            try {
                return super.mouseClicked(mouseX, mouseY, button);
            } finally {
                int i = 0;
                for (ClickableWidget w : ws) w.visible = was.get(i++);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void dragBarTo(double mouseY) {
        int travel = viewportH - barHeight();
        if (travel <= 0) return;
        double f = (mouseY - dragGrab - listTop) / travel;
        setScroll((int) Math.round(f * maxScroll));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (draggingBar) {
            dragBarTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == Tab.ITEMS && maxScroll > 0) {
            setScroll(scroll - (int) Math.round(verticalAmount * SCROLL_STEP));
            return true;
        }
        if (tab == Tab.HISTORY) {
            histScroll = Math.max(0, Math.min(histMaxScroll, histScroll - (int) Math.round(verticalAmount * 3)));
            return true;
        }
        if (tab == Tab.AUTO) {
            amScroll = Math.max(0, Math.min(amMaxScroll, amScroll - (int) Math.round(verticalAmount * 3)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() {
        flushPending(true);
        Autopilot ap = RynekAnalizaMod.autopilot();
        if (ap != null) ap.save();
        cfg.fix();
        cfg.save();
        cfg.applyToAnalyzer();
        ItemMatcher.configure(cfg);
    }

    private static String sellPart(BkBridge.BkTarget t) {
        double p = t.currentSellPrice();
        int c = t.sellCount();
        return "sprzedaż " + (p > 0 ? fmtR(p) + "$" + (c > 1 ? " za " + c + " szt." : "") : "-");
    }

    private static String buyPart(BkBridge.BkTarget t) {
        double b = t.currentBuyPrice();
        return "skup " + (b > 0 ? fmtR(b) + "$" : "-");
    }

    /** Zysk na sztuce: sprzedaż za sztukę x podatek (np. 0.97) - skup; pusty, gdy brakuje którejś ceny. */
    private String profitPart(BkBridge.BkTarget t) {
        double p = t.currentSellPrice(), b = t.currentBuyPrice();
        if (p <= 0 || b <= 0) return "";
        double z = p / Math.max(1, t.sellCount()) * cfg.taxFactor - b;
        return "zysk/szt. " + (z >= 0 ? "+" : "") + fmtR(z) + "$";
    }

    // ---------- liczby ----------
    private static Double parse(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String fmt(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "?";
        if (v == Math.rint(v)) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** Jak fmt, ale od 100 w górę bez ułamków (do ciasnych linii pod wykresem). */
    static String fmtR(double v) {
        return Math.abs(v) >= 100 ? (Double.isNaN(v) || Double.isInfinite(v) ? "?" : String.valueOf(Math.round(v))) : fmt(v);
    }

    /** Tekst przycięty do maxW z "..." na końcu (gdy się nie mieści). */
    private String ellipsize(String s, int maxW) {
        if (this.textRenderer.getWidth(s) <= maxW) return s;
        return this.textRenderer.trimToWidth(s, Math.max(0, maxW - this.textRenderer.getWidth("..."))).stripTrailing() + "...";
    }

    /** Pierwszy z wariantów tekstu (od najdłuższego), który mieści się w maxW; gdy żaden - ostatni. */
    private String firstThatFits(int maxW, String... variants) {
        for (String v : variants) if (this.textRenderer.getWidth(v) <= maxW) return v;
        return variants[variants.length - 1];
    }

    /** Składa części w jedną linię; części, które się już nie mieszczą w maxW, są pomijane (bez ucinania w pół słowa). */
    private String fitParts(int maxW, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            String next = sb.length() == 0 ? p : sb + " | " + p;
            if (this.textRenderer.getWidth(next) > maxW) break;
            sb.setLength(0);
            sb.append(next);
        }
        if (sb.length() > 0) return sb.toString();
        for (String p : parts) if (p != null && !p.isEmpty()) return this.textRenderer.trimToWidth(p, maxW);
        return "";
    }
}
