import java.io.*;
import java.net.*;

import com.sun.net.httpserver.*;

/**
 * 
 * @author Mitchell Levy
 *
 */
public class Server {
	/**
	 * The port the server will listen to (80/http).
	 */
	private static final int PORT = 80;
	/**
	 * Initialized in the main method to be the contents of the {@code index.html}
	 * resource.
	 */
	private static byte[] index;
	/**
	 * Initialized in the main method to be the contents of the
	 * {@code notfound.html} resource.
	 */
	private static byte[] notFound;

	public static void main(String[] args) {
		try {
			Server.index = Server.class.getResourceAsStream("index.html").readAllBytes();
			Server.notFound = Server.class.getResourceAsStream("notfound.html").readAllBytes();
		} catch (IOException ioe) {
			System.err.println("Could not read index.html: " + ioe);
		}

		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
			server.createContext("/", (HttpExchange t) -> {
				if (t.getRequestURI().getPath().equals("/") || t.getRequestURI().getPath().equals("/index.html")) {
					Server.send(t, "text/html", index);
				} else {
					Server.send(t, "text/html", notFound, 404);
				}
			});
			server.start();
		} catch (IOException ioe) {
			System.err.println("Failed to start server: " + ioe);
		}
	}

	/**
	 * Using the given {@code HttpExchange}, sends a response with the given
	 * {@code statusCode}, {@code contentType} (as the {@code Content-Type} header),
	 * and {@code content} (as the response body).
	 * 
	 * @param t           - The {@code HttpExchange} used to send the response.
	 * @param contentType - The MIME type of the {@code content}/response body. Sent
	 *                    directly to the client as the {@code Content-Type} header.
	 * @param content     - The content of the response (i.e., the response body).
	 * @param statusCode  - The HTTP status code used in the response.
	 * @throws IOException
	 */
	private static void send(HttpExchange t, String contentType, byte[] content, int statusCode) throws IOException {
		t.getResponseHeaders().set("Content-Type", contentType);
		t.sendResponseHeaders(statusCode, content.length);
		t.getResponseBody().write(content);
		t.getResponseBody().close();
	}

	/**
	 * Using the given {@code HttpExchange}, sends a response with
	 * {@code contentType} (as the {@code Content-Type} header) and {@code content}
	 * (as the response body). A status code of 200 is implied.
	 * 
	 * @param t           - The {@code HttpExchange} used to send the response.
	 * @param contentType - The MIME type of the {@code content}/response body. Sent
	 *                    directly to the client as the {@code Content-Type} header.
	 * @param content     - The content of the response (i.e., the response body).
	 * @throws IOException
	 */
	private static void send(HttpExchange t, String contentType, byte[] content) throws IOException {
		Server.send(t, contentType, content, 200);
	}
}
