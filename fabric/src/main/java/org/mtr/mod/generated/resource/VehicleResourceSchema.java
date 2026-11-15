package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class VehicleResourceSchema implements SerializedDataBase {

	protected final String id;

	protected final String name;

	protected final String color;

	protected final TransportMode transportMode;

	protected final double length;

	protected final double width;

	protected final double bogie1Position;

	protected final double bogie2Position;

	protected final double couplingPadding1;

	protected final double couplingPadding2;

	protected final String description;

	protected final String wikipediaArticle;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> tags = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel> models = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel> bogie1Models = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel> bogie2Models = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleModel>();

	protected final boolean hasGangway1;

	protected final boolean hasGangway2;

	protected final boolean hasBarrier1;

	protected final boolean hasBarrier2;

	protected final double legacyRiderOffset;

	protected final String bveSoundBaseResource;

	protected final String legacySpeedSoundBaseResource;

	protected final long legacySpeedSoundCount;

	protected final boolean legacyUseAccelerationSoundsWhenCoasting;

	protected final boolean legacyConstantPlaybackSpeed;

	protected final String legacyDoorSoundBaseResource;

	protected final double legacyDoorCloseSoundTime;

	protected final ResourceProvider resourceProvider;

	protected VehicleResourceSchema(final String id, final String name, final String color, final TransportMode transportMode, final double length, final double width, final double bogie1Position, final double bogie2Position, final double couplingPadding1, final double couplingPadding2, final String description, final String wikipediaArticle, final boolean hasGangway1, final boolean hasGangway2, final boolean hasBarrier1, final boolean hasBarrier2, final double legacyRiderOffset, final String bveSoundBaseResource, final String legacySpeedSoundBaseResource, final long legacySpeedSoundCount, final boolean legacyUseAccelerationSoundsWhenCoasting, final boolean legacyConstantPlaybackSpeed, final String legacyDoorSoundBaseResource, final double legacyDoorCloseSoundTime, final ResourceProvider resourceProvider) {
		this.id = id;
		this.name = name;
		this.color = color;
		this.transportMode = transportMode;
		this.length = length;
		this.width = width;
		this.bogie1Position = bogie1Position;
		this.bogie2Position = bogie2Position;
		this.couplingPadding1 = couplingPadding1;
		this.couplingPadding2 = couplingPadding2;
		this.description = description;
		this.wikipediaArticle = wikipediaArticle;
		this.hasGangway1 = hasGangway1;
		this.hasGangway2 = hasGangway2;
		this.hasBarrier1 = hasBarrier1;
		this.hasBarrier2 = hasBarrier2;
		this.legacyRiderOffset = legacyRiderOffset;
		this.bveSoundBaseResource = bveSoundBaseResource;
		this.legacySpeedSoundBaseResource = legacySpeedSoundBaseResource;
		this.legacySpeedSoundCount = legacySpeedSoundCount;
		this.legacyUseAccelerationSoundsWhenCoasting = legacyUseAccelerationSoundsWhenCoasting;
		this.legacyConstantPlaybackSpeed = legacyConstantPlaybackSpeed;
		this.legacyDoorSoundBaseResource = legacyDoorSoundBaseResource;
		this.legacyDoorCloseSoundTime = legacyDoorCloseSoundTime;
		this.resourceProvider = resourceProvider;
	}

	protected VehicleResourceSchema(final ReaderBase readerBase, final ResourceProvider resourceProvider) {
		id = readerBase.getString("id", "");
		name = readerBase.getString("name", "");
		color = readerBase.getString("color", "");
		transportMode = EnumHelper.valueOf(TransportMode.values()[0], readerBase.getString("transportMode", ""));
		length = readerBase.getDouble("length", 0);
		width = readerBase.getDouble("width", 0);
		bogie1Position = readerBase.getDouble("bogie1Position", 0);
		bogie2Position = readerBase.getDouble("bogie2Position", 0);
		couplingPadding1 = readerBase.getDouble("couplingPadding1", 0);
		couplingPadding2 = readerBase.getDouble("couplingPadding2", 0);
		description = readerBase.getString("description", "");
		wikipediaArticle = readerBase.getString("wikipediaArticle", "");
		hasGangway1 = readerBase.getBoolean("hasGangway1", false);
		hasGangway2 = readerBase.getBoolean("hasGangway2", false);
		hasBarrier1 = readerBase.getBoolean("hasBarrier1", false);
		hasBarrier2 = readerBase.getBoolean("hasBarrier2", false);
		legacyRiderOffset = readerBase.getDouble("legacyRiderOffset", 0);
		bveSoundBaseResource = readerBase.getString("bveSoundBaseResource", "");
		legacySpeedSoundBaseResource = readerBase.getString("legacySpeedSoundBaseResource", "");
		legacySpeedSoundCount = readerBase.getLong("legacySpeedSoundCount", 0);
		legacyUseAccelerationSoundsWhenCoasting = readerBase.getBoolean("legacyUseAccelerationSoundsWhenCoasting", false);
		legacyConstantPlaybackSpeed = readerBase.getBoolean("legacyConstantPlaybackSpeed", false);
		legacyDoorSoundBaseResource = readerBase.getString("legacyDoorSoundBaseResource", "");
		legacyDoorCloseSoundTime = readerBase.getDouble("legacyDoorCloseSoundTime", 0);
		this.resourceProvider = resourceProvider;
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateStringArray("tags", tags::clear, tags::add);
		readerBase.iterateReaderArray("models", models::clear, readerBaseChild -> models.add(new VehicleModel(readerBaseChild, modelsResourceProviderParameter())));
		readerBase.iterateReaderArray("bogie1Models", bogie1Models::clear, readerBaseChild -> bogie1Models.add(new VehicleModel(readerBaseChild, bogie1ModelsResourceProviderParameter())));
		readerBase.iterateReaderArray("bogie2Models", bogie2Models::clear, readerBaseChild -> bogie2Models.add(new VehicleModel(readerBaseChild, bogie2ModelsResourceProviderParameter())));
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("id", id);
		writerBase.writeString("name", name);
		writerBase.writeString("color", color);
		writerBase.writeString("transportMode", transportMode.toString());
		writerBase.writeDouble("length", length);
		writerBase.writeDouble("width", width);
		writerBase.writeDouble("bogie1Position", bogie1Position);
		writerBase.writeDouble("bogie2Position", bogie2Position);
		writerBase.writeDouble("couplingPadding1", couplingPadding1);
		writerBase.writeDouble("couplingPadding2", couplingPadding2);
		writerBase.writeString("description", description);
		writerBase.writeString("wikipediaArticle", wikipediaArticle);
		serializeTags(writerBase);
		serializeModels(writerBase);
		serializeBogie1Models(writerBase);
		serializeBogie2Models(writerBase);
		writerBase.writeBoolean("hasGangway1", hasGangway1);
		writerBase.writeBoolean("hasGangway2", hasGangway2);
		writerBase.writeBoolean("hasBarrier1", hasBarrier1);
		writerBase.writeBoolean("hasBarrier2", hasBarrier2);
		writerBase.writeDouble("legacyRiderOffset", legacyRiderOffset);
		writerBase.writeString("bveSoundBaseResource", bveSoundBaseResource);
		writerBase.writeString("legacySpeedSoundBaseResource", legacySpeedSoundBaseResource);
		writerBase.writeLong("legacySpeedSoundCount", legacySpeedSoundCount);
		writerBase.writeBoolean("legacyUseAccelerationSoundsWhenCoasting", legacyUseAccelerationSoundsWhenCoasting);
		writerBase.writeBoolean("legacyConstantPlaybackSpeed", legacyConstantPlaybackSpeed);
		writerBase.writeString("legacyDoorSoundBaseResource", legacyDoorSoundBaseResource);
		writerBase.writeDouble("legacyDoorCloseSoundTime", legacyDoorCloseSoundTime);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "id: " + id + "\n"
			+ "name: " + name + "\n"
			+ "color: " + color + "\n"
			+ "transportMode: " + transportMode + "\n"
			+ "length: " + length + "\n"
			+ "width: " + width + "\n"
			+ "bogie1Position: " + bogie1Position + "\n"
			+ "bogie2Position: " + bogie2Position + "\n"
			+ "couplingPadding1: " + couplingPadding1 + "\n"
			+ "couplingPadding2: " + couplingPadding2 + "\n"
			+ "description: " + description + "\n"
			+ "wikipediaArticle: " + wikipediaArticle + "\n"
			+ "tags: " + tags + "\n"
			+ "models: " + models + "\n"
			+ "bogie1Models: " + bogie1Models + "\n"
			+ "bogie2Models: " + bogie2Models + "\n"
			+ "hasGangway1: " + hasGangway1 + "\n"
			+ "hasGangway2: " + hasGangway2 + "\n"
			+ "hasBarrier1: " + hasBarrier1 + "\n"
			+ "hasBarrier2: " + hasBarrier2 + "\n"
			+ "legacyRiderOffset: " + legacyRiderOffset + "\n"
			+ "bveSoundBaseResource: " + bveSoundBaseResource + "\n"
			+ "legacySpeedSoundBaseResource: " + legacySpeedSoundBaseResource + "\n"
			+ "legacySpeedSoundCount: " + legacySpeedSoundCount + "\n"
			+ "legacyUseAccelerationSoundsWhenCoasting: " + legacyUseAccelerationSoundsWhenCoasting + "\n"
			+ "legacyConstantPlaybackSpeed: " + legacyConstantPlaybackSpeed + "\n"
			+ "legacyDoorSoundBaseResource: " + legacyDoorSoundBaseResource + "\n"
			+ "legacyDoorCloseSoundTime: " + legacyDoorCloseSoundTime + "\n"
		;
	}

	protected void serializeTags(final WriterBase writerBase) {
		final WriterBase.Array tagsWriterBaseArray = writerBase.writeArray("tags"); tags.forEach(tagsWriterBaseArray::writeString);
	}

	protected void serializeModels(final WriterBase writerBase) {
		writerBase.writeDataset(models, "models");
	}

	@Nonnull
	protected abstract ResourceProvider modelsResourceProviderParameter();

	protected void serializeBogie1Models(final WriterBase writerBase) {
		writerBase.writeDataset(bogie1Models, "bogie1Models");
	}

	@Nonnull
	protected abstract ResourceProvider bogie1ModelsResourceProviderParameter();

	protected void serializeBogie2Models(final WriterBase writerBase) {
		writerBase.writeDataset(bogie2Models, "bogie2Models");
	}

	@Nonnull
	protected abstract ResourceProvider bogie2ModelsResourceProviderParameter();
}