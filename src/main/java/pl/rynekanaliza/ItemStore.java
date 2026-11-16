package pl.rynekanaliza;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;

import java.io.IOException;
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
import java.util.stream.Stream;

/**
 * Lista przedmiotów moda (config/rynek-analiza-przedmioty.json) - zastępuje listę z BK-Rynku.
 * Za pierwszym razem (albo przyciskiem "Importuj z BK") wczytuje przedmioty z BK-Rynku 3.0:
 *  - config/BK-Mods/BK-Rynek/auto_market_configs/*.json - profile (settings."modules.settings.auto_market.item_registry"),
 *  - config/BK-Mods/BK-Rynek/bkrynek-config.json (albo plik z selected.txt) - lista servers[].prices i defaultProfile,
 *  - to samo w starszym miejscu config/bkrynek-config/.
 * Aktywny profil = defaultProfile z głównego configu BK (gdy jest w kilku plikach - najnowszy plik); bez niego - profil
 * z Anarchii albo najnowszy z przedmiotami. Z profilu biorę też ustawienia rynku (komenda, sloty, wzór ceny).
 */
public final class ItemStore {
    public static final String FILE = "rynek-analiza-przedmioty.json";
    static final String KEY = "modules.settings.auto_market.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static final class Data {
        int version = 1;
        /** Z jakiego profilu BK zaimportowano listę (pusty = nie importowano). */
        String importedFrom = "";
        long importedAt;
        List<MarketItem> items = new ArrayList<>();
    }

    private final Path file;
    final Data data;
    /** Zmienia się przy każdej zmianie listy albo cen (obserwator i snajper wtedy odświeżają swoje kopie). */
    private long version = 1;

    private ItemStore(Path file, Data data) {
        this.file = file;
        this.data = data;
    }

    public static ItemStore load() {
        return load(FabricLoader.getInstance().getConfigDir().resolve(FILE));
    }

    static ItemStore load(Path file) {
        Data d = null;
        if (file != null && Files.exists(file)) {
            try {
                d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (Throwable t) {
                try {
                    Files.copy(file, file.resolveSibling(FILE + ".uszkodzony"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
                MarketAnalyzer.log.accept("Lista przedmiotów była uszkodzona - kopia w config/" + FILE + ".uszkodzony");
            }
        }
        if (d == null) d = new Data();
        if (d.items == null) d.items = new ArrayList<>();
        d.items.removeIf(i -> i == null);
        for (MarketItem i : d.items) i.normalize();
        return new ItemStore(file, d);
    }

    /** Czy plik listy już istnieje (false = pierwsze uruchomienie, pora na import z BK). */
    public boolean exists() {
        return file != null && Files.exists(file);
    }

    public List<MarketItem> items() {
        return data.items;
    }

    public long version() {
        return version;
    }

    /** Lista albo ceny się zmieniły (bez zapisu na dysk). */
    public void touch() {
        version++;
    }

    // ---------- dodawanie z okna rynku (klawisz "dodaj przedmiot", domyślnie L) ----------
    /** Wynik dodania przedmiotu: wpis (nowy albo ten, który już pasował), czy dodany, komunikat na czat. */
    public static final class AddResult {
        public final MarketItem item;
        public final boolean added;
        public final String message;

        AddResult(MarketItem item, boolean added, String message) {
            this.item = item;
            this.added = added;
            this.message = message;
        }
    }

    /**
     * Nowy wpis listy z przedmiotu z okna rynku: id, nazwa z kolorami (jak w BK - podróbka z inną nazwą/kolorem nie pasuje),
     * CustomModelData, enchanty zaklętej księgi, ilość sprzedaży = ilość w klikniętym stacku (gdy więcej niż 1).
     * Ceny 0 - sprzedaż ustali najbliższa analiza, skup policzy wzór (gdy przedmiot ma marżę). "Kup: TAK", "Wyst.: kupione".
     */
    static MarketItem fromStack(ItemStack st) {
        MarketItem it = new MarketItem();
        it.material = ItemMatcher.itemId(st);
        it.name = ItemMatcher.toLegacy(st.getName()).trim();
        Integer cmd = ItemMatcher.customModelData(st);
        if (cmd != null && cmd != 0) it.customModelData = cmd;
        ItemEnchantmentsComponent stored = st.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored != null) {
            List<String> parts = new ArrayList<>();
            for (RegistryEntry<Enchantment> e : stored.getEnchantments()) {
                String id = e.getKey().map(k -> k.getValue().getPath()).orElse("");
                int lvl = stored.getLevel(e);
                if (id.isEmpty() || lvl <= 0) continue;
                it.enchantments.put(id, lvl);
                parts.add(id + " " + lvl);
            }
            if (!parts.isEmpty()) it.enchants = String.join(", ", parts);
        }
        if (st.getCount() > 1) it.sellCount = st.getCount();
        it.buyEnabled = true;
        it.sellEnabled = false;
        return it.normalize();
    }

    /** Pierwszy przedmiot z listy, do którego pasuje stack; null = żaden. */
    MarketItem find(ItemStack st) {
        for (MarketItem it : data.items) if (ItemMatcher.matches(st, it)) return it;
        return null;
    }

    /** Dodaje przedmiot z okna rynku do listy (gdy żaden z listy do niego nie pasuje) i zapisuje listę. */
    public synchronized AddResult addFromStack(ItemStack st, AnalizaConfig cfg) {
        if (st == null || st.isEmpty()) return new AddResult(null, false, "Pusty slot - najedź myszką na przedmiot.");
        String shown = ItemMatcher.stripColors(ItemMatcher.toLegacy(st.getName())).trim();
        MarketItem have = find(st);
        if (have != null) {
            String hn = ItemMatcher.stripColors(have.name == null ? "" : have.name).trim();
            return new AddResult(have, false, shown + " już jest na liście" + (hn.isEmpty() || hn.equalsIgnoreCase(shown) ? "" : " (jako " + hn + ")") + ".");
        }
        MarketItem it = fromStack(st);
        data.items.add(it);
        save();
        double dm = cfg == null ? 0 : cfg.defaultMinMargin;
        String extra = (it.enchants == null ? "" : ", " + it.enchants) + (it.sellCount == null ? "" : ", wystawka " + it.sellCount + " szt.");
        return new AddResult(it, true, "Dodano do listy: " + shown + " (" + it.material + extra + "). Cenę sprzedaży ustali następna analiza, skup - "
                + (dm > 0 ? "wzór z marżą domyślną " + AnalizaScreen.fmt(dm) + "$" : "po wpisaniu marży (panel, zakładka Przedmioty)") + ".");
    }

    public synchronized void save() {
        version++;
        if (file == null) return;
        try {
            Path tmp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            try {
                Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
            } catch (Throwable t2) {
                MarketAnalyzer.log.accept("Nie udało się zapisać listy przedmiotów: " + t2);
            }
        }
    }

    // ---------- import z BK-Rynku ----------
    /** Wynik szukania listy BK: skąd, przedmioty i ustawienia rynku z profilu (klucze bez prefiksu, np. "market_command"). */
    public static final class BkImport {
        public final String source;
        public final List<MarketItem> items;
        public final Map<String, JsonElement> settings;

        BkImport(String source, List<MarketItem> items, Map<String, JsonElement> settings) {
            this.source = source;
            this.items = items;
            this.settings = settings;
        }
    }

    /** Podmienia listę na zaimportowaną z BK i zapisuje (poprzednia lista zostaje w kopii). */
    public void replaceWith(BkImport imp, long now) {
        if (exists()) {
            try {
                Files.copy(file, file.resolveSibling("rynek-analiza-przedmioty-kopia.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {}
        }
        data.items = new ArrayList<>(imp.items);
        data.importedFrom = imp.source;
        data.importedAt = now;
        save();
    }

    /** Szuka listy przedmiotów BK w configu Minecrafta; null, gdy nie ma żadnej. */
    public static BkImport findBk(Path configDir) {
        List<Path> dirs = new ArrayList<>();
        for (Path d : new Path[]{configDir.resolve("BK-Mods").resolve("BK-Rynek"), configDir.resolve("bkrynek-config")}) {
            if (Files.isDirectory(d)) dirs.add(d);
        }
        if (dirs.isEmpty()) return null;
        String active = "";
        List<Candidate> cands = new ArrayList<>();
        for (Path dir : dirs) {
            if (active.isEmpty()) active = activeProfile(dir);
            for (Path f : jsonFiles(dir.resolve("auto_market_configs"))) readProfile(f, cands);
            for (Path f : jsonFiles(dir)) readLegacy(f, cands); // główny config: servers[].prices
            for (Path f : jsonFiles(dir.resolve("bkrynek-config"))) readLegacy(f, cands);
        }
        cands.removeIf(c -> c.items.isEmpty());
        if (cands.isEmpty()) return null;
        Candidate pick = null;
        if (!active.isEmpty()) {
            for (Candidate c : cands) {
                if (!c.name.equalsIgnoreCase(active) && !c.fileStem.equalsIgnoreCase(active)) continue;
                if (pick == null || c.modified > pick.modified) pick = c;
            }
        }
        if (pick == null) {
            for (Candidate c : cands) {
                if (!c.server.contains("anarchia")) continue;
                if (pick == null || c.modified > pick.modified) pick = c;
            }
        }
        if (pick == null) pick = cands.stream().max(Comparator.comparingLong(c -> c.modified)).orElse(null);
        if (pick == null) return null;
        // ustawienia rynku: z profilu o tej nazwie (gdy lista przyszła z głównego configu, a profil też jest)
        Map<String, JsonElement> settings = new LinkedHashMap<>(pick.settings);
        for (Candidate c : cands) {
            if (c != pick && c.name.equalsIgnoreCase(pick.name)) for (Map.Entry<String, JsonElement> e : c.settings.entrySet()) settings.putIfAbsent(e.getKey(), e.getValue());
        }
        return new BkImport(pick.name + " (" + pick.fileName + ")", pick.items, settings);
    }

    private static final class Candidate {
        String name = "", fileStem = "", fileName = "", server = "";
        long modified;
        List<MarketItem> items = new ArrayList<>();
        Map<String, JsonElement> settings = new LinkedHashMap<>();
    }

    private static List<Path> jsonFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json") && Files.isRegularFile(p)).forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    /** Nazwa aktywnego profilu BK: defaultProfile z głównego configu (wskazanego w selected.txt albo dowolnego z tym polem). */
    static String activeProfile(Path dir) {
        List<Path> mains = new ArrayList<>();
        try {
            Path sel = dir.resolve("selected.txt");
            if (Files.exists(sel)) {
                String n = Files.readString(sel, StandardCharsets.UTF_8).trim();
                if (!n.isEmpty()) {
                    mains.add(dir.resolve(n.endsWith(".json") ? n : n + ".json"));
                    mains.add(dir.resolve("bkrynek-config").resolve(n.endsWith(".json") ? n : n + ".json"));
                }
            }
        } catch (Throwable ignored) {}
        mains.add(dir.resolve("bkrynek-config.json"));
        mains.addAll(jsonFiles(dir));
        mains.addAll(jsonFiles(dir.resolve("bkrynek-config")));
        for (Path f : mains) {
            try {
                if (!Files.exists(f) || Files.size(f) > 8_000_000) continue;
                JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
                if (e.isJsonObject() && e.getAsJsonObject().has("defaultProfile")) {
                    JsonElement v = e.getAsJsonObject().get("defaultProfile");
                    if (v != null && v.isJsonPrimitive()) return v.getAsString().trim();
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static void readProfile(Path f, List<Candidate> out) {
        try {
            if (Files.size(f) > 8_000_000) return;
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return;
            JsonObject root = e.getAsJsonObject();
            JsonObject settings = root.has("settings") && root.get("settings").isJsonObject() ? root.getAsJsonObject("settings") : null;
            JsonElement reg = settings == null ? null : settings.get(KEY + "item_registry");
            Candidate c = new Candidate();
            c.fileName = f.getFileName().toString();
            c.fileStem = c.fileName.substring(0, c.fileName.length() - 5);
            c.name = root.has("name") && root.get("name").isJsonPrimitive() ? root.get("name").getAsString() : c.fileStem;
            c.server = root.has("server") && root.get("server").isJsonPrimitive() ? root.get("server").getAsString().toLowerCase(Locale.ROOT) : "";
            c.modified = Files.getLastModifiedTime(f).toMillis();
            if (reg != null && reg.isJsonArray()) c.items = parseItems(reg.getAsJsonArray());
            else if (root.has("prices") && root.get("prices").isJsonArray()) c.items = parseItems(root.getAsJsonArray("prices"));
            if (settings != null) {
                for (Map.Entry<String, JsonElement> s : settings.entrySet()) {
                    if (s.getKey().startsWith(KEY) && !s.getKey().endsWith("item_registry")) c.settings.put(s.getKey().substring(KEY.length()), s.getValue());
                }
            }
            out.add(c);
        } catch (Throwable ignored) {}
    }

    /** Stary format BK: {"servers":[{"profileName":..., "domains":[...], "prices":[...]}]}. */
    private static void readLegacy(Path f, List<Candidate> out) {
        try {
            if (Files.size(f) > 8_000_000) return;
            JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!e.isJsonObject() || !e.getAsJsonObject().has("servers")) return;
            JsonElement servers = e.getAsJsonObject().get("servers");
            if (!servers.isJsonArray()) return;
            long mod = Files.getLastModifiedTime(f).toMillis();
            for (JsonElement s : servers.getAsJsonArray()) {
                if (!s.isJsonObject()) continue;
                JsonObject so = s.getAsJsonObject();
                if (!so.has("prices") || !so.get("prices").isJsonArray()) continue;
                Candidate c = new Candidate();
                c.fileName = f.getFileName().toString();
                c.fileStem = c.fileName.substring(0, c.fileName.length() - 5);
                c.name = so.has("profileName") && so.get("profileName").isJsonPrimitive() ? so.get("profileName").getAsString() : c.fileStem;
                c.server = so.has("domains") ? so.get("domains").toString().toLowerCase(Locale.ROOT) : "";
                c.modified = mod;
                c.items = parseItems(so.getAsJsonArray("prices"));
                if (so.has("marketCommands") && so.get("marketCommands").isJsonArray() && !so.getAsJsonArray("marketCommands").isEmpty()) {
                    c.settings.put("market_command", so.getAsJsonArray("marketCommands").get(0));
                }
                String[][] map = {{"loreRegex", "lore_regex"}, {"loreSellerRegex", "lore_seller_regex"}, {"refreshSlot", "refresh_slot"},
                        {"confirmSlot", "confirm_slot"}, {"confirmItemName", "confirm_item_name"}, {"sortingSlot", "sorting_slot"},
                        {"sortingKeyword", "sorting_keyword"}, {"sellCommand", "sell_command"}, {"marketRestartDelayMs", "restart_delay"}};
                for (String[] m : map) if (so.has(m[0]) && !so.get(m[0]).isJsonNull()) c.settings.put(m[1], so.get(m[0]));
                if (so.has("unitMode") && so.get("unitMode").isJsonPrimitive()) {
                    c.settings.put("unit_mode_global", new com.google.gson.JsonPrimitive(so.get("unitMode").getAsBoolean() ? "Unit" : "Sum"));
                }
                out.add(c);
            }
        } catch (Throwable ignored) {}
    }

    static List<MarketItem> parseItems(JsonArray arr) {
        List<MarketItem> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            try {
                MarketItem it = GSON.fromJson(e, MarketItem.class);
                if (it == null || ((it.material == null || it.material.isBlank()) && (it.name == null || it.name.isBlank()))) continue;
                out.add(it.normalize());
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Ustawienia rynku z profilu BK -> nasza konfiguracja (komenda rynku, sloty, wzory ceny, tryb Unit, wystawianie). */
    static void applyBkSettings(Map<String, JsonElement> s, AnalizaConfig cfg) {
        String cmd = text(s, "market_command");
        if (!cmd.isBlank()) cfg.amCommand = cmd.trim().startsWith("/") ? cmd.trim().substring(1) : cmd.trim();
        Integer refresh = integer(s, "refresh_slot");
        if (refresh != null && refresh >= 0 && refresh < 54) cfg.amRefreshSlot = refresh;
        Integer confirm = integer(s, "confirm_slot");
        if (confirm != null && confirm >= 0 && confirm < 54) cfg.amConfirmSlot = confirm;
        Integer sorting = integer(s, "sorting_slot");
        if (sorting != null && sorting >= 0 && sorting < 54) cfg.amSortSlot = sorting;
        String kw = text(s, "sorting_keyword");
        if (!kw.isBlank()) cfg.amSortKeyword = kw.trim();
        String rx = text(s, "lore_regex");
        if (!rx.isBlank() && validRegex(rx)) cfg.priceRegex = rx;
        String seller = text(s, "lore_seller_regex");
        if (!seller.isBlank() && validRegex(seller)) cfg.sellerRegex = seller;
        String unit = text(s, "unit_mode_global");
        if (!unit.isBlank()) cfg.unitModeGlobal = !unit.trim().equalsIgnoreCase("Sum");
        String confirmName = text(s, "confirm_item_name");
        if (!confirmName.isBlank()) cfg.amConfirmName = confirmName.trim();
        String sell = text(s, "sell_command");
        if (sell.contains("{price}")) cfg.amSellCommand = sell.trim().startsWith("/") ? sell.trim().substring(1) : sell.trim();
        Integer restart = integer(s, "restart_delay");
        if (restart != null && restart >= 200 && restart <= 10_000) cfg.amRestartPauseMs = restart;
        cfg.fix();
    }

    private static boolean validRegex(String rx) {
        try {
            java.util.regex.Pattern.compile(rx);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String text(Map<String, JsonElement> s, String k) {
        JsonElement e = s.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static Integer integer(Map<String, JsonElement> s, String k) {
        JsonElement e = s.get(k);
        try {
            if (e != null && e.isJsonObject() && e.getAsJsonObject().has("value")) e = e.getAsJsonObject().get("value"); // {"value":1500.0,...}
            return e != null && e.isJsonPrimitive() ? (int) Math.round(e.getAsDouble()) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
