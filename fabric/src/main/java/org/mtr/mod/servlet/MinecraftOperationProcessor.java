package org.mtr.mod.servlet;

import org.mtr.core.operation.PlayerPresentResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.operation.VehicleLiftResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.servlet.QueueObject;
import org.mtr.mapping.holder.PlayerEntity;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketUpdateData;
import org.mtr.mod.packet.PacketUpdateVehiclesLifts;
import org.mtr.mod.item.ItemLiftRefresher;
import org.mtr.gtnh.RidingPermissions;

public final class MinecraftOperationProcessor {

	public static void process(QueueObject queueObject, ServerWorld serverWorld, String dimension) {
		switch (queueObject.key) {
			case OperationProcessor.VEHICLES_LIFTS:
				if (queueObject.data instanceof VehicleLiftResponse) {
					final PlayerEntity playerEntity = serverWorld.getPlayerByUuid(((VehicleLiftResponse) queueObject.data).uuid);
					if (playerEntity == null) {
						queueObject.runCallback(new PlayerPresentResponse(""));
					} else {
						RidingPermissions.observe(serverWorld, (VehicleLiftResponse) queueObject.data,
								(first, second) -> ItemLiftRefresher.findPath(new org.mtr.mapping.holder.World(serverWorld.data), first, second));
						Init.REGISTRY.sendPacketToClient(ServerPlayerEntity.cast(playerEntity), new PacketUpdateVehiclesLifts((VehicleLiftResponse) queueObject.data));
						queueObject.runCallback(new PlayerPresentResponse(dimension));
					}
				}
				break;
			case OperationProcessor.GENERATION_STATUS_UPDATE:
				if (queueObject.data instanceof UpdateDataResponse) {
					PacketUpdateData.sendDirectlyToClientDepotUpdate(serverWorld, (UpdateDataResponse) queueObject.data);
				}
				break;
		}

	}
}
