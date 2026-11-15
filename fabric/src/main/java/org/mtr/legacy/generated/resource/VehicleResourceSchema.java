package org.mtr.legacy.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;

public abstract class VehicleResourceSchema implements SerializedDataBase {

	protected String base_train_type = "";

	protected String name = "";

	protected String color = "";

	protected String model = "";

	protected String model_properties = "";

	protected String description = "";

	protected String wikipedia_article = "";

	protected String texture_id = "";

	protected boolean has_gangway_connection;

	protected String gangway_connection_id = "";

	protected String train_barrier_id = "";

	protected String door_animation_type = "";

	protected double rider_offset;

	protected String bve_sound_base_id = "";

	protected long speed_sound_count;

	protected String speed_sound_base_id = "";

	protected String door_sound_base_id = "";

	protected double door_close_sound_time;

	protected boolean accel_sound_at_coast;

	protected boolean const_playback_speed;

	protected boolean flipV;

	protected VehicleResourceSchema() {
	}

	protected VehicleResourceSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackString("base_train_type", value -> base_train_type = value);
		readerBase.unpackString("name", value -> name = value);
		readerBase.unpackString("color", value -> color = value);
		readerBase.unpackString("model", value -> model = value);
		readerBase.unpackString("model_properties", value -> model_properties = value);
		readerBase.unpackString("description", value -> description = value);
		readerBase.unpackString("wikipedia_article", value -> wikipedia_article = value);
		readerBase.unpackString("texture_id", value -> texture_id = value);
		readerBase.unpackBoolean("has_gangway_connection", value -> has_gangway_connection = value);
		readerBase.unpackString("gangway_connection_id", value -> gangway_connection_id = value);
		readerBase.unpackString("train_barrier_id", value -> train_barrier_id = value);
		readerBase.unpackString("door_animation_type", value -> door_animation_type = value);
		readerBase.unpackDouble("rider_offset", value -> rider_offset = value);
		readerBase.unpackString("bve_sound_base_id", value -> bve_sound_base_id = value);
		readerBase.unpackLong("speed_sound_count", value -> speed_sound_count = value);
		readerBase.unpackString("speed_sound_base_id", value -> speed_sound_base_id = value);
		readerBase.unpackString("door_sound_base_id", value -> door_sound_base_id = value);
		readerBase.unpackDouble("door_close_sound_time", value -> door_close_sound_time = value);
		readerBase.unpackBoolean("accel_sound_at_coast", value -> accel_sound_at_coast = value);
		readerBase.unpackBoolean("const_playback_speed", value -> const_playback_speed = value);
		readerBase.unpackBoolean("flipV", value -> flipV = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeBase_train_type(writerBase);
		serializeName(writerBase);
		serializeColor(writerBase);
		serializeModel(writerBase);
		serializeModel_properties(writerBase);
		serializeDescription(writerBase);
		serializeWikipedia_article(writerBase);
		serializeTexture_id(writerBase);
		serializeHas_gangway_connection(writerBase);
		serializeGangway_connection_id(writerBase);
		serializeTrain_barrier_id(writerBase);
		serializeDoor_animation_type(writerBase);
		serializeRider_offset(writerBase);
		serializeBve_sound_base_id(writerBase);
		serializeSpeed_sound_count(writerBase);
		serializeSpeed_sound_base_id(writerBase);
		serializeDoor_sound_base_id(writerBase);
		serializeDoor_close_sound_time(writerBase);
		serializeAccel_sound_at_coast(writerBase);
		serializeConst_playback_speed(writerBase);
		serializeFlipV(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "base_train_type: " + base_train_type + "\n"
			+ "name: " + name + "\n"
			+ "color: " + color + "\n"
			+ "model: " + model + "\n"
			+ "model_properties: " + model_properties + "\n"
			+ "description: " + description + "\n"
			+ "wikipedia_article: " + wikipedia_article + "\n"
			+ "texture_id: " + texture_id + "\n"
			+ "has_gangway_connection: " + has_gangway_connection + "\n"
			+ "gangway_connection_id: " + gangway_connection_id + "\n"
			+ "train_barrier_id: " + train_barrier_id + "\n"
			+ "door_animation_type: " + door_animation_type + "\n"
			+ "rider_offset: " + rider_offset + "\n"
			+ "bve_sound_base_id: " + bve_sound_base_id + "\n"
			+ "speed_sound_count: " + speed_sound_count + "\n"
			+ "speed_sound_base_id: " + speed_sound_base_id + "\n"
			+ "door_sound_base_id: " + door_sound_base_id + "\n"
			+ "door_close_sound_time: " + door_close_sound_time + "\n"
			+ "accel_sound_at_coast: " + accel_sound_at_coast + "\n"
			+ "const_playback_speed: " + const_playback_speed + "\n"
			+ "flipV: " + flipV + "\n"
		;
	}

	protected void serializeBase_train_type(final WriterBase writerBase) {
		writerBase.writeString("base_train_type", base_train_type);
	}

	protected void serializeName(final WriterBase writerBase) {
		writerBase.writeString("name", name);
	}

	protected void serializeColor(final WriterBase writerBase) {
		writerBase.writeString("color", color);
	}

	protected void serializeModel(final WriterBase writerBase) {
		writerBase.writeString("model", model);
	}

	protected void serializeModel_properties(final WriterBase writerBase) {
		writerBase.writeString("model_properties", model_properties);
	}

	protected void serializeDescription(final WriterBase writerBase) {
		writerBase.writeString("description", description);
	}

	protected void serializeWikipedia_article(final WriterBase writerBase) {
		writerBase.writeString("wikipedia_article", wikipedia_article);
	}

	protected void serializeTexture_id(final WriterBase writerBase) {
		writerBase.writeString("texture_id", texture_id);
	}

	protected void serializeHas_gangway_connection(final WriterBase writerBase) {
		writerBase.writeBoolean("has_gangway_connection", has_gangway_connection);
	}

	protected void serializeGangway_connection_id(final WriterBase writerBase) {
		writerBase.writeString("gangway_connection_id", gangway_connection_id);
	}

	protected void serializeTrain_barrier_id(final WriterBase writerBase) {
		writerBase.writeString("train_barrier_id", train_barrier_id);
	}

	protected void serializeDoor_animation_type(final WriterBase writerBase) {
		writerBase.writeString("door_animation_type", door_animation_type);
	}

	protected void serializeRider_offset(final WriterBase writerBase) {
		writerBase.writeDouble("rider_offset", rider_offset);
	}

	protected void serializeBve_sound_base_id(final WriterBase writerBase) {
		writerBase.writeString("bve_sound_base_id", bve_sound_base_id);
	}

	protected void serializeSpeed_sound_count(final WriterBase writerBase) {
		writerBase.writeLong("speed_sound_count", speed_sound_count);
	}

	protected void serializeSpeed_sound_base_id(final WriterBase writerBase) {
		writerBase.writeString("speed_sound_base_id", speed_sound_base_id);
	}

	protected void serializeDoor_sound_base_id(final WriterBase writerBase) {
		writerBase.writeString("door_sound_base_id", door_sound_base_id);
	}

	protected void serializeDoor_close_sound_time(final WriterBase writerBase) {
		writerBase.writeDouble("door_close_sound_time", door_close_sound_time);
	}

	protected void serializeAccel_sound_at_coast(final WriterBase writerBase) {
		writerBase.writeBoolean("accel_sound_at_coast", accel_sound_at_coast);
	}

	protected void serializeConst_playback_speed(final WriterBase writerBase) {
		writerBase.writeBoolean("const_playback_speed", const_playback_speed);
	}

	protected void serializeFlipV(final WriterBase writerBase) {
		writerBase.writeBoolean("flipV", flipV);
	}
}