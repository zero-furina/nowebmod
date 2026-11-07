package com.example.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class NowebmodClient implements ClientModInitializer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final float STRENGTH = 0.8f;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (mc.player == null || mc.world == null) return;
            if (mc.player.isSpectator() || mc.player.getAbilities().flying) return;
            if (!isInsideCobweb()) return;

            float forward = mc.player.input.movementForward;
            float strafe = mc.player.input.movementSideways;
            if (forward == 0.0f && strafe == 0.0f) return;

            Vec3d dir = inputDirection(forward, strafe);
            double inject = STRENGTH * 0.15 / 0.25;

            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x + dir.x * inject, v.y, v.z + dir.z * inject);
        });
    }

    private static Vec3d inputDirection(float forward, float strafe) {
        float f = MathHelper.clamp(forward, -1.0f, 1.0f);
        float s = MathHelper.clamp(strafe, -1.0f, 1.0f);
        double rad = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = s * cos - f * sin;
        double z = s * sin + f * cos;
        double len = Math.sqrt(x * x + z * z);

        if (len < 1.0E-6) return Vec3d.ZERO;
        return new Vec3d(x / len, 0.0, z / len);
    }

    private static boolean isInsideCobweb() {
        Box bb = mc.player.getBoundingBox();
        int minX = MathHelper.floor(bb.minX);
        int maxX = MathHelper.floor(bb.maxX);
        int minY = MathHelper.floor(bb.minY);
        int maxY = MathHelper.floor(bb.maxY);
        int minZ = MathHelper.floor(bb.minZ);
        int maxZ = MathHelper.floor(bb.maxZ);
        BlockPos.Mutable p = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    p.set(x, y, z);
                    if (mc.world.getBlockState(p).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
