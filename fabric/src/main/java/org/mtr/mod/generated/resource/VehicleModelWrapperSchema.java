package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class VehicleModelWrapperSchema implements SerializedDataBase {

	protected final String modelResource;

	protected final String textureResource;

	protected final String minecraftModelPropertiesResource;

	protected final String minecraftPositionDefinitionsResource;

	protected final boolean flipTextureV;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ModelPropertiesPartWrapper> parts = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ModelPropertiesPartWrapper>();

	protected final double modelYOffset;

	protected final String gangwayInnerSideResource;

	protected final String gangwayInnerTopResource;

	protected final String gangwayInnerBottomResource;

	protected final String gangwayOuterSideResource;

	protected final String gangwayOuterTopResource;

	protected final String gangwayOuterBottomResource;

	protected final double gangwayWidth;

	protected final double gangwayHeight;

	protected final double gangwayYOffset;

	protected final double gangwayZOffset;

	protected final String barrierInnerSideResource;

	protected final String barrierInnerTopResource;

	protected final String barrierInnerBottomResource;

	protected final String barrierOuterSideResource;

	protected final String barrierOuterTopResource;

	protected final String barrierOuterBottomResource;

	protected final double barrierWidth;

	protected final double barrierHeight;

	protected final double barrierYOffset;

	protected final double barrierZOffset;

	protected VehicleModelWrapperSchema(final String modelResource, final String textureResource, final String minecraftModelPropertiesResource, final String minecraftPositionDefinitionsResource, final boolean flipTextureV, final double modelYOffset, final String gangwayInnerSideResource, final String gangwayInnerTopResource, final String gangwayInnerBottomResource, final String gangwayOuterSideResource, final String gangwayOuterTopResource, final String gangwayOuterBottomResource, final double gangwayWidth, final double gangwayHeight, final double gangwayYOffset, final double gangwayZOffset, final String barrierInnerSideResource, final String barrierInnerTopResource, final String barrierInnerBottomResource, final String barrierOuterSideResource, final String barrierOuterTopResource, final String barrierOuterBottomResource, final double barrierWidth, final double barrierHeight, final double barrierYOffset, final double barrierZOffset) {
		this.modelResource = modelResource;
		this.textureResource = textureResource;
		this.minecraftModelPropertiesResource = minecraftModelPropertiesResource;
		this.minecraftPositionDefinitionsResource = minecraftPositionDefinitionsResource;
		this.flipTextureV = flipTextureV;
		this.modelYOffset = modelYOffset;
		this.gangwayInnerSideResource = gangwayInnerSideResource;
		this.gangwayInnerTopResource = gangwayInnerTopResource;
		this.gangwayInnerBottomResource = gangwayInnerBottomResource;
		this.gangwayOuterSideResource = gangwayOuterSideResource;
		this.gangwayOuterTopResource = gangwayOuterTopResource;
		this.gangwayOuterBottomResource = gangwayOuterBottomResource;
		this.gangwayWidth = gangwayWidth;
		this.gangwayHeight = gangwayHeight;
		this.gangwayYOffset = gangwayYOffset;
		this.gangwayZOffset = gangwayZOffset;
		this.barrierInnerSideResource = barrierInnerSideResource;
		this.barrierInnerTopResource = barrierInnerTopResource;
		this.barrierInnerBottomResource = barrierInnerBottomResource;
		this.barrierOuterSideResource = barrierOuterSideResource;
		this.barrierOuterTopResource = barrierOuterTopResource;
		this.barrierOuterBottomResource = barrierOuterBottomResource;
		this.barrierWidth = barrierWidth;
		this.barrierHeight = barrierHeight;
		this.barrierYOffset = barrierYOffset;
		this.barrierZOffset = barrierZOffset;
	}

	protected VehicleModelWrapperSchema(final ReaderBase readerBase) {
		modelResource = readerBase.getString("modelResource", "");
		textureResource = readerBase.getString("textureResource", "");
		minecraftModelPropertiesResource = readerBase.getString("minecraftModelPropertiesResource", "");
		minecraftPositionDefinitionsResource = readerBase.getString("minecraftPositionDefinitionsResource", "");
		flipTextureV = readerBase.getBoolean("flipTextureV", false);
		modelYOffset = readerBase.getDouble("modelYOffset", 0);
		gangwayInnerSideResource = readerBase.getString("gangwayInnerSideResource", "");
		gangwayInnerTopResource = readerBase.getString("gangwayInnerTopResource", "");
		gangwayInnerBottomResource = readerBase.getString("gangwayInnerBottomResource", "");
		gangwayOuterSideResource = readerBase.getString("gangwayOuterSideResource", "");
		gangwayOuterTopResource = readerBase.getString("gangwayOuterTopResource", "");
		gangwayOuterBottomResource = readerBase.getString("gangwayOuterBottomResource", "");
		gangwayWidth = readerBase.getDouble("gangwayWidth", 0);
		gangwayHeight = readerBase.getDouble("gangwayHeight", 0);
		gangwayYOffset = readerBase.getDouble("gangwayYOffset", 0);
		gangwayZOffset = readerBase.getDouble("gangwayZOffset", 0);
		barrierInnerSideResource = readerBase.getString("barrierInnerSideResource", "");
		barrierInnerTopResource = readerBase.getString("barrierInnerTopResource", "");
		barrierInnerBottomResource = readerBase.getString("barrierInnerBottomResource", "");
		barrierOuterSideResource = readerBase.getString("barrierOuterSideResource", "");
		barrierOuterTopResource = readerBase.getString("barrierOuterTopResource", "");
		barrierOuterBottomResource = readerBase.getString("barrierOuterBottomResource", "");
		barrierWidth = readerBase.getDouble("barrierWidth", 0);
		barrierHeight = readerBase.getDouble("barrierHeight", 0);
		barrierYOffset = readerBase.getDouble("barrierYOffset", 0);
		barrierZOffset = readerBase.getDouble("barrierZOffset", 0);
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("parts", parts::clear, readerBaseChild -> parts.add(new ModelPropertiesPartWrapper(readerBaseChild)));
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("modelResource", modelResource);
		writerBase.writeString("textureResource", textureResource);
		writerBase.writeString("minecraftModelPropertiesResource", minecraftModelPropertiesResource);
		writerBase.writeString("minecraftPositionDefinitionsResource", minecraftPositionDefinitionsResource);
		writerBase.writeBoolean("flipTextureV", flipTextureV);
		serializeParts(writerBase);
		writerBase.writeDouble("modelYOffset", modelYOffset);
		writerBase.writeString("gangwayInnerSideResource", gangwayInnerSideResource);
		writerBase.writeString("gangwayInnerTopResource", gangwayInnerTopResource);
		writerBase.writeString("gangwayInnerBottomResource", gangwayInnerBottomResource);
		writerBase.writeString("gangwayOuterSideResource", gangwayOuterSideResource);
		writerBase.writeString("gangwayOuterTopResource", gangwayOuterTopResource);
		writerBase.writeString("gangwayOuterBottomResource", gangwayOuterBottomResource);
		writerBase.writeDouble("gangwayWidth", gangwayWidth);
		writerBase.writeDouble("gangwayHeight", gangwayHeight);
		writerBase.writeDouble("gangwayYOffset", gangwayYOffset);
		writerBase.writeDouble("gangwayZOffset", gangwayZOffset);
		writerBase.writeString("barrierInnerSideResource", barrierInnerSideResource);
		writerBase.writeString("barrierInnerTopResource", barrierInnerTopResource);
		writerBase.writeString("barrierInnerBottomResource", barrierInnerBottomResource);
		writerBase.writeString("barrierOuterSideResource", barrierOuterSideResource);
		writerBase.writeString("barrierOuterTopResource", barrierOuterTopResource);
		writerBase.writeString("barrierOuterBottomResource", barrierOuterBottomResource);
		writerBase.writeDouble("barrierWidth", barrierWidth);
		writerBase.writeDouble("barrierHeight", barrierHeight);
		writerBase.writeDouble("barrierYOffset", barrierYOffset);
		writerBase.writeDouble("barrierZOffset", barrierZOffset);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "modelResource: " + modelResource + "\n"
			+ "textureResource: " + textureResource + "\n"
			+ "minecraftModelPropertiesResource: " + minecraftModelPropertiesResource + "\n"
			+ "minecraftPositionDefinitionsResource: " + minecraftPositionDefinitionsResource + "\n"
			+ "flipTextureV: " + flipTextureV + "\n"
			+ "parts: " + parts + "\n"
			+ "modelYOffset: " + modelYOffset + "\n"
			+ "gangwayInnerSideResource: " + gangwayInnerSideResource + "\n"
			+ "gangwayInnerTopResource: " + gangwayInnerTopResource + "\n"
			+ "gangwayInnerBottomResource: " + gangwayInnerBottomResource + "\n"
			+ "gangwayOuterSideResource: " + gangwayOuterSideResource + "\n"
			+ "gangwayOuterTopResource: " + gangwayOuterTopResource + "\n"
			+ "gangwayOuterBottomResource: " + gangwayOuterBottomResource + "\n"
			+ "gangwayWidth: " + gangwayWidth + "\n"
			+ "gangwayHeight: " + gangwayHeight + "\n"
			+ "gangwayYOffset: " + gangwayYOffset + "\n"
			+ "gangwayZOffset: " + gangwayZOffset + "\n"
			+ "barrierInnerSideResource: " + barrierInnerSideResource + "\n"
			+ "barrierInnerTopResource: " + barrierInnerTopResource + "\n"
			+ "barrierInnerBottomResource: " + barrierInnerBottomResource + "\n"
			+ "barrierOuterSideResource: " + barrierOuterSideResource + "\n"
			+ "barrierOuterTopResource: " + barrierOuterTopResource + "\n"
			+ "barrierOuterBottomResource: " + barrierOuterBottomResource + "\n"
			+ "barrierWidth: " + barrierWidth + "\n"
			+ "barrierHeight: " + barrierHeight + "\n"
			+ "barrierYOffset: " + barrierYOffset + "\n"
			+ "barrierZOffset: " + barrierZOffset + "\n"
		;
	}

	protected void serializeParts(final WriterBase writerBase) {
		writerBase.writeDataset(parts, "parts");
	}
}