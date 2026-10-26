package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class RailResourceSchema implements SerializedDataBase {

	protected final String id;

	protected final String name;

	protected final String color;

	protected final String modelResource;

	protected final String textureResource;

	protected final boolean flipTextureV;

	protected final double repeatInterval;

	protected final double modelYOffset;

	protected final ResourceProvider resourceProvider;

	protected RailResourceSchema(final String id, final String name, final String color, final String modelResource, final String textureResource, final boolean flipTextureV, final double repeatInterval, final double modelYOffset, final ResourceProvider resourceProvider) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.modelResource = modelResource;
		this.textureResource = textureResource;
		this.flipTextureV = flipTextureV;
		this.repeatInterval = repeatInterval;
		this.modelYOffset = modelYOffset;
		this.resourceProvider = resourceProvider;
	}

	protected RailResourceSchema(final ReaderBase readerBase, final ResourceProvider resourceProvider) {
		id = readerBase.getString("id", "");
		name = readerBase.getString("name", "");
		color = readerBase.getString("color", "");
		modelResource = readerBase.getString("modelResource", "");
		textureResource = readerBase.getString("textureResource", "");
		flipTextureV = readerBase.getBoolean("flipTextureV", false);
		repeatInterval = readerBase.getDouble("repeatInterval", 0);
		modelYOffset = readerBase.getDouble("modelYOffset", 0);
		this.resourceProvider = resourceProvider;
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("id", id);
		writerBase.writeString("name", name);
		writerBase.writeString("color", color);
		writerBase.writeString("modelResource", modelResource);
		writerBase.writeString("textureResource", textureResource);
		writerBase.writeBoolean("flipTextureV", flipTextureV);
		writerBase.writeDouble("repeatInterval", repeatInterval);
		writerBase.writeDouble("modelYOffset", modelYOffset);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "id: " + id + "\n"
			+ "name: " + name + "\n"
			+ "color: " + color + "\n"
			+ "modelResource: " + modelResource + "\n"
			+ "textureResource: " + textureResource + "\n"
			+ "flipTextureV: " + flipTextureV + "\n"
			+ "repeatInterval: " + repeatInterval + "\n"
			+ "modelYOffset: " + modelYOffset + "\n"
		;
	}
}