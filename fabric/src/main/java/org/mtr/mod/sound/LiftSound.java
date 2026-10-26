package org.mtr.mod.sound;

import org.mtr.core.data.Lift;
import org.mtr.core.tool.Vector;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.MovingSoundInstanceExtension;
import org.mtr.mapping.mapper.SoundHelper;
import org.mtr.mod.Init;
import org.mtr.mod.data.LiftExtension;

public final class LiftSound {

	private final VehicleLoopingSoundInstance motor = new VehicleLoopingSoundInstance(event("motor"));
	private Vector previousPosition;
	private boolean hadCoolDown;
	private boolean movedSinceArrival;
	private boolean openingPlayed;
	private boolean closingPlayed;

	public void tick(Lift lift, Vector position, boolean shouldDing, boolean audible) {
		final float door = lift.getDoorValue();
		final boolean coolDown = lift.hasCoolDown();
		final int doorMovement = lift instanceof LiftExtension ? ((LiftExtension) lift).getDoorMovement() : 0;
		boolean moving = false;
		if (!coolDown) {
			openingPlayed = false;
			closingPlayed = false;
		}
		if (previousPosition != null) {
			final double dx = position.x - previousPosition.x;
			final double dy = position.y - previousPosition.y;
			final double dz = position.z - previousPosition.z;
			moving = !coolDown && dx * dx + dy * dy + dz * dz > 1E-10;
			movedSinceArrival |= moving;
			// Predicted motion can stop before the server confirms arrival.
			if (coolDown && !hadCoolDown && movedSinceArrival) {
				if (audible && shouldDing) play("arrival", position, .8F);
				movedSinceArrival = false;
			}
			if (doorMovement > 0 && !openingPlayed) {
				if (audible) play("door_open", position, .45F);
				openingPlayed = true;
			}
			if (doorMovement < 0 && !closingPlayed) {
				if (audible) play("door_close", position, .45F);
				closingPlayed = true;
			}
		} else {
			openingPlayed = door > 0;
			closingPlayed = doorMovement < 0;
		}
		motor.setData(audible && moving ? .4F : 0, 1, Init.newBlockPos(position.x, position.y, position.z));
		previousPosition = position;
		hadCoolDown = coolDown;
	}

	public void dispose() {
		if (previousPosition != null) motor.dispose();
	}

	private static SoundEvent event(String name) {
		return SoundHelper.createSoundEvent(new Identifier(Init.MOD_ID, "lift_" + name));
	}

	private static void play(String name, Vector position, float gain) {
		final MovingSoundInstanceExtension sound = new MovingSoundInstanceExtension(event(name), SoundCategory.getBlocksMapped()) {
			{
				setVolume(gain);
				setX(position.x);
				setY(position.y);
				setZ(position.z);
			}
			@Override
			public void tick2() {
			}
		};
		MinecraftClient.getInstance().getSoundManager().play(new SoundInstance(sound));
	}
}
