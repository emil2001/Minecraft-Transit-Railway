package org.mtr.gtnh;

import org.mtr.core.servlet.Webserver;
import org.mtr.libraries.org.eclipse.jetty.server.ServerConnector;

import java.lang.reflect.Field;

/** Keeps the multiplayer client's map and resource creator on an OS-assigned loopback port. */
public final class ClientWebserver {
    private ClientWebserver() {}

    public static Webserver create() {
        Webserver webserver = new Webserver(0);
        connector(webserver).setHost("127.0.0.1");
        return webserver;
    }

    public static int localPort(Webserver webserver) {
        ServerConnector connector = connector(webserver);
        int port = connector.getLocalPort();
        // Original Webserver.start logs and swallows startup exceptions.
        if (!connector.isStarted() || !"127.0.0.1".equals(connector.getHost()) || port <= 0) {
            throw new IllegalStateException("Client map webserver did not start on loopback: " + connector.getState() + ", port=" + port);
        }
        return port;
    }

    private static ServerConnector connector(Webserver webserver) {
        try {
            Field field = Webserver.class.getDeclaredField("serverConnector");
            field.setAccessible(true);
            return (ServerConnector) field.get(webserver);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Cannot access the original MTR Webserver connector", error);
        }
    }
}
