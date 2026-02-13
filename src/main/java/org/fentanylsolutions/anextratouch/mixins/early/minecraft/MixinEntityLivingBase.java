package org.fentanylsolutions.anextratouch.mixins.early.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;

import org.fentanylsolutions.anextratouch.Config;
import org.fentanylsolutions.anextratouch.handlers.client.camera.CameraHandler;
import org.fentanylsolutions.anextratouch.handlers.client.camera.ScreenShakeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase {

    @Unique
    private long anextratouch$shakeHandle;

    @Inject(method = "swingItem", at = @At("RETURN"))
    private void anextratouch$onSwingItem(CallbackInfo ci) {
        if (!Config.cameraOverhaulEnabled) {
            return;
        }

        EntityLivingBase self = (EntityLivingBase) (Object) this;
        // only for the local player on the client
        if (!self.worldObj.isRemote) {
            return;
        }
        if (self != Minecraft.getMinecraft().thePlayer) {
            return;
        }

        anextratouch$shakeHandle = ScreenShakeManager.recreate(anextratouch$shakeHandle);
        ScreenShakeManager.Slot shake = ScreenShakeManager.get(anextratouch$shakeHandle);
        shake.trauma = Config.cameraHandSwingTrauma;
        shake.frequency = 0.5f;
        shake.lengthInSeconds = 0.5f;

        CameraHandler.notifyOfPlayerAction();
    }
}
