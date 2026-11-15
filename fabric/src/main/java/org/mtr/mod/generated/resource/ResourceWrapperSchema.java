package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class ResourceWrapperSchema implements SerializedDataBase {

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleResourceWrapper> vehicles = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleResourceWrapper>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ModelWrapper> modelResources = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ModelWrapper>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> textureResources = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<MinecraftModelResource> minecraftModelResources = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<MinecraftModelResource>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> minecraftTextureResources = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String>();

	protected boolean isMinecraftPaused;

	protected String exportDirectory = "";

	protected ResourceWrapperSchema() {
	}

	protected ResourceWrapperSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("vehicles", vehicles::clear, readerBaseChild -> vehicles.add(new VehicleResourceWrapper(readerBaseChild)));
		readerBase.iterateReaderArray("modelResources", modelResources::clear, readerBaseChild -> modelResources.add(new ModelWrapper(readerBaseChild)));
		readerBase.iterateStringArray("textureResources", textureResources::clear, textureResources::add);
		readerBase.iterateReaderArray("minecraftModelResources", minecraftModelResources::clear, readerBaseChild -> minecraftModelResources.add(new MinecraftModelResource(readerBaseChild)));
		readerBase.iterateStringArray("minecraftTextureResources", minecraftTextureResources::clear, minecraftTextureResources::add);
		readerBase.unpackBoolean("isMinecraftPaused", value -> isMinecraftPaused = value);
		readerBase.unpackString("exportDirectory", value -> exportDirectory = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeVehicles(writerBase);
		serializeModelResources(writerBase);
		serializeTextureResources(writerBase);
		serializeMinecraftModelResources(writerBase);
		serializeMinecraftTextureResources(writerBase);
		serializeIsMinecraftPaused(writerBase);
		serializeExportDirectory(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "vehicles: " + vehicles + "\n"
			+ "modelResources: " + modelResources + "\n"
			+ "textureResources: " + textureResources + "\n"
			+ "minecraftModelResources: " + minecraftModelResources + "\n"
			+ "minecraftTextureResources: " + minecraftTextureResources + "\n"
			+ "isMinecraftPaused: " + isMinecraftPaused + "\n"
			+ "exportDirectory: " + exportDirectory + "\n"
		;
	}

	protected void serializeVehicles(final WriterBase writerBase) {
		writerBase.writeDataset(vehicles, "vehicles");
	}

	protected void serializeModelResources(final WriterBase writerBase) {
		writerBase.writeDataset(modelResources, "modelResources");
	}

	protected void serializeTextureResources(final WriterBase writerBase) {
		final WriterBase.Array textureResourcesWriterBaseArray = writerBase.writeArray("textureResources"); textureResources.forEach(textureResourcesWriterBaseArray::writeString);
	}

	protected void serializeMinecraftModelResources(final WriterBase writerBase) {
		writerBase.writeDataset(minecraftModelResources, "minecraftModelResources");
	}

	protected void serializeMinecraftTextureResources(final WriterBase writerBase) {
		final WriterBase.Array minecraftTextureResourcesWriterBaseArray = writerBase.writeArray("minecraftTextureResources"); minecraftTextureResources.forEach(minecraftTextureResourcesWriterBaseArray::writeString);
	}

	protected void serializeIsMinecraftPaused(final WriterBase writerBase) {
		writerBase.writeBoolean("isMinecraftPaused", isMinecraftPaused);
	}

	protected void serializeExportDirectory(final WriterBase writerBase) {
		writerBase.writeString("exportDirectory", exportDirectory);
	}
}