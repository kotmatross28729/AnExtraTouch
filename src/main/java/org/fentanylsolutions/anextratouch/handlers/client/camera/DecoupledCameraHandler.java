package org.fentanylsolutions.anextratouch.handlers.client.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import org.fentanylsolutions.anextratouch.Config;
import org.fentanylsolutions.anextratouch.compat.ShoulderSurfingCompat;
import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.settings.KeyBinding;

@SideOnly(Side.CLIENT)
public final class DecoupledCameraHandler {

    public static final KeyBinding FREE_LOOK_KEY = new KeyBinding(
        "key.anextratouch.free_look", Keyboard.KEY_LMENU, "key.categories.anextratouch");

    // State
    private static boolean active;
    private static boolean wasActive;
    private static boolean freeLooking;

    // Independent camera rotation (matches modern ShoulderSurfingCamera.xRot/yRot)
    private static float cameraYaw;
    private static float cameraPitch;
    private static float prevCameraYaw;
    private static float prevCameraPitch;

    // Free look offsets that decay when Alt released (matches modern xRotOffset/yRotOffset)
    private static float yawOffset;
    private static float pitchOffset;
    private static float prevYawOffset;
    private static float prevPitchOffset;

    // Camera yaw snapshot when free look is NOT active (matches modern freeLookYRot)
    private static float freeLookYaw;

    // Turning toward interaction target
    private static int turningLockTicks;

    // Aiming state (bow draw, etc.) - player rotation follows camera
    private static boolean aiming;

    // Entity tracking for reset
    private static int lastEntityId = Integer.MIN_VALUE;

    private DecoupledCameraHandler() {}

    public static void registerKeybinding() {
        ClientRegistry.registerKeyBinding(FREE_LOOK_KEY);
    }

