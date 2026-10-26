package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class PartPositionSchema implements SerializedDataBase {

	protected final double x;

	protected final double y;

	protected final double z;

	protected PartPositionSchema(final double x, final double y, final double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	protected PartPositionSchema(final ReaderBase readerBase) {
		x = readerBase.getDouble("x", 0);
		y = readerBase.getDouble("y", 0);
		z = readerBase.getDouble("z", 0);
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeDouble("x", x);
		writerBase.writeDouble("y", y);
		writerBase.writeDouble("z", z);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "x: " + x + "\n"
			+ "y: " + y + "\n"
			+ "z: " + z + "\n"
		;
	}
}