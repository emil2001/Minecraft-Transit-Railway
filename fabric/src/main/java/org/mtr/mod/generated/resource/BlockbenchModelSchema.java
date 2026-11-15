package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class BlockbenchModelSchema implements SerializedDataBase {

	protected final BlockbenchResolution resolution;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchElement> elements = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchElement>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline> groups = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline> outliner = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline>();

	protected BlockbenchModelSchema(final BlockbenchResolution resolution) {
		this.resolution = resolution;
	}

	protected BlockbenchModelSchema(final ReaderBase readerBase) {
		resolution = new BlockbenchResolution(readerBase.getChild("resolution"));
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("elements", elements::clear, readerBaseChild -> elements.add(new BlockbenchElement(readerBaseChild)));
		readerBase.iterateReaderArray("groups", groups::clear, readerBaseChild -> groups.add(new BlockbenchOutline(readerBaseChild)));
		readerBase.iterateReaderArray("outliner", outliner::clear, readerBaseChild -> outliner.add(new BlockbenchOutline(readerBaseChild)));
	}

	public void serializeData(final WriterBase writerBase) {
		if (resolution != null) resolution.serializeData(writerBase.writeChild("resolution"));
		serializeElements(writerBase);
		serializeGroups(writerBase);
		serializeOutliner(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "resolution: " + resolution + "\n"
			+ "elements: " + elements + "\n"
			+ "groups: " + groups + "\n"
			+ "outliner: " + outliner + "\n"
		;
	}

	protected void serializeElements(final WriterBase writerBase) {
		writerBase.writeDataset(elements, "elements");
	}

	protected void serializeGroups(final WriterBase writerBase) {
		writerBase.writeDataset(groups, "groups");
	}

	protected void serializeOutliner(final WriterBase writerBase) {
		writerBase.writeDataset(outliner, "outliner");
	}
}