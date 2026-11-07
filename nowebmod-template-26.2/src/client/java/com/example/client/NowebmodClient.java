package com.example.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class NowebmodClient implements ClientModInitializer {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final float STRENGTH = 0.8f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.level == null) return;
            if (mc.player.isSpectator() || mc.player.getAbilities().flying) return;
            if (!isInsideCobweb()) return;

            float forward = mc.player.input.forwardImpulse;
            float strafe = mc.player.input.leftImpulse;
            if (forward == 0.0f && strafe == 0.0f) return;

            Vec3 dir = inputDirection(forward, strafe);
            double inject = STRENGTH * 0.15 / 0.25;

            Vec3 v = mc.player.getDeltaMovement();
            mc.player.setDeltaMovement(v.x + dir.x * inject, v.y, v.z + dir.z * inject);
        });
    }

    private static Vec3 inputDirection(float forward, float strafe) {
        float f = Mth.clamp(forward, -1.0f, 1.0f);
        float s = Mth.clamp(strafe, -1.0f, 1.0f);
        double rad = Math.toRadians(mc.player.getYRot());
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = s * cos - f * sin;
        double z = s * sin + f * cos;
        double len = Math.sqrt(x * x + z * z);

        if (len < 1.0E-6) return Vec3.ZERO;
        return new Vec3(x / len, 0.0, z / len);
    }

    private static boolean isInsideCobweb() {
        AABB bb = mc.player.getBoundingBox();
        int minX = Mth.floor(bb.minX);
        int maxX = Mth.floor(bb.maxX);
        int minY = Mth.floor(bb.minY);
        int maxY = Mth.floor(bb.maxY);
        int minZ = Mth.floor(bb.minZ);
        int maxZ = Mth.floor(bb.maxZ);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    p.set(x, y, z);
                    if (mc.level.getBlockState(p).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
