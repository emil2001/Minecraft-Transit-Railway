package org.mtr.mod.generated.config;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.mod.config.*;

public abstract class ServerSchema implements SerializedDataBase {

	protected long webserverPort = 8888;

	protected boolean useThreadedSimulation;

	protected boolean useThreadedFileLoading;

	protected boolean forceShutDownStrayThreads;

	protected ServerSchema() {
	}

	protected ServerSchema(final ReaderBase readerBase) {
	}

	public void updateData(final ReaderBase readerBase) {
		readerBase.unpackLong("webserverPort", value -> webserverPort = value);
		readerBase.unpackBoolean("useThreadedSimulation", value -> useThreadedSimulation = value);
		readerBase.unpackBoolean("useThreadedFileLoading", value -> useThreadedFileLoading = value);
		readerBase.unpackBoolean("forceShutDownStrayThreads", value -> forceShutDownStrayThreads = value);
	}

	public void serializeData(final WriterBase writerBase) {
		serializeWebserverPort(writerBase);
		serializeUseThreadedSimulation(writerBase);
		serializeUseThreadedFileLoading(writerBase);
		serializeForceShutDownStrayThreads(writerBase);
	}

	@Nonnull
	public String toString() {
		return ""
			+ "webserverPort: " + webserverPort + "\n"
			+ "useThreadedSimulation: " + useThreadedSimulation + "\n"
			+ "useThreadedFileLoading: " + useThreadedFileLoading + "\n"
			+ "forceShutDownStrayThreads: " + forceShutDownStrayThreads + "\n"
		;
	}

	protected void serializeWebserverPort(final WriterBase writerBase) {
		writerBase.writeLong("webserverPort", webserverPort);
	}

	protected void serializeUseThreadedSimulation(final WriterBase writerBase) {
		writerBase.writeBoolean("useThreadedSimulation", useThreadedSimulation);
	}

	protected void serializeUseThreadedFileLoading(final WriterBase writerBase) {
		writerBase.writeBoolean("useThreadedFileLoading", useThreadedFileLoading);
	}

	protected void serializeForceShutDownStrayThreads(final WriterBase writerBase) {
		writerBase.writeBoolean("forceShutDownStrayThreads", forceShutDownStrayThreads);
	}
}