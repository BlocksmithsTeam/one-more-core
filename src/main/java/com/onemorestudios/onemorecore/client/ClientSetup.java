package com.onemorestudios.onemorecore.client;


import com.onemorestudios.onemorecore.OneMoreCore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
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

@Mod(value = OneMoreCore.MODID, dist = Dist.CLIENT)
public class ClientSetup {
    public ClientSetup(IEventBus bus, net.neoforged.fml.ModContainer container) {
        // Mods > One More Core > Config: every option in the game, the stream chat too.
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class, net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(OneMoreCore.CUSTOMER.get(), CustomerRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerEntityRenderer(OneMoreCore.COURIER.get(), CourierRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreCore.OPEN_SIGN_ENTITY.get(), OpenSignRenderer::new));
        bus.addListener(OpenSignRenderer::registerModels);
        bus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private OpenSignRenderer.Item renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft mc = Minecraft.getInstance();
                if (renderer == null) renderer = new OpenSignRenderer.Item(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                return renderer;
            }
        }, OneMoreCore.OPEN_SIGN_ITEM.get()));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreCore.TRAY_ENTITY.get(), TrayRenderer::new));
        bus.addListener(TrayCarryRenderer::addLayers);
        var trayCarry = new TrayCarryRenderer();
        NeoForge.EVENT_BUS.addListener(trayCarry::beginFrame);
        NeoForge.EVENT_BUS.addListener(trayCarry::renderHand);
        NeoForge.EVENT_BUS.addListener(TrayCarryRenderer::preparePlayer);
        NeoForge.EVENT_BUS.addListener(TrayCarryRenderer::finishPlayer);
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreCore.MENU_BOOK_ENTITY.get(), MenuBookRenderer::new));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreCore.MENU_BOARD_ENTITY.get(), ScreenRenderer.Turned::new));
        bus.addListener((RegisterClientExtensionsEvent e) -> e.registerItem(new IClientItemExtensions() {
            private TrayRenderer.Item renderer;

            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return TrayCarryRenderer.armPose(entity);
            }

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                Minecraft mc = Minecraft.getInstance();
                if (renderer == null) renderer = new TrayRenderer.Item(mc.getBlockEntityRenderDispatcher(), mc.getEntityModels());
                return renderer;
            }
        }, OneMoreCore.TRAY_ITEM.get(), OneMoreCore.IRON_TRAY_ITEM.get()));
        bus.addListener((EntityRenderersEvent.RegisterRenderers e) -> e.registerBlockEntityRenderer(OneMoreCore.TABLE_NUMBER_ENTITY.get(), TableMarkRenderer.Text::new));
        bus.addListener(TableMarkRenderer::registerModels);
        TableMarkRenderer.register();
        ScreenRenderer.register();
        com.onemorestudios.onemorecore.ScreenSettings.Open.handler = o -> Minecraft.getInstance().setScreen(new ScreenSettingsScreen(o));
        MenuBook.register();
        NeoForge.EVENT_BUS.addListener(ClientSetup::renderOrderTooltip);
        // Up close with Diegesis a screen is open: the tooltip of the television item under the mouse.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ScreenEvent.Render.Post e) -> {
            if (!ScreenRenderer.stack.isEmpty()) e.getGuiGraphics().renderTooltip(Minecraft.getInstance().font, ScreenRenderer.stack, e.getMouseX(), e.getMouseY());
        });
        NeoForge.EVENT_BUS.addListener(BubbleHover::reset);
        bus.addListener(RestaurantHud::register);
        bus.addListener(DayFade::register);
        bus.addListener(ReadyHud::registerKeys);
        bus.addListener(ReadyHud::registerLayer);
        NeoForge.EVENT_BUS.addListener(ReadyHud::tick);
        bus.addListener(DirtRenderer::registerModels);
        DirtRenderer.register();
        ComputerCamera.register();
        if (net.neoforged.fml.ModList.get().isLoaded("diegesis")) {
            WipingBehavior.register();
            TrayDiegesis.register();
            ScreenStationBehavior.register();
            DiegesisHook.register();
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
