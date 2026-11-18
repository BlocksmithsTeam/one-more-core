package com.onemorestudios.onemoreclient.client;

import com.onemorestudios.onemoreclient.Customer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Which item of an order bubble the player looks at. The bubble faces the camera, so its plane is parallel to the
 * screen: the crosshair meets it at the bubble centre minus the centre's offset across the view.
 */
final class BubbleHover {
    private BubbleHover() {}

    /** Farthest bubble that the player can point at, in blocks. */
    static final double RANGE = 8;

    /** The item under the crosshair in this frame. Read by the tooltip, after the world is drawn. */
    static ItemStack stack = ItemStack.EMPTY;
    private static double best = Double.MAX_VALUE;

    /**
     * The index of the order item under the crosshair, or -1. itemsY is the height of the items in the bubble, from its
     * centre. Of several bubbles under the crosshair, the nearest one wins.
     */
    static int slot(Customer customer, int count, float itemsY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || ComputerCamera.active()) return -1;
        Camera camera = mc.gameRenderer.getMainCamera();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Vec3 centre = customer.getPosition(partial).add(0, customer.getBbHeight() + 0.75, 0);
        Vec3 d = centre.subtract(camera.getPosition());
        Vec3 look = new Vec3(camera.getLookVector()), up = new Vec3(camera.getUpVector()), left = new Vec3(camera.getLeftVector());
        double ahead = d.dot(look);
        if (ahead <= 0.1 || ahead > RANGE) return -1;
        // The crosshair point on the bubble plane, relative to the bubble centre.
        Vec3 o = look.scale(ahead).subtract(d);
        double x = -o.dot(left), y = o.dot(up) - itemsY;
        float half = CustomerRenderer.SLOT / 2;
        if (Math.abs(y) > half) return -1;
        int i = (int) Math.floor(x / CustomerRenderer.SLOT + count / 2.0);
        if (i < 0 || i >= count) return -1;
        // Of several bubbles under the crosshair in one frame, the nearest one wins.
        if (ahead >= best) return -1;
        best = ahead;
        stack = customer.getOrder().get(i);
        return i;
    }

    /** A new frame: no item is looked at yet. */
    static void reset(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        best = Double.MAX_VALUE;
        stack = ItemStack.EMPTY;
    }
}
