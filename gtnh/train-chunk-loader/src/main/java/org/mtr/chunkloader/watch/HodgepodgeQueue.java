package org.mtr.chunkloader.watch;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Optional bridge to the queue used by Hodgepodge 2.7.221's throttleChunkGeneration mixin. */
final class HodgepodgeQueue {
    private static final String CONFIG = "com.mitchej123.hodgepodge.config.SpeedupsConfig";
    private static final Api API;
    static final boolean SUPPORTED;

    static {
        Api api = null;
        boolean supported = true;
        try {
            if (hasSchedulerHook()) {
                api = new Api();
            } else if (HodgepodgeQueue.class.getClassLoader().getResource(CONFIG.replace('.', '/') + ".class") != null
                    && Class.forName(CONFIG).getField("throttleChunkGeneration").getBoolean(null)) {
                throw new NoSuchMethodException("Hodgepodge throttleChunkGeneration handler");
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            supported = false;
            org.apache.logging.log4j.LogManager.getLogger("MTR Train Chunk Loader").warn(
                    "Keeping native passenger view distance: unsupported Hodgepodge chunk scheduler", e);
        }
        API = api;
        SUPPORTED = supported;
    }

    private final Object scheduler;
    private final EntityPlayerMP player;
    private long[] requested = new long[32];
    private int count;

    private HodgepodgeQueue(int dimension, EntityPlayerMP player) {
        scheduler = ForgeSubscriptions.call(API.forDimension, null, dimension);
        this.player = player;
    }

    static HodgepodgeQueue forPlayer(PlayerManager manager, EntityPlayerMP player) {
        return API != null ? new HodgepodgeQueue(manager.getWorldServer().provider.dimensionId, player) : null;
    }

    void withdraw() {
        ForgeSubscriptions.call(API.remove, scheduler, player);
        count = 0;
    }

    void request(int x, int z) {
        if (count == requested.length) requested = Arrays.copyOf(requested, count * 2);
        requested[count++] = x & 0xffffffffL | (long) z << 32;
    }

    void submit() {
        if (count == 0) return;
        try {
            Object chunks = API.list.newInstance((Object) Arrays.copyOf(requested, count));
            ForgeSubscriptions.call(API.enqueue, scheduler, player, chunks);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot submit Hodgepodge chunk requests", e);
        }
    }

    private static boolean hasSchedulerHook() {
        // Mixin prefixes the injected handler name when it merges PlayerManager.
        for (Method method : PlayerManager.class.getDeclaredMethods()) {
            if (method.getName().contains("hodgepodge$processDeferredChunks")) return true;
        }
        return false;
    }

    private static final class Api {
        final Constructor<?> list;
        final Method forDimension, remove, enqueue;

        Api() throws ReflectiveOperationException {
            Class<?> scheduler = Class.forName("com.mitchej123.hodgepodge.mixins.hooks.ChunkGenScheduler");
            if (!PassengerView.Installed.class.isAssignableFrom(scheduler)) {
                throw new NoSuchMethodException("Hodgepodge queueResend recipient hook");
            }
            Class<?> longList = Class.forName("it.unimi.dsi.fastutil.longs.LongList");
            list = Class.forName("it.unimi.dsi.fastutil.longs.LongArrayList").getConstructor(long[].class);
            forDimension = scheduler.getMethod("forDimension", int.class);
            remove = scheduler.getMethod("removePlayer", EntityPlayerMP.class);
            enqueue = scheduler.getMethod("enqueueForPlayer", EntityPlayerMP.class, longList);
        }
    }
}
