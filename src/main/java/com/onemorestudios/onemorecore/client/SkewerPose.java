package com.onemorestudios.onemorecore.client;

import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The horizontal rest of a grilling skewer in its raw model coordinates. */
public final class SkewerPose {
    private SkewerPose() {}

    public static Quaternionf lyingTurn(Quaternionfc rotation, Vector3fc half) {
        Vector3f right = rotation.transform(new Vector3f(1, 0, 0));
        float yaw = (float) Math.atan2(-right.z, right.x);
        boolean verticalModel = half.y() > Math.max(half.x(), half.z());
        if (right.x * right.x + right.z * right.z < 1.0e-6f) {
            Vector3f forward = rotation.transform(verticalModel ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1));
            yaw = (float) Math.atan2(forward.x, forward.z);
        }
        Quaternionf turn = new Quaternionf().rotationY(yaw);
        // The sampled skewers already run along Z. A model built along Y needs a quarter turn.
        return verticalModel ? turn.rotateX(Mth.HALF_PI) : turn;
    }
}
