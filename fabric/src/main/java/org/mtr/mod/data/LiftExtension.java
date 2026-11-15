package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.data.Position;
import org.mtr.core.data.Vehicle;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MinecraftClientData;

import java.util.function.BiFunction;
import java.util.function.LongSupplier;

public final class LiftExtension extends Lift {

    private final LongSupplier millisSinceReceived;
    private boolean hasTicked;
    private int doorMovement;
    private LiftExtension arrivalPrediction;
    private long arrivalDoorElapsed = -1;

    public LiftExtension(Lift lift, Data data, LongSupplier millisSinceReceived) {
        super(new JsonReader(Utilities.getJsonObjectFromData(lift)), data);
        this.millisSinceReceived = millisSinceReceived;
        if (data instanceof MinecraftClientData && hasCoolDown() && speed == 0) {
            // The wrapper still holds the previous prediction until packet sync rebinds it.
            final MinecraftClientData.LiftWrapper wrapper = ((MinecraftClientData) data).liftWrapperList.get(getId());
            if (wrapper != null && wrapper.getLift() instanceof LiftExtension) {
                final LiftExtension previous = (LiftExtension) wrapper.getLift();
                final LiftExtension prediction = previous.arrivalPrediction == null ? previous : previous.arrivalPrediction;
                if (canContinueArrival(prediction)) {
                    arrivalPrediction = prediction;
                    arrivalDoorElapsed = 0;
                } else if (previous.arrivalDoorElapsed >= 0 && previous.hasCoolDown()
                        && previous.railProgress == railProgress && hasSameGeometry(previous)) {
                    arrivalDoorElapsed = previous.arrivalDoorElapsed;
                }
            }
        }
    }

    @Override
    public void tick(long millisElapsed) {
        final long frameMillisElapsed = millisElapsed;
        if (!hasTicked) {
            hasTicked = true;
            millisElapsed = millisSinceReceived.getAsLong();
        }
        final float oldDoorValue = getDoorValue();
        final boolean wasArriving = arrivalPrediction != null;
        super.tick(millisElapsed);
        if (arrivalPrediction != null) {
            // Only the authoritative DTO uses packet age; continue the old visual trajectory by frame time.
            arrivalPrediction.tick(frameMillisElapsed);
            if (arrivalPrediction.railProgress == railProgress) {
                arrivalPrediction = null;
            }
        }
        if (arrivalPrediction == null && arrivalDoorElapsed >= 0) {
            // Opening starts after visual arrival and uses frame time rather than packet age.
            if (!wasArriving) arrivalDoorElapsed += frameMillisElapsed;
            if ((float) arrivalDoorElapsed / (Vehicle.DOOR_MOVE_TIME / 2) >= super.getDoorValue()) {
                arrivalDoorElapsed = -1;
            }
        }
        doorMovement = Float.compare(getDoorValue(), oldDoorValue);
    }

    @Override
    public Vector getPosition(BiFunction<Position, Position, ObjectArrayList<Vector>> trackProvider) {
        return arrivalPrediction == null ? super.getPosition(trackProvider) : arrivalPrediction.getPosition(trackProvider);
    }

    @Override
    public float getDoorValue() {
        // A stopped server DTO can arrive before the rendered cabin reaches its floor.
        if (arrivalPrediction != null) return 0;
        final float authoritative = super.getDoorValue();
        return arrivalDoorElapsed < 0 ? authoritative
                : Math.min(authoritative, (float) arrivalDoorElapsed / (Vehicle.DOOR_MOVE_TIME / 2));
    }

    public int getDoorMovement() {
        return doorMovement;
    }

    private boolean canContinueArrival(LiftExtension prediction) {
        if (!prediction.hasTicked || prediction.hasCoolDown() || prediction.speed * (railProgress - prediction.railProgress) <= 0
                || prediction.instructions.isEmpty() || !hasSameGeometry(prediction)) {
            return false;
        }
        final int target = prediction.instructions.get(0).getFloor();
        if (target < 0 || target >= floors.size()) {
            return false;
        }
        long targetProgress = 0;
        for (int i = 1; i <= target; i++) {
            targetProgress += floors.get(i).getPosition().manhattanDistance(floors.get(i - 1).getPosition());
        }
        return targetProgress == railProgress;
    }

    private boolean hasSameGeometry(LiftExtension prediction) {
        if (floors.size() != prediction.floors.size()
                || offsetX != prediction.offsetX || offsetY != prediction.offsetY || offsetZ != prediction.offsetZ
                || angle != prediction.angle || height != prediction.height || width != prediction.width || depth != prediction.depth) {
            return false;
        }
        for (int i = 0; i < floors.size(); i++) {
            if (!floors.get(i).getPosition().equals(prediction.floors.get(i).getPosition())) {
                return false;
            }
        }
        return true;
    }
}
