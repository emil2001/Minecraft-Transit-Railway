package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class ModelMirrorSchema implements SerializedDataBase {

	protected final boolean x;

	protected final boolean y;

	protected final boolean z;

	protected ModelMirrorSchema(final boolean x, final boolean y, final boolean z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	protected ModelMirrorSchema(final ReaderBase readerBase) {
		x = readerBase.getBoolean("x", false);
		y = readerBase.getBoolean("y", false);
		z = readerBase.getBoolean("z", false);
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeBoolean("x", x);
		writerBase.writeBoolean("y", y);
		writerBase.writeBoolean("z", z);
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