package pl.rynekanaliza;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto rynek z analizą cen (dawniej dodatek do BK-Rynku; BK nie jest już potrzebny).
 *   Klawisz "otwórz panel" (domyślnie prawy Shift) - panel z listą przedmiotów, cenami, wykresami, auto rynkiem i ustawieniami
 *   Klawisz "auto rynek" (domyślnie F9)            - START/STOP: najpierw analiza cen, potem snajper (albo "Tylko sprzedaż"),
 *                                                    wystawianie przy restartach rynku, anty-AFK, analiza co 12 min
 *   Klawisz "pomiń analizę" (domyślnie F8)         - kończy trwającą analizę od razu (auto rynek wraca); to samo przycisk
 *                                                    "Pomiń analizę" obok okna rynku
 *   Klawisz "dodaj przedmiot" (domyślnie L)        - w oknie rynku: przedmiot pod myszą trafia na listę (cenę ustali analiza)
 *   Klawisze zmienisz w panelu (zakładka Ustawienia) albo w Sterowaniu Minecrafta; działają też przy otwartym rynku.
 *
 * Lista przedmiotów jest własna (config/rynek-analiza-przedmioty.json); za pierwszym razem importowana z BK-Rynku.
 * Analiza wpisuje do listy CENĘ SPRZEDAŻY (średnia 3 najtańszych ofert za sztukę x ilość sprzedaży); CENA SKUPU liczy się
 * ze wzoru (Pricing): sprzedaż za sztukę x 0,97 - marża. Oferty liczone niezależnie od ulepszeń % (np. "Dodatkowe
 * obrażenia 14%" - BkBridge.withoutPercentLore). Auto rynek (AutoMarket) kupuje i wystawia po tych cenach.
 *
 * Komendy (opcjonalnie):
 *   /analiza start [fragment nazwy]   /analiza stop   /analiza pomin   /analiza lista   /analiza auto
 */
public class RynekAnalizaMod implements ClientModInitializer {
    private static AnalizaConfig cfg;
    private static PriceHistory history;
    private static Autopilot autopilot;
    private static TradeLedger ledger;
    private static boolean bkWasRunning;
    private static int apTicker;
    // harmonogram skanów autopilota (pola pakietowe - ustawiają je testy)
    static long nextPlanAt;             // kolejne sprawdzenie, czy pora na skan (co 15 s)
    static long lastCycleAt;            // start ostatniego skanu wszystkich przedmiotów
    static long lastCycleEndAt;         // koniec ostatniego skanu autopilota (pełnego albo ponownego)
    private static int pendingScans;    // ile przedmiotów obejmie najbliższy skan wszystkich (zakładka Autopilot, znaczek)
    /** Podmieniane w testach: czy auto rynek pracuje, jego wstrzymanie i wznowienie, świeże cele, start skanu autopilota (lista, czy skan wszystkich). */
    static java.util.function.BooleanSupplier bkRunning = BkBridge::isRunning;
    static Runnable bkStop = BkBridge::stopBk;
    static Runnable bkStart = BkBridge::startBk;
    static java.util.concurrent.Callable<List<BkBridge.BkTarget>> freshTargets = () -> BkBridge.targets("", cfg);
    static java.util.function.BooleanSupplier bkAvailable = BkBridge::available;
    /** Auto rynek w trakcie zakupu, wystawiania albo skoków - skan autopilota poczeka. */
    static java.util.function.BooleanSupplier bkBusy = AutoMarket::busy;
    static java.util.concurrent.Callable<List<BkBridge.BkTarget>> observedTargets = () -> MarketObserver.targets(cfg);
    static java.util.function.BiConsumer<List<BkBridge.BkTarget>, Boolean> cycleStarter = (pick, full) -> startTargets(pick, true, full);
    static final int NONE = 0, FULL = 1, RESCAN = 2;
    /** Po skanie auto rynek pracuje co najmniej tyle, zanim ruszy następny skan wszystkich (długa lista nie zatrzyma go na stałe). */
    static final long MIN_BREAK_MS = 2 * 60_000L;
    /** Ponowny skan po zakupach - nie wcześniej niż tyle po poprzednim skanie. */
    static final long RESCAN_GAP_MS = 30_000L;
    private static boolean warnedAutopilot;

    public static KeyBinding openKey;
    public static KeyBinding toggleKey;
    public static KeyBinding autoKey;
    /** Komunikat o imporcie listy z BK - pokazany po wejściu do gry. */
    private static String pendingNotice;

    public static AnalizaConfig config() { return cfg; }

    public static PriceHistory history() { return history; }

    public static Autopilot autopilot() { return autopilot; }

    /** Auto rynek wystawił przedmiot: zapamiętane w historii (po tej cenie rozpoznamy sprzedaż). */
    static void onListed(MarketItem it, int qty, double total) {
        if (autopilot == null || it == null) return;
        BkBridge.BkTarget t = null;
        try {
            for (BkBridge.BkTarget x : observedTargets.call()) if (x.item() == it) { t = x; break; }
        } catch (Throwable ignored) {}
        String name = ItemMatcher.stripColors(it.name == null || it.name.isBlank() ? (it.material == null ? "" : it.material) : it.name);
        autopilot.onListed(t, name, qty, total, System.currentTimeMillis());
    }

    /** Historia zakupów i sprzedaży (zakładka Historia). */
    public static TradeLedger ledger() { return ledger; }

    /** Jak dawno musi być ostatnia analiza przedmiotu, żeby przed wstrzymaniem wystawki sprawdzić jego cenę jeszcze raz. */
    static final long SELL_CHECK_AGE = 5 * 60_000L;

    /**
     * Wystawianie bez straty: czy wystawić qty sztuk za price (cała wystawka)? planned = ile sztuk tego przedmiotu idzie już
     * we wcześniejszych wystawkach tej rundy. Koszt = najtańsze kupione sztuki, które jeszcze nie leżą na rynku (historia
     * zakupów). null = wystawiaj (bez straty, koszt nieznany albo funkcja wyłączona), inaczej - czekaj (powód).
     */
    static AutoMarket.Hold sellGuard(MarketItem it, int qty, long price, int planned, long now) {
        if (cfg == null || !cfg.amSellNoLoss || ledger == null || it == null || qty <= 0) return null;
        BkBridge.BkTarget t = null;
        try {
            for (BkBridge.BkTarget x : observedTargets.call()) if (x.item() == it) { t = x; break; }
        } catch (Throwable ignored) {}
        if (t == null) return null;
        TradeLedger.Cost c = ledger.cheapestOpen(t.key(), ledger.onMarket(t.key(), now) + Math.max(0, planned), qty);
        if (c == null) return null; // nie wiemy, za ile kupione (np. kupione ręcznie) - wystawiamy jak dotąd
        double netPP = price / (double) qty * Pricing.tax(cfg);
        double profitPP = netPP - c.costPP;
        if (profitPP >= cfg.amSellMinProfit - 1e-6) return null;
        if (cfg.amSellMaxWaitH > 0 && now - c.oldestT >= cfg.amSellMaxWaitH * 3_600_000L) {
            AutoMarket.note(t.displayName() + ": czeka już ponad " + AnalizaScreen.fmt(cfg.amSellMaxWaitH) + " h - wystawiam mimo straty "
                    + AutoPricing.money(-profitPP) + "/szt.");
            return null;
        }
        // cena sprzedaży starsza niż 5 min: najpierw analiza tego przedmiotu (auto rynek zrobi ją za chwilę), potem decyzja
        boolean check = false;
        if (autopilot != null && cfg.amAnalysis) {
            Autopilot.ItemState s = autopilot.state(t.key());
            if (s.auto && (s.sellCheck || now - s.lastScanAt > SELL_CHECK_AGE)) {
                s.sellCheck = true;
                check = true;
            }
        }
        return new AutoMarket.Hold(t.displayName(), qty, c.costPP, netPP, check);
    }

    @Override
    public void onInitializeClient() {
        cfg = AnalizaConfig.load();
        cfg.applyToAnalyzer();
        firstRunImport();
        ItemMatcher.configure(cfg);
        history = PriceHistory.load();
        autopilot = Autopilot.load(cfg);
        ledger = TradeLedger.load();
        autopilot.ledger = ledger;
        MarketAnalyzer.quietSink = line -> autopilot.scanLine(line);
        MarketAnalyzer.init();
        ClientTickEvents.END_CLIENT_TICK.register(MarketObserver::tick);
        ClientTickEvents.END_CLIENT_TICK.register(RynekAnalizaMod::autopilotTick);
        ClientTickEvents.END_CLIENT_TICK.register(AutoMarket::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AutoMarket.stop("wyjście z gry"); // m.in. przywraca pauzę gry przy utracie fokusu
            history.saveBlocking();
            autopilot.save();
            BkBridge.commit();
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof HandledScreen<?> hs) {
                ButtonWidget skip = addSkipButton(hs, width, height);
                ScreenEvents.afterRender(screen).register((scr, ctx, mouseX, mouseY, tickDelta) -> {
                    noteMouse(scr, mouseX, mouseY); // klawisz "dodaj przedmiot" bierze przedmiot spod myszy
                    AnalysisOverlay.render(scr, ctx);
                    StatusBadge.renderScreen(scr, ctx); // znaczek "Rynek Analiza: działa" obok okna rynku
                    skip.visible = MarketAnalyzer.isRunning();
                    AutoMarket.onFrame(client); // zakup: reakcja w tej samej klatce, w której przyszło okno (bez czekania na tick)
                });
                // klawisze moda działają też przy otwartym rynku (w oknach Minecraft nie sprawdza zwykłych klawiszy)
                ScreenKeyboardEvents.afterKeyPress(screen).register((scr, key, scancode, modifiers) -> screenKey(client, scr, key, scancode));
            }
        });
        // ten sam znaczek na ekranie gry, gdy auto rynek pracuje, a okno rynku jest zamknięte
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> StatusBadge.renderHud(ctx));
        // czat serwera: wyniki zakupów i wystawiania, sprzedaże, saldo (i dawne obejście błędu BK, gdyby był zainstalowany)
        ClientReceiveMessageEvents.ALLOW_GAME.register(BkChatGuard::allow);

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rynek_analiza.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "category.rynek_analiza"));
        // dawny "start/stop analizy" (ten sam identyfikator - przypisany klawisz zostaje): teraz "pomiń analizę"
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rynek_analiza.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.rynek_analiza"));
        autoKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rynek_analiza.auto", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "category.rynek_analiza"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) skipAnalysis();
            while (openKey.wasPressed()) openGui(client);
            while (autoKey.wasPressed()) AutoMarket.toggle();
            if (pendingNotice != null && client.player != null) {
                MarketAnalyzer.log.accept(pendingNotice);
                pendingNotice = null;
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("analiza")
                        .then(ClientCommandManager.literal("start")
                                .executes(ctx -> { startAnalysis(""); return 1; })
                                .then(ClientCommandManager.argument("fragment", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            startAnalysis(StringArgumentType.getString(ctx, "fragment"));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> { MarketAnalyzer.stop(); return 1; }))
                        .then(ClientCommandManager.literal("pomin")
                                .executes(ctx -> { skipAnalysis(); return 1; }))
                        .then(ClientCommandManager.literal("lista")
                                .executes(ctx -> { printList(ctx.getSource()); return 1; }))
                        .then(ClientCommandManager.literal("auto")
                                .executes(ctx -> { AutoMarket.toggle(); return 1; }))));
    }

    /** Pierwsze uruchomienie (albo brak listy): lista przedmiotów i ustawienia rynku z BK-Rynku, jeśli jest w configu. */
    static void firstRunImport() {
        ItemStore store = BkBridge.store();
        if (cfg.bkImportDone && store.exists()) return;
        if (!store.exists() || store.items().isEmpty()) {
            try {
                ItemStore.BkImport imp = ItemStore.findBk(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
                if (imp != null) {
                    store.replaceWith(imp, System.currentTimeMillis());
                    ItemStore.applyBkSettings(imp.settings, cfg);
                    pendingNotice = "Zaimportowano z BK-Rynku " + imp.items.size() + " przedmiotów (profil " + imp.source
                            + ") i ustawienia rynku. BK możesz już usunąć - auto rynek: klawisz F9 albo panel, zakładka \"Auto rynek\".";
                } else if (!store.exists()) {
                    store.save();
                }
            } catch (Throwable t) {
                pendingNotice = "Nie udało się zaimportować listy z BK: " + t;
            }
        }
        cfg.bkImportDone = true;
        cfg.save();
    }

    /** Ponowny import z BK (przycisk w panelu); tekst wyniku. */
    static String importFromBk() {
        ItemStore.BkImport imp = ItemStore.findBk(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        if (imp == null) return "Nie znalazłem listy BK-Rynku w config/BK-Mods/BK-Rynek ani w config/bkrynek-config.";
        BkBridge.store().replaceWith(imp, System.currentTimeMillis());
        ItemStore.applyBkSettings(imp.settings, cfg);
        ItemMatcher.configure(cfg);
        cfg.save();
        return "Zaimportowano " + imp.items.size() + " przedmiotów z BK (profil " + imp.source + "). Poprzednia lista: config/rynek-analiza-przedmioty-kopia.json.";
    }

    /** Klawisz wciśnięty w oknie (np. rynku): auto rynek, analiza, dodanie przedmiotu, panel. */
    static void screenKey(MinecraftClient client, Screen scr, int key, int scancode) {
        if (!(scr instanceof HandledScreen<?>)) return;
        if (autoKey != null && autoKey.matchesKey(key, scancode)) AutoMarket.toggle();
        else if (toggleKey != null && toggleKey.matchesKey(key, scancode)) skipAnalysis();
        else if (isAddKey(key) && scr instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs)) addHovered(hs);
        else if (openKey != null && openKey.matchesKey(key, scancode) && scr instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs)) openGui(client);
    }

    // ---------- klawisz "dodaj przedmiot" (domyślnie L) w oknie rynku ----------
    private static Screen mouseScreen;
    private static int mouseX, mouseY;

    /** Pozycja myszy w oknie (z każdej klatki okna z przedmiotami). */
    static void noteMouse(Screen scr, int x, int y) {
        mouseScreen = scr;
        mouseX = x;
        mouseY = y;
    }

    static boolean isAddKey(int key) {
        return cfg != null && cfg.addItemKey > 0 && key == cfg.addItemKey;
    }

    /** Nazwa klawisza "dodaj przedmiot" (do komunikatów i Ustawień). */
    static String addKeyText() {
        if (cfg == null || cfg.addItemKey <= 0) return "brak";
        try {
            String s = InputUtil.fromKeyCode(cfg.addItemKey, 0).getLocalizedText().getString();
            return s == null || s.isBlank() ? "L" : s;
        } catch (Throwable t) {
            return "L";
        }
    }

    /** Slot pod myszą w oknie rynku: oferty i ekwipunek gracza (bez dolnego rzędu przycisków rynku); -1 = żaden. */
    static int hoveredSlot(HandledScreen<?> hs, int mx, int my) {
        ScreenHandler h = hs.getScreenHandler();
        int size = MarketAnalyzer.containerSize(h);
        int rows = Math.max(1, size / 9);
        int left = (hs.width - 176) / 2, top = (hs.height - (114 + rows * 18)) / 2;
        for (int i = 0; i < h.slots.size(); i++) {
            if (i >= size - 9 && i < size) continue; // przyciski rynku (strony, sortowanie, odświeżanie)
            Slot s = h.slots.get(i);
            int sx = left + s.x, sy = top + s.y;
            if (mx >= sx - 1 && mx < sx + 17 && my >= sy - 1 && my < sy + 17) return i;
        }
        return -1;
    }

    /** Klawisz "dodaj przedmiot": przedmiot spod myszy na listę (cena sprzedaży przy następnej analizie). */
    static void addHovered(HandledScreen<?> hs) {
        int slot = mouseScreen == hs ? hoveredSlot(hs, mouseX, mouseY) : -1;
        ItemStack st = slot < 0 ? null : hs.getScreenHandler().slots.get(slot).getStack();
        if (st == null || st.isEmpty()) {
            MarketAnalyzer.log.accept("Najedź myszką na przedmiot w oknie rynku (oferta albo Twój ekwipunek) i naciśnij " + addKeyText()
                    + " - przedmiot trafi na listę.");
            return;
        }
        ItemStore store = BkBridge.store();
        if (store == null) return;
        ItemStore.AddResult r = store.addFromStack(st, cfg);
        MarketAnalyzer.log.accept(r.message);
    }

    // ---------- panel i start/stop ----------
    public static void openGui(MinecraftClient client) {
        if (client.currentScreen != null) {
            // z okna rynku (np. w trakcie pracy auto rynku): rynek zamykamy, auto rynek czeka, aż panel się zamknie
            if (!(client.currentScreen instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs)) || MarketAnalyzer.isRunning()) return;
            if (client.player != null) client.player.closeHandledScreen();
        }
        if (MarketAnalyzer.isRunning()) {
            MarketAnalyzer.log.accept("Trwa analiza - panel otworzysz po niej albo pomiń ją: " + keyText(toggleKey, "F8")
                    + " albo przycisk \"Pomiń analizę\" obok rynku.");
            return;
        }
        client.setScreen(new AnalizaScreen(cfg));
    }

    public static void toggleAnalysis() {
        if (MarketAnalyzer.isRunning()) MarketAnalyzer.stop();
        else startAnalysis("");
    }

    /** "Pomiń analizę" (klawisz, przycisk obok rynku, /analiza pomin): trwająca analiza kończy się od razu. */
    public static void skipAnalysis() {
        if (!MarketAnalyzer.isRunning()) {
            MarketAnalyzer.log.accept("Analiza nie trwa (auto rynek: " + keyText(autoKey, "F9") + ").");
            return;
        }
        if (!MarketAnalyzer.skip()) return; // już się kończy
        if (!MarketAnalyzer.isCycle()) MarketAnalyzer.log.accept("Analiza pominięta.");
    }

    static String keyText(KeyBinding kb, String def) {
        try {
            return kb == null ? def : kb.getBoundKeyLocalizedText().getString();
        } catch (Throwable t) {
            return def;
        }
    }

    /** Przycisk "Pomiń analizę" obok okna rynku (widoczny tylko w trakcie analizy). */
    static ButtonWidget addSkipButton(HandledScreen<?> hs, int width, int height) {
        int rows = Math.max(1, (hs.getScreenHandler().slots.size() - 36) / 9);
        int bgH = 114 + rows * 18;
        int left = (width - 176) / 2, top = (height - bgH) / 2;
        int w = 84, h = 20;
        int x = left + 176 + 4, y = Math.max(0, top);
        if (x + w > width - 2) { // za wąsko po prawej - pod oknem
            x = (width - w) / 2;
            y = Math.min(height - h, top + bgH + 2);
        }
        ButtonWidget b = ButtonWidget.builder(Text.literal("Pomiń analizę"), btn -> skipAnalysis()).dimensions(x, y, w, h).build();
        b.visible = MarketAnalyzer.isRunning();
        Screens.getButtons(hs).add(b);
        return b;
    }

    public static void startAnalysis(String filter) {
        if (MarketAnalyzer.isRunning()) {
            MarketAnalyzer.log.accept("Analiza już trwa (klawisz start/stop albo /analiza stop, żeby przerwać).");
            return;
        }
        try {
            List<BkBridge.BkTarget> targets = BkBridge.targets(filter, cfg);
            if (targets.isEmpty()) {
                MarketAnalyzer.log.accept("Brak przedmiotów na liście" + (filter.isBlank() ? " (zaimportuj ją z BK w panelu, zakładka \"Auto rynek\")." : " pasujących do: " + filter));
                return;
            }
            startTargets(targets, false);
        } catch (Throwable t) {
            MarketAnalyzer.log.accept("Błąd startu analizy: " + t);
        }
    }

    /** Analiza podanych przedmiotów (zwykła: z panelu, klawisza albo komendy). */
    static void startTargets(List<BkBridge.BkTarget> targets, boolean cycle) {
        startTargets(targets, cycle, true);
    }

    static boolean startTargets(List<BkBridge.BkTarget> targets, boolean cycle, boolean full) {
        return startTargets(targets, cycle, full, false);
    }

    /**
     * Analiza podanych przedmiotów; cycle = analiza auto rynku (cicha, na koniec podsumowanie, auto rynek zawsze
     * wznawiany), full = wszystkie przedmioty (false = ponowna analiza po zakupach), fresh = auto rynek dopiero wystartował
     * i nic jeszcze nie kliknął - bez przerwy przed analizą. false = analiza nie ruszyła.
     */
    static boolean startTargets(List<BkBridge.BkTarget> targets, boolean cycle, boolean full, boolean fresh) {
        cfg.applyToAnalyzer();
        MarketAnalyzer.PRICE_REGEX = BkBridge.priceRegex();

        // auto rynek wstrzymany -> przerwa -> analiza -> zamknięcie rynku -> przerwa -> auto rynek wznowiony (bez nakładania się)
        bkWasRunning = bkRunning.getAsBoolean();
        if (bkWasRunning) bkStop.run();
        boolean resume = bkWasRunning && (cycle || cfg.resumeBk);
        int pause = bkPauseTicks();
        MarketAnalyzer.otherRunning = bkRunning;
        MarketAnalyzer.otherStop = bkStop;

        MarketAnalyzer.cycle = cycle;
        BkBridge.resetPriceChanges();
        MarketAnalyzer.onFinished = () -> {
            MarketAnalyzer.cycle = false;
            cfg.save();        // ostatnie pomiary
            history.save();    // historia cen
            if (cycle) {
                autopilot.endCycle();
                lastCycleEndAt = System.currentTimeMillis();
            } else {
                autopilot.save();
            }
            BkBridge.commit(); // ceny na listę (snajper i wystawianie biorą je od razu)
            if (cycle && AutoMarket.enabled()) { // (po stopie auto rynku - bez podsumowania)
                int[] ch = BkBridge.priceChanges();
                String what = ch[0] + ch[1] == 0 ? " (ceny bez zmian)" : " (zmienione ceny - sprzedaż: " + ch[0] + ", skup: " + ch[1] + ")";
                AutoMarket.say((MarketAnalyzer.wasSkipped() ? "Analiza pominięta" : "Analiza zakończona") + what + "."
                        + (resume ? " Auto rynek wraca." : ""));
            }
            if (resume) bkStart.run();
        };
        if (cycle && AutoMarket.enabled()) {
            AutoMarket.say((full ? "Analiza cen: " + targets.size() + " przedm." : "Ponowna analiza: " + names(targets))
                    + " Pomiń: " + keyText(toggleKey, "F8") + " albo przycisk \"Pomiń analizę\" obok rynku.");
        }
        int before = bkWasRunning && !fresh ? pause : 0;
        if (!MarketAnalyzer.start(targets, before, resume ? pause : 0)) {
            MarketAnalyzer.cycle = false;
            if (cycle) lastCycleEndAt = System.currentTimeMillis();
            MarketAnalyzer.log.accept("Nie udało się wystartować analizy.");
            if (resume) bkStart.run();
            return false;
        }
        return true;
    }

    private static String names(List<BkBridge.BkTarget> ts) {
        List<String> n = new ArrayList<>();
        for (BkBridge.BkTarget t : ts) {
            if (n.size() == 3) {
                n.add("i " + (ts.size() - 3) + " więcej");
                break;
            }
            n.add(t.displayName());
        }
        return String.join(", ", n) + ".";
    }

    /**
     * Start auto rynku: analiza wszystkich przedmiotów od razu (auto rynek czeka i rusza po niej). false = nie ruszyła
     * (np. już trwa inna analiza albo pusta lista) - auto rynek startuje bez niej.
     */
    static boolean startBotScanNow() {
        if (cfg == null || autopilot == null || MarketAnalyzer.isRunning()) return false;
        try {
            long now = System.currentTimeMillis();
            List<BkBridge.BkTarget> fresh = freshTargets.call();
            if (fresh.isEmpty()) return false;
            // kolejność jak w skanie autopilota: jeszcze niesprawdzane, potem najdawniej sprawdzane
            List<BkBridge.BkTarget> pick = new ArrayList<>();
            for (Autopilot.Pick pk : autopilot.plan(fresh, now)) pick.add(pk.t);
            lastCycleAt = now;
            nextPlanAt = now + 15_000L;
            pendingScans = pick.size();
            return startTargets(pick, true, true, true);
        } catch (Throwable t) {
            MarketAnalyzer.log.accept("Błąd startu analizy: " + t);
            return false;
        }
    }

    // ---------- zakupy/sprzedaże auto rynku (historia), budżet (autopilot), skup ze wzoru, analiza co apIntervalMin minut ----------
    static void autopilotTick(MinecraftClient mc) {
        if (cfg == null || autopilot == null) return;
        if (++apTicker < 20) return; // raz na sekundę
        apTicker = 0;
        long now = System.currentTimeMillis();
        try {
            if (mc.player == null || mc.world == null || !bkAvailable.getAsBoolean()) return;
            List<BkBridge.BkTarget> ts = observedTargets.call();
            // zakupy i sprzedaże trafiają do historii także przy wyłączonym autopilocie
            for (BkBridge.Purchase p : BkBridge.pollPurchases()) autopilot.onPurchase(ts, p.stack, p.total, p.count, now);
            for (BkBridge.SaleEvent e : BkBridge.pollSales(now)) autopilot.onSale(ts, e.amount, e.text, now);
            autopilot.tick(ts, now);
            autopilot.flush();
            // skup ze wzoru: sprzedaż x 0,97 - marża (po każdej zmianie ceny sprzedaży albo marży)
            Pricing.syncAll(ts, cfg, autopilot);
            // analiza auto rynku co apIntervalMin minut i ponowna po zakupach - także bez autopilota
            if (cfg.amAnalysis) maybeStartCycle(mc, ts, now);
        } catch (Throwable t) {
            if (!warnedAutopilot) {
                warnedAutopilot = true;
                MarketAnalyzer.log.accept("Autopilot: błąd (" + t + ") - sprawdź log gry.");
            }
            t.printStackTrace();
        }
    }

    /**
     * Analizy auto rynku (ustawienie amAnalysis):
     *  - co apIntervalMin minut (licząc od startu poprzedniej) - WSZYSTKIE przedmioty z listy; po analizie auto rynek
     *    pracuje co najmniej MIN_BREAK_MS, nawet gdy analiza długiej listy trwała dłużej niż odstęp,
     *  - przedmiot, który snajper kupił apRescanBuys razy w ciągu apRescanMin minut - od razu sam (ponowna analiza).
     * Tylko gdy auto rynek pracuje (wtedy i tak stoi na rynku), nie jest w trakcie zakupu/wystawiania i gracz nie ma
     * otwartego innego okna (np. ekwipunku albo tego panelu). Auto rynek jest wstrzymywany na czas skanu i wznawiany po nim.
     */
    static void maybeStartCycle(MinecraftClient mc, List<BkBridge.BkTarget> ts, long now) throws Exception {
        if (MarketAnalyzer.isRunning() || now < nextPlanAt) return;
        nextPlanAt = now + 15_000L;
        List<Autopilot.Pick> plan = autopilot.plan(ts, now);
        pendingScans = plan.size();
        int rescans = 0;
        for (Autopilot.Pick pk : plan) if (pk.kind == Autopilot.Pick.RESCAN) rescans++;
        int kind = cycleKind(now, plan.size(), rescans);
        if (kind == NONE) return;
        if (!bkRunning.getAsBoolean() || bkBusy.getAsBoolean()) return;
        if (mc.currentScreen != null && !(mc.currentScreen instanceof HandledScreen<?> hs && MarketAnalyzer.isMarket(hs))) return;
        List<BkBridge.BkTarget> fresh = freshTargets.call(); // świeże cele (te z obserwacji są tylko do odczytu)
        List<BkBridge.BkTarget> pick = new ArrayList<>();
        for (Autopilot.Pick pk : plan) {
            if (kind == RESCAN && pk.kind != Autopilot.Pick.RESCAN) continue;
            for (BkBridge.BkTarget f : fresh) if (f.key().equals(pk.t.key())) { pick.add(f); break; }
        }
        if (pick.isEmpty()) return;
        if (kind == FULL) lastCycleAt = now;
        cycleStarter.accept(pick, kind == FULL);
    }

    /**
     * Jaki skan teraz: FULL = pora na skan wszystkich (odstęp od startu poprzedniego i przerwa po końcu ostatniego),
     * RESCAN = tylko przedmioty po zakupach (nie wcześniej niż RESCAN_GAP_MS po ostatnim skanie), NONE = nic.
     */
    static int cycleKind(long now, int planned, int rescans) {
        if (planned <= 0) return NONE;
        if (now >= nextFullScanAt()) return FULL;
        if (rescans > 0 && now - lastCycleEndAt >= RESCAN_GAP_MS) return RESCAN;
        return NONE;
    }

    /** Przerwa przed analizą (po wstrzymaniu auto rynku) i po niej (przed wznowieniem), w tickach; ustawienie w sekundach, 0-15. */
    static int bkPauseTicks() {
        return (int) Math.round(Math.max(0, Math.min(15, cfg.bkPauseSec)) * 20);
    }

    static long intervalMs() {
        return Math.max(2, cfg.apIntervalMin) * 60_000L;
    }

    /** Kiedy może ruszyć następny skan wszystkich: odstęp od startu poprzedniego i przerwa po końcu ostatniego skanu. */
    static long nextFullScanAt() {
        return Math.max(lastCycleAt + intervalMs(), lastCycleEndAt + MIN_BREAK_MS);
    }

    /** Za ile ms autopilot zrobi następny skan wszystkich przedmiotów (0 = zaraz). */
    public static long msToNextCycle() {
        return Math.max(0, nextFullScanAt() - System.currentTimeMillis());
    }

    /** Ile przedmiotów obejmie najbliższy skan wszystkich (odświeżane co 15 s). */
    public static int pendingScans() {
        return pendingScans;
    }

    private static void printList(FabricClientCommandSource src) {
        try {
            List<String> lines = BkBridge.describe(cfg);
            if (lines.isEmpty()) src.sendFeedback(Text.literal("[Analiza] Lista przedmiotów jest pusta."));
            for (String line : lines) src.sendFeedback(Text.literal("[Analiza] " + line));
        } catch (Throwable t) {
            src.sendFeedback(Text.literal("[Analiza] Błąd: " + t));
        }
    }
}
