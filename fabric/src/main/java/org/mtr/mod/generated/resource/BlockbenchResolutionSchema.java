package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class BlockbenchResolutionSchema implements SerializedDataBase {

	protected final long width;

	protected final long height;

	protected BlockbenchResolutionSchema(final long width, final long height) {
		this.width = width;
		this.height = height;
	}

	protected BlockbenchResolutionSchema(final ReaderBase readerBase) {
		width = readerBase.getLong("width", 0);
		height = readerBase.getLong("height", 0);
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeLong("width", width);
		writerBase.writeLong("height", height);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "width: " + width + "\n"
			+ "height: " + height + "\n"
		;
	}
}