package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class BlockbenchElementSchema implements SerializedDataBase {

	protected final String name;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList from = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList to = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected final double inflate;

	protected boolean shade = true;

	protected boolean mirror_uv = false;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList rotation = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList origin = new org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleArrayList();

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList uv_offset = new org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList();

	protected final String uuid;

	protected BlockbenchElementSchema(final String name, final double inflate, final String uuid) {
		this.name = name;
		this.inflate = inflate;
		this.uuid = uuid;
	}

	protected BlockbenchElementSchema(final ReaderBase readerBase) {
		name = readerBase.getString("name", "");
		inflate = readerBase.getDouble("inflate", 0);
		uuid = readerBase.getString("uuid", "");
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateDoubleArray("from", from::clear, from::add);
		readerBase.iterateDoubleArray("to", to::clear, to::add);
		readerBase.unpackBoolean("shade", value -> shade = value);
		readerBase.unpackBoolean("mirror_uv", value -> mirror_uv = value);
		readerBase.iterateDoubleArray("rotation", rotation::clear, rotation::add);
		readerBase.iterateDoubleArray("origin", origin::clear, origin::add);
		readerBase.iterateLongArray("uv_offset", uv_offset::clear, uv_offset::add);
	}

	public void serializeData(final WriterBase writerBase) {
		writerBase.writeString("name", name);
		serializeFrom(writerBase);
		serializeTo(writerBase);
		writerBase.writeDouble("inflate", inflate);
		serializeShade(writerBase);
		serializeMirror_uv(writerBase);
		serializeRotation(writerBase);
		serializeOrigin(writerBase);
		serializeUv_offset(writerBase);
		writerBase.writeString("uuid", uuid);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "name: " + name + "\n"
			+ "from: " + from + "\n"
			+ "to: " + to + "\n"
			+ "inflate: " + inflate + "\n"
			+ "shade: " + shade + "\n"
			+ "mirror_uv: " + mirror_uv + "\n"
			+ "rotation: " + rotation + "\n"
			+ "origin: " + origin + "\n"
			+ "uv_offset: " + uv_offset + "\n"
			+ "uuid: " + uuid + "\n"
		;
	}

	protected void serializeFrom(final WriterBase writerBase) {
		final WriterBase.Array fromWriterBaseArray = writerBase.writeArray("from"); from.forEach(fromWriterBaseArray::writeDouble);
	}

	protected void serializeTo(final WriterBase writerBase) {
		final WriterBase.Array toWriterBaseArray = writerBase.writeArray("to"); to.forEach(toWriterBaseArray::writeDouble);
	}

	protected void serializeShade(final WriterBase writerBase) {
		writerBase.writeBoolean("shade", shade);
	}

	protected void serializeMirror_uv(final WriterBase writerBase) {
		writerBase.writeBoolean("mirror_uv", mirror_uv);
	}

	protected void serializeRotation(final WriterBase writerBase) {
		final WriterBase.Array rotationWriterBaseArray = writerBase.writeArray("rotation"); rotation.forEach(rotationWriterBaseArray::writeDouble);
	}

	protected void serializeOrigin(final WriterBase writerBase) {
		final WriterBase.Array originWriterBaseArray = writerBase.writeArray("origin"); origin.forEach(originWriterBaseArray::writeDouble);
	}

	protected void serializeUv_offset(final WriterBase writerBase) {
		final WriterBase.Array uv_offsetWriterBaseArray = writerBase.writeArray("uv_offset"); uv_offset.forEach(uv_offsetWriterBaseArray::writeLong);
	}
}