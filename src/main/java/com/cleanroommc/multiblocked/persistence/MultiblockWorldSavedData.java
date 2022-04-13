package com.cleanroommc.multiblocked.persistence;

import com.cleanroommc.multiblocked.Multiblocked;
import com.cleanroommc.multiblocked.api.capability.CapabilityProxy;
import com.cleanroommc.multiblocked.api.pattern.MultiblockState;
import com.cleanroommc.multiblocked.api.tile.ComponentTileEntity;
import com.cleanroommc.multiblocked.api.tile.ControllerTileEntity;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MultiblockWorldSavedData extends WorldSavedData {

    @SideOnly(Side.CLIENT)
    public static Set<BlockPos> modelDisabled;
    @SideOnly(Side.CLIENT)
    public static Map<BlockPos, Collection<BlockPos>> multiDisabled;

    public final static ThreadLocal<Boolean> isBuildingChunk = ThreadLocal.withInitial(()-> Boolean.FALSE);

    static {
        if (Multiblocked.isClient()) {
            modelDisabled = new HashSet<>();
            multiDisabled = new HashMap<>();
        }
    }

    private static WeakReference<World> worldRef;

    public static MultiblockWorldSavedData getOrCreate(World world) {
        MapStorage perWorldStorage = world.getPerWorldStorage();
        String name = getName(world);
        worldRef = new WeakReference<>(world);
        MultiblockWorldSavedData mbwsd = (MultiblockWorldSavedData) perWorldStorage.getOrLoadData(MultiblockWorldSavedData.class, name);
        worldRef = null;
        if (mbwsd == null) {
            perWorldStorage.setData(name, mbwsd = new MultiblockWorldSavedData(name));
        }
        return mbwsd;
    }

    private static String getName(World world) {
        return "Multiblocked" + world.provider.getDimensionType().getSuffix();
    }

    public final Map<BlockPos, MultiblockState> mapping;
    public final Map<ChunkPos, Set<MultiblockState>> chunkPosMapping;
    public final Set<ComponentTileEntity<?>> loading;

    public MultiblockWorldSavedData(String name) { // Also constructed Reflectively by MapStorage
        super(name);
        this.mapping = new Object2ObjectOpenHashMap<>();
        this.chunkPosMapping = new HashMap<>();
        this.loading = new ObjectOpenHashSet<>();
    }

    public static void clearDisabled() {
        modelDisabled.clear();
        multiDisabled.clear();
    }

    public Collection<MultiblockState> getControllerInChunk(ChunkPos chunkPos) {
        return new ArrayList<>(chunkPosMapping.getOrDefault(chunkPos, Collections.emptySet()));
    }

    public Collection<ComponentTileEntity<?>> getLoadings() {
        return loading;
    }

    public void addMapping(MultiblockState state) {
        this.mapping.put(state.getController().getPos(), state);
        for (BlockPos blockPos : state.getCache()) {
            chunkPosMapping.computeIfAbsent(new ChunkPos(blockPos), c->new HashSet<>()).add(state);
        }
        addLoading(state.getController());
        setDirty(true);
    }

    public void removeMapping(MultiblockState state) {
        this.mapping.remove(state.getController().getPos());
        for (Set<MultiblockState> set : chunkPosMapping.values()) {
            set.remove(state);
        }
        setDirty(true);
    }

    public void addLoading(ComponentTileEntity<?> tileEntity) {
        loading.add(tileEntity);
        if (tileEntity instanceof ControllerTileEntity) {
            controllers.add((ControllerTileEntity) tileEntity);
            createSearchingThread();
        }
    }

    public void removeLoading(ComponentTileEntity<?> tileEntity) {
        loading.remove(tileEntity);
        if (tileEntity instanceof ControllerTileEntity) {
            controllers.remove(tileEntity);
            if (controllers.isEmpty()) {
                releaseSearchingThread();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public static void removeDisableModel(BlockPos controllerPos) {
        Collection<BlockPos> poses = multiDisabled.remove(controllerPos);
        if (poses == null) return;
        modelDisabled.clear();
        multiDisabled.values().forEach(modelDisabled::addAll);
        updateRenderChunk(poses);
    }

    @SideOnly(Side.CLIENT)
    private static void updateRenderChunk(Collection<BlockPos> poses) {
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            for (BlockPos pos : poses) {
                world.markBlockRangeForRenderUpdate(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            }
        }
    }

    @SideOnly(Side.CLIENT)
    public static void addDisableModel(BlockPos controllerPos, Collection<BlockPos> poses) {
        multiDisabled.put(controllerPos, poses);
        modelDisabled.addAll(poses);
        updateRenderChunk(poses);
    }

    @SideOnly(Side.CLIENT)
    public static boolean isModelDisabled(BlockPos pos) {
        if (isBuildingChunk.get()) {
            return modelDisabled.contains(pos);
        }
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        for (String key : nbt.getKeySet()) {
            BlockPos pos = BlockPos.fromLong(Long.parseLong(key));
            MultiblockState state = new MultiblockState(worldRef.get(), pos);
            state.deserialize(new PacketBuffer(Unpooled.copiedBuffer(nbt.getByteArray(key))));
            this.mapping.put(pos, state);
            for (BlockPos blockPos : state.getCache()) {
                chunkPosMapping.computeIfAbsent(new ChunkPos(blockPos), c->new HashSet<>()).add(state);
            }
        }
    }

    @Nonnull
    @Override
    public NBTTagCompound writeToNBT(@Nonnull NBTTagCompound compound) {
        this.mapping.forEach((pos, state) -> {
            ByteBuf byteBuf = Unpooled.buffer();
            state.serialize(new PacketBuffer(byteBuf));
            compound.setByteArray(String.valueOf(pos.toLong()), Arrays.copyOfRange(byteBuf.array(), 0, byteBuf.writerIndex()));
        });
        return compound;
    }

    // ********************************* thread for searching ********************************* //
    private final Set<ControllerTileEntity> controllers = Collections.synchronizedSet(new HashSet<>());
    private Thread thread;
    private long periodID = Multiblocked.RNG.nextLong();

    public void createSearchingThread() {
        if (thread != null && !thread.isInterrupted()) return;
        thread = new Thread(this::searchingTask);
        thread.start();
    }

    private void searchingTask() {
        while (!Thread.interrupted()) {
            for (ControllerTileEntity controller : controllers) {
                try {
                    if (controller.hasProxies()) {
                        // should i do lock for proxies?
                        for (Long2ObjectOpenHashMap<CapabilityProxy<?>> map : controller.getCapabilities().values()) {
                            if (map != null) {
                                for (CapabilityProxy<?> proxy : map.values()) {
                                    if (proxy != null) {
                                        proxy.updateChangedState(periodID);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Multiblocked.LOGGER.error("something run while checking proxy changes");
                }
            }
            periodID++;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public void releaseSearchingThread() {
        if (thread != null) {
            thread.interrupt();
        }
        thread = null;
    }

    public long getPeriodID() {
        return periodID;
    }
}
