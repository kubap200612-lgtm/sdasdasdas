package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rozpoznawanie przedmiotów, cen i komunikatów rynku - przeniesione 1:1 z BK-Rynku 3.0 (AutoMarketScanner,
 * ItemStackExtractor, AutoSellController), żeby po usunięciu BK mod kupował i wystawiał dokładnie to samo.
 *
 * Oferta pasuje do przedmiotu z listy, gdy (kolejność jak w BK):
 *  1. ma to samo id (material; pusty = dowolne id),
 *  2. w jej lore nie ma żadnej zakazanej linii (bannedLore),
 *  3. nazwa bez kolorów jest ta sama (bez wielkości liter, czcionka "small caps" jak zwykłe litery); gdy nazwa na liście
 *     ma kody kolorów (np. "§4Anarchiczny hełm"), pierwszy kolor nazwy oferty i jej pogrubienie/podkreślenie też muszą się
 *     zgadzać (podróbka przemianowana w innym kolorze nie przejdzie; "§f" = bez wymagania koloru),
 *  4. efekty mikstury (gdy pozycja je ma) - wtedy wymagane lore się nie liczy,
 *  5. każda część wymaganego lore (rozdzielone ";" albo nową linią) jest w opisie przedmiotu - bez kolorów, małymi
 *     literami, bez akcentów; "Dodatkowe obrażenia 0-100%" pasuje do liczby z zakresu; linie z ceną, sprzedawcą,
 *     "kliknij", wygaśnięciem itp. są pomijane,
 *  6. enchanty co najmniej na podanym poziomie (z przedmiotu albo z opisu), żadnego zakazanego enchantu,
 *  7. CustomModelData (gdy pozycja go ma i go nie ignoruje).
 */
public final class ItemMatcher {
    private ItemMatcher() {}

    /** Pełny opis przedmiotu (jak w dymku). Podmieniany w testach. */
    static final Function<ItemStack, List<String>> DEFAULT_TOOLTIP = ItemMatcher::fullTooltip;
    static Function<ItemStack, List<String>> tooltipSource = DEFAULT_TOOLTIP;
    /** Opisy już odczytanych stacków (rynek przysyła przy odświeżeniu nowe stacki, więc stare same znikają). */
    private static final Map<ItemStack, List<String>> TIP_CACHE = new WeakHashMap<>();

