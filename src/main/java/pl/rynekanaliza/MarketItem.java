package pl.rynekanaliza;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Przedmiot z listy moda (to, co kupuje snajper, wystawia sprzedaż i liczy analiza).
 * Pola mają te same nazwy co wpis BK-Rynku (item_registry w profilu BK), więc lista importuje się z BK 1:1,
 * a BkBridge.BkTarget czyta je tak samo jak dawniej pola wpisu BK.
 */
public class MarketItem {
    /** Nazwa z kolorami (np. "§4Anarchiczny hełm"); pusta = dowolna nazwa. */
    public String name;
    /** Id przedmiotu bez "minecraft:" (np. "netherite_helmet"). */
    public String material;
    /** Wymagane linie lore rozdzielone ";" albo nową linią (każda musi wystąpić w opisie przedmiotu). */
    public String lore;
    /** Zakazane linie lore (oferta z którąkolwiek nie pasuje). */
    public String bannedLore;
    /** Enchanty jako tekst z BK, np. "protection 5, unbreaking 5" (minimalne poziomy). */
    public String enchants;
    /** Enchanty jako mapa id -> minimalny poziom (gdy pusta, liczy się pole enchants). */
    public Map<String, Integer> enchantments = new LinkedHashMap<>();
    /** Enchanty, których przedmiot mieć nie może. */
    public List<String> bannedEnchantsList = new ArrayList<>();
    public String excludedEnchants;
    public Integer customModelData;
    public boolean ignoreCustomModelData;
    /** Liczba komponentów przedmiotu (sprawdzana tylko przy "Ścisłe komponenty"). */
    public int componentCount;
    /** Skup za sztukę (true / brak) albo za całą ofertę requiredCount sztuk (false). */
    public Boolean unitMode;
    public Integer requiredCount;
    public Integer minBuyCount;
    /** Ile sztuk w jednej wystawce (cena sprzedaży jest za tyle sztuk). */
    public Integer sellCount;
    /** Wystawiaj tylko stacki z co najmniej tyloma sztukami. */
    public Integer minSellCount;
    /** Cena skupu (za sztukę; bez trybu "Unit" za całą ofertę) - liczy ją wzór (Pricing): sprzedaż x 0,97 - marża. */
    public double maxPrice;
    /** Cena sprzedaży całej wystawki (sellCount sztuk) - wpisuje ją analiza (średnia 3 najtańszych) i monitorowanie rynku. */
    public double sellPrice;
    /** Minimalna marża za sztukę ($), wpisywana przez gracza; null = marża domyślna z Ustawień. */
    public Double margin;
    public Boolean buyEnabled;
    public Boolean sellEnabled;
    /** Pety/kostiumy: kupuj tylko z co najmniej tyloma dniami do wygaśnięcia. */
    public Integer minDaysLeft;
    public Integer dyeColor;
    public String headTextureValue;
    /** Sztuki kupione przez auto rynek, jeszcze niewystawione (wystawianie bierze je nawet przy wyłączonym "Wystawiaj"). */
    public int toSell;
    /** Efekty mikstury (z BK): id, poziom (amplifier), czas (0 = dowolny). */
    public List<Potion> potionEffects = new ArrayList<>();

    public static final class Potion {
        public String effectId;
        public int amplifier;
        public int duration;
    }

    /** Kopia (np. z innym wymaganym lore do analizy z pominięciem ulepszeń %). */
    public MarketItem copy() {
        MarketItem c = new MarketItem();
        c.name = name;
        c.material = material;
        c.lore = lore;
        c.bannedLore = bannedLore;
        c.enchants = enchants;
        c.enchantments = enchantments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(enchantments);
        c.bannedEnchantsList = bannedEnchantsList == null ? new ArrayList<>() : new ArrayList<>(bannedEnchantsList);
        c.excludedEnchants = excludedEnchants;
        c.customModelData = customModelData;
        c.ignoreCustomModelData = ignoreCustomModelData;
        c.componentCount = componentCount;
        c.unitMode = unitMode;
        c.requiredCount = requiredCount;
        c.minBuyCount = minBuyCount;
        c.sellCount = sellCount;
        c.minSellCount = minSellCount;
        c.maxPrice = maxPrice;
        c.sellPrice = sellPrice;
        c.margin = margin;
        c.buyEnabled = buyEnabled;
        c.sellEnabled = sellEnabled;
        c.minDaysLeft = minDaysLeft;
        c.dyeColor = dyeColor;
        c.headTextureValue = headTextureValue;
        c.potionEffects = potionEffects == null ? new ArrayList<>() : new ArrayList<>(potionEffects);
        c.toSell = toSell;
        return c;
    }

    /**
     * Wymagane enchanty (id -> minimalny poziom) - tylko mapa "enchantments", tak jak w BK (tekst "enchants" jest tylko opisem).
     */
    public Map<String, Integer> requiredEnchants() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (enchantments != null) for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank()) out.put(e.getKey().trim().toLowerCase(java.util.Locale.ROOT), e.getValue() == null ? 1 : e.getValue());
        }
        return out;
    }

    /** Ilość kupna (BK: requiredCount z listy albo minBuyCount z profilu 3.0; brak = 1). */
    public int buyCount() {
        Integer v = requiredCount != null ? requiredCount : minBuyCount;
        return v == null ? 1 : Math.max(1, v);
    }

    /** Ile sztuk w jednej wystawce (sellCount albo minSellCount z profilu 3.0; brak = 1). */
    public int sellUnits() {
        Integer v = sellCount != null && sellCount > 0 ? sellCount : minSellCount;
        return v == null ? 1 : Math.max(1, v);
    }

    /** Po wczytaniu: puste listy zamiast null, id bez "minecraft:". */
    public MarketItem normalize() {
        if (material != null) material = material.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
        if (enchantments == null) enchantments = new LinkedHashMap<>();
        if (bannedEnchantsList == null) bannedEnchantsList = new ArrayList<>();
        if (potionEffects == null) potionEffects = new ArrayList<>();
        if (Double.isNaN(maxPrice) || Double.isInfinite(maxPrice)) maxPrice = 0;
        if (Double.isNaN(sellPrice) || Double.isInfinite(sellPrice)) sellPrice = 0;
        if (toSell < 0) toSell = 0;
        if (margin != null && (margin.isNaN() || margin.isInfinite() || margin < 0)) margin = null;
        return this;
    }
}
