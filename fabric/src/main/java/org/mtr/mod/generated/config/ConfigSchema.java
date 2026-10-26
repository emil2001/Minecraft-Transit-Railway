package org.mtr.mod.generated.config;

import org.mtr.core.serializer.*;
import org.mtr.core.tool.*;
import javax.annotation.*;
import org.mtr.mod.config.*;

public abstract class ConfigSchema implements SerializedDataBase {

	protected final Client client;

	protected final Server server;

	protected ConfigSchema(final Client client, final Server server) {
		this.client = client;
		this.server = server;
	}

	protected ConfigSchema(final ReaderBase readerBase) {
		client = new Client(readerBase.getChild("client"));
		server = new Server(readerBase.getChild("server"));
	}

	public void updateData(final ReaderBase readerBase) {
	}

	public void serializeData(final WriterBase writerBase) {
		if (client != null) client.serializeData(writerBase.writeChild("client"));
		if (server != null) server.serializeData(writerBase.writeChild("server"));
	}

	@Nonnull
	public String toString() {
		return ""
			+ "client: " + client + "\n"
			+ "server: " + server + "\n"
		;
	}
}