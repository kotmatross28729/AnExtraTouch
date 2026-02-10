package org.fentanylsolutions.anextratouch.mixins.late.dsurround;

import net.minecraft.entity.player.EntityPlayer;

import org.blockartistry.mod.DynSurround.client.footsteps.engine.interfaces.EventType;
import org.blockartistry.mod.DynSurround.client.footsteps.game.system.PFReaderH;
import org.fentanylsolutions.anextratouch.handlers.client.ArmorSoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Dynamic Surroundings compat

@Mixin(value = PFReaderH.class, remap = false)
public abstract class MixinDSFootsteps {

    @Inject(
        method = "produceStep(Lnet/minecraft/entity/player/EntityPlayer;Lorg/blockartistry/mod/DynSurround/client/footsteps/engine/interfaces/EventType;D)V",
        at = @At("HEAD"))
    private void onProduceStep(EntityPlayer ply, EventType event, double verticalOffsetAsMinus, CallbackInfo ci) {
        ArmorSoundHandler.onEntityStep(ply);
    }
}
