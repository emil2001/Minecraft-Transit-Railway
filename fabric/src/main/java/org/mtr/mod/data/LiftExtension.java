package org.mtr.mod.data;

import org.mtr.core.data.Data;
import org.mtr.core.data.Lift;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;

import java.util.function.LongSupplier;

public final class LiftExtension extends Lift {

    private final LongSupplier millisSinceReceived;
    private boolean hasTicked;

    public LiftExtension(Lift lift, Data data, LongSupplier millisSinceReceived) {
        super(new JsonReader(Utilities.getJsonObjectFromData(lift)), data);
        this.millisSinceReceived = millisSinceReceived;
    }

    @Override
    public void tick(long millisElapsed) {
        if (!hasTicked) {
            hasTicked = true;
            millisElapsed = millisSinceReceived.getAsLong();
        }
        super.tick(millisElapsed);
    }
}
