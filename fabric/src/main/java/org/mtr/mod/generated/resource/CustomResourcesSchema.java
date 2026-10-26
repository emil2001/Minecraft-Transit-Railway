package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class CustomResourcesSchema implements SerializedDataBase {

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleResource> vehicles = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<VehicleResource>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<SignResource> signs = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<SignResource>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<RailResource> rails = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<RailResource>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ObjectResource> objects = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<ObjectResource>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<LiftResource> lifts = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<LiftResource>();

	protected final ResourceProvider resourceProvider;

	protected CustomResourcesSchema(final ResourceProvider resourceProvider) {
		this.resourceProvider = resourceProvider;
	}

	protected CustomResourcesSchema(final ReaderBase readerBase, final ResourceProvider resourceProvider) {
		this.resourceProvider = resourceProvider;
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("vehicles", vehicles::clear, readerBaseChild -> vehicles.add(new VehicleResource(readerBaseChild, vehiclesResourceProviderParameter())));
		readerBase.iterateReaderArray("signs", signs::clear, readerBaseChild -> signs.add(new SignResource(readerBaseChild)));
		readerBase.iterateReaderArray("rails", rails::clear, readerBaseChild -> rails.add(new RailResource(readerBaseChild, railsResourceProviderParameter())));
		readerBase.iterateReaderArray("objects", objects::clear, readerBaseChild -> objects.add(new ObjectResource(readerBaseChild, objectsResourceProviderParameter())));
		readerBase.iterateReaderArray("lifts", lifts::clear, readerBaseChild -> lifts.add(new LiftResource(readerBaseChild)));
	}

	public void serializeData(final WriterBase writerBase) {
		serializeVehicles(writerBase);
		serializeSigns(writerBase);
		serializeRails(writerBase);
		serializeObjects(writerBase);
		serializeLifts(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "vehicles: " + vehicles + "\n"
			+ "signs: " + signs + "\n"
			+ "rails: " + rails + "\n"
			+ "objects: " + objects + "\n"
			+ "lifts: " + lifts + "\n"
		;
	}

	protected void serializeVehicles(final WriterBase writerBase) {
		writerBase.writeDataset(vehicles, "vehicles");
	}

	@Nonnull
	protected abstract ResourceProvider vehiclesResourceProviderParameter();

	protected void serializeSigns(final WriterBase writerBase) {
		writerBase.writeDataset(signs, "signs");
	}

	protected void serializeRails(final WriterBase writerBase) {
		writerBase.writeDataset(rails, "rails");
	}

	@Nonnull
	protected abstract ResourceProvider railsResourceProviderParameter();

	protected void serializeObjects(final WriterBase writerBase) {
		writerBase.writeDataset(objects, "objects");
	}

	@Nonnull
	protected abstract ResourceProvider objectsResourceProviderParameter();

	protected void serializeLifts(final WriterBase writerBase) {
		writerBase.writeDataset(lifts, "lifts");
	}
}