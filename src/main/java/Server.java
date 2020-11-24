import java.io.*;
import java.net.*;
import java.util.List;
import java.util.function.*;

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
			System.err.println("Could not read a required resource: " + ioe);
			System.exit(1);
		}

		HttpServer server = null;
		try {
			server = HttpServer.create(new InetSocketAddress(PORT), 0);
		} catch (IOException ioe) {
			System.err.println("Failed to create server: " + ioe);
			System.exit(1);
		}

		Server.addPath(server, "/", List.of("/", "/index.html"), (HttpExchange t) -> {
			Server.send(t, "text/html", index);
		});
		server.start();
	}

	/**
	 * Serves as a wrapper around
	 * {@link com.sun.net.httpserver.HttpServer#createContext(String, HttpHandler)
	 * HttpServer#createContext(String, HttpHandler)} to deal with boilerplate
	 * around path matching. Using
	 * {@code HttpServer#createContext(String, HttpHandler)}, the path {@code /}
	 * will match all requests (as all requests will contain at least one {@code /}
	 * in their path). When using this method, boilerplate for checking that the
	 * matched path is what's intended (e.g., the handler for {@code /about.html}
	 * will only match {@code /about.html}, not {@code /about.html-foo-bar}) is
	 * added. If {@code HttpServer#createContext(String, HttpHandler)} matches a
	 * request, but the path doesn't exactly match {@code path}, a response with a
	 * 404 status and a body of {@link Server#notFound} is sent.
	 * 
	 * @param server   - The server that the handler is being added to. Cannot be
	 *                 null.
	 * @param path     - The path that must be matched.
	 * @param callback - The callback to run if {@code path} exactly matches the
	 *                 request path.
	 */
	private static void addPath(HttpServer server, String path, Consumer<HttpExchange> callback) {
		addPath(server, path, List.of(path), callback);
	}

	/**
	 * Serves as a wrapper around
	 * {@link com.sun.net.httpserver.HttpServer#createContext(String, HttpHandler)
	 * HttpServer#createContext(String, HttpHandler)} to deal with boilerplate
	 * around path matching. Using
	 * {@code HttpServer#createContext(String, HttpHandler)}, the path {@code /}
	 * will match all requests (as all requests will contain at least one {@code /}
	 * in their path). When using this method, boilerplate for checking that the
	 * matched path is what's intended (e.g., the handler for {@code /} will only
	 * match paths like {@code /}, {@code /index.html}, etc.) is added. If
	 * {@code HttpServer#createContext(String, HttpHandler)} matches a request, but
	 * the path is not contained within {@code paths}, a response with a 404 status
	 * and a body of {@link Server#notFound} is sent.
	 * 
	 * @param server   - The server that the handler is being added to. Cannot be
	 *                 null.
	 * @param basePath - The base path, passed directly to
	 *                 {@code HttpServer#createContext(String, HttpHandler)}. Cannot
	 *                 be null.
	 * @param paths    - The list of valid paths. This should include
	 *                 {@code basePath} in the vast majority of cases. Cannot be
	 *                 null.
	 * @param callback - The callback to run if the request path is within
	 *                 {@code paths}.
	 */
	private static void addPath(HttpServer server, String basePath, List<String> paths,
			Consumer<HttpExchange> callback) {
		server.createContext(basePath, (HttpExchange t) -> {
			if (paths.contains(t.getRequestURI().getPath())) {
				callback.accept(t);
			} else {
				Server.send(t, "text/html", notFound, HttpURLConnection.HTTP_NOT_FOUND);
			}
		});
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
	private static void send(HttpExchange t, String contentType, byte[] content, int statusCode) {
		try {
			t.getResponseHeaders().set("Content-Type", contentType);
			t.sendResponseHeaders(statusCode, content.length);
			t.getResponseBody().write(content);
			t.getResponseBody().close();
		} catch (IOException ioe) {
			System.err.println("Error sending response: " + ioe);
		}
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
	private static void send(HttpExchange t, String contentType, byte[] content) {
		Server.send(t, contentType, content, HttpURLConnection.HTTP_OK);
	}
}