    /**
     * Called every client tick. Updates state, decays free look offsets.
     * Ported from ShoulderSurfingCamera.tick() + ShoulderSurfingImpl.tick().
     */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isGamePaused()) {
            return;
        }

        boolean shouldBeActive = Config.decoupledCameraEnabled
            && (ShoulderSurfingCompat.isShoulderSurfingActive()
                || (!ShoulderSurfingCompat.isAvailable() && mc.gameSettings.thirdPersonView > 0));

        EntityLivingBase entity = mc.renderViewEntity;
        if (entity == null) {
            shouldBeActive = false;
        }

        // Detect entity change -> reset
        if (entity != null) {
            int entityId = entity.getEntityId();
            if (entityId != lastEntityId) {
                lastEntityId = entityId;
                resetState(entity);
            }
        }

        // Handle activation transition
        if (shouldBeActive && !wasActive && entity != null) {
            cameraYaw = entity.rotationYaw;
            cameraPitch = entity.rotationPitch;
            prevCameraYaw = cameraYaw;
            prevCameraPitch = cameraPitch;
            yawOffset = 0f;
            pitchOffset = 0f;
            prevYawOffset = 0f;
            prevPitchOffset = 0f;
            freeLookYaw = cameraYaw;
        }

        wasActive = active;
        active = shouldBeActive;

        if (!active) {
            return;
        }

        // Store previous tick values for interpolation
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;
        prevYawOffset = yawOffset;
        prevPitchOffset = pitchOffset;

        // Update free look state
        freeLooking = FREE_LOOK_KEY.getKeyCode() != 0 && Keyboard.isKeyDown(FREE_LOOK_KEY.getKeyCode());

        // Disable free look while aiming (matches modern: isFreeLooking = FREE_LOOK.isDown() && !isAiming)
        if (aiming) {
            freeLooking = false;
        }

        // When not free-looking, store freeLookYaw and decay offsets
        // Matches modern ShoulderSurfingCamera.tick() lines 98-103
        if (!freeLooking) {
            freeLookYaw = cameraYaw;
            yawOffset *= Config.decoupledCameraOffsetDecay;
            pitchOffset *= Config.decoupledCameraOffsetDecay;
        }

        // Turning toward interaction target
        // Ported from modern ShoulderSurfingImpl.tick() + EntityHelper.lookAtTarget()
        if (turningLockTicks > 0) {
            turningLockTicks--;
        }

        if (!freeLooking && !aiming && entity instanceof EntityPlayerSP) {
            EntityPlayerSP player = (EntityPlayerSP) entity;
            boolean acting = mc.gameSettings.keyBindAttack.getIsKeyPressed()
                || mc.gameSettings.keyBindUseItem.getIsKeyPressed();

            if (acting && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.MISS
                && mc.objectMouseOver.hitVec != null) {
                lookAtTarget(player, mc.objectMouseOver.hitVec);
                turningLockTicks = Config.decoupledCameraTurningLockTicks;
            }
        }
    }

    /**
     * Called from Entity.setAngles mixin. Returns true to cancel vanilla rotation.
     * Ported from ShoulderSurfingCamera.turn().
     *
     * In 1.7.10, Entity.setAngles receives raw sensitivity-scaled deltas and applies * 0.15 internally.
     * We replicate that same scaling, matching the sign conventions:
     *   vanilla: rotationYaw += yaw * 0.15;  rotationPitch -= pitch * 0.15;
     */
    public static boolean onSetAngles(float yaw, float pitch) {
        if (!active) {
            return false;
        }

        // Match vanilla Entity.setAngles scaling
        float scaledYaw = yaw * 0.15f;
        float scaledPitch = pitch * 0.15f;

        if (freeLooking) {
            // Free look: accumulate into offsets, set prev=current for no interpolation lag
            // Matches modern turn() lines 406-412
            yawOffset = MathHelper.wrapAngleTo180_float(yawOffset + scaledYaw);
            pitchOffset = MathHelper.clamp_float(pitchOffset - scaledPitch, -90f, 90f);
            prevYawOffset = yawOffset;
            prevPitchOffset = pitchOffset;
            return true;
        }

        // Decoupled: update independent camera rotation
        // Matches modern turn() lines 416-417
        cameraYaw += scaledYaw;
        cameraPitch = MathHelper.clamp_float(cameraPitch - scaledPitch, -90f, 90f);

        // When aiming, immediately sync player rotation toward crosshair target.
        // Matches modern turn() lines 429-434 + lookAtCrosshairTargetInternal().
        // Uses parallax-corrected direction (player→hitVec) so arrows hit where the
        // crosshair points despite the camera's shoulder offset.
        if (aiming) {
            EntityLivingBase entity = Minecraft.getMinecraft().renderViewEntity;
            if (entity != null) {
                float[] aim = computeAimRotation(entity);
                entity.prevRotationYaw += MathHelper.wrapAngleTo180_float(aim[0] - entity.rotationYaw);
                entity.prevRotationPitch += aim[1] - entity.rotationPitch;
                entity.rotationYaw = aim[0];
                entity.rotationPitch = aim[1];
            }
        }

        return true;
    }

    /**
     * Called from EntityPlayerSP.updateEntityActionState RETURN mixin.
     * Rotates moveStrafing/moveForward to be camera-relative and turns player body toward movement.
     *
     * Ported directly from modern InputHandler.updateMovementInput().
     */
    public static void transformMovement(EntityPlayerSP player) {
        if (!active) {
            return;
        }

        // Aiming detection - when using a bow etc., sync player rotation to camera
        // so projectiles fire where the crosshair points.
        // Computed here (before arrow creation in the same tick) for correct timing.
        boolean wasAiming = aiming;
        aiming = computeAiming(player);

        if (!wasAiming && aiming) {
            // Aiming just started - set turning lock so body doesn't snap away on release
            turningLockTicks = Config.decoupledCameraTurningLockTicks;
        }

        if (aiming) {
            // Sync player rotation toward crosshair target (parallax-corrected).
            // Arrow/projectile creation uses player.rotationYaw/Pitch directly.
            float[] aim = computeAimRotation(player);
            player.rotationYaw = aim[0];
            player.rotationPitch = aim[1];
            // No movement input rotation needed - player yaw already matches aim direction
            return;
        }

        if (wasAiming && !aiming) {
            turningLockTicks = Config.decoupledCameraTurningLockTicks;
        }

        float strafe = player.moveStrafing;
        float forward = player.moveForward;
        boolean isMoving = strafe * strafe + forward * forward > 0f;
        boolean turningLocked = turningLockTicks > 0;

        // Smooth head pitch toward half camera pitch when moving
        // Matches modern: xRot = xRotO + degreesDifference(xRotO, cameraXRot * 0.5F) * 0.25F
        // Don't update prevRotationPitch, let vanilla's tick-start prev=current cycle handle it,
        // so the renderer interpolates smoothly between ticks instead of snapping.
        // Skip when turning is locked - lookAtTarget already set the pitch.
        if (isMoving && !turningLocked) {
            float targetPitch = cameraPitch * 0.5f;
            player.rotationPitch += degreesDifference(player.rotationPitch, targetPitch)
                * Config.decoupledCameraPlayerTurnSpeed;
        }

        if (freeLooking) {
            // Free look: rotate input so "forward" goes in the freeLookYaw direction
            // Matches modern: moveVector.rotateDegrees(degreesDifference(cameraEntity.getYRot(), freeLookYRot))
            float angle = degreesDifference(player.rotationYaw, freeLookYaw);
            float[] rotated = rotateDegrees(strafe, forward, angle);
            player.moveStrafing = rotated[0];
            player.moveForward = rotated[1];
            return;
        }

        if (!isMoving) {
            return;
        }

        // When turning is locked (player facing interaction target), skip body yaw rotation
        // but still rotate movement input to be camera-relative (step 4)
        if (!turningLocked) {
            float yRot = player.rotationYaw;

            // Step 1: Rotate raw input by camera yaw to get world-space movement direction
            // Matches modern: Vec2f rotated = moveVector.rotateDegrees(cameraYRot)
            float[] worldMove = rotateDegrees(strafe, forward, cameraYaw);

            // Step 2: Calculate target player yaw from world-space movement
            // Matches modern: yRot = atan2(-rotated.x(), rotated.y()) * RAD_TO_DEG
            float targetYaw = (float) (Math.atan2(-worldMove[0], worldMove[1]) * (180.0 / Math.PI));

            // Step 3: Smooth player yaw toward target
            // Matches modern: yRot = yRotO + degreesDifference(yRotO, yRot) * 0.25F
            float newYaw = yRot + degreesDifference(yRot, targetYaw) * Config.decoupledCameraPlayerTurnSpeed;

            // Update player rotation with proper prev tracking to avoid jitter
            if (player.ridingEntity == null) {
                player.prevRotationYaw += newYaw - player.rotationYaw;
                player.rotationYaw = newYaw;
            }
        }

        // Step 4: Rotate raw input by difference between (new) player yaw and camera yaw
        // Matches modern: moveVector = moveVector.rotateDegrees(degreesDifference(yRot, camera.getYRot()))
        float angle = degreesDifference(player.rotationYaw, cameraYaw);
        float[] result = rotateDegrees(strafe, forward, angle);
        player.moveStrafing = result[0];
        player.moveForward = result[1];
    }

    /**
     * Returns interpolated camera yaw including free look offset.
     */
    public static float getCameraYaw(float partialTicks) {
        float baseYaw = prevCameraYaw + (cameraYaw - prevCameraYaw) * partialTicks;
        float offsetYaw = prevYawOffset + (yawOffset - prevYawOffset) * partialTicks;
        return baseYaw + offsetYaw;
    }

    /**
     * Returns interpolated camera pitch including free look offset.
     */
    public static float getCameraPitch(float partialTicks) {
        float basePitch = prevCameraPitch + (cameraPitch - prevCameraPitch) * partialTicks;
        float offsetPitch = prevPitchOffset + (pitchOffset - prevPitchOffset) * partialTicks;
        return MathHelper.clamp_float(basePitch + offsetPitch, -90f, 90f);
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isFreeLooking() {
        return active && freeLooking;
    }

    public static boolean isTurningLocked() {
        return active && turningLockTicks > 0;
    }

    public static boolean isAiming() {
        return active && aiming;
    }

    /**
     * Raw (non-interpolated) effective camera yaw for entity rotation swapping.
     */
    public static float getEffectiveYaw() {
        return cameraYaw + yawOffset;
    }

    /**
     * Raw (non-interpolated) effective previous camera yaw for entity rotation swapping.
     */
    public static float getEffectivePrevYaw() {
        return prevCameraYaw + prevYawOffset;
    }

    /**
     * Raw (non-interpolated) effective camera pitch for entity rotation swapping.
     */
    public static float getEffectivePitch() {
        return MathHelper.clamp_float(cameraPitch + pitchOffset, -90f, 90f);
    }

    /**
     * Raw (non-interpolated) effective previous camera pitch for entity rotation swapping.
     */
    public static float getEffectivePrevPitch() {
        return MathHelper.clamp_float(prevCameraPitch + prevPitchOffset, -90f, 90f);
    }

    /**
     * Computes parallax-corrected aim rotation for the player.
     * If objectMouseOver has a valid hit, returns yaw/pitch from the player's eye
     * to the hit point (so arrows converge on the crosshair target despite camera offset).
     * Falls back to camera direction for sky shots (no hit).
     * Ported from modern ShoulderSurfingImpl.lookAtCrosshairTargetInternal().
     */
    private static float[] computeAimRotation(EntityLivingBase entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.MISS
            && mc.objectMouseOver.hitVec != null) {
            double dx = mc.objectMouseOver.hitVec.xCoord - entity.posX;
            double dy = mc.objectMouseOver.hitVec.yCoord - (entity.posY + entity.getEyeHeight());
            double dz = mc.objectMouseOver.hitVec.zCoord - entity.posZ;
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
            float pitch = MathHelper.clamp_float(
                (float) (-(Math.atan2(dy, horizontalDist) * (180.0 / Math.PI))), -90.0f, 90.0f);
            return new float[] { yaw, pitch };
        }
        // No hit (sky shot) - fall back to camera direction
        return new float[] {
            cameraYaw + yawOffset,
            MathHelper.clamp_float(cameraPitch + pitchOffset, -90f, 90f)
        };
    }

    /**
     * Checks if the player is currently using an aiming item (bow, etc.).
     * Matches by EnumAction name (configurable) or by item registry name override.
     */
    private static boolean computeAiming(EntityPlayerSP player) {
        if (!player.isUsingItem()) return false;
        ItemStack itemInUse = player.getItemInUse();
        if (itemInUse == null) return false;

        // Check by EnumAction name
        String actionName = itemInUse.getItemUseAction().name();
        for (String action : Config.decoupledCameraAimingActions) {
            if (actionName.equalsIgnoreCase(action)) {
                return true;
            }
        }

        // Check by item registry name override
        String registryName = Item.itemRegistry.getNameForObject(itemInUse.getItem());
        if (registryName != null) {
            for (String item : Config.decoupledCameraAimingItems) {
                if (registryName.equals(item)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Rotates the player to look at the given target position.
     * Ported from modern EntityHelper.lookAtTarget().
     * Does NOT update prev rotation, so the renderer interpolates smoothly.
     */
    private static void lookAtTarget(EntityPlayerSP player, Vec3 hitVec) {
        double dx = hitVec.xCoord - player.posX;
        double dy = hitVec.yCoord - (player.posY + player.getEyeHeight());
        double dz = hitVec.zCoord - player.posZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        player.rotationYaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        player.rotationPitch = MathHelper.clamp_float(
            (float) (-(Math.atan2(dy, horizontalDist) * (180.0 / Math.PI))), -90.0f, 90.0f);
    }

    private static void resetState(EntityLivingBase entity) {
        cameraYaw = entity.rotationYaw;
        cameraPitch = entity.rotationPitch;
        prevCameraYaw = cameraYaw;
        prevCameraPitch = cameraPitch;
        yawOffset = 0f;
        pitchOffset = 0f;
        prevYawOffset = 0f;
        prevPitchOffset = 0f;
        freeLookYaw = cameraYaw;
        freeLooking = false;
        turningLockTicks = 0;
        aiming = false;
    }

    /**
     * Standard 2D rotation: (x', y') = (x*cos - y*sin, x*sin + y*cos)
     * Matches modern Vec2f.rotateDegrees().
     */
    private static float[] rotateDegrees(float x, float y, float degrees) {
        float rad = (float) Math.toRadians(degrees);
        float cos = MathHelper.cos(rad);
        float sin = MathHelper.sin(rad);
        return new float[] { x * cos - y * sin, x * sin + y * cos };
    }

    /**
     * Matches modern Mth.degreesDifference(from, to) = wrapDegrees(to - from).
     */
    private static float degreesDifference(float from, float to) {
        return MathHelper.wrapAngleTo180_float(to - from);
    }
}
