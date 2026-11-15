package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class VehicleModelSchema implements SerializedDataBase {

	protected final String modelResource;

	protected final String textureResource;

	protected final String modelPropertiesResource;

	protected final String positionDefinitionsResource;

	protected final boolean flipTextureV;

	protected final ResourceProvider resourceProvider;

	protected VehicleModelSchema(final String modelResource, final String textureResource, final String modelPropertiesResource, final String positionDefinitionsResource, final boolean flipTextureV, final ResourceProvider resourceProvider) {
		this.modelResource = modelResource;
		this.textureResource = textureResource;
		this.modelPropertiesResource = modelPropertiesResource;
		this.positionDefinitionsResource = positionDefinitionsResource;
		this.flipTextureV = flipTextureV;
		this.resourceProvider = resourceProvider;
	}

	protected VehicleModelSchema(final ReaderBase readerBase, final ResourceProvider resourceProvider) {
		modelResource = readerBase.getString("modelResource", "");
		textureResource = readerBase.getString("textureResource", "");
		modelPropertiesResource = readerBase.getString("modelPropertiesResource", "");
		positionDefinitionsResource = readerBase.getString("positionDefinitionsResource", "");
		flipTextureV = readerBase.getBoolean("flipTextureV", false);
		this.resourceProvider = resourceProvider;
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("modelResource", modelResource);
		writerBase.writeString("textureResource", textureResource);
		writerBase.writeString("modelPropertiesResource", modelPropertiesResource);
		writerBase.writeString("positionDefinitionsResource", positionDefinitionsResource);
		writerBase.writeBoolean("flipTextureV", flipTextureV);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "modelResource: " + modelResource + "\n"
			+ "textureResource: " + textureResource + "\n"
			+ "modelPropertiesResource: " + modelPropertiesResource + "\n"
			+ "positionDefinitionsResource: " + positionDefinitionsResource + "\n"
			+ "flipTextureV: " + flipTextureV + "\n"
		;
	}
}