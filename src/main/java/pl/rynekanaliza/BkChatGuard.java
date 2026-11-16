package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Czat serwera dla auto rynku: wynik zakupu i wystawiania (AutoMarket), sprzedaże naszych wystawek (każda osobno, z treścią
 * komunikatu - do historii; BkBridge.noteSaleMessage) i saldo konta (Wallet).
 * <p>
 * Zostało też dawne obejście błędu BK-Rynku 3.0 (gdyby BK był jeszcze zainstalowany): jego komunikaty ("[BK Client]"
 * po zdjęciu kolorów) trafiają prosto do okna czatu, z pominięciem zdarzeń czatu - inaczej BK czytał własne "Brak
 * środków na zakup" i pisał je w kółko, aż gra się wysypała. Komunikaty serwera, graczy i tego moda przechodzą bez zmian.
 */
final class BkChatGuard {
    static final String BK_PREFIX = "[BK Client]";

    private BkChatGuard() {}

    /** Tekst bez polskich znaków (ą -> a, ł -> l, ...). */
    static String plain(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = "ąćęłńóśźż".indexOf(c);
            b.append(k >= 0 ? "acelnoszz".charAt(k) : c);
        }
        return b.toString();
    }

    /** Zdarzenie ALLOW_GAME: każdy komunikat serwera idzie do auto rynku; komunikat BK - od razu do okna czatu (false). */
    static boolean allow(Text message, boolean overlay) {
        try {
            if (overlay || message == null) return true;
            String s = message.getString();
            if (!isBkMessage(s)) {
                long now = System.currentTimeMillis();
                BkBridge.noteSaleMessage(s, now); // sprzedaż z treścią komunikatu (nazwa przedmiotu) - historia
                Wallet.onChat(s, now);
                AutoMarket.onChat(s);
                return true;
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.inGameHud == null) return true;
            mc.inGameHud.getChatHud().addMessage(message);
            return false;
        } catch (Throwable t) {
            return true; // w razie kłopotu zwykła droga komunikatu
        }
    }

    /** Czy to komunikat BK-Rynku: po zdjęciu kodów kolorów zaczyna się od "[BK Client]". */
    static boolean isBkMessage(String s) {
        return s != null && !s.isEmpty() && strip(s).startsWith(BK_PREFIX);
    }

    /** Tekst bez kodów formatowania (§ i znak po nim). */
    static String strip(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            b.append(c);
        }
        return b.toString().trim();
    }
}
