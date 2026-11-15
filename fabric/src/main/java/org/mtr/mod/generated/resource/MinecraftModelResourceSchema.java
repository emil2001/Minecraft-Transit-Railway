package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class MinecraftModelResourceSchema implements SerializedDataBase {

	protected final String modelResource;

	protected final String modelPropertiesResource;

	protected final String positionDefinitionsResource;

	protected MinecraftModelResourceSchema(final String modelResource, final String modelPropertiesResource, final String positionDefinitionsResource) {
		this.modelResource = modelResource;
		this.modelPropertiesResource = modelPropertiesResource;
		this.positionDefinitionsResource = positionDefinitionsResource;
	}

	protected MinecraftModelResourceSchema(final ReaderBase readerBase) {
		modelResource = readerBase.getString("modelResource", "");
		modelPropertiesResource = readerBase.getString("modelPropertiesResource", "");
		positionDefinitionsResource = readerBase.getString("positionDefinitionsResource", "");
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("modelResource", modelResource);
		writerBase.writeString("modelPropertiesResource", modelPropertiesResource);
		writerBase.writeString("positionDefinitionsResource", positionDefinitionsResource);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "modelResource: " + modelResource + "\n"
			+ "modelPropertiesResource: " + modelPropertiesResource + "\n"
			+ "positionDefinitionsResource: " + positionDefinitionsResource + "\n"
		;
	}
}