package org.mtr.mod.sound;

import org.mtr.mapping.holder.BlockPos;

public abstract class VehicleSoundBase {

	private boolean doorSoundInitialized;
	private boolean pendingDoorOpenSound;
	private boolean pendingDoorCloseSound;
	private boolean doorCloseSoundCaptured;

	public abstract void playMotorSound(BlockPos blockPos, float speed, float speedChange, float acceleration, boolean isOnRoute);

	/** Capture door transitions even when this car's render/audio callback is culled. */
	public final void updateDoorSound(double doorValue, double oldDoorValue) {
		if (doorValue == 0) {
			pendingDoorOpenSound = false;
		}
		if (doorValue > oldDoorValue) {
			pendingDoorCloseSound = false;
			doorCloseSoundCaptured = false;
		}
		if (doorValue > 0 && oldDoorValue == 0) {
			pendingDoorOpenSound = true;
		}
		if (!doorCloseSoundCaptured && doorValue < oldDoorValue && doorValue < getDoorCloseSoundTime()) {
			pendingDoorOpenSound = false;
			pendingDoorCloseSound = true;
			doorCloseSoundCaptured = true;
		}
		doorSoundInitialized = true;
	}

	public final void playDoorSound(BlockPos blockPos, double doorValue, double oldDoorValue) {
		if (!doorSoundInitialized) {
			updateDoorSound(doorValue, oldDoorValue);
		}
		if (pendingDoorOpenSound) {
			pendingDoorOpenSound = false;
			playDoorSound(blockPos, true);
		}
		if (pendingDoorCloseSound) {
			pendingDoorCloseSound = false;
			playDoorSound(blockPos, false);
		}
	}

	public abstract void dispose();

	protected abstract void playDoorSound(BlockPos blockPos, boolean isOpen);

	protected abstract double getDoorCloseSoundTime();
}
