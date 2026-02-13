package org.fentanylsolutions.anextratouch.mixins.early.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

import org.fentanylsolutions.anextratouch.AnExtraTouch;
import org.fentanylsolutions.anextratouch.Config;
import org.fentanylsolutions.anextratouch.footsteps.FootprintUtil;
import org.fentanylsolutions.anextratouch.handlers.client.StepSoundHandler;
import org.fentanylsolutions.anextratouch.handlers.client.camera.CameraHandler;
import org.fentanylsolutions.anextratouch.handlers.client.camera.ScreenShakeManager;
import org.fentanylsolutions.anextratouch.handlers.server.ServerArmorHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class MixinEntity {

    // Track position for actual distance calculation
    @Unique
    private double anextratouch$lastPosX = Double.NaN;
    @Unique
    private double anextratouch$lastPosZ = Double.NaN;

    // Track distance for footprints separately from step sounds
    @Unique
    private float anextratouch$footprintDistance = 0.0f;

    @Unique
    private boolean anextratouch$isRightFoot = true;
    @Unique
    private long anextratouch$cameraFallShakeHandle;

    // Hook into call site of func_145780_a (step sound method) inside moveEntity, because we'd be missing overrides
    // that are noops
    @Inject(
        method = "moveEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;func_145780_a(IIILnet/minecraft/block/Block;)V"))
    private void onPlayStepSound(double dx, double dy, double dz, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.worldObj.isRemote) {
            StepSoundHandler.onEntityStep(self);
        } else {
            ServerArmorHandler.onEntityStep(self);
        }
    }

    // Hook into call site of fall() inside updateFallState (fall() is not called directly in moveEntity)
    @Inject(method = "updateFallState", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;fall(F)V"))
    private void onFall(double distanceFallenThisTick, boolean isOnGround, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.worldObj.isRemote) {
            StepSoundHandler.onEntityLand(self, self.fallDistance);

            if (!Config.cameraOverhaulEnabled || !Config.cameraFallShakeEnabled) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (self != mc.thePlayer) {
                return;
            }
            if (Config.cameraDisableWhilePaused && mc.isGamePaused()) {
                return;
            }

            float distance = self.fallDistance;
            if (distance < Config.cameraFallShakeMinDistance) {
                return;
            }

            float denom = Math.max(0.0001f, Config.cameraFallShakeMaxDistance - Config.cameraFallShakeMinDistance);
            float t = (distance - Config.cameraFallShakeMinDistance) / denom;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;

            anextratouch$cameraFallShakeHandle = ScreenShakeManager.recreate(anextratouch$cameraFallShakeHandle);
            ScreenShakeManager.Slot shake = ScreenShakeManager.get(anextratouch$cameraFallShakeHandle);
            shake.trauma = Config.cameraFallShakeMaxTrauma * (t * t);
            shake.frequency = Config.cameraFallShakeFrequency;
            shake.lengthInSeconds = Config.cameraFallShakeLength;

            CameraHandler.notifyOfPlayerAction();
        } else {
            ServerArmorHandler.onEntityLand(self, self.fallDistance);
        }
    }

    // tracking actual position changes.
    @Inject(method = "moveEntity", at = @At("RETURN"))
    private void onMoveEntityReturn(double dx, double dy, double dz, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        if (!self.worldObj.isRemote) {
            return;
        }
        if (!(self instanceof EntityLivingBase)) {
            return;
        }
        if (!AnExtraTouch.vic.entityStrides.containsKey(self.getClass())) {
            return;
        }
        if (self.ridingEntity != null) {
            return;
        }

        // init last position on first call
        if (Double.isNaN(anextratouch$lastPosX)) {
            anextratouch$lastPosX = self.posX;
            anextratouch$lastPosZ = self.posZ;
            return;
        }

        if (!self.onGround) {
            anextratouch$lastPosX = self.posX;
            anextratouch$lastPosZ = self.posZ;
            return;
        }

        // Calculate actual horizontal distance moved this tick
        double deltaX = self.posX - anextratouch$lastPosX;
        double deltaZ = self.posZ - anextratouch$lastPosZ;
        float distMoved = (float) Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        anextratouch$lastPosX = self.posX;
        anextratouch$lastPosZ = self.posZ;

        if (distMoved < 0.001f) {
            return;
        }

        anextratouch$footprintDistance += distMoved;

        // Check if we've walked far enough for a footprint
        boolean isBaby = ((EntityLivingBase) self).isChild();
        float stride = isBaby ? AnExtraTouch.vic.babyEntityStrides.getFloat(self.getClass())
            : AnExtraTouch.vic.entityStrides.getFloat(self.getClass());

        if (anextratouch$footprintDistance >= stride) {
            anextratouch$footprintDistance %= stride;
            anextratouch$isRightFoot = !anextratouch$isRightFoot;
            FootprintUtil.spawnFootprint(self, isBaby, anextratouch$isRightFoot);
        }
    }
}
