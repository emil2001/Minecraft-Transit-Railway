package org.mtr.mod.generated.config;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.mod.config.*;

public abstract class ClientSchema implements SerializedDataBase {

	protected boolean chatAnnouncements = true;

	protected boolean textToSpeechAnnouncements = true;

	protected boolean hideTranslucentParts;

	protected LanguageDisplay languageDisplay = LanguageDisplay.values()[0];

	protected double vehicleOscillationMultiplier = 1.0;

	protected long dynamicTextureResolution = 2;

	protected boolean defaultRail3D = true;

	protected boolean useMTRFont;

	protected boolean disableShadowsForShaders;

	protected String preloadResourcePattern = "";

	protected String betaWarningVersion = "";

	protected ClientSchema() {
	}

	protected ClientSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackBoolean("chatAnnouncements", value -> chatAnnouncements = value);
		readerBase.unpackBoolean("textToSpeechAnnouncements", value -> textToSpeechAnnouncements = value);
		readerBase.unpackBoolean("hideTranslucentParts", value -> hideTranslucentParts = value);
		readerBase.unpackString("languageDisplay", value -> languageDisplay = EnumHelper.valueOf(LanguageDisplay.values()[0], value));
		readerBase.unpackDouble("vehicleOscillationMultiplier", value -> vehicleOscillationMultiplier = value);
		readerBase.unpackLong("dynamicTextureResolution", value -> dynamicTextureResolution = value);
		readerBase.unpackBoolean("defaultRail3D", value -> defaultRail3D = value);
		readerBase.unpackBoolean("useMTRFont", value -> useMTRFont = value);
		readerBase.unpackBoolean("disableShadowsForShaders", value -> disableShadowsForShaders = value);
		readerBase.unpackString("preloadResourcePattern", value -> preloadResourcePattern = value);
		readerBase.unpackString("betaWarningVersion", value -> betaWarningVersion = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeChatAnnouncements(writerBase);
		serializeTextToSpeechAnnouncements(writerBase);
		serializeHideTranslucentParts(writerBase);
		serializeLanguageDisplay(writerBase);
		serializeVehicleOscillationMultiplier(writerBase);
		serializeDynamicTextureResolution(writerBase);
		serializeDefaultRail3D(writerBase);
		serializeUseMTRFont(writerBase);
		serializeDisableShadowsForShaders(writerBase);
		serializePreloadResourcePattern(writerBase);
		serializeBetaWarningVersion(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "chatAnnouncements: " + chatAnnouncements + "\n"
			+ "textToSpeechAnnouncements: " + textToSpeechAnnouncements + "\n"
			+ "hideTranslucentParts: " + hideTranslucentParts + "\n"
			+ "languageDisplay: " + languageDisplay + "\n"
			+ "vehicleOscillationMultiplier: " + vehicleOscillationMultiplier + "\n"
			+ "dynamicTextureResolution: " + dynamicTextureResolution + "\n"
			+ "defaultRail3D: " + defaultRail3D + "\n"
			+ "useMTRFont: " + useMTRFont + "\n"
			+ "disableShadowsForShaders: " + disableShadowsForShaders + "\n"
			+ "preloadResourcePattern: " + preloadResourcePattern + "\n"
			+ "betaWarningVersion: " + betaWarningVersion + "\n"
		;
	}

	protected void serializeChatAnnouncements(final WriterBase writerBase) {
		writerBase.writeBoolean("chatAnnouncements", chatAnnouncements);
	}

	protected void serializeTextToSpeechAnnouncements(final WriterBase writerBase) {
		writerBase.writeBoolean("textToSpeechAnnouncements", textToSpeechAnnouncements);
	}

	protected void serializeHideTranslucentParts(final WriterBase writerBase) {
		writerBase.writeBoolean("hideTranslucentParts", hideTranslucentParts);
	}

	protected void serializeLanguageDisplay(final WriterBase writerBase) {
		writerBase.writeString("languageDisplay", languageDisplay.toString());
	}

	protected void serializeVehicleOscillationMultiplier(final WriterBase writerBase) {
		writerBase.writeDouble("vehicleOscillationMultiplier", vehicleOscillationMultiplier);
	}

	protected void serializeDynamicTextureResolution(final WriterBase writerBase) {
		writerBase.writeLong("dynamicTextureResolution", dynamicTextureResolution);
	}

	protected void serializeDefaultRail3D(final WriterBase writerBase) {
		writerBase.writeBoolean("defaultRail3D", defaultRail3D);
	}

	protected void serializeUseMTRFont(final WriterBase writerBase) {
		writerBase.writeBoolean("useMTRFont", useMTRFont);
	}

	protected void serializeDisableShadowsForShaders(final WriterBase writerBase) {
		writerBase.writeBoolean("disableShadowsForShaders", disableShadowsForShaders);
	}

	protected void serializePreloadResourcePattern(final WriterBase writerBase) {
		writerBase.writeString("preloadResourcePattern", preloadResourcePattern);
	}

	protected void serializeBetaWarningVersion(final WriterBase writerBase) {
		writerBase.writeString("betaWarningVersion", betaWarningVersion);
	}
}