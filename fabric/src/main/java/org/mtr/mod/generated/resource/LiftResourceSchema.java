package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class LiftResourceSchema implements SerializedDataBase {

	protected final String id;

	protected final String name;

	protected final String color;

	protected final String textureResource;

	protected LiftResourceSchema(final String id, final String name, final String color, final String textureResource) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.textureResource = textureResource;
	}

	protected LiftResourceSchema(final ReaderBase readerBase) {
		id = readerBase.getString("id", "");
		name = readerBase.getString("name", "");
		color = readerBase.getString("color", "");
		textureResource = readerBase.getString("textureResource", "");
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("id", id);
		writerBase.writeString("name", name);
		writerBase.writeString("color", color);
		writerBase.writeString("textureResource", textureResource);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "id: " + id + "\n"
			+ "name: " + name + "\n"
			+ "color: " + color + "\n"
			+ "textureResource: " + textureResource + "\n"
		;
	}
}