package org.mtr.legacy.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;

public abstract class RailResourceSchema implements SerializedDataBase {

	protected String atlasIndex = "";

	protected String model = "";

	protected String textureId = "";

	protected boolean flipV;

	protected double repeatInterval;

	protected double yOffset;

	protected String name = "";

	protected RailResourceSchema() {
	}

	protected RailResourceSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackString("atlasIndex", value -> atlasIndex = value);
		readerBase.unpackString("model", value -> model = value);
		readerBase.unpackString("textureId", value -> textureId = value);
		readerBase.unpackBoolean("flipV", value -> flipV = value);
		readerBase.unpackDouble("repeatInterval", value -> repeatInterval = value);
		readerBase.unpackDouble("yOffset", value -> yOffset = value);
		readerBase.unpackString("name", value -> name = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeAtlasIndex(writerBase);
		serializeModel(writerBase);
		serializeTextureId(writerBase);
		serializeFlipV(writerBase);
		serializeRepeatInterval(writerBase);
		serializeYOffset(writerBase);
		serializeName(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "atlasIndex: " + atlasIndex + "\n"
			+ "model: " + model + "\n"
			+ "textureId: " + textureId + "\n"
			+ "flipV: " + flipV + "\n"
			+ "repeatInterval: " + repeatInterval + "\n"
			+ "yOffset: " + yOffset + "\n"
			+ "name: " + name + "\n"
		;
	}

	protected void serializeAtlasIndex(final WriterBase writerBase) {
		writerBase.writeString("atlasIndex", atlasIndex);
	}

	protected void serializeModel(final WriterBase writerBase) {
		writerBase.writeString("model", model);
	}

	protected void serializeTextureId(final WriterBase writerBase) {
		writerBase.writeString("textureId", textureId);
	}

	protected void serializeFlipV(final WriterBase writerBase) {
		writerBase.writeBoolean("flipV", flipV);
	}

	protected void serializeRepeatInterval(final WriterBase writerBase) {
		writerBase.writeDouble("repeatInterval", repeatInterval);
	}

	protected void serializeYOffset(final WriterBase writerBase) {
		writerBase.writeDouble("yOffset", yOffset);
	}

	protected void serializeName(final WriterBase writerBase) {
		writerBase.writeString("name", name);
	}
}