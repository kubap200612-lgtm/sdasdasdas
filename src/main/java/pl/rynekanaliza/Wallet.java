package pl.rynekanaliza;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saldo konta: linia "Stan konta: 397 700$" z tablicy wyników (sidebar), a gdy jej nie ma - ostatni komunikat czatu
 * o saldzie (ważny 10 minut). Dawniej saldo podawał BK.
 */
final class Wallet {
    private Wallet() {}

    static final Pattern LINE = Pattern.compile("(?:stan konta|saldo|portfel|balance|pieniadze|kasa|money)\\s*[:»>\\-]?\\s*\\$?\\s*([\\d\\s.,]+(?:mld|[kmbt])?)");
    private static double lastChat = -1;
    private static long lastChatAt;
    private static double cached = -1;
    private static long cachedAt; // 0 = brak odczytu

    /** Saldo albo -1 (odczyt najwyżej raz na sekundę). */
    static double balance() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0 && now >= cachedAt && now - cachedAt < 1000) return cached;
        cachedAt = now;
        double v = fromScoreboard();
        if (!(v > 0) && lastChat > 0 && now - lastChatAt < 10 * 60_000L) v = lastChat;
        cached = v > 0 ? v : -1;
        return cached;
    }

    static double fromScoreboard() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.world == null) return -1;
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return -1;
            for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) {
                if (e.hidden()) continue;
                Text line = Team.decorateName(sb.getScoreHolderTeam(e.owner()), e.name());
                double v = parse(line.getString());
                if (v > 0) return v;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Kwota z linii "Stan konta: 12 345$" (bez kolorów i polskich znaków); -1 = to nie linia salda. */
    static double parse(String raw) {
        if (raw == null) return -1;
        String s = BkChatGuard.plain(ItemMatcher.stripColors(raw).toLowerCase(Locale.ROOT));
        Matcher m = LINE.matcher(s);
        if (!m.find()) return -1;
        double v = ItemMatcher.parsePriceWithSuffix(m.group(1));
        return v > 0 ? v : -1;
    }

    /** Komunikat czatu: gdy podaje saldo, zapamiętaj je. */
    static void onChat(String raw, long now) {
        double v = parse(raw);
        if (!(v > 0)) return;
        lastChat = v;
        lastChatAt = now;
        cachedAt = 0;
    }
}
