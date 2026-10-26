package org.mtr.mod.generated.resource;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.core.data.*;
import org.mtr.mod.resource.*;

public abstract class ModelPropertiesPartWrapperSchema implements SerializedDataBase {

	protected final PositionDefinition positionDefinition;

	protected final PartCondition condition;

	protected final RenderStage renderStage;

	protected final PartType type;

	protected final double displayXPadding;

	protected final double displayYPadding;

	protected final String displayColorCjk;

	protected final String displayColor;

	protected final double displayMaxLineHeight;

	protected final double displayCjkSizeRatio;

	protected final org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String> displayOptions = new org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList<String>();

	protected final double displayPadZeros;

	protected final DisplayType displayType;

	protected final String displayDefaultText;

	protected final double doorXMultiplier;

	protected final double doorZMultiplier;

	protected final DoorAnimationType doorAnimationType;

	protected final long renderFromOpeningDoorTime;

	protected final long renderUntilOpeningDoorTime;

	protected final long renderFromClosingDoorTime;

	protected final long renderUntilClosingDoorTime;

	protected final long flashOffTime;

	protected final long flashOnTime;

	protected ModelPropertiesPartWrapperSchema(final PositionDefinition positionDefinition, final PartCondition condition, final RenderStage renderStage, final PartType type, final double displayXPadding, final double displayYPadding, final String displayColorCjk, final String displayColor, final double displayMaxLineHeight, final double displayCjkSizeRatio, final double displayPadZeros, final DisplayType displayType, final String displayDefaultText, final double doorXMultiplier, final double doorZMultiplier, final DoorAnimationType doorAnimationType, final long renderFromOpeningDoorTime, final long renderUntilOpeningDoorTime, final long renderFromClosingDoorTime, final long renderUntilClosingDoorTime, final long flashOffTime, final long flashOnTime) {
		this.positionDefinition = positionDefinition;
		this.condition = condition;
		this.renderStage = renderStage;
		this.type = type;
		this.displayXPadding = displayXPadding;
		this.displayYPadding = displayYPadding;
		this.displayColorCjk = displayColorCjk;
		this.displayColor = displayColor;
		this.displayMaxLineHeight = displayMaxLineHeight;
		this.displayCjkSizeRatio = displayCjkSizeRatio;
		this.displayPadZeros = displayPadZeros;
		this.displayType = displayType;
		this.displayDefaultText = displayDefaultText;
		this.doorXMultiplier = doorXMultiplier;
		this.doorZMultiplier = doorZMultiplier;
		this.doorAnimationType = doorAnimationType;
		this.renderFromOpeningDoorTime = renderFromOpeningDoorTime;
		this.renderUntilOpeningDoorTime = renderUntilOpeningDoorTime;
		this.renderFromClosingDoorTime = renderFromClosingDoorTime;
		this.renderUntilClosingDoorTime = renderUntilClosingDoorTime;
		this.flashOffTime = flashOffTime;
		this.flashOnTime = flashOnTime;
	}

	protected ModelPropertiesPartWrapperSchema(final ReaderBase readerBase) {
		positionDefinition = new PositionDefinition(readerBase.getChild("positionDefinition"));
		condition = EnumHelper.valueOf(PartCondition.values()[0], readerBase.getString("condition", ""));
		renderStage = EnumHelper.valueOf(RenderStage.values()[0], readerBase.getString("renderStage", ""));
		type = EnumHelper.valueOf(PartType.values()[0], readerBase.getString("type", ""));
		displayXPadding = readerBase.getDouble("displayXPadding", 0);
		displayYPadding = readerBase.getDouble("displayYPadding", 0);
		displayColorCjk = readerBase.getString("displayColorCjk", "");
		displayColor = readerBase.getString("displayColor", "");
		displayMaxLineHeight = readerBase.getDouble("displayMaxLineHeight", 0);
		displayCjkSizeRatio = readerBase.getDouble("displayCjkSizeRatio", 0);
		displayPadZeros = readerBase.getDouble("displayPadZeros", 0);
		displayType = EnumHelper.valueOf(DisplayType.values()[0], readerBase.getString("displayType", ""));
		displayDefaultText = readerBase.getString("displayDefaultText", "");
		doorXMultiplier = readerBase.getDouble("doorXMultiplier", 0);
		doorZMultiplier = readerBase.getDouble("doorZMultiplier", 0);
		doorAnimationType = EnumHelper.valueOf(DoorAnimationType.values()[0], readerBase.getString("doorAnimationType", ""));
		renderFromOpeningDoorTime = readerBase.getLong("renderFromOpeningDoorTime", 0);
		renderUntilOpeningDoorTime = readerBase.getLong("renderUntilOpeningDoorTime", 0);
		renderFromClosingDoorTime = readerBase.getLong("renderFromClosingDoorTime", 0);
		renderUntilClosingDoorTime = readerBase.getLong("renderUntilClosingDoorTime", 0);
		flashOffTime = readerBase.getLong("flashOffTime", 0);
		flashOnTime = readerBase.getLong("flashOnTime", 0);
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.iterateStringArray("displayOptions", displayOptions::clear, displayOptions::add);
	}

