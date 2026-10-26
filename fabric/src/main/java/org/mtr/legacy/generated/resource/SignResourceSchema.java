package org.mtr.legacy.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;

public abstract class SignResourceSchema implements SerializedDataBase {

	protected String texture_id = "";

	protected boolean flip_texture;

	protected String custom_text = "";

	protected boolean flip_custom_text;

	protected boolean small;

	protected String background_color = "";

	protected SignResourceSchema() {
	}

	protected SignResourceSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackString("texture_id", value -> texture_id = value);
		readerBase.unpackBoolean("flip_texture", value -> flip_texture = value);
		readerBase.unpackString("custom_text", value -> custom_text = value);
		readerBase.unpackBoolean("flip_custom_text", value -> flip_custom_text = value);
		readerBase.unpackBoolean("small", value -> small = value);
		readerBase.unpackString("background_color", value -> background_color = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeTexture_id(writerBase);
		serializeFlip_texture(writerBase);
		serializeCustom_text(writerBase);
		serializeFlip_custom_text(writerBase);
		serializeSmall(writerBase);
		serializeBackground_color(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "texture_id: " + texture_id + "\n"
			+ "flip_texture: " + flip_texture + "\n"
			+ "custom_text: " + custom_text + "\n"
			+ "flip_custom_text: " + flip_custom_text + "\n"
			+ "small: " + small + "\n"
			+ "background_color: " + background_color + "\n"
		;
	}

	protected void serializeTexture_id(final WriterBase writerBase) {
		writerBase.writeString("texture_id", texture_id);
	}

	protected void serializeFlip_texture(final WriterBase writerBase) {
		writerBase.writeBoolean("flip_texture", flip_texture);
	}

	protected void serializeCustom_text(final WriterBase writerBase) {
		writerBase.writeString("custom_text", custom_text);
	}

	protected void serializeFlip_custom_text(final WriterBase writerBase) {
		writerBase.writeBoolean("flip_custom_text", flip_custom_text);
	}

	protected void serializeSmall(final WriterBase writerBase) {
		writerBase.writeBoolean("small", small);
	}

	protected void serializeBackground_color(final WriterBase writerBase) {
		writerBase.writeString("background_color", background_color);
	}
}