/*------------------------------------------------------------------------------
 Copyright (c) CovertJaguar, 2011-2017
 http://railcraft.info

 This code is the property of CovertJaguar
 and may only be used with explicit written
 permission unless otherwise specified on the
 license page at http://railcraft.info/wiki/info:license.
 -----------------------------------------------------------------------------*/
package mods.railcraft.common.carts;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import mods.railcraft.api.carts.*;
import mods.railcraft.api.carts.locomotive.LocomotiveRenderType;
import mods.railcraft.common.carts.EntityLocomotive.LocoLockButtonState;
import mods.railcraft.common.core.RailcraftConfig;
import mods.railcraft.common.core.RailcraftConstants;
import mods.railcraft.common.gui.buttons.ButtonTextureSet;
import mods.railcraft.common.gui.buttons.IButtonTextureSet;
import mods.railcraft.common.gui.buttons.IMultiButtonState;
import mods.railcraft.common.gui.buttons.MultiButtonController;
import mods.railcraft.common.gui.tooltips.ToolTip;
import mods.railcraft.common.items.ItemTicket;
import mods.railcraft.common.items.ItemWhistleTuner;
import mods.railcraft.common.items.RailcraftItems;
import mods.railcraft.common.plugins.color.EnumColor;
import mods.railcraft.common.plugins.forge.DataManagerPlugin;
import mods.railcraft.common.plugins.forge.NBTPlugin;
import mods.railcraft.common.plugins.forge.PlayerPlugin;
import mods.railcraft.common.plugins.misc.SeasonPlugin;
import mods.railcraft.common.util.effects.EffectManager;
import mods.railcraft.common.util.inventory.InvTools;
import mods.railcraft.common.util.misc.*;
import mods.railcraft.common.util.network.IGuiReturnHandler;
import mods.railcraft.common.util.network.PacketBuilder;
import mods.railcraft.common.util.network.RailcraftInputStream;
import mods.railcraft.common.util.network.RailcraftOutputStream;
import mods.railcraft.common.util.sounds.SoundHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.util.EnumHand;
import net.minecraft.util.IStringSerializable;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Locale;

/**
 * @author CovertJaguar <http://www.railcraft.info>
 */
