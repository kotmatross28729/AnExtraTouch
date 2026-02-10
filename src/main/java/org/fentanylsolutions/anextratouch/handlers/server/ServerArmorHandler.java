package org.fentanylsolutions.anextratouch.handlers.server;

import java.util.Collections;
import java.util.HashSet;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;

import org.fentanylsolutions.anextratouch.Config;
import org.fentanylsolutions.anextratouch.network.NetworkHandler;
import org.fentanylsolutions.anextratouch.network.message.MessageArmorStep;

public class ServerArmorHandler {

    private static final float LAND_DISTANCE_MIN = 0.9f;

    private static HashSet<String> whitelistNames;

    public static void init() {
        whitelistNames = new HashSet<>();
        Collections.addAll(whitelistNames, Config.armorSoundEntityWhitelist);
    }

    private static boolean isWhitelisted(Entity entity) {
        if (entity instanceof EntityPlayerMP) {
            return whitelistNames.contains("Player");
        }
        String name = EntityList.getEntityString(entity);
        return name != null && whitelistNames.contains(name);
    }

    public static void onEntityStep(Entity entity) {
        if (!Config.armorSoundsEnabled) {
            return;
        }
        if (entity.worldObj.isRemote) {
            return;
        }
        if (!(entity instanceof EntityLivingBase)) {
            return;
        }
        if (!isWhitelisted(entity)) {
            return;
        }

        broadcastArmorStep(entity);
    }

    public static void onEntityLand(Entity entity, float distance) {
        if (!Config.armorSoundsEnabled) {
            return;
        }
        if (entity.worldObj.isRemote) {
            return;
        }
        if (distance < LAND_DISTANCE_MIN) {
            return;
        }
        if (!(entity instanceof EntityLivingBase)) {
            return;
        }
        if (!isWhitelisted(entity)) {
            return;
        }

        broadcastArmorStep(entity);
    }

    private static void broadcastArmorStep(Entity entity) {
        if (!(entity.worldObj instanceof WorldServer)) {
            return;
        }

        WorldServer world = (WorldServer) entity.worldObj;
        MessageArmorStep msg = new MessageArmorStep(entity.getEntityId());

        for (Object obj : world.playerEntities) {
            EntityPlayerMP player = (EntityPlayerMP) obj;

            // Skip the stepping entity itself (if it's a player) to prevent double sounds
            if (player == entity) {
                continue;
            }

            double dx = player.posX - entity.posX;
            double dz = player.posZ - entity.posZ;
            if (dx * dx + dz * dz <= 48.0 * 48.0) {
                NetworkHandler.channel.sendTo(msg, player);
            }
        }
    }
}
