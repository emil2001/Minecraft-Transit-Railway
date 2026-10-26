package org.mtr.gtnh;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.AxisAlignedBB;
import org.mtr.core.data.*;
import org.mtr.core.operation.*;
import org.mtr.core.serializer.*;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.tool.PlayerPhysics;
import org.mtr.mod.item.ItemDriverKey;
import org.mtr.mod.item.ItemDepotDriverKey;
import java.util.*;
import java.util.function.BiFunction;

/** Authorizes riding only against fresh, server-generated numeric cabin snapshots. */
public final class RidingPermissions {
    private static final long FRESH_TICKS = 40, SWEEP_TICKS = 4, FROZEN_TICKS = 200;
    private static final double MARGIN = 1.5;
    // shortcut: The pinned core exposes train width/length but no model floor/roof bounds. This finite six-block envelope needs server model metadata for exact interior parity.
    private static final double TRAIN_HEIGHT = 6;
    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final Map<EntityPlayerMP, Selection> RIDERS = new WeakHashMap<>();
    // Retain active intent through short Core read gaps; revoke must still reach its captured Core source.
    private static final Map<EntityPlayerMP, PassengerHold> PASSENGER_HOLDS = new HashMap<>();
    private RidingPermissions() {}

    private static boolean lifecycleInstalled;
    public static synchronized void initialize() {
        if (!lifecycleInstalled) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new WorldLifecycle());
            PlayerPhysics.setMovementDeferrer(RidingPermissions::deferMovement);
            lifecycleInstalled = true;
        }
    }
    public static final class WorldLifecycle {
        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void unload(net.minecraftforge.event.world.WorldEvent.Unload event) {
            synchronized (RidingPermissions.class) {
                VIEWS.values().removeIf(view -> view.world == event.world);
                for (EntityPlayerMP player : new ArrayList<>(RIDERS.keySet()))
                    if (player.worldObj == event.world) revoke(new ServerPlayerEntity(player));
                for (EntityPlayerMP player : new ArrayList<>(PASSENGER_HOLDS.keySet()))
                    if (PASSENGER_HOLDS.get(player).world == event.world) releasePassengerHold(player, true);
            }
        }
    }

    public static synchronized void observe(ServerWorld world, VehicleLiftResponse response,
            BiFunction<Position, Position, ObjectArrayList<Vector>> liftPath) {
        View previous = VIEWS.get(response.uuid);
        org.mtr.mapping.holder.Identifier identifier = org.mtr.mapping.mapper.MinecraftServerHelper.getWorldId(new org.mtr.mapping.holder.World(world.data));
        String dimension = identifier.getNamespace() + "/" + identifier.getPath();
        CoreRidingSnapshots.Source source = CoreRidingSnapshots.bind(response, dimension);
        if (source == null) { VIEWS.remove(response.uuid); refreshRiders(response.uuid); return; }
        Set<Long> vehicles = new HashSet<>(), lifts = new HashSet<>();
        response.iterateVehiclesToKeep(vehicles::add); response.iterateLiftsToKeep(lifts::add);
        response.iterateVehiclesToUpdate(update -> vehicles.add(update.getVehicle().getId()));
        response.iterateLiftsToUpdate(lift -> lifts.add(lift.getId()));
        // Ordinary delta traffic must not discard a completed read before tick can accept it.
        // Keep is membership only: retaining this view changes no pose or freshness timestamp.
        if (previous != null && previous.world == world.data && previous.source == source
                && previous.vehicleIds.equals(vehicles) && previous.liftIds.equals(lifts)) return;
        // Actual world/source/membership changes invalidate the old query generation.
        View view = new View(world, source, vehicles, lifts, liftPath);
        if (previous != null && previous.world == world.data && previous.source == source) {
            previous.vehicles.forEach((id, snapshot) -> { if (vehicles.contains(id)) view.vehicles.put(id, snapshot); });
            previous.lifts.forEach((id, snapshot) -> { if (lifts.contains(id)) view.lifts.put(id, snapshot); });
        }
        VIEWS.put(response.uuid, view);
        refreshRiders(response.uuid);
    }

    /** Native world END tick: accept detached Core reads, then schedule at most one read per view. */
    public static synchronized void tick(ServerWorld world, BiFunction<Position, Position, ObjectArrayList<Vector>> liftPath) {
        long now = world.data.getTotalWorldTime();
        Iterator<Map.Entry<UUID, View>> iterator = VIEWS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, View> entry = iterator.next(); View view = entry.getValue();
            if (view.world != world.data) continue;
            org.mtr.mapping.holder.PlayerEntity holder = world.getPlayerByUuid(entry.getKey());
            if (holder == null || holder.data.worldObj != view.world || holder.data.dimension != view.dimension) {
                iterator.remove(); refreshRiders(entry.getKey()); continue;
            }
            acceptCompleted(entry.getKey(), view, now);
            resumeMovement(entry.getKey(), view, now);
            if (view.pending == null && now >= view.nextQueryAt) {
                view.pending = view.source.request(entry.getKey(), view.liftIds, view.vehicleIds, now);
                view.nextQueryAt = now + 2;
            }
        }
        expirePassengerHolds(world, now);
    }
    private static boolean acceptCompleted(UUID uuid, View view, long now) {
        CoreRidingSnapshots.Pending pending = view.pending;
        if (pending == null || !pending.done) return false;
        view.pending = null;
        if (pending.result != null && now >= pending.requestedAt && now - pending.requestedAt < FRESH_TICKS) {
            Map<Long, Snapshot> vehicles = new HashMap<>(), lifts = new HashMap<>();
            pending.result.lifts.forEach((id, json) -> {
                try { lifts.put(id, lift(new Lift(new JsonReader(Utilities.parseJson(json)), new Data() {}), pending.requestedAt, view.lifts.get(id), view.liftPath)); }
                catch (RuntimeException invalid) { /* Invalid current data removes the old cabin. */ }
            });
            pending.result.vehicles.forEach((id, json) -> {
                try { vehicles.put(id, vehicle(new VehicleUpdate(new JsonReader(Utilities.parseJson(json))), pending.requestedAt, view.vehicles.get(id))); }
                catch (RuntimeException invalid) { /* Invalid current data removes the old cabin. */ }
            });
            view.vehicles.clear(); view.vehicles.putAll(vehicles); view.lifts.clear(); view.lifts.putAll(lifts);
            refreshRiders(uuid);
        }
        return true;
    }

    /** Native C03 follows World END; a completed numeric read may have arrived since that poll. */
    private static synchronized boolean deferMovement(net.minecraft.network.NetHandlerPlayServer handler,
            net.minecraft.network.play.client.C03PacketPlayer packet) {
        EntityPlayerMP player = handler.playerEntity;
        Selection selected = RIDERS.get(player);
        View view = VIEWS.get(player.getUniqueID());
        if (selected == null || selected.replayingMovement) return false;
        if (view == null || view.world != player.worldObj || view.dimension != player.dimension
                || !selected.matches(view, selected.siding, selected.id, selected.car, selected.gangway)) {
            revoke(new ServerPlayerEntity(player)); return false;
        }
        long now = player.worldObj.getTotalWorldTime();
        acceptCompleted(player.getUniqueID(), view, now);
        Snapshot snapshot = (selected.siding == 0 ? view.lifts : view.vehicles).get(selected.id);
        if (RIDERS.get(player) != selected || !PlayerPhysics.authorized(player) || snapshot == null || !snapshot.fresh(now)) return false;
        DeferredMovement previous = selected.movement;
        if (previous != null) {
            if (!previous.current(player) || previous.expired(now)) { rejectMovement(player, selected); return true; }
            selected.movement.packet = packet;
            if (snapshot.freshAt > previous.snapshotAt) { selected.movement = null; return false; }
            applyHeldLook(handler, packet);
            return true;
        }
        if (player.playerNetServerHandler != handler || !handler.netManager.isChannelOpen()) return false;
        List<AxisAlignedBB> bounds = selected.bounds(snapshot, now);
        if (bounds.isEmpty()) { revoke(new ServerPlayerEntity(player)); return false; }
        if (selected.frozen && !selected.frozenProof(player, snapshot, now)) { revoke(new ServerPlayerEntity(player)); return false; }
        List<AxisAlignedBB> current = snapshot.bounds(selected.car, selected.gangway, now);
        if (snapshot.sweeping(now) && contains(current, packet.func_149464_c(), packet.func_149467_d(), packet.func_149472_e())) {
            // Commit only by matching these coordinates to the actual native player on the next refresh.
            selected.anchor = new NativeAnchor(handler, packet, now);
            selected.frozen = false;
            return false;
        }
        if (selected.frozenProof(player, snapshot, now) && selected.anchor.matches(packet)) {
            selected.frozen = true;
            applyHeldLook(handler, packet);
            return true;
        }
        if (selected.frozen) return false;
        selected.anchor = null;
        if (contains(bounds, packet.func_149464_c(), packet.func_149467_d(), packet.func_149472_e())) return false;
        if (view.pending == null && now > snapshot.freshAt) {
            view.pending = view.source.request(player.getUniqueID(), view.liftIds, view.vehicleIds, now);
            view.nextQueryAt = now + 2;
        }
        if (view.pending == null || view.pending.requestedAt <= snapshot.freshAt) return false;
        selected.movement = new DeferredMovement(handler, packet, snapshot.freshAt, now + SWEEP_TICKS);
        applyHeldLook(handler, packet);
        return true;
    }

    private static void applyHeldLook(net.minecraft.network.NetHandlerPlayServer handler,
            net.minecraft.network.play.client.C03PacketPlayer packet) {
        // Only position waits for Core evidence; native look/ground ordering continues immediately.
        handler.processPlayer(packet.func_149463_k()
            ? new net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook(
                packet.func_149462_g(), packet.func_149470_h(), packet.func_149465_i())
            : new net.minecraft.network.play.client.C03PacketPlayer(packet.func_149465_i()));
    }

    private static void resumeMovement(UUID uuid, View view, long now) {
        for (EntityPlayerMP player : new ArrayList<>(RIDERS.keySet())) {
            Selection selected = RIDERS.get(player);
            if (!uuid.equals(player.getUniqueID()) || selected == null || selected.movement == null) continue;
            DeferredMovement movement = selected.movement;
            if (!movement.current(player) || movement.expired(now)
                    || !selected.matches(view, selected.siding, selected.id, selected.car, selected.gangway)
                    || !PlayerPhysics.authorized(player)) { rejectMovement(player, selected); continue; }
            Snapshot snapshot = (selected.siding == 0 ? view.lifts : view.vehicles).get(selected.id);
            if (snapshot == null || !snapshot.fresh(now)) { rejectMovement(player, selected); continue; }
            if (snapshot.freshAt <= movement.snapshotAt) continue;
            selected.movement = null;
            selected.replayingMovement = true;
            try {
                net.minecraft.network.play.client.C03PacketPlayer packet = movement.packet;
                movement.handler.processPlayer(new net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition(
                    packet.func_149464_c(), packet.func_149467_d(), packet.func_149471_f(), packet.func_149472_e(), player.onGround));
            }
            finally { selected.replayingMovement = false; }
        }
    }

    private static void rejectMovement(EntityPlayerMP player, Selection selected) {
        DeferredMovement movement = selected.movement;
        selected.movement = null;
        revoke(new ServerPlayerEntity(player));
        if (movement != null && movement.current(player)) movement.handler.setPlayerLocation(
            player.posX, player.posY, player.posZ, player.rotationYaw, player.rotationPitch);
    }

    private static final class DeferredMovement {
        final net.minecraft.network.NetHandlerPlayServer handler;
        final net.minecraft.world.World world;
        final int dimension;
        final long snapshotAt, until;
        final long untilNanos = System.nanoTime() + 250_000_000L;
        net.minecraft.network.play.client.C03PacketPlayer packet;
        DeferredMovement(net.minecraft.network.NetHandlerPlayServer handler, net.minecraft.network.play.client.C03PacketPlayer packet,
                long snapshotAt, long until) {
            this.handler = handler; this.packet = packet; this.snapshotAt = snapshotAt; this.until = until;
            world = handler.playerEntity.worldObj; dimension = handler.playerEntity.dimension;
        }
        boolean current(EntityPlayerMP player) {
            return handler.playerEntity == player && player.playerNetServerHandler == handler
                && player.worldObj == world && player.dimension == dimension && handler.netManager.isChannelOpen();
        }
        boolean expired(long now) { return now >= until || System.nanoTime() - untilNanos >= 0; }
    }

    private static void refreshRiders(UUID uuid) {
        for (EntityPlayerMP player : new ArrayList<>(RIDERS.keySet()))
            if (uuid.equals(player.getUniqueID())) refresh(new ServerPlayerEntity(player));
    }

    /** Called by the packet's data factory before it can enqueue an operation in the core. */
    public static synchronized UpdateVehicleRidingEntities validate(ServerPlayerEntity holder, ReaderBase request, boolean dismount) {
        EntityPlayerMP player = holder.data;
        try {
            List<ReaderBase> rows = new ArrayList<>();
            request.iterateReaderArray("ridingEntities", rows::clear, rows::add);
            if (rows.size() != 1) throw new Rejected("Exactly one authenticated rider is required");
            ReaderBase row = rows.get(0);
            VehicleRidingEntity rider = new VehicleRidingEntity(row);
            if (!rider.uuid.equals(holder.getUuid()) || !finite(row.getDouble("x", Double.NaN), row.getDouble("y", Double.NaN), row.getDouble("z", Double.NaN)))
                throw new Rejected("Invalid authenticated rider or coordinates");
            if (dismount != (rider.getRidingCar() < 0)) throw new Rejected("Mismatched dismount flag");
            long siding = request.getLong("sidingId", 0), id = request.getLong("vehicleId", 0);
            if (dismount) {
                Selection previous = RIDERS.get(player);
                if (previous == null) throw new Rejected("Player has no authoritative ride to dismount");
                siding = previous.siding; id = previous.id;
                revoke(holder);
                return bound(holder.getUuid(), siding, id, rider, false, false, false);
            }
            View view = VIEWS.get(holder.getUuid());
            Snapshot snapshot = view == null || view.world != player.worldObj || view.dimension != player.dimension ? null : (siding == 0 ? view.lifts : view.vehicles).get(id);
            long now = player.worldObj.getTotalWorldTime(), car = rider.getRidingCar();
            if (snapshot == null || snapshot.siding != siding || !snapshot.fresh(now) || car < 0 || car >= snapshot.cabins.length)
                throw new Rejected("Unknown, stale or wrong-dimension cabin");
            Cabin cabin = snapshot.cabins[(int) car];
            Selection selected = RIDERS.get(player);
            boolean active = selected != null && selected.matches(view, siding, id, (int) car, rider.getIsOnGangway()) && PlayerPhysics.authorized(player);
            if (!active) selected = new Selection(view, siding, id, (int) car, rider.getIsOnGangway());
            List<AxisAlignedBB> bounds = active ? selected.bounds(snapshot, now) : snapshot.bounds((int) car, rider.getIsOnGangway(), now);
            if (!contains(bounds, player.posX, player.posY, player.posZ)) throw new Rejected("Player is outside the authoritative cabin");
            if (rider.getIsOnGangway()) {
                if (snapshot.siding == 0 || car == 0 || rider.getX() < 0 || rider.getX() > 1 || rider.getZ() < 0 || rider.getZ() > 1 || Math.abs(rider.getY()) > TRAIN_HEIGHT)
                    throw new Rejected("Invalid gangway coordinates");
            } else if (!cabin.containsLocal(rider.getX(), rider.getY(), rider.getZ())
                    || !(active ? selected.nearLocal(player, rider, now) : cabin.distanceTo(player.posX, player.posY, player.posZ, rider.getX(), rider.getY(), rider.getZ(), snapshot.sweeping(now)) <= MARGIN * 2))
                throw new Rejected("Claimed local position differs from the server player");
            boolean[] controls = controls(player, snapshot.depot);
            boolean passengerHold = rider.getDoorOverride() && !rider.getIsOnGangway() && snapshot.holdsDoorway(player, (int) car, now);
            UpdateVehicleRidingEntities sanitized = bound(holder.getUuid(), siding, id, rider, controls[0], controls[1], passengerHold);
            if (!active) selected.bounds(snapshot, now);
            RIDERS.put(player, selected);
            PlayerPhysics.authorize(player, bounds, snapshot.freshAt + FRESH_TICKS);
            PassengerHold previous = PASSENGER_HOLDS.remove(player);
            if (previous != null && !previous.matches(view, siding, id, (int) car)) previous.release(false);
            if (rider.getDoorOverride() && !controls[1] && !rider.getIsOnGangway() && snapshot.siding != 0 && cabin.holdAllowed && !cabin.doorways.isEmpty()
                    && (passengerHold || !snapshot.sweeping(now))) PASSENGER_HOLDS.put(player, new PassengerHold(view, siding, id, (int) car, player.playerNetServerHandler, rider,
                rider.isDriver() && (controls[0] || controls[1]), passengerHold));
            return sanitized;
        } catch (Rejected rejected) { revoke(holder); throw rejected; }
        catch (IllegalArgumentException malformed) { revoke(holder); throw new Rejected("Malformed riding data"); }
    }

    public static synchronized boolean refresh(ServerPlayerEntity holder) {
        EntityPlayerMP player = holder.data;
        Selection selected = RIDERS.get(player);
        View view = VIEWS.get(holder.getUuid());
        Snapshot snapshot = selected == null || view == null || view.world != player.worldObj || view.dimension != player.dimension ? null
            : (selected.siding == 0 ? view.lifts : view.vehicles).get(selected.id);
        long now = player.worldObj.getTotalWorldTime();
        if (snapshot == null || !selected.matches(view, snapshot.siding, selected.id, selected.car, selected.gangway) || !snapshot.fresh(now) || selected.car >= snapshot.cabins.length
                || !PlayerPhysics.authorized(player)) { revoke(holder); return false; }
        List<AxisAlignedBB> bounds = selected.bounds(snapshot, now);
        if (bounds.isEmpty()) { revoke(holder); return false; }
        if (selected.frozen || !contains(bounds, player.posX, player.posY, player.posZ)) {
            if (!selected.frozenProof(player, snapshot, now)) { revoke(holder); return false; }
            selected.frozen = true;
            // A stalled client retains flags, never the old position as movement authority.
            bounds = snapshot.bounds(selected.car, selected.gangway, now);
        }
        PlayerPhysics.authorize(player, bounds, snapshot.freshAt + FRESH_TICKS);
        return true;
    }
    public static synchronized void revoke(ServerPlayerEntity player) { releasePassengerHold(player.data, true); RIDERS.remove(player.data); PlayerPhysics.revoke(player.data); }
    public static synchronized void disconnect(ServerPlayerEntity player) { revoke(player); VIEWS.remove(player.getUuid()); }
    public static synchronized void clear() {
        for (EntityPlayerMP player : new ArrayList<>(RIDERS.keySet())) PlayerPhysics.revoke(player);
        for (EntityPlayerMP player : new ArrayList<>(PASSENGER_HOLDS.keySet())) releasePassengerHold(player, true);
        RIDERS.clear(); VIEWS.clear();
    }
    private static void expirePassengerHolds(ServerWorld world, long now) {
        Iterator<Map.Entry<EntityPlayerMP, PassengerHold>> iterator = PASSENGER_HOLDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<EntityPlayerMP, PassengerHold> entry = iterator.next();
            EntityPlayerMP player = entry.getKey(); PassengerHold hold = entry.getValue();
            if (hold.world != world.data) continue;
            View view = VIEWS.get(player.getUniqueID()); Selection selected = RIDERS.get(player);
            Snapshot snapshot = view == null ? null : view.vehicles.get(hold.id);
            org.mtr.mapping.holder.PlayerEntity currentPlayer = world.getPlayerByUuid(player.getUniqueID());
            if (currentPlayer == null || currentPlayer.data != player || player.worldObj != hold.world || player.dimension != hold.world.provider.dimensionId || player.playerNetServerHandler != hold.handler
                    || view == null || !hold.matches(view, hold.siding, hold.id, hold.car) || selected == null
                    || !selected.matches(view, hold.siding, hold.id, hold.car, false) || !PlayerPhysics.authorized(player)
                    || snapshot == null || snapshot.siding != hold.siding || hold.car >= snapshot.cabins.length) {
                iterator.remove(); hold.release(true); continue;
            }
            // Original stationary clients send only the initial edge. Renew from native proof, never a packet heartbeat.
            if (!snapshot.sweeping(now)) { hold.setActive(false, hold.driver); continue; }
            if (!snapshot.holdsDoorway(player, hold.car, now)) {
                iterator.remove(); hold.setActive(false, hold.driver); continue;
            }
            if (!hold.active) {
                boolean[] permission = controls(player, snapshot.depot);
                hold.setActive(true, hold.driver && (permission[0] || permission[1]));
            }
        }
    }
    private static void releasePassengerHold(EntityPlayerMP player, boolean dismount) {
        PassengerHold hold = PASSENGER_HOLDS.remove(player);
        if (hold != null) hold.release(dismount);
    }
    private static final class PassengerHold {
        final net.minecraft.world.World world; final CoreRidingSnapshots.Source source;
        final long siding, id; final int car; final VehicleRidingEntity rider; boolean driver;
        final net.minecraft.network.NetHandlerPlayServer handler; boolean active;
        PassengerHold(View view, long siding, long id, int car, net.minecraft.network.NetHandlerPlayServer handler, VehicleRidingEntity rider, boolean driver, boolean active) {
            world = view.world; source = view.source; this.siding = siding; this.id = id; this.car = car; this.handler = handler; this.rider = rider; this.driver = driver; this.active = active;
        }
        boolean matches(View view, long siding, long id, int car) {
            return view.world == world && view.source == source && this.siding == siding && this.id == id && this.car == car;
        }
        void setActive(boolean active, boolean driver) {
            if (this.active == active) return;
            this.active = active; this.driver = driver; send(false, active, driver);
        }
        void release(boolean dismount) { send(dismount, false, driver); }
        private void send(boolean dismount, boolean active, boolean driver) {
            UpdateVehicleRidingEntities update = new UpdateVehicleRidingEntities(siding, id);
            update.add(new VehicleRidingEntity(rider.uuid, dismount ? -1 : car, rider.getX(), rider.getY(), rider.getZ(), false,
                !dismount && driver, false, false, false, false, active));
            source.updatePassengerHold(update);
        }
    }
    public static final class Rejected extends IllegalArgumentException { public Rejected(String message) { super(message); } }

    private static Map<String, Double> cableFloorOffsets;
    private static Map<String, List<AxisAlignedBB>> vehicleDoorways;
    private static final Map<String, com.google.gson.JsonObject> bundledResources = new HashMap<>();

    private static com.google.gson.JsonObject cachedBundledJson(String resource) {
        return bundledResources.computeIfAbsent(resource, RidingPermissions::bundledJson);
    }

    private static synchronized List<AxisAlignedBB> doorways(TransportMode mode, String vehicleId) {
        if (vehicleDoorways == null) {
            floorOffset(TransportMode.CABLE_CAR, "");
            Map<String, List<AxisAlignedBB>> result = new HashMap<>(), models = new HashMap<>();
            try {
                for (com.google.gson.JsonElement entry : cachedBundledJson("mtr:mtr_custom_resources.json").getAsJsonArray("vehicles")) {
                    com.google.gson.JsonObject vehicle = entry.getAsJsonObject();
                    String key = vehicle.get("transportMode").getAsString() + ":" + vehicle.get("id").getAsString();
                    List<AxisAlignedBB> boxes = new ArrayList<>();
                    try {
                        for (com.google.gson.JsonElement modelEntry : vehicle.getAsJsonArray("models")) {
                            com.google.gson.JsonObject model = modelEntry.getAsJsonObject();
                            String mesh = model.get("modelResource").getAsString(), properties = model.get("modelPropertiesResource").getAsString(), positions = model.get("positionDefinitionsResource").getAsString();
                            String triple = mesh + ":" + properties + ":" + positions;
                            if (!models.containsKey(triple)) {
                                List<AxisAlignedBB> parsed = null;
                                try {
                                    if (!mesh.endsWith(".bbmodel")) throw new IllegalArgumentException("Unsupported doorway model");
                                    parsed = parseDoorways(cachedBundledJson(properties), cachedBundledJson(positions), cachedBundledJson(mesh));
                                } catch (RuntimeException invalid) { /* Unsupported metadata cannot authorize passengers. */ }
                                models.put(triple, parsed);
                            }
                            if (models.get(triple) == null) throw new IllegalArgumentException("Invalid doorway body");
                            boxes.addAll(models.get(triple));
                        }
                    } catch (RuntimeException invalid) { boxes.clear(); }
                    // Duplicate mode/ID declarations are ambiguous server authority.
                    if (result.containsKey(key)) result.put(key, Collections.emptyList());
                    else result.put(key, Collections.unmodifiableList(boxes));
                }
            } catch (RuntimeException invalid) { result.clear(); }
            vehicleDoorways = Collections.unmodifiableMap(result);
            bundledResources.clear();
        }
        return vehicleDoorways.getOrDefault(mode.name() + ":" + vehicleId, Collections.emptyList());
    }

    private static List<AxisAlignedBB> parseDoorways(com.google.gson.JsonObject properties, com.google.gson.JsonObject positions, com.google.gson.JsonObject mesh) {
        double offset = number(properties, "modelYOffset", 0);
        List<AxisAlignedBB> result = new ArrayList<>();
        for (com.google.gson.JsonElement partEntry : properties.getAsJsonArray("parts")) {
            com.google.gson.JsonObject part = partEntry.getAsJsonObject();
            if (!part.has("type") || !"DOORWAY".equals(part.get("type").getAsString())) continue;
            List<AxisAlignedBB> elements = new ArrayList<>();
            for (com.google.gson.JsonElement name : part.getAsJsonArray("names")) {
                com.google.gson.JsonObject selected = null;
                for (com.google.gson.JsonElement groupEntry : mesh.getAsJsonArray("outliner")) {
                    if (!groupEntry.isJsonObject()) continue;
                    com.google.gson.JsonObject group = groupEntry.getAsJsonObject();
                    if (!name.getAsString().equals(group.get("name").getAsString())) continue;
                    if (selected != null) throw new IllegalArgumentException("Ambiguous doorway group");
                    selected = group;
                }
                // The original client ignores missing names, including stock class_802/1938 asset gaps.
                if (selected == null) continue;
                unrotated(selected);
                for (com.google.gson.JsonElement child : selected.getAsJsonArray("children")) {
                    if (!child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Nested doorway group");
                    com.google.gson.JsonObject selectedElement = null;
                    for (com.google.gson.JsonElement elementEntry : mesh.getAsJsonArray("elements")) {
                        com.google.gson.JsonObject element = elementEntry.getAsJsonObject();
                        if (!child.getAsString().equals(element.get("uuid").getAsString())) continue;
                        if (selectedElement != null) throw new IllegalArgumentException("Ambiguous doorway element");
                        selectedElement = element;
                    }
                    if (selectedElement == null) throw new IllegalArgumentException("Missing doorway element");
                    unrotated(selectedElement);
                    if (number(selectedElement, "inflate", 0) != 0) throw new IllegalArgumentException("Inflated doorway element");
                    double[] from = vector(selectedElement, "from"), to = vector(selectedElement, "to");
                    elements.add(box(from[0] / 16, from[1] / 16 + offset, -to[2] / 16, to[0] / 16, to[1] / 16 + offset, -from[2] / 16));
                }
            }
            for (com.google.gson.JsonElement name : part.getAsJsonArray("positionDefinitions")) {
                com.google.gson.JsonObject selected = null;
                for (com.google.gson.JsonElement positionEntry : positions.getAsJsonArray("positionDefinitions")) {
                    com.google.gson.JsonObject position = positionEntry.getAsJsonObject();
                    if (!name.getAsString().equals(position.get("name").getAsString())) continue;
                    if (selected != null) throw new IllegalArgumentException("Ambiguous doorway placement");
                    selected = position;
                }
                if (selected == null) continue;
                for (String field : new String[]{"positions", "positionsFlipped"}) if (selected.has(field)) {
                    boolean flipped = field.equals("positionsFlipped");
                    for (com.google.gson.JsonElement placementEntry : selected.getAsJsonArray(field)) {
                        com.google.gson.JsonObject placement = placementEntry.getAsJsonObject();
                        double x = number(placement, "x", 0) * (flipped ? -1 : 1) / 16, y = number(placement, "y", 0) / 16, z = number(placement, "z", 0) / 16;
                        for (AxisAlignedBB element : elements) result.add(box(
                            (flipped ? -element.minX : element.minX) + x, element.minY + y, (flipped ? element.minZ : -element.minZ) + z,
                            (flipped ? -element.maxX : element.maxX) + x, element.maxY + y, (flipped ? element.maxZ : -element.maxZ) + z));
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }
    private static double number(com.google.gson.JsonObject object, String field, double fallback) {
        if (!object.has(field)) return fallback;
        com.google.gson.JsonElement value = object.get(field);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException("Invalid doorway number");
        return value.getAsDouble();
    }
    private static double[] vector(com.google.gson.JsonObject object, String field) {
        com.google.gson.JsonArray values = object.getAsJsonArray(field);
        if (values == null || values.size() != 3) throw new IllegalArgumentException("Invalid doorway vector");
        double[] result = new double[3];
        for (int index = 0; index < 3; index++) {
            com.google.gson.JsonElement value = values.get(index);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw new IllegalArgumentException("Invalid doorway coordinate");
            result[index] = value.getAsDouble();
        }
        return result;
    }
    private static void unrotated(com.google.gson.JsonObject object) {
        if (object.has("origin")) vector(object, "origin");
        if (object.has("rotation")) for (double rotation : vector(object, "rotation")) if (rotation != 0) throw new IllegalArgumentException("Rotated doorway geometry");
    }
    private static AxisAlignedBB box(double x1, double y1, double z1, double x2, double y2, double z2) {
        if (!finite(x1, y1, z1, x2, y2, z2)) throw new IllegalArgumentException("Non-finite doorway box");
        return AxisAlignedBB.getBoundingBox(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    // Server-bundled stock metadata only; client resource packs never supply authority.
    private static synchronized double floorOffset(TransportMode mode, String vehicleId) {
        if (mode != TransportMode.CABLE_CAR) return 0;
        if (cableFloorOffsets == null) {
            Map<String, Double> result = new HashMap<>();
            for (com.google.gson.JsonElement entry : cachedBundledJson("mtr:mtr_custom_resources.json").getAsJsonArray("vehicles")) {
                com.google.gson.JsonObject vehicle = entry.getAsJsonObject();
                if (!"CABLE_CAR".equals(vehicle.get("transportMode").getAsString())) continue;
                com.google.gson.JsonArray models = vehicle.getAsJsonArray("models");
                if (models.size() != 1) continue; // Unsupported bundled/addon layouts retain the original envelope.
                com.google.gson.JsonObject model = models.get(0).getAsJsonObject();
                com.google.gson.JsonObject properties = cachedBundledJson(model.get("modelPropertiesResource").getAsString());
                com.google.gson.JsonObject definition = cachedBundledJson(model.get("positionDefinitionsResource").getAsString());
                com.google.gson.JsonObject mesh = cachedBundledJson(model.get("modelResource").getAsString());
                Double floor = null;
                for (com.google.gson.JsonElement partEntry : properties.getAsJsonArray("parts")) {
                    com.google.gson.JsonObject part = partEntry.getAsJsonObject();
                    if (!part.has("type") || !"FLOOR".equals(part.get("type").getAsString())) continue;
                    com.google.gson.JsonArray names = part.getAsJsonArray("names"), positions = part.getAsJsonArray("positionDefinitions");
                    if (names.size() != 1 || positions.size() != 1) continue;
                    String positionName = positions.get(0).getAsString(); boolean identity = false;
                    for (com.google.gson.JsonElement value : definition.getAsJsonArray("positionDefinitions")) {
                        com.google.gson.JsonObject position = value.getAsJsonObject();
                        if (positionName.equals(position.get("name").getAsString()) && (position.has("positions") != position.has("positionsFlipped"))) {
                            com.google.gson.JsonArray origins = position.getAsJsonArray(position.has("positions") ? "positions" : "positionsFlipped");
                            if (origins.size() == 1 && origins.get(0).getAsJsonObject().entrySet().isEmpty()) identity = true;
                        }
                    }
                    if (!identity) continue;
                    for (com.google.gson.JsonElement value : mesh.getAsJsonArray("outliner")) {
                        if (!value.isJsonObject()) continue;
                        com.google.gson.JsonObject group = value.getAsJsonObject();
                        if (!names.get(0).getAsString().equals(group.get("name").getAsString()) || !zero(group, "rotation") || !zero(group, "origin")) continue;
                        com.google.gson.JsonArray children = group.getAsJsonArray("children");
                        if (children.size() != 1 || !children.get(0).isJsonPrimitive()) continue;
                        String uuid = children.get(0).getAsString();
                        for (com.google.gson.JsonElement elementEntry : mesh.getAsJsonArray("elements")) {
                            com.google.gson.JsonObject element = elementEntry.getAsJsonObject();
                            if (!uuid.equals(element.get("uuid").getAsString()) || !zero(element, "rotation") || !zero(element, "origin")
                                || element.has("inflate") && element.get("inflate").getAsDouble() != 0) continue;
                            double from = element.getAsJsonArray("from").get(1).getAsDouble(), to = element.getAsJsonArray("to").get(1).getAsDouble();
                            if (from != to || !Double.isFinite(from)) continue;
                            double offset = properties.get("modelYOffset").getAsDouble() + to / 16;
                            if (!Double.isFinite(offset) || floor != null) throw new IllegalArgumentException("Invalid bundled cable floor");
                            floor = offset;
                        }
                    }
                }
                if (floor != null && result.put(vehicle.get("id").getAsString(), floor) != null)
                    throw new IllegalArgumentException("Duplicate bundled cable vehicle");
            }
            cableFloorOffsets = Collections.unmodifiableMap(result);
        }
        return cableFloorOffsets.getOrDefault(vehicleId, 0D);
    }
    private static boolean zero(com.google.gson.JsonObject object, String key) {
        if (!object.has(key)) return true;
        for (com.google.gson.JsonElement value : object.getAsJsonArray(key))
            if (value.getAsDouble() != 0) return false;
        return true;
    }
    private static com.google.gson.JsonObject bundledJson(String resource) {
        if (!resource.matches("mtr:[a-z0-9_/]+\\.(json|bbmodel)")) throw new IllegalArgumentException("Invalid bundled cable resource path");
        String path = "/assets/mtr/" + resource.substring(4);
        try (java.io.InputStream input = RidingPermissions.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("Missing bundled cable resource " + path);
            try (java.io.Reader reader = new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
                return new com.google.gson.JsonParser().parse(reader).getAsJsonObject();
            }
        } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read bundled cable resource " + path, error); }
    }

    private static UpdateVehicleRidingEntities bound(UUID uuid, long siding, long id, VehicleRidingEntity rider, boolean drive, boolean doors, boolean passengerHold) {
        boolean riding = rider.getRidingCar() >= 0;
        UpdateVehicleRidingEntities update = new UpdateVehicleRidingEntities(siding, id);
        update.add(new VehicleRidingEntity(uuid, rider.getRidingCar(), rider.getX(), rider.getY(), rider.getZ(), rider.getIsOnGangway(),
            riding && rider.isDriver() && (drive || doors), riding && drive && rider.manualAccelerate(), riding && drive && rider.manualBrake(),
            riding && doors && rider.manualToggleDoors(), riding && drive && rider.manualToggleAto(), riding && (doors || passengerHold) && rider.getDoorOverride()));
        return update;
    }
    private static boolean[] controls(EntityPlayerMP player, long depot) {
        org.mtr.mapping.holder.ItemStack held = new org.mtr.mapping.holder.ItemStack(player.getHeldItem());
        if (depot == 0 || held.isEmpty() || !(held.getItem().data instanceof ItemDriverKey) || !ItemDepotDriverKey.isCreativeDriverKeyOrMatchesDepot(held, depot)) return new boolean[]{false, false};
        ItemDriverKey key = (ItemDriverKey) held.getItem().data;
        return new boolean[]{key.canDrive, key.canOpenDoors};
    }
    private static Snapshot lift(Lift source, long now, Snapshot old, BiFunction<Position, Position, ObjectArrayList<Vector>> path) {
        Lift copy = new Lift(new JsonReader(Utilities.getJsonObjectFromData(source)), new Data() {});
        Vector position = copy.getPosition(path).add(copy.getOffsetX(), copy.getOffsetY(), copy.getOffsetZ());
        Frame frame = new Frame(position, -Math.PI / 2 - copy.getAngle().angleRadians, 0);
        Cabin cabin = new Cabin(frame, copy.getWidth(), copy.getDepth(), copy.getHeight(), previous(old, 0, frame, now));
        boolean retainPrevious = old != null && old.siding == 0 && old.cabins.length == 1 && Selection.continuous(old.cabins[0], cabin);
        // A membership update can reread the same unchanged lift within its original native tick.
        if (retainPrevious && old.freshAt == now && old.cabins[0].frame.position.distanceTo(frame.position) == 0
                && old.cabins[0].frame.yaw == frame.yaw && old.cabins[0].frame.pitch == frame.pitch) return old;
        return new Snapshot(0, 0, now, now, new Cabin[]{cabin}, retainPrevious ? old.freshAt : now);
    }
    private static Snapshot vehicle(VehicleUpdate update, long now, Snapshot old) {
        Data data = new Data() {};
        VehicleExtraData extra = new VehicleExtraData(new JsonReader(Utilities.getJsonObjectFromData(update.getVehicleExtraData())));
        PathData.writePathCache(extra.immutablePath, data, update.getVehicle().getTransportMode());
        Vehicle copy = new Vehicle(extra, null, new JsonReader(Utilities.getJsonObjectFromData(update.getVehicle())), data);
        List<Cabin> cabins = new ArrayList<>();
        copy.getVehicleCarsAndPositions().forEach(car -> {
            List<Frame> bogies = new ArrayList<>();
            car.right().forEach(pair -> bogies.add(frame(pair.left(), pair.right(), true)));
            if (bogies.size() != 1 && bogies.size() != 2) throw new IllegalArgumentException("Invalid bogie count");
            boolean pitch = copy.getTransportMode().hasPitchAscending || copy.getTransportMode().hasPitchDescending;
            Frame orientation = bogies.size() == 1 ? new Frame(bogies.get(0).position, bogies.get(0).yaw, pitch ? bogies.get(0).pitch : 0)
                : frame(bogies.get(0).position, bogies.get(1).position, pitch);
            double pivot = (car.left().getBogie1Position() + car.left().getBogie2Position()) / 2;
            Vector center = orientation.world(0, 0, -pivot);
            Frame frame = new Frame(center, orientation.yaw, orientation.pitch);
            List<AxisAlignedBB> doorways = doorways(copy.getTransportMode(), car.left().getVehicleId());
            boolean holdAllowed = copy.getTransportMode().continuousMovement || !copy.isMoving();
            boolean previousHoldAllowed = old != null && old.siding == extra.getSidingId() && cabins.size() < old.cabins.length && old.cabins[cabins.size()].doorways == doorways && old.cabins[cabins.size()].holdAllowed;
            cabins.add(new Cabin(frame, car.left().getWidth(), car.left().getLength(), TRAIN_HEIGHT, floorOffset(copy.getTransportMode(), car.left().getVehicleId()), previous(old, cabins.size(), frame, now), doorways, holdAllowed, previousHoldAllowed, old == null ? now : old.freshAt));
        });
        if (cabins.isEmpty()) throw new IllegalArgumentException("No authoritative vehicle cars");
        boolean retainPrevious = old != null && old.siding == extra.getSidingId() && old.cabins.length == cabins.size();
        for (int index = 0; retainPrevious && index < cabins.size(); index++) retainPrevious = Selection.continuous(old.cabins[index], cabins.get(index));
        Map<UUID, CoreSeat> riders = new HashMap<>();
        extra.iterateRidingEntities(rider -> {
            CoreSeat seat = new CoreSeat(rider.getRidingCar(), rider.getIsOnGangway());
            riders.put(rider.uuid, riders.containsKey(rider.uuid) ? CoreSeat.AMBIGUOUS : seat);
        });
        return new Snapshot(extra.getSidingId(), extra.getDepotId(), now, now, cabins.toArray(new Cabin[0]), retainPrevious ? old.freshAt : now, riders);
    }
    private static Frame previous(Snapshot old, int index, Frame current, long now) {
        if (old == null || index >= old.cabins.length || !old.fresh(now) || now - old.freshAt > SWEEP_TICKS) return null;
        Frame previous = old.cabins[index].frame;
        return previous.position.distanceTo(current.position) <= 64 ? previous : null;
    }
    private static Frame frame(Vector first, Vector second, boolean pitch) {
        double dx = second.x - first.x, dy = second.y - first.y, dz = second.z - first.z;
        return new Frame(Vector.getAverage(first, second), Math.atan2(dx, dz), pitch ? Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) : 0);
    }
    private static boolean contains(List<AxisAlignedBB> bounds, double x, double y, double z) {
        for (AxisAlignedBB box : bounds) if (x >= box.minX && x <= box.maxX && y >= box.minY && y <= box.maxY && z >= box.minZ && z <= box.maxZ) return true;
        return false;
    }
    private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
    private static final class View {
        final int dimension; final net.minecraft.world.World world; final CoreRidingSnapshots.Source source;
        final Map<Long, Snapshot> vehicles = new HashMap<>(), lifts = new HashMap<>();
        final Set<Long> vehicleIds, liftIds;
        CoreRidingSnapshots.Pending pending; long nextQueryAt;
        final BiFunction<Position, Position, ObjectArrayList<Vector>> liftPath;
        View(ServerWorld world, CoreRidingSnapshots.Source source, Set<Long> vehicles, Set<Long> lifts,
                BiFunction<Position, Position, ObjectArrayList<Vector>> liftPath) {
            this.liftPath = liftPath;
            this.dimension = world.data.provider.dimensionId; this.world = world.data; this.source = source;
            vehicleIds = Collections.unmodifiableSet(vehicles); liftIds = Collections.unmodifiableSet(lifts);
        }
    }
    private static final class Selection {
        final long siding, id; final int car; final boolean gangway;
        final net.minecraft.world.World world; final CoreRidingSnapshots.Source source;
        final Deque<Snapshot> history = new ArrayDeque<>();
        DeferredMovement movement; boolean replayingMovement, frozen; NativeAnchor anchor;
        Selection(View view, long siding, long id, int car, boolean gangway) {
            this.world = view.world; this.source = view.source;
            this.siding = siding; this.id = id; this.car = car; this.gangway = gangway;
        }
        boolean matches(View view, long siding, long id, int car, boolean gangway) {
            return view.world == world && view.source == source && this.siding == siding && this.id == id && this.car == car && this.gangway == gangway;
        }
        boolean frozenProof(EntityPlayerMP player, Snapshot snapshot, long now) {
            CoreSeat seat = snapshot.riders.get(player.getUniqueID());
            boolean present = false;
            for (Object current : world.playerEntities) if (current == player) { present = true; break; }
            return movement == null && siding != 0 && snapshot.sweeping(now) && seat != null && seat.car == car && seat.gangway == gangway
                && anchor != null && anchor.current(player, now) && present;
        }
        List<AxisAlignedBB> bounds(Snapshot snapshot, long now) {
            Snapshot last = history.peekLast();
            if (last == null && snapshot.previousAt < snapshot.freshAt && snapshot.sweeping(now) && snapshot.cabins[car].previous != null
                    && (!gangway || car == 0 || snapshot.cabins[car - 1].previous != null)) {
                Cabin[] previous = snapshot.cabins.clone();
                for (int index = gangway && car > 0 ? car - 1 : car; index <= car; index++) {
                    Cabin cabin = previous[index];
                    previous[index] = new Cabin(cabin.previous, cabin.width, cabin.length, cabin.height, cabin.floorOffset, null);
                }
                // Boarding can rely on the previous frame; its age belongs to the original Core read.
                history.addLast(new Snapshot(siding, snapshot.depot, snapshot.previousAt, snapshot.previousAt, previous));
            }
            if (last != snapshot) {
                // Only continuous server poses for the already selected car can extend its trail.
                if (last != null && (!last.fresh(now) || snapshot.freshAt <= last.freshAt || snapshot.cabins.length != last.cabins.length
                        || !continuous(last.cabins[car], snapshot.cabins[car])
                        || gangway && car > 0 && !continuous(last.cabins[car - 1], snapshot.cabins[car - 1]))) return Collections.emptyList();
                history.addLast(snapshot);
            }
            while (!history.isEmpty() && (!history.peekFirst().fresh(now) || history.size() > FRESH_TICKS / 2)) history.removeFirst();
            List<AxisAlignedBB> result = snapshot.bounds(car, gangway, now);
            // Keep separate cabin boxes: enclosing the whole trail would authorize shortcuts off the route.
            for (Snapshot past : history) {
                result.add(past.cabins[car].bounds(false));
                if (gangway && car > 0) result.add(past.cabins[car - 1].bounds(false));
            }
            return result;
        }
        boolean nearLocal(EntityPlayerMP player, VehicleRidingEntity rider, long now) {
            for (Snapshot past : history)
                if (past.fresh(now) && past.cabins[car].distanceTo(player.posX, player.posY, player.posZ, rider.getX(), rider.getY(), rider.getZ(), past.sweeping(now)) <= MARGIN * 2) return true;
            return false;
        }
        // shortcut: Core read gaps over four ticks reset the trail; relax only with authoritative path continuity evidence.
        private static boolean continuous(Cabin before, Cabin after) {
            return before.width == after.width && before.length == after.length && before.height == after.height && before.floorOffset == after.floorOffset && before.doorways == after.doorways
                && after.previous == before.frame;
        }
    }
    private static final class NativeAnchor {
        final net.minecraft.network.NetHandlerPlayServer handler;
        final net.minecraft.world.World world; final int dimension;
        final double x, feetY, z; final long since, until, untilNanos;
        NativeAnchor(net.minecraft.network.NetHandlerPlayServer handler, net.minecraft.network.play.client.C03PacketPlayer packet, long now) {
            this.handler = handler; world = handler.playerEntity.worldObj; dimension = handler.playerEntity.dimension;
            x = packet.func_149464_c(); feetY = packet.func_149467_d(); z = packet.func_149472_e();
            since = now; until = now + FROZEN_TICKS; untilNanos = System.nanoTime() + 10_000_000_000L;
        }
        boolean current(EntityPlayerMP player, long now) {
            return now >= since && now < until && System.nanoTime() - untilNanos < 0 && handler.playerEntity == player && player.playerNetServerHandler == handler
                && handler.netManager.isChannelOpen() && player.worldObj == world && player.dimension == dimension
                && player.posX == x && player.boundingBox.minY == feetY && player.posZ == z;
        }
        boolean matches(net.minecraft.network.play.client.C03PacketPlayer packet) {
            return packet.func_149464_c() == x && packet.func_149467_d() == feetY && packet.func_149472_e() == z;
        }
    }
    private static final class CoreSeat {
        static final CoreSeat AMBIGUOUS = new CoreSeat(-1, false);
        final long car; final boolean gangway;
        CoreSeat(long car, boolean gangway) { this.car = car; this.gangway = gangway; }
    }
    private static final class Snapshot {
        final long siding, depot, freshAt, poseAt, previousAt; final Cabin[] cabins;
        final Map<UUID, CoreSeat> riders;
        Snapshot(long siding, long depot, long freshAt, long poseAt, Cabin[] cabins) { this(siding, depot, freshAt, poseAt, cabins, freshAt); }
        Snapshot(long siding, long depot, long freshAt, long poseAt, Cabin[] cabins, long previousAt) {
            this(siding, depot, freshAt, poseAt, cabins, previousAt, Collections.emptyMap());
        }
        Snapshot(long siding, long depot, long freshAt, long poseAt, Cabin[] cabins, long previousAt, Map<UUID, CoreSeat> riders) {
            this.siding = siding; this.depot = depot; this.freshAt = freshAt; this.poseAt = poseAt; this.cabins = cabins; this.previousAt = previousAt;
            this.riders = Collections.unmodifiableMap(new HashMap<>(riders));
        }
        boolean fresh(long now) { return now >= freshAt && now - freshAt < FRESH_TICKS; }
        boolean sweeping(long now) { return now >= poseAt && now - poseAt <= SWEEP_TICKS; }
        boolean holdsDoorway(EntityPlayerMP player, int car, long now) {
            Cabin cabin = cabins[car];
            if (siding == 0 || !sweeping(now) || !cabin.holdAllowed) return false;
            return cabin.inDoorway(cabin.frame, player) || cabin.previousHoldAllowed && cabin.previous != null
                && now >= cabin.previousHoldAt && now - cabin.previousHoldAt <= SWEEP_TICKS && cabin.inDoorway(cabin.previous, player);
        }
        List<AxisAlignedBB> bounds(int car, boolean gangway, long now) {
            List<AxisAlignedBB> result = new ArrayList<>(); result.add(cabins[car].bounds(sweeping(now)));
            if (gangway && car > 0) result.add(cabins[car - 1].bounds(sweeping(now)));
            return result;
        }
    }
    private static final class Frame {
        final Vector position; final double yaw, pitch;
        Frame(Vector position, double yaw, double pitch) {
            if (!finite(position.x, position.y, position.z, yaw, pitch)) throw new IllegalArgumentException("Non-finite cabin transform");
            this.position = new Vector(position.x, position.y, position.z); this.yaw = yaw; this.pitch = pitch;
        }
        Vector world(double x, double y, double z) { return new Vector(x, y, z).rotateX(pitch).rotateY(yaw).add(position); }
    }
    private static final class Cabin {
        final Frame frame, previous; final double width, length, height, floorOffset;
        final List<AxisAlignedBB> doorways; final boolean holdAllowed, previousHoldAllowed; final long previousHoldAt;
        Cabin(Frame frame, double width, double length, double height, Frame previous) { this(frame, width, length, height, 0, previous); }
        Cabin(Frame frame, double width, double length, double height, double floorOffset, Frame previous) {
            this(frame, width, length, height, floorOffset, previous, Collections.emptyList(), false, false, 0);
        }
        Cabin(Frame frame, double width, double length, double height, double floorOffset, Frame previous, List<AxisAlignedBB> doorways, boolean holdAllowed, boolean previousHoldAllowed, long previousHoldAt) {
            if (!finite(width, length, height, floorOffset) || width <= 0 || length <= 0 || height <= 0) throw new IllegalArgumentException("Invalid cabin dimensions");
            this.frame = frame; this.previous = previous; this.width = width; this.length = length; this.height = height; this.floorOffset = floorOffset;
            this.doorways = doorways; this.holdAllowed = holdAllowed; this.previousHoldAllowed = previousHoldAllowed; this.previousHoldAt = previousHoldAt;
        }
        boolean inDoorway(Frame pose, EntityPlayerMP player) {
            Vector local = new Vector(player.posX - pose.position.x, player.boundingBox.minY - pose.position.y, player.posZ - pose.position.z).rotateY(-pose.yaw).rotateX(-pose.pitch);
            for (AxisAlignedBB doorway : doorways)
                if (local.x > doorway.minX - 0.3 && local.x < doorway.maxX + 0.3 && local.y >= doorway.minY - 1E-6 && local.y <= doorway.maxY + 1E-6
                        && local.z > doorway.minZ - 0.3 && local.z < doorway.maxZ + 0.3) return true;
            return false;
        }
        boolean containsLocal(double x, double y, double z) { return Math.abs(x) <= width / 2 + MARGIN && y >= floorOffset - MARGIN && y <= floorOffset + height + MARGIN && Math.abs(z) <= length / 2 + MARGIN; }
        double distanceTo(double x, double y, double z, double localX, double localY, double localZ, boolean swept) {
            Vector point = new Vector(x, y, z); double distance = frame.world(localX, localY, localZ).distanceTo(point);
            return swept && previous != null ? Math.min(distance, previous.world(localX, localY, localZ).distanceTo(point)) : distance;
        }
        AxisAlignedBB bounds(boolean swept) {
            double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (Frame transform : swept && previous != null ? new Frame[]{frame, previous} : new Frame[]{frame})
                for (double x : new double[]{-width / 2 - MARGIN, width / 2 + MARGIN})
                    for (double y : new double[]{floorOffset - MARGIN, floorOffset + height + MARGIN})
                        for (double z : new double[]{-length / 2 - MARGIN, length / 2 + MARGIN}) {
                            Vector point = transform.world(x, y, z);
                            low[0] = Math.min(low[0], point.x); low[1] = Math.min(low[1], point.y); low[2] = Math.min(low[2], point.z);
                            high[0] = Math.max(high[0], point.x); high[1] = Math.max(high[1], point.y); high[2] = Math.max(high[2], point.z);
                        }
            return AxisAlignedBB.getBoundingBox(low[0], low[1], low[2], high[0], high[1], high[2]);
        }
    }
}
