package org.mtr.gtnh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mtr.core.servlet.Webserver;
import org.mtr.core.servlet.WebServlet;
import org.mtr.libraries.org.eclipse.jetty.server.ServerConnector;
import org.mtr.libraries.org.eclipse.jetty.servlet.ServletHolder;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ClientWebserverTest {
    @Test
    void originalServletsUseLoopbackEphemeralPortAndStopReleasesIt() throws Exception {
        Webserver webserver = ClientWebserver.create();
        ServerConnector connector = connector(webserver);
        int port = 0;
        try {
            assertEquals("127.0.0.1", connector.getHost());
            assertEquals(0, connector.getPort());
            assertFalse(connector.isStarted());
            webserver.addServlet(new ServletHolder(new WebServlet(path -> "index.html".equals(path) ? "map alive" : null, "/") {}), "/");
            webserver.addServlet(new ServletHolder(new WebServlet(path -> "index.html".equals(path) ? "creator alive" : null, "/creator/") {}), "/creator/*");
            webserver.start();
            port = ClientWebserver.localPort(webserver);
            assertTrue(port > 0);
            assertEquals(connector.getLocalPort(), port);
            assertEquals("map alive", get(port, "/"));
            assertEquals("creator alive", get(port, "/creator/"));
        } finally {
            webserver.stop();
        }
        assertFalse(connector.isStarted());
        assertThrows(IllegalStateException.class, () -> ClientWebserver.localPort(webserver));
        try (ServerSocket released = new ServerSocket()) {
            released.setReuseAddress(true);
            released.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }

    @Test
    void portCannotBePublishedBeforeOriginalStart() {
        Webserver webserver = ClientWebserver.create();
        try {
            assertThrows(IllegalStateException.class, () -> ClientWebserver.localPort(webserver));
        } finally {
            webserver.stop();
        }
    }

    @Test
    void swallowedOriginalBindFailureCannotPublishAnUrl() throws Exception {
        Webserver webserver = ClientWebserver.create();
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            ServerConnector connector = connector(webserver);
            assertEquals("127.0.0.1", connector.getHost());
            connector.setPort(occupied.getLocalPort());
            webserver.start();
            assertFalse(connector.isStarted());
            assertThrows(IllegalStateException.class, () -> ClientWebserver.localPort(webserver));
        } finally {
            webserver.stop();
        }
    }

    private static String get(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestProperty("Connection", "close");
        try {
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static ServerConnector connector(Webserver webserver) throws Exception {
        Field field = Webserver.class.getDeclaredField("serverConnector");
        field.setAccessible(true);
        return (ServerConnector) field.get(webserver);
    }
}
