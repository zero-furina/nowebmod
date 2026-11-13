package com.example.noweb.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Клиентский Mixin модуля NoWeb для Minecraft 26.2 (Fabric, официальные Mojang-маппинги, Java 25).
 *
 * <p>Логика работы:
 * <ol>
 *   <li>Каждый тик проверяет пересечение {@link LocalPlayer#getBoundingBox()} с {@link Blocks#COBWEB}.</li>
 *   <li>При попадании в паутину отправляет {@code START_DESTROY_BLOCK}, ждёт 4 тика (~0.2с)
 *       и затем отправляет {@code STOP_DESTROY_BLOCK} с корректным sequence ID через {@code startPrediction}.</li>
 *   <li>Ограничивает частоту циклов отправки пакетов (кулдаун 10 тиков = 0.5с) для защиты от Matrix Packet Spam.</li>
 *   <li>Перехватывает ванильные методы ломания блока, чтобы не дублировать/не срывать пакеты по активной паутине.</li>
 *   <li>При сохранении замедления (по {@link LocalPlayer#getDeltaMovement()}) применяет страховочный
 *       горизонтальный импульс {@code 0.23} через {@code setDeltaMovement}.</li>
 * </ol>
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class NoWebMixin {

    /** Задержка между START_DESTROY_BLOCK и STOP_DESTROY_BLOCK (3–4 тика ≈ 0.2 сек). */
    @Unique
    private static final int NOWEB_BREAK_DELAY_TICKS = 4;

    /** Минимальный интервал между циклами отправки пакетов (10 тиков = 0.5 сек, защита от Matrix Packet Spam). */
    @Unique
    private static final int NOWEB_PACKET_COOLDOWN_TICKS = 10;

    /** Сила страховочного горизонтального импульса (Vulcan Strafe style). */
    @Unique
    private static final double NOWEB_FALLBACK_STRAFE_SPEED = 0.23;

    /** Порог горизонтальной скорости, ниже которого считаем, что замедление паутины всё ещё активно. */
    @Unique
    private static final double NOWEB_SLOWDOWN_THRESHOLD = 0.10;

    /** Квадрат максимальной серверной дистанции обработки пакетов ломания (6 блоков). */
    @Unique
    private static final double NOWEB_MAX_REACH_SQR = 36.0;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ClientPacketListener connection;

    @Shadow
    private void startPrediction(ClientLevel level, PredictiveAction action) {
        throw new AssertionError();
    }

    @Unique
    private BlockPos noweb$pendingPos = null;

    @Unique
    private int noweb$breakTimer = 0;

    @Unique
    private int noweb$packetCooldown = 0;

    @Unique
    private boolean noweb$sentBreakCycle = false;

    @Unique
    private final BlockPos.MutableBlockPos noweb$cursor = new BlockPos.MutableBlockPos();

    /**
     * Перехват начала ломания блока: не даём ванильному ЛКМ отправить дублирующий
     * START_DESTROY_BLOCK по той же паутине, пока активен наш таймер.
     */
    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void noweb$onStartDestroyBlock(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.noweb$pendingPos != null && this.noweb$pendingPos.equals(pos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Перехват продолжения ломания блока: не даём клиенту преждевременно отправить
     * STOP_DESTROY_BLOCK до истечения 3–4 тиков ожидания.
     */
    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void noweb$onContinueDestroyBlock(
            BlockPos pos,
            Direction direction,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.noweb$pendingPos != null && this.noweb$pendingPos.equals(pos)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Перехват отмены ломания блока: подавляем ABORT_DESTROY_BLOCK на время
     * 3–4-тикового окна, чтобы не нарушать порядок digging-пакетов для Grim.
     */
    @Inject(method = "stopDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void noweb$onStopDestroyBlock(CallbackInfo ci) {
        if (this.noweb$pendingPos != null) {
            ci.cancel();
        }
    }

    /**
     * Основной цикл NoWeb, выполняющийся каждый клиентский тик.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void noweb$onTick(CallbackInfo ci) {
        // При подключении собственного менеджера модулей добавьте здесь проверку, например:
        // if (!NoWebConfig.isEnabled()) { this.noweb$resetState(); return; }

        LocalPlayer player = this.minecraft.player;
        ClientLevel level = this.minecraft.level;
        if (player == null || level == null || this.connection == null) {
            this.noweb$resetState();
            return;
        }

        if (this.noweb$packetCooldown > 0) {
            this.noweb$packetCooldown--;
        }

        // 1. Проверяем, пересекает ли хитбокс игрока блок Blocks.COBWEB
        BlockPos cobwebPos = this.noweb$findIntersectingCobweb(player, level);

        // 2. Если уже отправили START_DESTROY_BLOCK — выжидаем 3–4 тика перед STOP_DESTROY_BLOCK
        if (this.noweb$pendingPos != null) {
            this.noweb$breakTimer--;
            if (this.noweb$breakTimer <= 0) {
                BlockPos targetPos = this.noweb$pendingPos;
                this.noweb$pendingPos = null;

                boolean stillCobweb = level.getBlockState(targetPos).is(Blocks.COBWEB);
                boolean inReach = player.getEyePosition()
                        .distanceToSqr(Vec3.atCenterOf(targetPos)) <= NOWEB_MAX_REACH_SQR;

                if (stillCobweb && inReach) {
                    this.noweb$sendDigPacket(
                            level,
                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                            targetPos
                    );
                    this.noweb$sentBreakCycle = true;
                }

                // Ограничение: следующий цикл не раньше чем через 10 тиков (0.5 сек)
                this.noweb$packetCooldown = NOWEB_PACKET_COOLDOWN_TICKS;
            }
        } else if (cobwebPos != null && this.noweb$packetCooldown == 0) {
            // 3. Игрок в паутине и кулдаун истёк — отправляем START_DESTROY_BLOCK
            this.noweb$sendDigPacket(
                    level,
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    cobwebPos
            );
            this.noweb$pendingPos = cobwebPos;
            this.noweb$breakTimer = NOWEB_BREAK_DELAY_TICKS;
            this.noweb$packetCooldown = NOWEB_PACKET_COOLDOWN_TICKS;
        }

        // Игрок вышел из паутины — страховка больше не нужна
        if (cobwebPos == null) {
            this.noweb$sentBreakCycle = false;
            return;
        }

        // 4. Страховка: если после отправки пакетов замедление сохраняется,
        //    мягко выталкиваем игрока с горизонтальной силой 0.23
        if (this.noweb$sentBreakCycle) {
            this.noweb$applyFallbackStrafe(player);
        }
    }

    /**
     * Находит ближайший блок {@link Blocks#COBWEB}, пересекающий {@link LocalPlayer#getBoundingBox()}.
     */
    @Unique
    private BlockPos noweb$findIntersectingCobweb(LocalPlayer player, ClientLevel level) {
        AABB box = player.getBoundingBox().deflate(1.0E-7);
        int minX = Mth.floor(box.minX);
        int minY = Mth.floor(box.minY);
        int minZ = Mth.floor(box.minZ);
        int maxX = Mth.floor(box.maxX);
        int maxY = Mth.floor(box.maxY);
        int maxZ = Mth.floor(box.maxZ);

        BlockPos closest = null;
        double closestDistSqr = Double.MAX_VALUE;
        Vec3 eyePos = player.getEyePosition();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    this.noweb$cursor.set(x, y, z);
                    if (level.getBlockState(this.noweb$cursor).is(Blocks.COBWEB)) {
                        double distSqr = eyePos.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
                        if (distSqr < closestDistSqr) {
                            closestDistSqr = distSqr;
                            closest = this.noweb$cursor.immutable();
                        }
                    }
                }
            }
        }

        return closest;
    }

    /**
     * Отправляет {@link ServerboundPlayerActionPacket} через {@code startPrediction},
     * чтобы клиент 26.2 автоматически подставил валидный {@code sequence} ID.
     */
    @Unique
    private void noweb$sendDigPacket(
            ClientLevel level,
            ServerboundPlayerActionPacket.Action action,
            BlockPos pos
    ) {
        this.startPrediction(level, sequence ->
                new ServerboundPlayerActionPacket(action, pos, Direction.UP, sequence)
        );
    }

    /**
     * Страховочное ускорение (Strafe 0.23), если после пакетов ломания
     * горизонтальная скорость игрока в паутине всё ещё ниже порога.
     */
    @Unique
    private void noweb$applyFallbackStrafe(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(delta.x, delta.z);
        if (horizontalSpeed >= NOWEB_SLOWDOWN_THRESHOLD) {
            return;
        }

        // В Minecraft 1.21.5+ и 26.2 направление ввода берётся через getMoveVector() (x = strafe, y = forward)
        Vec2 moveInput = player.input.getMoveVector();
        float strafe = moveInput.x;
        float forward = moveInput.y;

        if (Math.abs(strafe) < 1.0E-4F && Math.abs(forward) < 1.0E-4F) {
            return;
        }

        float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yawRad);
        double cos = Mth.cos(yawRad);

        double dirX = strafe * cos - forward * sin;
        double dirZ = forward * cos + strafe * sin;
        double len = Math.hypot(dirX, dirZ);

        if (len > 1.0E-6) {
            double motionX = (dirX / len) * NOWEB_FALLBACK_STRAFE_SPEED;
            double motionZ = (dirZ / len) * NOWEB_FALLBACK_STRAFE_SPEED;
            // Сохраняем delta.y, чтобы не провоцировать вертикальные флаги
            player.setDeltaMovement(motionX, delta.y, motionZ);
        }
    }

    @Unique
    private void noweb$resetState() {
        this.noweb$pendingPos = null;
        this.noweb$breakTimer = 0;
        this.noweb$packetCooldown = 0;
        this.noweb$sentBreakCycle = false;
    }
}
