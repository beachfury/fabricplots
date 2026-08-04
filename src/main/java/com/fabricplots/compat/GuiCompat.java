package com.fabricplots.compat;

import eu.pb4.sgui.api.elements.GuiElementBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * sgui differences between versions funnel through here. 1.21.1 ships sgui 1.6.x, whose
 * {@link GuiElementBuilder} has no {@code setProfile(UUID)} (that's a 2.x addition) — player
 * heads are set via {@code setSkullOwner(GameProfile, server)} with a cached profile instead.
 */
public final class GuiCompat {
    private GuiCompat() {}

    /** A player-head builder showing {@code id}'s skin when the server knows the profile. */
    public static GuiElementBuilder head(MinecraftServer server, UUID id) {
        GuiElementBuilder b = new GuiElementBuilder(Items.PLAYER_HEAD);
        try {
            if (server != null && server.getProfileCache() != null) {
                server.getProfileCache().get(id).ifPresent(profile -> b.setSkullOwner(profile, server));
            }
        } catch (Throwable ignored) { /* plain head is fine */ }
        return b;
    }
}
