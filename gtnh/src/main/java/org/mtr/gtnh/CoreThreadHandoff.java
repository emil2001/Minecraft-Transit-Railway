package org.mtr.gtnh;

import org.mtr.core.data.ClientData;
import org.mtr.core.operation.ArrivalsResponse;
import org.mtr.core.operation.DataResponse;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.operation.NearbyAreasResponse;
import org.mtr.core.operation.RailsResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.operation.UpdateVehicleRidingEntities;
import org.mtr.core.operation.VehicleLiftResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;

/** Freezes Core-owned response objects before their queue publishes them to the server thread. */
public final class CoreThreadHandoff {
    private static final SerializedDataBase EMPTY = new SerializedDataBase() {
        @Override public void updateData(ReaderBase reader) {}
        @Override public void serializeData(WriterBase writer) {}
    };
    private CoreThreadHandoff() {}

    /** Runs on Core before a reply callback schedules native world/player work. */
    public static <T extends SerializedDataBase> T reply(T response, Class<T> expected) {
        if (response == null || expected == null || !expected.isInstance(response)) {
            throw new IllegalArgumentException("Invalid Core callback response type");
        }
        JsonObject json = Utilities.getJsonObjectFromData(response);
        JsonReader reader = new JsonReader(json);
        SerializedDataBase copy;
        if (response instanceof DataResponse) copy = new DataResponse(reader, new ClientData());
        else if (response instanceof UpdateDataResponse) copy = new UpdateDataResponse(reader, new ClientData());
        else if (response instanceof ListDataResponse) copy = new ListDataResponse(reader, new ClientData());
        else if (response instanceof NearbyAreasResponse) copy = new NearbyAreasResponse(reader, new ClientData());
        else if (response instanceof RailsResponse) copy = new RailsResponse(reader);
        else if (response instanceof ArrivalsResponse) copy = new ArrivalsResponse(reader);
        else if (response instanceof DeleteDataResponse) copy = new DeleteDataResponse(reader);
        else if (response instanceof UpdateVehicleRidingEntities) copy = new UpdateVehicleRidingEntities(reader);
        else if (expected == SerializedDataBase.class && json.entrySet().isEmpty()) copy = EMPTY;
        else throw new IllegalArgumentException("Unsupported Core callback response: " + response.getClass().getName());
        return expected.cast(copy);
    }

    public static SerializedDataBase detach(SerializedDataBase data, Simulator source) {
        if (!(data instanceof VehicleLiftResponse) && !(data instanceof UpdateDataResponse)) {
            throw new IllegalArgumentException("Unsupported Core S2C response: " + (data == null ? "null" : data.getClass().getName()));
        }
        JsonReader reader = new JsonReader(Utilities.getJsonObjectFromData(data));
        // Constructors build owned payloads; UpdateDataResponse.write() would mutate the source and must not run here.
        return data instanceof VehicleLiftResponse ? new VehicleLiftResponse(reader, source) : new UpdateDataResponse(reader, source);
    }
}