public abstract class EntityLocomotive extends CartBaseContainer implements IDirectionalCart, IGuiReturnHandler,
        ILinkableCart, IMinecart, ISecureObject<LocoLockButtonState>, IPaintedCart, IRoutableCart, IEntityAdditionalSpawnData {
    private static final DataParameter<Boolean> HAS_FUEL = DataManagerPlugin.create(DataSerializers.BOOLEAN);
    private static final DataParameter<Byte> LOCOMOTIVE_MODE = DataManagerPlugin.create(DataSerializers.BYTE);
    private static final DataParameter<Byte> LOCOMOTIVE_SPEED = DataManagerPlugin.create(DataSerializers.BYTE);
    private static final DataParameter<Boolean> REVERSE = DataManagerPlugin.create(DataSerializers.BOOLEAN);
    private static final DataParameter<EnumColor> PRIMARY_COLOR = DataManagerPlugin.create(DataManagerPlugin.ENUM_COLOR);
    private static final DataParameter<EnumColor> SECONDARY_COLOR = DataManagerPlugin.create(DataManagerPlugin.ENUM_COLOR);
    private static final DataParameter<String> EMBLEM = DataManagerPlugin.create(DataSerializers.STRING);
    private static final DataParameter<String> DEST = DataManagerPlugin.create(DataSerializers.STRING);
    private static final double DRAG_FACTOR = 0.9;
    private static final float HS_FORCE_BONUS = 3.5F;
    private static final byte FUEL_USE_INTERVAL = 8;
    private static final byte KNOCKBACK = 1;
    private static final int WHISTLE_INTERVAL = 256;
    private static final int WHISTLE_DELAY = 160;
    private static final int WHISTLE_CHANCE = 4;
    private final MultiButtonController<LocoLockButtonState> lockController = MultiButtonController.create(0, LocoLockButtonState.VALUES);
    public LocoMode clientMode = LocoMode.SHUTDOWN;
    public LocoSpeed clientSpeed = LocoSpeed.MAX;
    public boolean clientCanLock;
    protected float renderYaw;
    private String model = "";
    private int fuel;
    private int update = MiscTools.RANDOM.nextInt();
    private int whistleDelay;
    private int tempIdle;
    private float whistlePitch = getNewWhistlePitch();
    private boolean preReverse;

    private EnumSet<LocoMode> allowedModes = EnumSet.allOf(LocoMode.class);
    private LocoSpeed maxReverseSpeed = LocoSpeed.NORMAL;

    protected EntityLocomotive(World world) {
        super(world);
    }

    protected EntityLocomotive(World world, double x, double y, double z) {
        super(world, x, y, z);
    }

    @Override
    protected void entityInit() {
        super.entityInit();

        setSize(0.98F, 1F);

        dataManager.register(HAS_FUEL, false);
        dataManager.register(PRIMARY_COLOR, EnumColor.WHITE);
        dataManager.register(SECONDARY_COLOR, EnumColor.WHITE);
        dataManager.register(LOCOMOTIVE_MODE, (byte) LocoMode.SHUTDOWN.ordinal());
        dataManager.register(LOCOMOTIVE_SPEED, (byte) LocoSpeed.NORMAL.ordinal());
        dataManager.register(REVERSE, false);
        dataManager.register(EMBLEM, "");
        dataManager.register(DEST, "");
    }

    @Override
    public void initEntityFromItem(ItemStack item) {
        NBTTagCompound nbt = item.getTagCompound();
        if (nbt == null)
            return;

        setPrimaryColor(ItemLocomotive.getPrimaryColor(item).getDye());
        setSecondaryColor(ItemLocomotive.getSecondaryColor(item).getDye());
        if (nbt.hasKey("whistlePitch"))
            whistlePitch = nbt.getFloat("whistlePitch");
        if (nbt.hasKey("owner")) {
            CartToolsAPI.setCartOwner(this, PlayerPlugin.readOwnerFromNBT(nbt));
            setSecurityState(LocoLockButtonState.LOCKED);
        }
        if (nbt.hasKey("security"))
            setSecurityState(LocoLockButtonState.VALUES[nbt.getByte("security")]);
        if (nbt.hasKey("emblem"))
            setEmblem(nbt.getString("emblem"));
        if (nbt.hasKey("model"))
            model = nbt.getString("model");
    }

    @Override
    public boolean doesCartMatchFilter(ItemStack stack, EntityMinecart cart) {
        return RailcraftCarts.getCartType(stack) == getCartType();
    }

    @Override
    public MultiButtonController<LocoLockButtonState> getLockController() {
        return lockController;
    }

    @Override
    public GameProfile getOwner() {
        return CartToolsAPI.getCartOwner(this);
    }

    private float getNewWhistlePitch() {
        return 1f + (float) rand.nextGaussian() * 0.2f;
    }

    @Override
    public ItemStack createCartItem(EntityMinecart cart) {
        ItemStack item = getCartItemBase();
        if (isSecure() && CartToolsAPI.doesCartHaveOwner(this))
            ItemLocomotive.setOwnerData(item, CartToolsAPI.getCartOwner(this));
        ItemLocomotive.setItemColorData(item, getPrimaryColor(), getSecondaryColor());
        ItemLocomotive.setItemWhistleData(item, whistlePitch);
        ItemLocomotive.setModel(item, getModel());
        ItemLocomotive.setEmblem(item, getEmblem());
        if (hasCustomName())
            item.setStackDisplayName(getCustomNameTag());
        return item;
    }

    @Nullable
    protected abstract ItemStack getCartItemBase();

    @Override
    public boolean doInteract(EntityPlayer player, @Nullable ItemStack stack, @Nullable EnumHand hand) {
        if (Game.isHost(worldObj)) {
            if (!InvTools.isEmpty(stack) && stack.getItem() instanceof ItemWhistleTuner) {
                if (whistleDelay <= 0) {
                    whistlePitch = getNewWhistlePitch();
                    whistle();
                    stack.damageItem(1, player);
                }
                return true;
            }
            if (!isPrivate() || PlayerPlugin.isOwnerOrOp(getOwner(), player.getGameProfile()))
                openGui(player);
        }
        return true;
    }

    protected abstract void openGui(EntityPlayer player);

    @Override
    public boolean isSecure() {
        return getSecurityState() == LocoLockButtonState.LOCKED || isPrivate();
    }

    public boolean isPrivate() {
        return getSecurityState() == LocoLockButtonState.PRIVATE;
    }

    public boolean canControl(GameProfile user) {
        return !isPrivate() || PlayerPlugin.isOwnerOrOp(getOwner(), user);
    }

    public LocoLockButtonState getSecurityState() {
        return lockController.getButtonState();
    }

    public void setSecurityState(LocoLockButtonState state) {
        lockController.setCurrentState(state);
    }

    public String getEmblem() {
        return dataManager.get(EMBLEM);
    }

    public void setEmblem(String emblem) {
        if (!getEmblem().equals(emblem))
            dataManager.set(EMBLEM, emblem);
    }

    @Nullable
    public ItemStack getDestItem() {
        return getTicketInventory().getStackInSlot(1);
    }

    @Override
    public String getDestination() {
        return StringUtils.defaultIfBlank(dataManager.get(DEST), "");
    }

    public void setDestString(String dest) {
        if (!StringUtils.equals(getDestination(), dest))
            dataManager.set(DEST, dest);
    }

    public LocoMode getMode() {
        return DataManagerPlugin.readEnum(dataManager, LOCOMOTIVE_MODE, LocoMode.VALUES);
    }

    public void setMode(LocoMode mode) {
        if (!allowedModes.contains(mode))
            mode = LocoMode.SHUTDOWN;
        if (getMode() != mode)
            DataManagerPlugin.writeEnum(dataManager, LOCOMOTIVE_MODE, mode);
    }

    public EnumSet<LocoMode> getAllowedModes() {
        return allowedModes;
    }

    protected final void setAllowedModes(EnumSet<LocoMode> allowedModes) {
        this.allowedModes = allowedModes;
    }

    public LocoSpeed getSpeed() {
        return DataManagerPlugin.readEnum(dataManager, LOCOMOTIVE_SPEED, LocoSpeed.VALUES);
    }

    public void setSpeed(LocoSpeed speed) {
        if (getSpeed() != speed)
            DataManagerPlugin.writeEnum(dataManager, LOCOMOTIVE_SPEED, speed);
    }

    public LocoSpeed getMaxReverseSpeed() {
        return maxReverseSpeed;
    }

    protected final void setMaxReverseSpeed(LocoSpeed speed) {
        this.maxReverseSpeed = speed;
    }

    public void increaseSpeed() {
        LocoSpeed speed = getSpeed();
        speed = speed.shiftUp();
        setSpeed(speed);
    }

    public void decreaseSpeed() {
        LocoSpeed speed = getSpeed();
        speed = speed.shiftDown();
        setSpeed(speed);
    }

    public boolean hasFuel() {
        return dataManager.get(HAS_FUEL);
    }

    public void setHasFuel(boolean powered) {
        dataManager.set(HAS_FUEL, powered);
    }

    public boolean isReverse() {
        return dataManager.get(REVERSE);
    }

    public void setReverse(boolean reverse) {
        dataManager.set(REVERSE, reverse);
    }

    public boolean isRunning() {
        return fuel > 0 && getMode() == LocoMode.RUNNING && !(isIdle() || isShutdown());
    }

    public boolean isIdle() {
        return !isShutdown() && (tempIdle > 0 || getMode() == LocoMode.IDLE || Train.getTrain(this).isIdle());
    }

    public boolean isShutdown() {
        return getMode() == LocoMode.SHUTDOWN || Train.getTrain(this).isStopped();
    }

    public void forceIdle(int ticks) {
        tempIdle = Math.max(tempIdle, ticks);
    }

    @Override
    public void reverse() {
        rotationYaw += 180;
        motionX = -motionX;
        motionZ = -motionZ;
    }

    @Override
    public void setRenderYaw(float yaw) {
        renderYaw = yaw;
    }

    public abstract SoundEvent getWhistle();

    public final void whistle() {
        if (whistleDelay <= 0) {
            PacketBuilder.instance().sendMovingSoundPacket(getWhistle(), getSoundCategory(), this, SoundHelper.MovingSoundType.CART);
            whistleDelay = WHISTLE_DELAY;
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        update++;

        if (Game.isClient(worldObj)) {
            if (SeasonPlugin.isPolarExpress(this) && (!MathTools.nearZero(motionX) || !MathTools.nearZero(motionZ)))
                EffectManager.instance.snowEffect(worldObj, this, getEntityBoundingBox().minY - posY);
            return;
        }

//        {
//            boolean reverse = ObfuscationReflectionHelper.getPrivateValue(EntityMinecart.class, this, IS_REVERSED_INDEX);
//            if (reverse != preReverse || prevRotationYaw != rotationYaw) {
//                preReverse = reverse;
//                Game.log(Level.INFO, "tick={0}, reverse={1}, yaw={2}", worldObj.getTotalWorldTime(), reverse, rotationYaw);
//            }
//        }

        processTicket();
        updateFuel();

//        if (getEntityData().getBoolean("HighSpeed"))
//            System.out.println(CartTools.getCartSpeedUncapped(this));
        if (whistleDelay > 0)
            whistleDelay--;

        if (tempIdle > 0)
            tempIdle--;

        if (update % WHISTLE_INTERVAL == 0 && isRunning() && rand.nextInt(WHISTLE_CHANCE) == 0)
            whistle();
    }

    @Override
    public boolean setDestination(@Nullable ItemStack ticket) {
        if (ticket != null && ticket.getItem() instanceof ItemTicket) {
            if (isSecure() && !ItemTicket.matchesOwnerOrOp(ticket, CartToolsAPI.getCartOwner(this)))
                return false;
            String dest = ItemTicket.getDestination(ticket);
            if (!dest.equals(getDestination())) {
                setDestString(dest);
                getTicketInventory().setInventorySlotContents(1, ItemTicket.copyTicket(ticket));
                return true;
            }
        }
        return false;
    }

    protected abstract IInventory getTicketInventory();

    private void processTicket() {
        IInventory invTicket = getTicketInventory();
        ItemStack stack = invTicket.getStackInSlot(0);
        if (stack != null)
            if (stack.getItem() instanceof ItemTicket) {
                if (setDestination(stack))
                    invTicket.setInventorySlotContents(0, InvTools.depleteItem(stack));
            } else
                invTicket.setInventorySlotContents(0, null);
    }

    @Override
    protected void applyDrag() {
        motionX *= getDrag();
        motionY *= 0.0D;
        motionZ *= getDrag();

        if (isReverse() && getSpeed().getLevel() > getMaxReverseSpeed().getLevel()) {
            setSpeed(getMaxReverseSpeed());
        }

        LocoSpeed speed = getSpeed();
        if (isRunning()) {
            float force = RailcraftConfig.locomotiveHorsepower() * 0.006F;
            if (isReverse())
                force = -force;
            switch (speed) {
                case MAX:
                    boolean highSpeed = CartTools.isTravellingHighSpeed(this);
                    if (highSpeed)
                        force *= HS_FORCE_BONUS;
                    break;
            }
            double yaw = rotationYaw * Math.PI / 180D;
            motionX += Math.cos(yaw) * force;
            motionZ += Math.sin(yaw) * force;
        }

        if (speed != LocoSpeed.MAX) {
            float limit = 0.4f;
            switch (speed) {
                case SLOWEST:
                    limit = 0.1f;
                    break;
                case SLOWER:
                    limit = 0.2f;
                    break;
                case NORMAL:
                    limit = 0.3f;
                    break;
            }
            motionX = Math.copySign(Math.min(Math.abs(motionX), limit), motionX);
            motionZ = Math.copySign(Math.min(Math.abs(motionZ), limit), motionZ);
        }
    }

    private int getFuelUse() {
        if (isRunning()) {
            LocoSpeed speed = getSpeed();
            switch (speed) {
                case SLOWEST:
                    return 2;
                case SLOWER:
                    return 4;
                case NORMAL:
                    return 6;
                default:
                    return 8;
            }
        } else if (isIdle())
            return getIdleFuelUse();
        return 0;
    }

    protected int getIdleFuelUse() {
        return 1;
    }

    protected void updateFuel() {
        if (update % FUEL_USE_INTERVAL == 0 && fuel > 0) {
            fuel -= getFuelUse();
            if (fuel < 0)
                fuel = 0;
        }
        while (fuel <= FUEL_USE_INTERVAL && !isShutdown()) {
            int newFuel = getMoreGoJuice();
            if (newFuel <= 0)
                break;
            fuel += newFuel;
        }
        setHasFuel(fuel > 0);
    }

    private boolean cartVelocityIsGreaterThan(@SuppressWarnings("SameParameterValue") float vel) {
        return Math.abs(motionX) > vel || Math.abs(motionZ) > vel;
    }

    public int getDamageToRoadKill(EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) {
            ItemStack pants = entity.getItemStackFromSlot(EntityEquipmentSlot.LEGS);
            if (pants != null && RailcraftItems.OVERALLS.isInstance(pants)) {
                entity.setItemStackToSlot(EntityEquipmentSlot.LEGS, InvTools.damageItem(pants, 5));
                return 4;
            }
        }
        return 25;
    }

    @Override
    public void applyEntityCollision(Entity entity) {
        if (Game.isHost(worldObj)) {
            if (!entity.isEntityAlive())
                return;
            if (!Train.getTrain(this).isPassenger(entity) && (cartVelocityIsGreaterThan(0.2f) || CartTools.isTravellingHighSpeed(this)) && MiscTools.isKillableEntity(entity)) {
                EntityLivingBase living = (EntityLivingBase) entity;
                if (RailcraftConfig.locomotiveDamageMobs())
                    living.attackEntityFrom(RailcraftDamageSource.TRAIN, getDamageToRoadKill(living));
                if (living.getHealth() > 0) {
                    float yaw = (rotationYaw - 90) * (float) Math.PI / 180.0F;
                    living.addVelocity(-MathHelper.sin(yaw) * KNOCKBACK * 0.5F, 0.2D, MathHelper.cos(yaw) * KNOCKBACK * 0.5F);
                }
                return;
            }
            if (collidedWithOtherLocomotive(entity)) {
                EntityLocomotive otherLoco = (EntityLocomotive) entity;
                explode();
                if (otherLoco.isEntityAlive())
                    otherLoco.explode();
                return;
            }
        }
        super.applyEntityCollision(entity);
    }

    @SuppressWarnings("SimplifiableIfStatement")
    private boolean collidedWithOtherLocomotive(Entity entity) {
        if (!(entity instanceof EntityLocomotive))
            return false;
        EntityLocomotive otherLoco = (EntityLocomotive) entity;
        if (getUniqueID() == entity.getUniqueID())
            return false;
        if (Train.areInSameTrain(this, otherLoco))
            return false;
        return cartVelocityIsGreaterThan(0.2f) && otherLoco.cartVelocityIsGreaterThan(0.2f)
                && (Math.abs(motionX - entity.motionX) > 0.3f || Math.abs(motionZ - entity.motionZ) > 0.3f);
    }

    @Override
    public void killAndDrop(EntityMinecart cart) {
        getTicketInventory().setInventorySlotContents(1, null);
        super.killAndDrop(cart);
    }

    @Override
    public void setDead() {
        getTicketInventory().setInventorySlotContents(1, null);
        super.setDead();
    }

    public void explode() {
        CartTools.explodeCart(this);
        setDead();
    }

    public abstract int getMoreGoJuice();

    public double getDrag() {
        return DRAG_FACTOR;
    }

    @Override
    public void writeEntityToNBT(NBTTagCompound data) {
        super.writeEntityToNBT(data);

        Boolean isInReverse = ObfuscationReflectionHelper.getPrivateValue(EntityMinecart.class, this, RailcraftConstants.IS_REVERSED_VARIABLE_INDEX);

        data.setBoolean("isInReverse", isInReverse);

        data.setString("model", model);

        data.setString("emblem", getEmblem());

        data.setString("dest", StringUtils.defaultIfBlank(getDestination(), ""));

        data.setByte("locoMode", (byte) getMode().ordinal());
        data.setByte("locoSpeed", (byte) getSpeed().ordinal());

        EnumColor.fromDye(getPrimaryColor()).writeToNBT(data, "primaryColor");
        EnumColor.fromDye(getSecondaryColor()).writeToNBT(data, "secondaryColor");

        data.setFloat("whistlePitch", whistlePitch);

        data.setInteger("fuel", fuel);

        lockController.writeToNBT(data, "lock");
    }

    @Override
    public void readEntityFromNBT(NBTTagCompound data) {
        super.readEntityFromNBT(data);

        ObfuscationReflectionHelper.setPrivateValue(EntityMinecart.class, this, data.getBoolean("isInReverse"), RailcraftConstants.IS_REVERSED_VARIABLE_INDEX);

        model = data.getString("model");

        setEmblem(data.getString("emblem"));

        setDestString(data.getString("dest"));

        setMode(LocoMode.VALUES[data.getByte("locoMode")]);
        setSpeed(NBTPlugin.readEnumOrdinal(data, "locoSpeed", LocoSpeed.VALUES, LocoSpeed.NORMAL));

        setPrimaryColor(EnumColor.readFromNBT(data, "primaryColor").getDye());
        setSecondaryColor(EnumColor.readFromNBT(data, "secondaryColor").getDye());

        whistlePitch = data.getFloat("whistlePitch");

        fuel = data.getInteger("fuel");

        lockController.readFromNBT(data, "lock");
    }

    @Override
    public void writeGuiData(@Nonnull RailcraftOutputStream data) throws IOException {
        data.writeByte(clientMode.ordinal());
        data.writeByte(clientSpeed.ordinal());
        data.writeByte(lockController.getCurrentState());
        data.writeBoolean(isReverse());
    }

    @Override
    public void readGuiData(@Nonnull RailcraftInputStream data, EntityPlayer sender) throws IOException {
        setMode(LocoMode.VALUES[data.readByte()]);
        setSpeed(LocoSpeed.VALUES[data.readByte()]);
        byte lock = data.readByte();
        if (PlayerPlugin.isOwnerOrOp(getOwner(), sender.getGameProfile()))
            lockController.setCurrentState(lock);
        setReverse(data.readBoolean());
    }

    @Override
    public void writeSpawnData(ByteBuf data) {
        try {
            DataOutputStream byteStream = new DataOutputStream(new ByteBufOutputStream(data));
            byteStream.writeUTF(hasCustomName() ? getName() : "");
            byteStream.writeUTF(model);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void readSpawnData(ByteBuf data) {
        try {
            DataInputStream byteSteam = new DataInputStream(new ByteBufInputStream(data));
            String name = byteSteam.readUTF();
            if (!name.equals(""))
                setCustomNameTag(name);
            model = byteSteam.readUTF();
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean canBeRidden() {
        return false;
    }

    @Override
    public int getSizeInventory() {
        return 0;
    }

    @Override
    public boolean isPoweredCart() {
        return true;
    }

    @Override
    public boolean isLinkable() {
        return true;
    }

    @Override
    public boolean canLinkWithCart(EntityMinecart cart) {
        if (isExemptFromLinkLimits(cart))
            return true;

        LinkageManager lm = LinkageManager.instance();

        EntityMinecart linkA = lm.getLinkedCartA(this);
        if (linkA != null && !isExemptFromLinkLimits(linkA))
            return false;

        EntityMinecart linkB = lm.getLinkedCartB(this);
        return linkB == null || isExemptFromLinkLimits(linkB);
    }

    private boolean isExemptFromLinkLimits(EntityMinecart cart) {
        return cart instanceof EntityLocomotive || cart instanceof CartBaseMaintenance;
    }

    @Override
    public float getLinkageDistance(EntityMinecart cart) {
        return LinkageManager.LINKAGE_DISTANCE;
    }

    @Override
    public float getOptimalDistance(EntityMinecart cart) {
        return 0.9f;
    }

    @Override
    public boolean canPassItemRequests() {
        return true;
    }

    public abstract LocomotiveRenderType getRenderType();

    @Override
    public final EnumDyeColor getPrimaryColor() {
        return dataManager.get(PRIMARY_COLOR).getDye();
    }

    public final void setPrimaryColor(EnumDyeColor color) {
        dataManager.set(PRIMARY_COLOR, EnumColor.fromDye(color));
    }

    @Override
    public final EnumDyeColor getSecondaryColor() {
        return dataManager.get(SECONDARY_COLOR).getDye();
    }

    public final void setSecondaryColor(EnumDyeColor color) {
        dataManager.set(SECONDARY_COLOR, EnumColor.fromDye(color));
    }

    public final String getModel() {
        return model;
    }

    public final void setModel(String model) {
        this.model = model;
    }

    @Override
    public World theWorld() {
        return worldObj;
    }

    public enum LocoMode implements IStringSerializable {

        SHUTDOWN, IDLE, RUNNING;
        public static final LocoMode[] VALUES = values();

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum LocoSpeed implements IStringSerializable {

        SLOWEST(1, 1, 0),
        SLOWER(2, 1, -1),
        NORMAL(3, 1, -1),
        MAX(4, 0, -1);
        public static final LocoSpeed[] VALUES = values();
        private final int shiftUp;
        private final int shiftDown;
        private final int level;

        LocoSpeed(int level, int shiftUp, int shiftDown) {
            this.level = level;
            this.shiftUp = shiftUp;
            this.shiftDown = shiftDown;
        }

        public static LocoSpeed fromName(String name) {
            for (LocoSpeed speed : VALUES) {
                if (speed.getName().equals(name))
                    return speed;
            }
            return MAX;
        }

        public int getLevel() {
            return level;
        }

        @Override
        public String getName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public LocoSpeed shiftUp() {
            LocoSpeed newSpeed = LocoSpeed.VALUES[ordinal() + shiftUp];
            return newSpeed;
        }

        public LocoSpeed shiftDown() {
            return LocoSpeed.VALUES[ordinal() + shiftDown];
        }
    }

    public enum LocoLockButtonState implements IMultiButtonState {

        UNLOCKED(new ButtonTextureSet(224, 0, 16, 16)),
        LOCKED(new ButtonTextureSet(240, 0, 16, 16)),
        PRIVATE(new ButtonTextureSet(240, 48, 16, 16));
        public static final LocoLockButtonState[] VALUES = values();
        private final IButtonTextureSet texture;

        LocoLockButtonState(IButtonTextureSet texture) {
            this.texture = texture;
        }

        @Override
        public String getLabel() {
            return "";
        }

        @Override
        public IButtonTextureSet getTextureSet() {
            return texture;
        }

        @Nullable
        @Override
        public ToolTip getToolTip() {
            return null;
        }

    }
}
