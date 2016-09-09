/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2016
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.blocks.machine.manipulator;

import cofh.api.energy.IEnergyProvider;
import mods.railcraft.api.carts.CartToolsAPI;
import mods.railcraft.common.blocks.machine.IEnumMachine;
import mods.railcraft.common.carts.EntityCartRF;
import mods.railcraft.common.gui.EnumGui;
import mods.railcraft.common.gui.GuiHandler;
import mods.railcraft.common.plugins.rf.RedstoneFluxPlugin;
import mods.railcraft.common.util.misc.Game;
import mods.railcraft.common.util.network.IGuiReturnHandler;
import mods.railcraft.common.util.network.RailcraftInputStream;
import mods.railcraft.common.util.network.RailcraftOutputStream;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import javax.annotation.Nullable;
import java.io.IOException;

public class TileRFUnloader extends TileRFManipulator implements IEnergyProvider, IGuiReturnHandler {
    private static final int AMOUNT_TO_PUSH_TO_TILES = 2000;
    private boolean waitTillEmpty = true;

    @Override
    public IEnumMachine getMachineType() {
        return ManipulatorVariant.RF_UNLOADER;
    }

    @Override
    public boolean openGui(EntityPlayer player) {
        GuiHandler.openGui(EnumGui.UNLOADER_RF, player, worldObj, getX(), getY(), getZ());
        return true;
    }

    @Override
    public void update() {
        super.update();
        if (Game.isClient(worldObj))
            return;

        RedstoneFluxPlugin.pushToTiles(this, tileCache, AMOUNT_TO_PUSH_TO_TILES);
    }

    @Override
    protected boolean processCart() {
        boolean transferred = false;

        EntityMinecart cart = CartToolsAPI.getMinecartOnSide(worldObj, getPos(), 0.1f, direction);

        if (cart != currentCart) {
            setPowered(false);
            currentCart = cart;
            cartWasSent();
        }

        if (cart == null)
            return false;

        if (!canHandleCart(cart)) {
            sendCart(cart);
            return false;
        }

        if (isPaused())
            return false;

        EntityCartRF rfCart = (EntityCartRF) cart;

        if (amountRF < getMaxRF() && rfCart.getRF() > 0) {
            int request = TRANSFER_RATE;

            int room = getMaxRF() - getRF();
            if (room < request) {
                request = room;
            }

            double extracted = rfCart.removeRF(request);
            amountRF += extracted;
            transferred = extracted > 0;
        }

        if (!transferred && !isPowered() && shouldSendCart(cart))
            sendCart(cart);
        return transferred;
    }

    @Override
    protected boolean shouldSendCart(EntityMinecart cart) {
        if (!(cart instanceof EntityCartRF))
            return true;
        EntityCartRF rfCart = (EntityCartRF) cart;
        if (!waitTillEmpty)
            return true;
        else if (rfCart.getRF() <= 0)
            return true;
        return false;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("WaitTillEmpty", waitTillEmpty());
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        setWaitTillEmpty(nbttagcompound.getBoolean("WaitTillEmpty"));
    }

    @Override
    public void writePacketData(RailcraftOutputStream data) throws IOException {
        super.writePacketData(data);

        data.writeBoolean(waitTillEmpty);
    }

    @Override
    public void readPacketData(RailcraftInputStream data) throws IOException {
        super.readPacketData(data);

        waitTillEmpty = data.readBoolean();
    }

    @Override
    public void writeGuiData(RailcraftOutputStream data) throws IOException {
        data.writeBoolean(waitTillEmpty);
    }

    @Override
    public void readGuiData(RailcraftInputStream data, @Nullable EntityPlayer sender) throws IOException {
        waitTillEmpty = data.readBoolean();
    }

    public boolean waitTillEmpty() {
        return waitTillEmpty;
    }

    public void setWaitTillEmpty(boolean wait) {
        waitTillEmpty = wait;
    }

    @Override
    public int extractEnergy(EnumFacing from, int maxExtract, boolean simulate) {
        return removeRF(maxExtract, simulate);
    }

    @Override
    public int getEnergyStored(EnumFacing from) {
        return getRF();
    }

    @Override
    public int getMaxEnergyStored(EnumFacing from) {
        return getMaxRF();
    }

    @Override
    public boolean canConnectEnergy(EnumFacing from) {
        return true;
    }
}