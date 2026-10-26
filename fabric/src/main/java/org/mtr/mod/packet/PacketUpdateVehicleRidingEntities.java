package org.mtr.mod.packet;

import org.mtr.core.data.VehicleRidingEntity;
import org.mtr.core.operation.UpdateVehicleRidingEntities;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.tool.PacketBufferReceiver;
import org.mtr.mapping.tool.PacketBufferSender;
import org.mtr.mod.Init;
import org.mtr.gtnh.RidingPermissions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PacketUpdateVehicleRidingEntities extends PacketRequestResponseBase implements Utilities {

	private final boolean dismount;
	private ServerPlayerEntity authenticatedPlayer;

	public static PacketUpdateVehicleRidingEntities create(long sidingId, long vehicleId, int ridingCar, double x, double y, double z, boolean isOnGangway, boolean isDriver, boolean manualAccelerate, boolean manualBrake, boolean manualToggleDoors, boolean manualToggleAto, boolean doorOverride) {
		final UpdateVehicleRidingEntities updateVehicleRidingEntities = new UpdateVehicleRidingEntities(sidingId, vehicleId);
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			updateVehicleRidingEntities.add(new VehicleRidingEntity(clientPlayerEntity.getUuid(), ridingCar, x, y, z, isOnGangway, isDriver, manualAccelerate, manualBrake, manualToggleDoors, manualToggleAto, doorOverride));
		}
		return new PacketUpdateVehicleRidingEntities(Utilities.getJsonObjectFromData(updateVehicleRidingEntities).toString(), ridingCar < 0);
	}

	public PacketUpdateVehicleRidingEntities(PacketBufferReceiver packetBufferReceiver) {
		super(packetBufferReceiver);
		dismount = packetBufferReceiver.readBoolean();
	}

	private PacketUpdateVehicleRidingEntities(String content, boolean dismount) {
		super(content);
		this.dismount = dismount;
	}

	@Override
	public void write(PacketBufferSender packetBufferSender) {
		super.write(packetBufferSender);
		packetBufferSender.writeBoolean(dismount);
	}

	@Override
	protected void runServerOutbound(ServerWorld serverWorld, @Nullable ServerPlayerEntity serverPlayerEntity) {
		if (serverPlayerEntity == null) return;
		authenticatedPlayer = serverPlayerEntity;
		try {
			// The data factory validates and binds the sender before the base class can enqueue the operation.
			super.runServerOutbound(serverWorld, serverPlayerEntity);
			Init.updateRidingEntity(serverPlayerEntity, dismount);
		} catch (RidingPermissions.Rejected | org.mtr.libraries.com.google.gson.JsonParseException rejected) {
			org.apache.logging.log4j.LogManager.getLogger("MTRRiding").debug("Rejected riding request: {}", rejected.getMessage());
			RidingPermissions.revoke(serverPlayerEntity);
		} finally {
			authenticatedPlayer = null;
		}
	}

	@Override
	protected PacketRequestResponseBase getInstance(String content) {
		return new PacketUpdateVehicleRidingEntities(content, dismount);
	}

	@Override
	protected SerializedDataBase getDataInstance(JsonReader jsonReader) {
		if (authenticatedPlayer == null) throw new RidingPermissions.Rejected("Missing authenticated server sender");
		return RidingPermissions.validate(authenticatedPlayer, jsonReader, dismount);
	}

	@Nonnull
	@Override
	protected String getKey() {
		return OperationProcessor.UPDATE_RIDING_ENTITIES;
	}

	@Override
	protected PacketRequestResponseBase.ResponseType responseType() {
		return PacketRequestResponseBase.ResponseType.NONE;
	}
}
