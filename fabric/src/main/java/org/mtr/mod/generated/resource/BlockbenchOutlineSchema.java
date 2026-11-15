package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class BlockbenchOutlineSchema implements SerializedDataBase {

	protected final String name;

	protected final String uuid;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline> children = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<BlockbenchOutline>();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList rotation = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList origin = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected BlockbenchOutlineSchema(final String name, final String uuid) {
		this.name = name;
		this.uuid = uuid;
	}

	protected BlockbenchOutlineSchema(final ReaderBase readerBase) {
		name = readerBase.getString("name", "");
		uuid = readerBase.getString("uuid", "");
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateReaderArray("children", children::clear, readerBaseChild -> children.add(new BlockbenchOutline(readerBaseChild)));
		readerBase.iterateDoubleArray("rotation", rotation::clear, rotation::add);
		readerBase.iterateDoubleArray("origin", origin::clear, origin::add);
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("name", name);
		writerBase.writeString("uuid", uuid);
		serializeChildren(writerBase);
		serializeRotation(writerBase);
		serializeOrigin(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "name: " + name + "\n"
			+ "uuid: " + uuid + "\n"
			+ "children: " + children + "\n"
			+ "rotation: " + rotation + "\n"
			+ "origin: " + origin + "\n"
		;
	}

	protected void serializeChildren(final WriterBase writerBase) {
		writerBase.writeDataset(children, "children");
	}

	protected void serializeRotation(final WriterBase writerBase) {
		final WriterBase.Array rotationWriterBaseArray = writerBase.writeArray("rotation"); rotation.forEach(rotationWriterBaseArray::writeDouble);
	}

	protected void serializeOrigin(final WriterBase writerBase) {
		final WriterBase.Array originWriterBaseArray = writerBase.writeArray("origin"); origin.forEach(originWriterBaseArray::writeDouble);
	}
}