package com.onemorestudios.onemorecore;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** A dish's place in north-facing tray pixels, also available without Diegesis. */
public record TrayPose(float x, float y, float z, float lift, Quaternionfc rotation, float scale, int on, float wanted) {
    public static final float MIN_X = 0.5f, MAX_X = 15.5f, MIN_Z = 3, MAX_Z = 13, BED = 1;
    public static final float ITEM_SIZE = 6.75f;

    private static final Codec<TrayPose> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.floatRange(MIN_X, MAX_X).fieldOf("x").forGetter(TrayPose::x),
            Codec.floatRange(BED, BED + 10).fieldOf("y").forGetter(TrayPose::y),
            Codec.floatRange(MIN_Z, MAX_Z).fieldOf("z").forGetter(TrayPose::z),
            Codec.floatRange(0, 10).fieldOf("lift").forGetter(TrayPose::lift),
            ExtraCodecs.QUATERNIONF.fieldOf("rotation").forGetter(pose -> new Quaternionf(pose.rotation)),
            Codec.floatRange(0.05f, 16).fieldOf("scale").forGetter(TrayPose::scale),
            Codec.intRange(-1, TrayBlockEntity.SIZE - 1).optionalFieldOf("on", -1).forGetter(TrayPose::on),
            Codec.floatRange(0.05f, 16).fieldOf("wanted").forGetter(TrayPose::wanted)
    ).apply(instance, TrayPose::new));
    public static final Codec<List<TrayPose>> SLOTS_CODEC = CODEC.listOf(0, TrayBlockEntity.SIZE);

    public TrayPose {
        // NBT float tags canonicalize signed zero.
        rotation = new Quaternionf(rotation.x() + 0f, rotation.y() + 0f, rotation.z() + 0f, rotation.w() + 0f);
    }

    @Override
    public Quaternionfc rotation() {
        return new Quaternionf(rotation);
    }

    /** Zero lift marks a pose whose resting turn and height depend on the client model. */
    public static TrayPose atRest(float x, float z, Quaternionfc yaw) {
        return new TrayPose(x, BED, z, 0, yaw, 1, -1, 1);
    }

    public static TrayPose legacy(int slot) {
        return atRest(3 + 5 * slot, 8, new Quaternionf().rotateY((float) Math.PI));
    }

    public static TrayPose inSlot(List<TrayPose> poses, int slot) {
        return slot < poses.size() ? poses.get(slot) : legacy(slot);
    }
}
