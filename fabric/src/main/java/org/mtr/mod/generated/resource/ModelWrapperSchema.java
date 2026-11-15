package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class ModelWrapperSchema implements SerializedDataBase {

	protected final String id;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> modelParts = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String>();

	protected ModelWrapperSchema(final String id) {
		this.id = id;
	}

	protected ModelWrapperSchema(final ReaderBase readerBase) {
		id = readerBase.getString("id", "");
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateStringArray("modelParts", modelParts::clear, modelParts::add);
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("id", id);
		serializeModelParts(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "id: " + id + "\n"
			+ "modelParts: " + modelParts + "\n"
		;
	}

	protected void serializeModelParts(final WriterBase writerBase) {
		final WriterBase.Array modelPartsWriterBaseArray = writerBase.writeArray("modelParts"); modelParts.forEach(modelPartsWriterBaseArray::writeString);
	}
}