    static final Pattern RANGE = Pattern.compile("(.*?)(\\d+(?:[.,]\\d+)?)-(\\d+(?:[.,]\\d+)?)(.*)");
    static final Pattern NUMBER = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Pattern UUID_LINE = Pattern.compile(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
    private static final String[] IGNORED_LORE = {"wygas", "kliknij", "click", "koszt", "cena", "price", "seller", "wystawi", "wystawiono",
            "szyfr", "unikalny", "wygenerowany", "weryfik", "odblok", "komend", "darmo", "utrat", "utrac", "expire"};

    // ---------- dopasowanie ----------
    public static boolean matches(ItemStack st, MarketItem it) {
        return reject(st, it) == null;
    }

    /** null = oferta pasuje do przedmiotu z listy; inaczej krótki powód odrzucenia (do diagnostyki). */
    static String reject(ItemStack st, MarketItem it) {
        if (st == null || st.isEmpty() || it == null) return "pusty slot";
        String want = material(it);
        String have = itemId(st);
        if (!want.isEmpty() && !want.equals(have)) return "inne id (" + have + ")";
        List<String> lore = loreLines(st);
        String banned = bannedLoreLine(it, lore);
        if (banned != null) return "zakazane lore \"" + banned + "\"";
        if (it.name != null && !it.name.isEmpty()) {
            String legacy = toLegacy(st.getName());
            if (!nameMatches(legacy, it.name)) {
                boolean sameText = stripColors(legacy).trim().equalsIgnoreCase(stripColors(it.name).trim());
                return sameText ? "inny kolor/styl nazwy (" + shown(leadingColorCode(legacy)) + " zamiast " + shown(leadingColorCode(it.name)) + ")"
                        : "nazwa \"" + stripColors(legacy) + "\"";
            }
        }
        boolean potionsOk = false;
        if (it.potionEffects != null && !it.potionEffects.isEmpty()) {
            if (!potionsMatch(st, it.potionEffects)) return "inne efekty mikstury";
            potionsOk = true;
        }
        List<String> tip = null;
        if (!potionsOk && it.lore != null && !it.lore.isEmpty()) {
            tip = tooltip(st);
            String miss = missingLore(it.lore, tip);
            if (miss != null) return "brak w opisie \"" + miss + "\"";
        }
        if (!(st.getItem() instanceof BlockItem)) { // BK nie sprawdza enchantów bloków
            for (Map.Entry<String, Integer> e : it.requiredEnchants().entrySet()) {
                int need = e.getValue() == null ? 1 : e.getValue();
                if (enchantLevel(st, e.getKey()) >= need) continue;
                if (tip == null) tip = tooltip(st);
                if (displayedEnchantLevel(tip, e.getKey()) >= need) continue;
                return "enchant " + e.getKey() + " " + need + " (jest " + Math.max(enchantLevel(st, e.getKey()), displayedEnchantLevel(tip, e.getKey())) + ")";
            }
        }
        if (it.bannedEnchantsList != null) {
            for (String b : it.bannedEnchantsList) if (b != null && !b.isBlank() && enchantLevel(st, b) > 0) return "zakazany enchant " + b;
        }
        if (it.customModelData != null && it.customModelData != 0 && !it.ignoreCustomModelData) {
            Integer cmd = customModelData(st);
            if (cmd == null || !cmd.equals(it.customModelData)) return "CustomModelData " + cmd + " zamiast " + it.customModelData;
        }
        return null;
    }

    private static String shown(String code) {
        return code.isEmpty() ? "bez koloru" : code.replace('§', '&');
    }

    static String material(MarketItem it) {
        return it.material == null ? "" : it.material.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
    }

    /** Pierwsza zakazana linia (bannedLore, części rozdzielone ";" / nową linią) obecna w lore oferty; null = brak. */
    static String bannedLoreLine(MarketItem it, List<String> lore) {
        if (it.bannedLore == null || it.bannedLore.isBlank() || lore.isEmpty()) return null;
        List<String> norm = new ArrayList<>();
        for (String l : lore) norm.add(normalizeLore(l));
        for (String part : it.bannedLore.split("[;\\r\\n]+")) {
            String p = normalizeLore(part);
            if (p.isEmpty()) continue;
            for (String l : norm) if (l.contains(p)) return stripColors(part).trim();
        }
        return null;
    }

    /** Wymagane lore (części rozdzielone ";" albo nową linią) - każda musi być w opisie. */
    static boolean loreMatches(String required, List<String> tip) {
        return missingLore(required, tip) == null;
    }

    /** Pierwsza część wymaganego lore, której nie ma w opisie przedmiotu; null = wszystkie są. */
    static String missingLore(String required, List<String> tip) {
        if (required == null || required.isEmpty()) return null;
        List<String> norm = null;
        for (String part : required.split("[;\\n]+")) {
            if (part.trim().isEmpty()) continue;
            String p = normalizeLore(part);
            if (ignoredLore(p)) continue;
            if (norm == null) {
                norm = new ArrayList<>();
                for (String l : tip) norm.add(normalizeLore(l));
            }
            if (!lorePartFound(p, norm)) return stripColors(part).trim();
        }
        return null;
    }

    private static boolean lorePartFound(String p, List<String> norm) {
        Matcher r = RANGE.matcher(p);
        if (r.matches()) {
            String prefix = r.group(1).trim(), suffix = r.group(4).trim();
            try {
                double lo = Double.parseDouble(r.group(2).replace(',', '.'));
                double hi = Double.parseDouble(r.group(3).replace(',', '.'));
                for (String l : norm) {
                    if (l.isEmpty()) continue;
                    if (!prefix.isEmpty() && !l.contains(prefix)) continue;
                    Matcher n = NUMBER.matcher(prefix.isEmpty() ? l : l.substring(l.indexOf(prefix) + prefix.length()));
                    if (!n.find()) continue;
                    double v = Double.parseDouble(n.group(1).replace(',', '.'));
                    if (v >= lo && v <= hi && (suffix.isEmpty() || l.contains(suffix))) return true;
                }
                return false;
            } catch (NumberFormatException ignored) {
                // jak w BK: zakres nie do odczytania = zwykłe porównanie tekstu
            }
        }
        for (String l : norm) if (l.contains(p)) return true;
        return false;
    }

    /** Czy linię wymaganego lore pominąć (puste, ozdobniki, cena/sprzedawca/kliknij/wygaśnięcie, UUID) - jak w BK. */
    static boolean ignoredLore(String norm) {
        if (norm == null || norm.isEmpty()) return true;
        if (norm.replaceAll("[⎯‹›«»—―─▬Ξ⚠×•神ᐐ \\-]+", "").isEmpty()) return true;
        if (UUID_LINE.matcher(norm).matches()) return true;
        for (String w : IGNORED_LORE) if (norm.contains(w)) return true;
        return false;
    }

    // ---------- nazwa (kolory jak w BK: nazwa oferty zamieniona na tekst z kodami §) ----------
    static final int[] LEGACY_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String CODES = "0123456789abcdef";

    /** Tekst z kodami § (kolor każdego kawałka, potem l/o/n/m/k) - jak ItemStackExtractor.toLegacy w BK. */
    static String toLegacy(Text t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        t.visit((style, str) -> {
            TextColor c = style.getColor();
            if (c != null) {
                int rgb = c.getRgb() & 0xFFFFFF;
                int idx = -1;
                for (int i = 0; i < LEGACY_RGB.length; i++) if (LEGACY_RGB[i] == rgb) { idx = i; break; }
                if (idx >= 0) sb.append('§').append(CODES.charAt(idx));
                else {
                    sb.append("§x");
                    for (char h : String.format("%06x", rgb).toCharArray()) sb.append('§').append(h);
                }
            }
            if (style.isBold()) sb.append("§l");
            if (style.isItalic()) sb.append("§o");
            if (style.isUnderlined()) sb.append("§n");
            if (style.isStrikethrough()) sb.append("§m");
            if (style.isObfuscated()) sb.append("§k");
            sb.append(str);
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    /** matchesConfiguredName z BK: ten sam tekst; gdy nazwa z listy ma kody - ten sam pierwszy kolor i style l/m/n/k. */
    static boolean nameMatches(String itemLegacy, String configured) {
        if (!stripColors(itemLegacy).trim().equalsIgnoreCase(stripColors(configured).trim())) return false;
        if (!normalizeLegacy(configured).matches("(?s).*§[0-9A-FK-ORXa-fk-orx].*")) return true;
        String have = leadingColorCode(itemLegacy), want = leadingColorCode(configured);
        if ("§f".equals(want)) want = "";
        if (!want.isEmpty() && !want.equals(have)) return false;
        int wantMask = leadingStyleMask(configured);
        return (leadingStyleMask(itemLegacy) & wantMask) == wantMask;
    }

    /** Pierwszy kod koloru na początku tekstu ("§4" albo "§x§a§b§c§d§e§f"); pusty = brak. */
    static String leadingColorCode(String s) {
        String n = normalizeLegacy(s);
        for (int i = 0; i < n.length() - 1; i++) {
            char c = n.charAt(i);
            if (c != '§') {
                if (Character.isWhitespace(c)) continue;
                break;
            }
            char k = Character.toLowerCase(n.charAt(++i));
            if (k == 'x' && i + 12 < n.length()) return n.substring(i - 1, i + 13).toLowerCase(Locale.ROOT);
            if (CODES.indexOf(k) >= 0) return "§" + k;
        }
        return "";
    }

    /** Style z kodów na początku tekstu (jak w BK): l = 1, m = 2, n = 4, k = 8; kolor albo "§r" je zeruje. */
    static int leadingStyleMask(String s) {
        String n = normalizeLegacy(s);
        int mask = 0;
        for (int i = 0; i < n.length() - 1; i++) {
            char c = n.charAt(i);
            if (c != '§') {
                if (Character.isWhitespace(c)) continue;
                break;
            }
            char k = Character.toLowerCase(n.charAt(++i));
            switch (k) {
                case 'l' -> mask |= 1;
                case 'm' -> mask |= 2;
                case 'n' -> mask |= 4;
                case 'k' -> mask |= 8;
                case 'r' -> mask = 0;
                case 'x' -> i += 12;
                default -> { if (CODES.indexOf(k) >= 0) mask = 0; }
            }
        }
        return mask;
    }

    // ---------- enchanty, CMD, mikstury ----------
    /** Poziom enchantu (id jak "protection" albo "minecraft:protection") na przedmiocie albo w księdze; 0 = brak. */
    static int enchantLevel(ItemStack st, String id) {
        String want = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        if (want.isEmpty()) return 0;
        int best = 0;
        for (ItemEnchantmentsComponent c : new ItemEnchantmentsComponent[]{st.get(DataComponentTypes.ENCHANTMENTS), st.get(DataComponentTypes.STORED_ENCHANTMENTS)}) {
            if (c == null) continue;
            for (RegistryEntry<Enchantment> e : c.getEnchantments()) {
                String path = e.getKey().map(k -> k.getValue().getPath()).orElse("");
                if (path.equals(want)) best = Math.max(best, c.getLevel(e));
            }
        }
        return best;
    }

    /** Polskie nazwy enchantów w opisie (gdy poziom jest tylko w lore). */
    static final Map<String, String> PL_ENCHANTS = new LinkedHashMap<>();
    static {
        String[][] pairs = {{"sharpness", "ostrość"}, {"smite", "pogromca nieumarłych"}, {"bane_of_arthropods", "zmora stawonogów"},
                {"knockback", "odrzut"}, {"fire_aspect", "zaklęty ogień"}, {"looting", "grabież"}, {"sweeping_edge", "szerokie ostrze"},
                {"efficiency", "wydajność"}, {"silk_touch", "jedwabny dotyk"}, {"unbreaking", "niezniszczalność"}, {"fortune", "szczęście"},
                {"power", "moc"}, {"punch", "uderzenie"}, {"flame", "płomień"}, {"infinity", "nieskończoność"},
                {"luck_of_the_sea", "morskie szczęście"}, {"lure", "przynęta"}, {"mending", "naprawa"}, {"respiration", "oddychanie"},
                {"aqua_affinity", "wydajność pod wodą"}, {"depth_strider", "głębinowy wędrowiec"}, {"soul_speed", "prędkość duszy"},
                {"loyalty", "lojalność"}, {"multishot", "wielostrzał"}, {"quick_charge", "szybkie przeładowanie"}, {"density", "gęstość"},
                {"breach", "wyłom"}, {"protection", "ochrona"}, {"fire_protection", "ochrona przed ogniem"},
                {"blast_protection", "ochrona przed wybuchami"}, {"projectile_protection", "ochrona przed pociskami"},
                {"feather_falling", "powolne opadanie"}, {"thorns", "ciernie"}, {"frost_walker", "mroźny piechur"},
                {"swift_sneak", "szybkie skradanie"}, {"impaling", "przebicie"}, {"riptide", "torpeda"}, {"channeling", "porażenie"},
                {"piercing", "przeszycie"}, {"wind_burst", "podmuch wiatru"}};
        for (String[] p : pairs) PL_ENCHANTS.put(p[0], p[1]);
    }

    /** Poziom enchantu odczytany z opisu (np. "Ochrona VII", "Ostrość 6"); 0 = nie ma. */
    static int displayedEnchantLevel(List<String> tip, String id) {
        String eid = id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        if (eid.isEmpty()) return 0;
        List<String> keys = new ArrayList<>();
        keys.add(normalizeLore(eid.replace('_', ' ')));
        String pl = PL_ENCHANTS.get(eid);
        if (pl != null) keys.add(normalizeLore(pl));
        int best = 0;
        for (String raw : tip) {
            String l = normalizeLore(raw);
            for (String k : keys) {
                for (int at = l.indexOf(k); at >= 0; at = l.indexOf(k, at + 1)) {
                    // środek słowa albo koniec dłuższej nazwy ("morskie szczęście" przy szukaniu "szczęście",
                    // "fire protection" przy "protection") - to inny enchant
                    int p = at - 1;
                    while (p >= 0 && (Character.isWhitespace(l.charAt(p)) || Character.isSpaceChar(l.charAt(p)))) p--;
                    if (p >= 0 && Character.isLetter(l.charAt(p))) continue;
                    String rest = l.substring(at + k.length()).trim();
                    String first = rest.isEmpty() ? "" : rest.split("\\s+")[0];
                    // dłuższa nazwa z tym samym początkiem ("ochrona przed ogniem" przy szukaniu "ochrona"): dalej słowo, nie poziom -> 0
                    int lvl = 0;
                    if (roman(first) > 0) lvl = roman(first);
                    else if (!first.isEmpty() && Character.isDigit(first.charAt(0))) {
                        Matcher n = NUMBER.matcher(first);
                        if (n.find()) {
                            try { lvl = (int) Math.floor(Double.parseDouble(n.group(1).replace(',', '.'))); } catch (NumberFormatException ignored) {}
                        }
                    } else if (rest.isEmpty()) lvl = 1;
                    best = Math.max(best, lvl);
                }
            }
        }
        return best;
    }

    static int roman(String s) {
        if (s == null || s.isEmpty() || !s.matches("[ivxlc]+")) return 0;
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) { case 'i' -> 1; case 'v' -> 5; case 'x' -> 10; case 'l' -> 50; default -> 100; };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    static Integer customModelData(ItemStack st) {
        CustomModelDataComponent c = st.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (c == null || c.floats() == null || c.floats().isEmpty()) return null;
        Float f = c.floats().get(0);
        return f == null ? null : Math.round(f);
    }

    static boolean potionsMatch(ItemStack st, List<MarketItem.Potion> wanted) {
        PotionContentsComponent pc = st.get(DataComponentTypes.POTION_CONTENTS);
        if (pc == null) return false;
        for (MarketItem.Potion w : wanted) {
            if (w == null || w.effectId == null) continue;
            String want = w.effectId.toLowerCase(Locale.ROOT);
            String wantPath = want.replace("minecraft:", "");
            boolean ok = false;
            for (StatusEffectInstance e : pc.getEffects()) {
                String id = e.getEffectType().getKey().map(k -> k.getValue().getPath()).orElse("").toLowerCase(Locale.ROOT);
                if ((id.equals(want) || id.equals(wantPath)) && e.getAmplifier() == w.amplifier && (w.duration <= 0 || e.getDuration() == w.duration)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return true;
    }

    // ---------- pety / kostiumy: "Pet wygaśnie za: 2d 5h" ----------
    private static final Pattern PET_HEADER = Pattern.compile("(?:^|\\W)pet\\s+wygasnie\\s+za\\s*:");
    private static final Pattern PET_DURATION = Pattern.compile("(?:(\\d+)\\s*d\\s*)?(?:(\\d+)\\s*h\\s*)?(?:(\\d+)\\s*m\\s*)?(?:(\\d+)\\s*s\\s*)?");

    /** Ile pełnych dni zostało do wygaśnięcia peta/kostiumu (z linii "Pet wygaśnie za: ..."); null = brak takiej linii. */
    static Integer daysLeft(List<String> lines) {
        for (String raw : lines) {
            String l = Normalizer.normalize(stripColors(raw), Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace(' ', ' ').trim();
            Matcher h = PET_HEADER.matcher(l);
            if (!h.find()) continue;
            Matcher d = PET_DURATION.matcher(l.substring(h.end()).trim());
            if (!d.matches()) return null;
            long secs = 0;
            long[] mult = {86400, 3600, 60, 1};
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                if (d.group(i + 1) != null) { secs += Long.parseLong(d.group(i + 1)) * mult[i]; any = true; }
            }
            return any ? (int) (secs / 86400) : null;
        }
        return null;
    }

    /** Pozycja z minDaysLeft > 0: przedmiot musi mieć co najmniej tyle dni do wygaśnięcia (jak AnarchiaPetExpiry w BK). */
    static boolean meetsMinDays(MarketItem it, ItemStack st) {
        if (it.minDaysLeft == null || it.minDaysLeft <= 0) return true;
        Integer left = daysLeft(loreLines(st));
        return left != null && left >= it.minDaysLeft;
    }

    // ---------- cena oferty (AutoMarketScanner.extractPrice z BK) ----------
    /** Domyślny wzór ceny BK; z profilu BK przychodzi zwykle "(?i).*Koszt.*?([\d.,]+(?:mld|[km])?).*". */
    public static final String DEFAULT_PRICE_REGEX = "(?i).*(?:Koszt|Cena|Price).*?(?:\\$\\s*)?([\\d\\s.,]+(?:mld|[kmbt])?)\\s*\\$?.*";
    private static final Pattern FALLBACK_PRICE = Pattern.compile("(?i).*(?:koszt|cena|price).*?(?:\\$\\s*)?([\\d\\s.,]+(?:mld|[kmbt])?)\\s*\\$?.*");
    /** Wzór ceny (grupa 1 = liczba) i wzór linii sprzedawcy - z ustawień (import z profilu BK). */
    static volatile String priceRegex = DEFAULT_PRICE_REGEX;
    static volatile String sellerRegex = "";
    private static final Map<String, Pattern> PATTERNS = new HashMap<>();

    /** Ustawienia z configu (wywoływane przy starcie i po imporcie). */
    static void configure(AnalizaConfig cfg) {
        priceRegex = cfg.priceRegex == null || cfg.priceRegex.isBlank() ? DEFAULT_PRICE_REGEX : cfg.priceRegex;
        sellerRegex = cfg.sellerRegex == null ? "" : cfg.sellerRegex;
    }

    private static synchronized Pattern pattern(String rx) {
        return PATTERNS.computeIfAbsent(rx, r -> {
            try { return Pattern.compile(r); } catch (Throwable t) { return Pattern.compile(DEFAULT_PRICE_REGEX); }
        });
    }

    /** Cena całej oferty: z lore, potem z pełnego opisu, na końcu własny odczyt analizy; NaN = brak. */
    public static double offerPrice(ItemStack st) {
        double v = extractPrice(loreLines(st));
        if (!(v > 0)) v = extractPrice(tooltip(st));
        if (!(v > 0)) v = MarketAnalyzer.parsePrice(st);
        return v > 0 ? v : Double.NaN;
    }

    /** Cena z linii lore jak w BK; -1 = brak. */
    static double extractPrice(List<String> lines) {
        if (lines == null || lines.isEmpty()) return -1;
        String rx = priceRegex;
        if (rx == null || rx.isEmpty()) return -1;
        Pattern p = pattern(rx);
        String seller = sellerRegex;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.replaceAll("§x(§[0-9A-Fa-f]){6}", "").replaceAll("§[0-9A-FK-ORXa-fk-orx]", "").trim();
            if (line.isEmpty()) continue;
            boolean label = hasDefaultPriceLabel(line);
            if (!label && DEFAULT_PRICE_REGEX.equals(rx)) continue;
            if (isItemUsageCostLine(line)) continue;
            if (seller != null && !seller.isEmpty() && pattern(seller).matcher(line).find()) continue;
            String low = line.toLowerCase(Locale.ROOT);
            if (low.contains("wystawil") || low.contains("wystawił") || low.contains("wystawiono przez")) continue;
            Matcher m = p.matcher(line);
            if (m.find()) {
                double v = parsePriceWithSuffix(m.groupCount() >= 1 ? m.group(1) : m.group());
                double b = extractBracketPrice(line);
                return b > 0 ? b : v;
            }
            Matcher f = FALLBACK_PRICE.matcher(line);
            if (label && f.find()) {
                double v = parsePriceWithSuffix(f.group(1));
                double b = extractBracketPrice(line);
                return b > 0 ? b : v;
            }
        }
        return -1;
    }

    static boolean hasDefaultPriceLabel(String line) {
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("koszt") || l.contains("cena") || l.contains("price");
    }

    /** "Kosztuje ... sztuk", "powyżej ..." - opis przedmiotu, a nie cena oferty (BK je pomija). */
    static boolean isItemUsageCostLine(String line) {
        if (line == null || line.isBlank()) return false;
        String l = Normalizer.normalize(line.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (l.contains("kosztuje") || l.contains("powyzej")) return true;
        return l.contains("sztuk") && !l.contains("cena") && !l.contains("price") && !l.contains("$");
    }

    static double extractBracketPrice(String line) {
        int a = line.indexOf('(');
        int b = a >= 0 ? line.indexOf(')', a + 1) : -1;
        if (a < 0 || b < 0 || b <= a) return -1;
        String inner = line.substring(a + 1, b).replaceAll("[^0-9.,A-Za-z]", "");
        return inner.isEmpty() ? -1 : parsePriceWithSuffix(inner);
    }

    /** "20 370", "1.5k", "2,5m", "1mld", "3.289" -> liczba (jak parsePriceWithSuffix w BK); -1 = nie da się. */
    static double parsePriceWithSuffix(String s) {
        if (s == null || s.trim().isEmpty()) return -1;
        String t = s.toLowerCase(Locale.ROOT).trim();
        int open = t.indexOf('('), close = t.indexOf(')');
        if (open >= 0 && close > open) {
            String inner = t.substring(open + 1, close).trim();
            if (!inner.isEmpty() && !inner.equals(t)) {
                double v = parsePriceWithSuffix(inner.replace("$", ""));
                if (v > 0) return v;
            }
            t = t.substring(0, open).trim();
        }
        double mult = 1;
        if (t.endsWith("mld") || t.endsWith("b")) {
            mult = 1e9;
            t = t.substring(0, t.length() - (t.endsWith("mld") ? 3 : 1));
        } else if (t.endsWith("t")) {
            mult = 1e12;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            mult = 1e6;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("k")) {
            mult = 1e3;
            t = t.substring(0, t.length() - 1);
        }
        String d = t.replaceAll("[^0-9.,]", "");
        if (d.isEmpty()) return -1;
        int sep = Math.max(d.lastIndexOf('.'), d.lastIndexOf(','));
        if (sep != -1) {
            int dec = d.length() - sep - 1;
            if (dec == 3) d = d.replaceAll("[.,]", "");
            else if (dec > 0 && dec <= 2) d = d.substring(0, sep).replaceAll("[.,]", "") + "." + d.substring(sep + 1);
            else d = d.replaceAll("[.,]", "");
        }
        try {
            return Double.parseDouble(d) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------- sortowanie rynku ----------
    /**
     * Czy w opisie przycisku sortowania zaznaczona jest opcja ze słowem keyword (np. "najnowsz"): zaznaczona linia ma
     * żółty/złoty kolor albo podkreślenie (na Anarchii pomarańczowa strzałka i tekst).
     */
    static boolean sortSelected(ItemStack button, String keyword) {
        if (button == null || button.isEmpty()) return false;
        LoreComponent lore = button.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        String kw = normalizeLore(keyword == null ? "" : keyword);
        if (kw.isEmpty()) return false;
        for (Text line : lore.lines()) {
            if (!normalizeLore(line.getString()).contains(kw)) continue;
            boolean[] marked = {false};
            line.visit((style, str) -> {
                if (str.isBlank()) return Optional.empty();
                TextColor c = style.getColor();
                int rgb = c == null ? -1 : c.getRgb() & 0xFFFFFF;
                if (style.isUnderlined() || rgb == 0xFFAA00 || rgb == 0xFFFF55) marked[0] = true;
                return Optional.empty();
            }, Style.EMPTY);
            String legacy = normalizeLegacy(line.getString()).toLowerCase(Locale.ROOT);
            if (legacy.contains("§6") || legacy.contains("§e") || legacy.contains("§n")) marked[0] = true;
            return marked[0];
        }
        return false;
    }

    // ---------- tekst ----------
    static String itemId(ItemStack st) {
        return Registries.ITEM.getId(st.getItem()).getPath().toLowerCase(Locale.ROOT);
    }

    /** "&4" i "Â§" -> "§". */
    static String normalizeLegacy(String s) {
        if (s == null) return "";
        return s.replaceAll("&(?=[0-9A-FK-ORXa-fk-orx])", "§").replace("Â§", "§");
    }

    /** Bez kodów kolorów, z literami "small caps" jako zwykłe, z pojedynczymi spacjami. */
    static String stripColors(String s) {
        if (s == null) return "";
        String r = normalizeLegacy(s).replaceAll("§x(§[0-9A-Fa-f]){6}", "").replaceAll("§[0-9A-FK-ORXa-fk-orx]", "").replace(' ', ' ');
        return mapSmallFont(r).replaceAll("\\s+", " ").trim();
    }

    /** Linia do porównań lore: bez kolorów, małe litery, bez ozdobników i akcentów (normalizeLoreForFilter w BK). */
    static String normalizeLore(String s) {
        String r = stripColors(s).toLowerCase(Locale.ROOT).replaceAll("[\\p{Co}•◦▪▫●○]+", " ").replaceAll("\\s+", " ").trim();
        return Normalizer.normalize(r, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡʏᴢ";
    private static final String NORMAL = "abcdefghijklmnopqrstuvwyz";

    /** Czcionka "small caps" (ᴀɴᴀʀᴄʜɪᴀ) -> zwykłe litery. */
    static String mapSmallFont(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = SMALL.indexOf(c);
            b.append(k >= 0 ? NORMAL.charAt(k) : c);
        }
        return b.toString();
    }

    /** Linie lore przedmiotu (bez nazwy i enchantów). */
    static List<String> loreLines(ItemStack st) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) out.add(t.getString());
        return out;
    }

    /** Pełny opis przedmiotu jak w dymku (zapamiętany dla tego stacka). */
    static List<String> tooltip(ItemStack st) {
        if (tooltipSource != DEFAULT_TOOLTIP) return tooltipSource.apply(st);
        synchronized (TIP_CACHE) {
            List<String> c = TIP_CACHE.get(st);
            if (c != null) return c;
        }
        List<String> tip = fullTooltip(st);
        synchronized (TIP_CACHE) {
            TIP_CACHE.put(st, tip);
        }
        return tip;
    }

    static List<String> fullTooltip(ItemStack st) {
        List<String> out = new ArrayList<>();
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.world != null) {
                for (Text t : Screen.getTooltipFromItem(mc, st)) out.add(t.getString());
                return out;
            }
        } catch (Throwable ignored) {
            out.clear();
        }
        out.add(st.getName().getString());
        out.addAll(loreLines(st));
        return out;
    }

    // ---------- komunikaty czatu ----------
    /**
     * Komunikat serwera o sprzedaży Twojego przedmiotu (warunek jak w BK): kwota z komunikatu; NaN = to nie sprzedaż.
     * Liczą się "Twój przedmiot ... został kupiony", "sprzedany/sprzedano/sprzedałeś/zarobek/dodane/sold"; komunikaty
     * nieudanego zakupu ("już sprzedany") i własne komunikaty modów - nie.
     */
    static double saleAmount(String raw) {
        if (raw == null) return Double.NaN;
        String low = stripColors(raw).toLowerCase(Locale.ROOT);
        String p = BkChatGuard.plain(low);
        if (p.contains("[bk") || p.contains("bk rynek") || p.contains("[auto rynek]") || p.contains("[analiza]")) return Double.NaN;
        if (p.contains("prawdopodobnie") || p.contains("usun") || p.contains("juz sprzedane") || p.contains("already sold")) return Double.NaN;
        if (any(p, BUY_FAIL) || any(p, BUY_OK) || p.contains("you bought")) return Double.NaN;
        boolean seller = p.contains("twoj przedmiot") && p.contains("zostal kupiony");
        boolean title = p.contains("przedmiot zostal kupiony") && p.contains("za");
        boolean sale = seller || title || p.contains("sprzedany") || p.contains("bought") || p.contains("sold")
                || p.contains("dodane") || p.contains("zarobek") || p.contains("sprzedales") || p.contains("sprzedano");
        if (!sale) return Double.NaN;
        double v = chatAmount(low);
        return v > 0 ? v : Double.NaN;
    }

    private static final Pattern[] AMOUNT = {
            Pattern.compile("(?:dodane|profit|zarobek|otrzymasz|za|sold for)\\s*([\\d\\s.,]+[kKmMldLD]?)\\s*\\$"),
            Pattern.compile("(?:za)\\s*([0-9][0-9\\s.,]*[kKmMldLD]?)"),
            Pattern.compile("([\\d\\s.,]+[kKmMldLD]?)\\s*\\$")};

    /** Kwota z komunikatu ("... za 20 370$", "zarobek: 1.5k$"); NaN = brak. */
    static double chatAmount(String text) {
        for (Pattern p : AMOUNT) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                double v = parsePriceWithSuffix(m.group(1));
                if (v > 0) return v;
            }
        }
        return Double.NaN;
    }

    static final String[] BUY_OK = {"pomyslnie zakupiles", "pomyslnie kupiles", "kupiles przedmiot", "zakupiles przedmiot",
            "zakupiono przedmiot", "zakupiono produkt", "bought item", "gratulacj"};
    static final String[] BUY_FAIL = {"juz sprzedany", "juz kupiony", "nie jest juz dostepny", "zostal juz sprzedany",
            "produkt ten zostal juz", "already sold", "already bought", "wygasl", "expired", "nie mozesz kupic", "nie mozesz kupowac"};
    static final String[] NO_MONEY = {"brak srodkow", "nie stac cie", "nie masz tyle", "nie posiadasz wystarczajac", "insufficient funds",
            "no money", "za malo pieniedzy", "nie masz wystarczajaco"};
    static final String[] LIST_OK = {"pomyslnie wystawiles", "wystawiono przedmiot", "wystawiono produkt", "successfully listed",
            "wystawiles przedmiot"};
    static final String[] LIST_LIMIT = {"osiagnales limit", "limit wystawionych", "limit produktow", "limit itemow", "limit mozesz zwiekszyc",
            "limit przedmiotow", "osiagnieto limit"};

    /** Tekst do porównań z listami komunikatów: bez kolorów, małe litery, bez polskich znaków. */
    static String plainChat(String raw) {
        return BkChatGuard.plain(stripColors(raw).toLowerCase(Locale.ROOT));
    }

    static boolean any(String plain, String[] words) {
        for (String w : words) if (plain.contains(w)) return true;
        return false;
    }
}
