/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2017
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.client.render.carts;

import mods.railcraft.api.carts.locomotive.ICartRenderer;
import mods.railcraft.api.carts.locomotive.LocomotiveRenderType;
import mods.railcraft.client.emblems.EmblemToolsClient;
import mods.railcraft.common.carts.EntityLocomotive;
import mods.railcraft.common.plugins.color.EnumColor;
import mods.railcraft.common.plugins.misc.SeasonPlugin;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StringUtils;

/**
 * @author CovertJaguar <http://www.railcraft.info>
 */
public class LocomotiveRenderer extends CartModelRenderer {

    public static final LocomotiveRenderer INSTANCE = new LocomotiveRenderer();

    @Override
    public boolean render(ICartRenderer renderer, EntityMinecart cart, float light, float time) {
        EntityLocomotive loco = (EntityLocomotive) cart;

        boolean ghost = SeasonPlugin.isGhostTrain(cart);
        EnumColor pColor = ghost ? EnumColor.SILVER : EnumColor.fromDye(loco.getPrimaryColor());
        EnumColor sColor = ghost ? EnumColor.SILVER : EnumColor.fromDye(loco.getSecondaryColor());

        int primaryColor = pColor.getHexColor();
        int secondaryColor = sColor.getHexColor();

        String emblem = loco.getEmblem();
        ResourceLocation emblemTexture = null;
        if (!StringUtils.isNullOrEmpty(emblem) && EmblemToolsClient.packageManager != null)
            emblemTexture = EmblemToolsClient.packageManager.getEmblemTextureLocation(emblem);

        LocomotiveRenderType renderType = loco.getRenderType();
        mods.railcraft.api.carts.locomotive.LocomotiveModelRenderer locoRenderer = renderType.getRenderer(loco.getModel());

        locoRenderer.renderLocomotive(renderer, loco, primaryColor, secondaryColor, emblemTexture, light, time);
        return false;
    }

}
