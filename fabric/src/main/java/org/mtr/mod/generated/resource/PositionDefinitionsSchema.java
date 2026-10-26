package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class PositionDefinitionsSchema implements SerializedDataBase {

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PositionDefinition> positionDefinitions = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PositionDefinition>();

	protected PositionDefinitionsSchema() {
	}

	protected PositionDefinitionsSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("positionDefinitions", positionDefinitions::clear, readerBaseChild -> positionDefinitions.add(new PositionDefinition(readerBaseChild)));
	}

	public void serializeData(final WriterBase writerBase) {
		serializePositionDefinitions(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "positionDefinitions: " + positionDefinitions + "\n"
		;
	}

	protected void serializePositionDefinitions(final WriterBase writerBase) {
		writerBase.writeDataset(positionDefinitions, "positionDefinitions");
	}
}