package com.fabricplots.player;

import com.fabricplots.FabricPlots;
import com.fabricplots.core.PlotsConfig;

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
 * behaves safely when the API/economy mod isn't installed: charges resolve to {@link Result#NO_ECONOMY}
 * (claims fail closed while economy is enabled) and refunds report failure. All API references live in the nested
 * {@code Bridge}, which is only class-loaded when a method is actually invoked, so a missing API can
 * never break class loading of the rest of the mod.
 */
public final class PlotEconomy {
    public enum Result { CHARGED, INSUFFICIENT, NO_ECONOMY }

    private PlotEconomy() {}

    /** Take {@code amount} from the player. NO_ECONOMY tells callers to refuse a paid claim. */
    public static Result charge(ServerPlayer player, long amount) {
        if (amount <= 0) return Result.CHARGED;
        try { return Bridge.charge(player, amount); }
        catch (Throwable error) {
            System.err.println("[FabricPlots] Economy charge unavailable: " + error);
            return Result.NO_ECONOMY;
        }
    }

    /** Give money back. Returns false instead of claiming success when the provider is unavailable. */
    public static boolean refund(ServerPlayer player, long amount) {
        if (amount <= 0) return true;
        try { return Bridge.refund(player, amount); }
        catch (Throwable error) {
            System.err.println("[FabricPlots] Economy refund unavailable: " + error);
            return false;
        }
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

        static boolean refund(ServerPlayer player, long amount) {
            EconomyAccount acc = account(player);
            return acc != null && acc.increaseBalance(amount).isSuccessful();
        }

        static String format(ServerPlayer player, long amount) {
            EconomyAccount acc = account(player);
            return acc != null ? acc.currency().formatValue(BigInteger.valueOf(amount), false) : Long.toString(amount);
        }

        /** The configured account, or the only available account when no currency id was supplied. */
        static EconomyAccount account(ServerPlayer player) {
            String cid = PlotsConfig.economyCurrencyId;
            if (cid != null && !cid.isBlank()) return CommonEconomy.getAccount(player, Identifier.parse(cid));
            var accounts = CommonEconomy.getAccounts(player);
            // With multiple currencies, "the first" is provider-order dependent and could charge
            // the wrong wallet. Require an explicit id unless exactly one account exists.
            return accounts.size() == 1 ? accounts.iterator().next() : null;
        }
    }
}
