package com.onemorestudios.onemoreclient.client;


import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = OneMoreClient.MODID, dist = Dist.CLIENT)
public class ClientSetup {
    public ClientSetup(IEventBus bus) {
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(OneMoreClient.CUSTOMER.get(), CustomerRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreClient.OPEN_SIGN_ENTITY.get(), OpenSignRenderer::new));
        bus.addListener(OpenSignRenderer::registerModels);
        bus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private OpenSignRenderer.Item renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft mc = Minecraft.getInstance();
                if (renderer == null) renderer = new OpenSignRenderer.Item(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                return renderer;
            }
        }, OneMoreClient.OPEN_SIGN_ITEM.get()));
        NeoForge.EVENT_BUS.addListener(ClientSetup::renderOrderTooltip);
        NeoForge.EVENT_BUS.addListener(BubbleHover::reset);
        bus.addListener(RestaurantHud::register);
        bus.addListener(ReadyHud::registerKeys);
        bus.addListener(ReadyHud::registerLayer);
        NeoForge.EVENT_BUS.addListener(ReadyHud::tick);
        bus.addListener(DirtRenderer::registerModels);
        DirtRenderer.register();
        ComputerCamera.register();
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) WipingBehavior.register();
    }

    /** Shows the tooltip of the order item that the player looks at in a bubble. */
    private static void renderOrderTooltip(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || BubbleHover.stack.isEmpty()) return;
        var g = event.getGuiGraphics();
        g.renderTooltip(mc.font, BubbleHover.stack, g.guiWidth() / 2 + 8, g.guiHeight() / 2 + 8);
    }
}
