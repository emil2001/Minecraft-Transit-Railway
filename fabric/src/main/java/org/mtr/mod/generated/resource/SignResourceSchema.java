package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class SignResourceSchema implements SerializedDataBase {

	protected final String id;

	protected final String textureResource;

	protected final boolean flipTexture;

	protected final String customText;

	protected final boolean flipCustomText;

	protected final boolean small;

	protected final String backgroundColor;

	protected SignResourceSchema(final String id, final String textureResource, final boolean flipTexture, final String customText, final boolean flipCustomText, final boolean small, final String backgroundColor) {
		this.id = id;
		this.textureResource = textureResource;
		this.flipTexture = flipTexture;
		this.customText = customText;
		this.flipCustomText = flipCustomText;
		this.small = small;
		this.backgroundColor = backgroundColor;
	}

	protected SignResourceSchema(final ReaderBase readerBase) {
		id = readerBase.getString("id", "");
		textureResource = readerBase.getString("textureResource", "");
		flipTexture = readerBase.getBoolean("flipTexture", false);
		customText = readerBase.getString("customText", "");
		flipCustomText = readerBase.getBoolean("flipCustomText", false);
		small = readerBase.getBoolean("small", false);
		backgroundColor = readerBase.getString("backgroundColor", "");
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("id", id);
		writerBase.writeString("textureResource", textureResource);
		writerBase.writeBoolean("flipTexture", flipTexture);
		writerBase.writeString("customText", customText);
		writerBase.writeBoolean("flipCustomText", flipCustomText);
		writerBase.writeBoolean("small", small);
		writerBase.writeString("backgroundColor", backgroundColor);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "id: " + id + "\n"
			+ "textureResource: " + textureResource + "\n"
			+ "flipTexture: " + flipTexture + "\n"
			+ "customText: " + customText + "\n"
			+ "flipCustomText: " + flipCustomText + "\n"
			+ "small: " + small + "\n"
			+ "backgroundColor: " + backgroundColor + "\n"
		;
	}
}