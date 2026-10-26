package org.mtr.legacy.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;

public abstract class ObjectResourceSchema implements SerializedDataBase {

	protected String name = "";

	protected String model = "";

	protected String textureId = "";

	protected boolean flipV;

	protected ObjectResourceSchema() {
	}

	protected ObjectResourceSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackString("name", value -> name = value);
		readerBase.unpackString("model", value -> model = value);
		readerBase.unpackString("textureId", value -> textureId = value);
		readerBase.unpackBoolean("flipV", value -> flipV = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeName(writerBase);
		serializeModel(writerBase);
		serializeTextureId(writerBase);
		serializeFlipV(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "name: " + name + "\n"
			+ "model: " + model + "\n"
			+ "textureId: " + textureId + "\n"
			+ "flipV: " + flipV + "\n"
		;
	}

	protected void serializeName(final WriterBase writerBase) {
		writerBase.writeString("name", name);
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
}