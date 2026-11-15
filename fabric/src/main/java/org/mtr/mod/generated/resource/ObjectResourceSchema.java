package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class ObjectResourceSchema implements SerializedDataBase {

	protected final String id;

	protected final String name;

	protected final String color;

	protected final String modelResource;

	protected final String textureResource;

	protected final boolean flipTextureV;

	protected final PartPosition translation;

	protected final PartPosition rotation;

	protected final PartPosition scale;

	protected final ModelMirror mirror;

	protected final ResourceProvider resourceProvider;

	protected ObjectResourceSchema(final String id, final String name, final String color, final String modelResource, final String textureResource, final boolean flipTextureV, final PartPosition translation, final PartPosition rotation, final PartPosition scale, final ModelMirror mirror, final ResourceProvider resourceProvider) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.modelResource = modelResource;
		this.textureResource = textureResource;
		this.flipTextureV = flipTextureV;
		this.translation = translation;
		this.rotation = rotation;
		this.scale = scale;
		this.mirror = mirror;
		this.resourceProvider = resourceProvider;
	}

	protected ObjectResourceSchema(final ReaderBase readerBase, final ResourceProvider resourceProvider) {
		id = readerBase.getString("id", "");
		name = readerBase.getString("name", "");
		color = readerBase.getString("color", "");
		modelResource = readerBase.getString("modelResource", "");
		textureResource = readerBase.getString("textureResource", "");
		flipTextureV = readerBase.getBoolean("flipTextureV", false);
		translation = new PartPosition(readerBase.getChild("translation"));
		rotation = new PartPosition(readerBase.getChild("rotation"));
		scale = new PartPosition(readerBase.getChild("scale"));
		mirror = new ModelMirror(readerBase.getChild("mirror"));
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
		if (translation != null) translation.serializeData(writerBase.writeChild("translation"));
		if (rotation != null) rotation.serializeData(writerBase.writeChild("rotation"));
		if (scale != null) scale.serializeData(writerBase.writeChild("scale"));
		if (mirror != null) mirror.serializeData(writerBase.writeChild("mirror"));
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
			+ "translation: " + translation + "\n"
			+ "rotation: " + rotation + "\n"
			+ "scale: " + scale + "\n"
			+ "mirror: " + mirror + "\n"
		;
	}
}