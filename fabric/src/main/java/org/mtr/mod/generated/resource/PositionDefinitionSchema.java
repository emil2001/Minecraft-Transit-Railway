package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class PositionDefinitionSchema implements SerializedDataBase {

	protected final String name;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PartPosition> positions = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PartPosition>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PartPosition> positionsFlipped = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<PartPosition>();

	protected PositionDefinitionSchema(final String name) {
		this.name = name;
	}

	protected PositionDefinitionSchema(final ReaderBase readerBase) {
		name = readerBase.getString("name", "");
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("positions", positions::clear, readerBaseChild -> positions.add(new PartPosition(readerBaseChild)));
		readerBase.iterateReaderArray("positionsFlipped", positionsFlipped::clear, readerBaseChild -> positionsFlipped.add(new PartPosition(readerBaseChild)));
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("name", name);
		serializePositions(writerBase);
		serializePositionsFlipped(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "name: " + name + "\n"
			+ "positions: " + positions + "\n"
			+ "positionsFlipped: " + positionsFlipped + "\n"
		;
	}

	protected void serializePositions(final WriterBase writerBase) {
		writerBase.writeDataset(positions, "positions");
	}

	protected void serializePositionsFlipped(final WriterBase writerBase) {
		writerBase.writeDataset(positionsFlipped, "positionsFlipped");
	}
}