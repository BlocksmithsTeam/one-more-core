package com.onemorestudios.onemoreclient.client;


import com.onemorestudios.onemoreclient.OneMoreClient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
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
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreClient.MENU_BOARD_ENTITY.get(), ScreenRenderer::new));
        bus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private OpenSignRenderer.Item renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft mc = Minecraft.getInstance();
                if (renderer == null) renderer = new OpenSignRenderer.Item(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                return renderer;
            }
        }, OneMoreClient.OPEN_SIGN_ITEM.get()));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreClient.TRAY_ENTITY.get(), TrayRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreClient.MENU_BOOK_ENTITY.get(), MenuBookRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreClient.ORDER_BOARD_ENTITY.get(), ScreenRenderer::new));
        bus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private TrayRenderer.Item renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft mc = Minecraft.getInstance();
                if (renderer == null) renderer = new TrayRenderer.Item(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                return renderer;
            }
        }, OneMoreClient.TRAY_ITEM.get()));
        TableMarkRenderer.register();
        ScreenRenderer.register();
        MenuBook.register();
        NeoForge.EVENT_BUS.addListener(ClientSetup::renderOrderTooltip);
        // Up close with Diegesis a screen is open: the tooltip of the television item under the mouse.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ScreenEvent.Render.Post e) -> {
            if (!ScreenRenderer.stack.isEmpty()) e.getGuiGraphics().renderTooltip(Minecraft.getInstance().font, ScreenRenderer.stack, e.getMouseX(), e.getMouseY());
        });
        NeoForge.EVENT_BUS.addListener(BubbleHover::reset);
        bus.addListener(RestaurantHud::register);
        bus.addListener(ReadyHud::registerKeys);
        bus.addListener(ReadyHud::registerLayer);
        NeoForge.EVENT_BUS.addListener(ReadyHud::tick);
        bus.addListener(DirtRenderer::registerModels);
        DirtRenderer.register();
        ComputerCamera.register();
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) {
            WipingBehavior.register();
            ScreenBehavior.register();
        }
    }

    /** Shows the tooltip of the order item that the player looks at in a bubble. */
    private static void renderOrderTooltip(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ItemStack shown = !BubbleHover.stack.isEmpty() ? BubbleHover.stack : ScreenRenderer.stack;
        if (mc.screen != null || shown.isEmpty()) return;
        var g = event.getGuiGraphics();
        g.renderTooltip(mc.font, shown, g.guiWidth() / 2 + 8, g.guiHeight() / 2 + 8);
    }
}
