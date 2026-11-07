package com.example.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class NoWebMixin {
    
    // Сила пинка (как в Prisma). Если Matrix/Vulcan кикает — снизь до 0.5f или 0.3f
    private static final float STRENGTH = 0.8f;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (player.level() == null) return;
        if (player.isSpectator() || player.getAbilities().flying) return;
        if (!isInsideCobweb(player)) return;

        Minecraft mc = Minecraft.getInstance();
        
        float forward = 0.0f;
        if (mc.options.keyUp.isDown()) forward += 1.0f;
        if (mc.options.keyDown.isDown()) forward -= 1.0f;

        float strafe = 0.0f;
        if (mc.options.keyLeft.isDown()) strafe += 1.0f;
        if (mc.options.keyRight.isDown()) strafe -= 1.0f;

        // Выход вверх по Y (прыжок)
        if (mc.options.keyJump.isDown()) {
            player.setDeltaMovement(player.getDeltaMovement().x, 0.42, player.getDeltaMovement().z);
        }

        if (forward == 0.0f && strafe == 0.0f) return;

        // Математика Prisma.Tech
        double rad = Math.toRadians(player.getYRot());
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = strafe * cos - forward * sin;
        double z = strafe * sin + forward * cos;
        double len = Math.sqrt(x * x + z * z);

        if (len < 1.0E-6) return;

        x /= len;
        z /= len;

        // Вычисление инжекта (0.8 * 0.15 / 0.25 = 0.48)
        double inject = STRENGTH * 0.15 / 0.25;

        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x + x * inject, v.y, v.z + z * inject);
    }

    private boolean isInsideCobweb(LocalPlayer player) {
        AABB bb = player.getBoundingBox();
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
                    if (player.level().getBlockState(p).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}