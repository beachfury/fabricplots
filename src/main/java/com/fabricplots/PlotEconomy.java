package com.fabricplots;

import eu.pb4.common.economy.api.CommonEconomy;
import eu.pb4.common.economy.api.EconomyAccount;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigInteger;

/**
 * Optional economy integration via Patbox's Common Economy API ({@code eu.pb4:common-economy-api}).
 *
 * The API is a compile-only dependency — it's provided at runtime only when the server also runs a
 * Common Economy provider (e.g. Savs Common Economy). Every public method is guarded so FabricPlots
 * behaves normally when the API/economy mod isn't installed: charges resolve to {@link Result#NO_ECONOMY}
 * (treated as "free" by callers) and refunds/format no-op. All API references live in the nested
 * {@code Bridge}, which is only class-loaded when a method is actually invoked, so a missing API can
 * never break class loading of the rest of the mod.
 */
public final class PlotEconomy {
    public enum Result { CHARGED, INSUFFICIENT, NO_ECONOMY }

    private PlotEconomy() {}

    /** Take {@code amount} from the player. Callers treat NO_ECONOMY as free (fail-safe if no provider). */
    public static Result charge(ServerPlayer player, long amount) {
        if (amount <= 0) return Result.CHARGED;
        try { return Bridge.charge(player, amount); }
        catch (Throwable ignored) { return Result.NO_ECONOMY; }
    }

    /** Give money back (e.g. a refund on plot delete). No-op if economy is unavailable. */
    public static void refund(ServerPlayer player, long amount) {
        if (amount <= 0) return;
        try { Bridge.refund(player, amount); } catch (Throwable ignored) { /* no economy */ }
    }

    /** Format an amount with the provider's currency, or fall back to a plain number. */
    public static String format(ServerPlayer player, long amount) {
        try { return Bridge.format(player, amount); }
        catch (Throwable ignored) { return Long.toString(amount); }
    }

    /** All Common Economy API references are isolated here — loaded only when the API is present. */
    private static final class Bridge {
        static Result charge(ServerPlayer player, long amount) {
            EconomyAccount acc = account(player);
            if (acc == null) return Result.NO_ECONOMY;
            return acc.decreaseBalance(amount).isSuccessful() ? Result.CHARGED : Result.INSUFFICIENT;
        }

        static void refund(ServerPlayer player, long amount) {
            EconomyAccount acc = account(player);
            if (acc != null) acc.increaseBalance(amount);
        }

        static String format(ServerPlayer player, long amount) {
            EconomyAccount acc = account(player);
            return acc != null ? acc.currency().formatValue(BigInteger.valueOf(amount), false) : Long.toString(amount);
        }

        /** The player's economy account for the configured currency (blank config = the provider's default). */
        static EconomyAccount account(ServerPlayer player) {
            String cid = PlotsConfig.economyCurrencyId;
            if (cid != null && !cid.isBlank()) return CommonEconomy.getAccount(player, Identifier.parse(cid));
            var accounts = CommonEconomy.getAccounts(player);
            return accounts.isEmpty() ? null : accounts.iterator().next();
        }
    }
}