	public void serializeData(final WriterBase writerBase) {
		if (positionDefinition != null) positionDefinition.serializeData(writerBase.writeChild("positionDefinition"));
		writerBase.writeString("condition", condition.toString());
		writerBase.writeString("renderStage", renderStage.toString());
		writerBase.writeString("type", type.toString());
		writerBase.writeDouble("displayXPadding", displayXPadding);
		writerBase.writeDouble("displayYPadding", displayYPadding);
		writerBase.writeString("displayColorCjk", displayColorCjk);
		writerBase.writeString("displayColor", displayColor);
		writerBase.writeDouble("displayMaxLineHeight", displayMaxLineHeight);
		writerBase.writeDouble("displayCjkSizeRatio", displayCjkSizeRatio);
		serializeDisplayOptions(writerBase);
		writerBase.writeDouble("displayPadZeros", displayPadZeros);
		writerBase.writeString("displayType", displayType.toString());
		writerBase.writeString("displayDefaultText", displayDefaultText);
		writerBase.writeDouble("doorXMultiplier", doorXMultiplier);
		writerBase.writeDouble("doorZMultiplier", doorZMultiplier);
		writerBase.writeString("doorAnimationType", doorAnimationType.toString());
		writerBase.writeLong("renderFromOpeningDoorTime", renderFromOpeningDoorTime);
		writerBase.writeLong("renderUntilOpeningDoorTime", renderUntilOpeningDoorTime);
		writerBase.writeLong("renderFromClosingDoorTime", renderFromClosingDoorTime);
		writerBase.writeLong("renderUntilClosingDoorTime", renderUntilClosingDoorTime);
		writerBase.writeLong("flashOffTime", flashOffTime);
		writerBase.writeLong("flashOnTime", flashOnTime);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "positionDefinition: " + positionDefinition + "\n"
			+ "condition: " + condition + "\n"
			+ "renderStage: " + renderStage + "\n"
			+ "type: " + type + "\n"
			+ "displayXPadding: " + displayXPadding + "\n"
			+ "displayYPadding: " + displayYPadding + "\n"
			+ "displayColorCjk: " + displayColorCjk + "\n"
			+ "displayColor: " + displayColor + "\n"
			+ "displayMaxLineHeight: " + displayMaxLineHeight + "\n"
			+ "displayCjkSizeRatio: " + displayCjkSizeRatio + "\n"
			+ "displayOptions: " + displayOptions + "\n"
			+ "displayPadZeros: " + displayPadZeros + "\n"
			+ "displayType: " + displayType + "\n"
			+ "displayDefaultText: " + displayDefaultText + "\n"
			+ "doorXMultiplier: " + doorXMultiplier + "\n"
			+ "doorZMultiplier: " + doorZMultiplier + "\n"
			+ "doorAnimationType: " + doorAnimationType + "\n"
			+ "renderFromOpeningDoorTime: " + renderFromOpeningDoorTime + "\n"
			+ "renderUntilOpeningDoorTime: " + renderUntilOpeningDoorTime + "\n"
			+ "renderFromClosingDoorTime: " + renderFromClosingDoorTime + "\n"
			+ "renderUntilClosingDoorTime: " + renderUntilClosingDoorTime + "\n"
			+ "flashOffTime: " + flashOffTime + "\n"
			+ "flashOnTime: " + flashOnTime + "\n"
		;
	}

	protected void serializeDisplayOptions(final WriterBase writerBase) {
		final WriterBase.Array displayOptionsWriterBaseArray = writerBase.writeArray("displayOptions"); displayOptions.forEach(displayOptionsWriterBaseArray::writeString);
	}
}