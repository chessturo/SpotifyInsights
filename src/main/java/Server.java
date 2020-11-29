import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

import org.json.*;

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

	/**
	 * The ID of this Spotify application as registered through their developer
	 * portal. Initialized in the main method to be the contents of the
	 * {@code spotify_client_id} resource.
	 */
	private static String spotifyClientId;

	/**
	 * The secret used by this app to access the Spotify API. Initialized in the
	 * main method to be the contents of the {@code spotify_client_secret} resource.
	 */
	private static String spotifyClientSecret;

	/**
	 * The callback URL for the Spotify OAuth flow.
	 */
	private static final String SPOTIFY_OAUTH_CALLBACK = "http://localhost/callback";

	/**
	 * The scopes that the application needs to have access to on the Spotify API.
	 */
	private static final String SPOTIFY_SCOPE = "user-read-email user-library-read";

	/**
	 * The name of the state cookie given to the user-agent to help prevent CSRF.
	 */
	private static final String STATE_COOKIE_NAME = "spotify_oauth_state";

	public static void main(String[] args) {
		try {
			Server.index = Server.class.getResourceAsStream("index.html").readAllBytes();
			Server.notFound = Server.class.getResourceAsStream("notfound.html").readAllBytes();
			Server.spotifyClientId = new String(Server.class.getResourceAsStream("spotify_client_id").readAllBytes());
			Server.spotifyClientSecret = new String(
					Server.class.getResourceAsStream("spotify_client_secret").readAllBytes());
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

		Server.addPath(server, "/login", (HttpExchange t) -> {
			// Random hex value from 0x000000 -> 0xFFFFFF
			int stateNumber = (int) (Math.random() * (0xFFFFFF + 1));
			String state = String.format("%06x", stateNumber);

			Server.addCookie(t, STATE_COOKIE_NAME, state);
			Server.redirect(t,
					"https://accounts.spotify.com/authorize?" + "response_type=code" + "&client_id="
							+ URLEncoder.encode(spotifyClientId, StandardCharsets.UTF_8) + "&scope="
							+ URLEncoder.encode(SPOTIFY_SCOPE, StandardCharsets.UTF_8) + "&redirect_uri="
							+ URLEncoder.encode(SPOTIFY_OAUTH_CALLBACK, StandardCharsets.UTF_8) + "&state="
							+ URLEncoder.encode(state, StandardCharsets.UTF_8));
		});
		Server.addPath(server, "/callback", (HttpExchange t) -> {
			String cookieState = null, queryState = null;

			List<String> rawCookies = t.getRequestHeaders().get("Cookie");
			if (rawCookies != null) {				
				for (String cookieKvp : rawCookies) {
					if (cookieKvp.startsWith(STATE_COOKIE_NAME + "=")) {
						String[] stateCookie = cookieKvp.split("=", 2);
						
						// Handle if the header looks like
						// Cookie: spotify_oauth_state=000000; Secure; HttpOnly
						int endOfValue = stateCookie[1].indexOf(';');
						endOfValue = endOfValue == -1 ? stateCookie[1].length() : endOfValue;
						cookieState = stateCookie[1].substring(0, endOfValue);
					}
				}
			}

			String query = t.getRequestURI().getQuery();
			Map<String, String> queryPairs = new HashMap<>();
			if (query != null) {				
				String[] keyValuePairs = query.split("&");
				for (String queryKvp : keyValuePairs) {
					String[] keyAndValue = queryKvp.split("=", 2);
					String key = keyAndValue[0];
					String value = keyAndValue[1];
					queryPairs.put(key, value);
				}
				queryState = queryPairs.get("state");
			}

			if (cookieState != null && queryState != null && cookieState.equals(queryState)) {
				if (queryPairs.containsKey("error")) {
					Server.send(t, "text/plain", "Error: " + queryPairs.get("error"));
				} else {
					// Get auth token from Spotify account service.
					String authToken = null;
					try {
						URL spotifyAccountService = new URL("https://accounts.spotify.com/api/token");
						String requestBody = "grant_type=authorization_code" + "&code=" + queryPairs.get("code")
								+ "&redirect_uri=" + SPOTIFY_OAUTH_CALLBACK + "&client_id=" + spotifyClientId
								+ "&client_secret=" + spotifyClientSecret;
						String response = Server.makePostRequest(spotifyAccountService, requestBody);

						authToken = new JSONObject(response).getString("access_token");
					} catch (IOException ioe) {
						System.err.println("Error connecting to the Spotify account service: " + ioe);
					}

					if (authToken == null) {
						System.err.println("Auth token evaluated to null.");
						Server.send(t, "text/plain", "Server error.", HttpURLConnection.HTTP_INTERNAL_ERROR);
					} else {
						String email = null;
						try {
							URL spotifyApi = new URL("https://api.spotify.com/v1/me");
							String response = Server.makeGetRequest(spotifyApi, "application/json",
									"Bearer " + authToken);
							email = new JSONObject(response).getString("email");
						} catch (IOException ioe) {
							System.err.println("Error accessing the Spotify api: " + ioe);
						}
						Server.send(t, "text/plain", "OAuth success. Email: " + email);
					}
				}
			} else {
				Server.send(t, "text/plain", "State mismatch.", HttpURLConnection.HTTP_FORBIDDEN);
			}
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
	 */
	private static void send(HttpExchange t, String contentType, byte[] content, int statusCode) {
		try {
			t.getResponseHeaders().set("Content-Type", contentType);
			if (content.length != 0) {
				t.sendResponseHeaders(statusCode, content.length);
				t.getResponseBody().write(content);
			} else {
				t.sendResponseHeaders(statusCode, -1);
			}
			t.getResponseBody().close();
		} catch (IOException ioe) {
			System.err.println("Error sending response: " + ioe);
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
	 */
	private static void send(HttpExchange t, String contentType, String content, int statusCode) {
		Server.send(t, contentType, content.getBytes(), statusCode);
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
	 */
	private static void send(HttpExchange t, String contentType, String content) {
		Server.send(t, contentType, content.getBytes());
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
	 */
	private static void send(HttpExchange t, String contentType, byte[] content) {
		Server.send(t, contentType, content, HttpURLConnection.HTTP_OK);
	}

	/**
	 * Redirects the client at the other end of {@code t} to the URL in
	 * {@code Location} using a 302 status code.
	 * 
	 * @param t
	 * @param location
	 */
	private static void redirect(HttpExchange t, String location) {
		try {
			t.getResponseHeaders().set("Location", location);
			// For whatever reason, HttpURLConnection uses the HTTP/1.0 name of "Moved
			// Temporarily" rather than HTTP/1.1 name of "Found".
			t.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1);
			t.getResponseBody().close();
		} catch (IOException ioe) {
			System.err.println("Error redirecting: " + ioe);
		}
	}

	/**
	 * Adds a cookie with the given name ({@code key}) and {@code value}).
	 * 
	 * @param t     - The {@code HttpExchange} that the cookie should be added to.
	 * @param key   - The name of the cookie (must be a valid cookie name).
	 * @param value - The value of the cookie (must be a valid cookie value).
	 * 
	 * @see <a href=
	 *      "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie">Set-Cookie
	 *      on MDN</a>
	 */
	private static void addCookie(HttpExchange t, String key, String value) {
		t.getResponseHeaders().add("Set-Cookie", key + "=" + value);
	}

	/**
	 * Makes a HTTP POST request to the specified {@code url}. The body of the
	 * response is returned. {@code Content-Type} is assumed to be
	 * {@code application/x-www-form-urlencoded"}, {@code Accept} is assumed to be
	 * {@code application/json}.
	 * 
	 * @param url         - The URL to make the request to. Must be an HTTP URL
	 *                    (i.e., have a protocol of http).
	 * @param requestBody - The body of the post request.
	 * @return The body of the server response.
	 * @throws IOException If there are exceptions thrown by the IO methods involved
	 *                     in making/writing to the connection.
	 */
	private static String makePostRequest(URL url, String requestBody) throws IOException {
		return makePostRequest(url, requestBody, "application/x-www-form-urlencoded", "application/json", null);
	}

	/**
	 * Makes a HTTP POST request to the specified {@code url}. The body of the
	 * response is returned.
	 * 
	 * @param url           - The URL to make the request to. Must be an HTTP URL
	 *                      (i.e., have a protocol of http).
	 * @param requestBody   - The body of the post request.
	 * @param contentType   - The value of the {@code Content-Type} header, sent
	 *                      directly to the server.
	 * @param accept        - The value of the {@code Accept} header, sent directly
	 *                      to the server.
	 * @param authorization - The value of the {@code Authorization} header, sent
	 *                      directly to the server. If null, the header is omitted.
	 * 
	 * @return The body of the server response.
	 * @throws IOException If there are exceptions thrown by the IO methods involved
	 *                     in making/writing to the connection.
	 */
	private static String makePostRequest(URL url, String requestBody, String contentType, String accept,
			String authorization) throws IOException {
		HttpURLConnection accountServiceConnection = (HttpURLConnection) url.openConnection();
		accountServiceConnection.setDoOutput(true);
		accountServiceConnection.setRequestMethod("POST");
		accountServiceConnection.setRequestProperty("Content-Type", contentType);
		accountServiceConnection.setRequestProperty("Accept", accept);
		if (authorization != null) {
			accountServiceConnection.setRequestProperty("Authorization", authorization);
		}
		try (OutputStream req = accountServiceConnection.getOutputStream()) {
			req.write(requestBody.getBytes());
		}

		String response;
		try (InputStream res = accountServiceConnection.getInputStream()) {
			response = (new String(res.readAllBytes()));
		}
		return response;
	}

	/**
	 * Makes a HTTP GET request to the specified {@code url}. The body of the
	 * response is returned.
	 * 
	 * @param url           - The URL to make the request to. Must be an HTTP URL
	 *                      (i.e., have a protocol of http).
	 * @param requestBody   - The body of the post request.
	 * @param contentType   - The value of the {@code Content-Type} header, sent
	 *                      directly to the server.
	 * @param accept        - The value of the {@code Accept} header, sent directly
	 *                      to the server.
	 * @param authorization - The value of the {@code Authorization} header, sent
	 *                      directly to the server. If null, the header is omitted.
	 * 
	 * @return The body of the server response.
	 * @throws IOException If there are exceptions thrown by the IO methods involved
	 *                     in making/writing to the connection.
	 */
	private static String makeGetRequest(URL url, String accept, String authorization) throws IOException {
		HttpURLConnection accountServiceConnection = (HttpURLConnection) url.openConnection();
		accountServiceConnection.setDoOutput(true);
		accountServiceConnection.setRequestMethod("GET");
		accountServiceConnection.setRequestProperty("Accept", accept);
		if (authorization != null) {
			accountServiceConnection.setRequestProperty("Authorization", authorization);
		}

		String response;
		try (InputStream res = accountServiceConnection.getInputStream()) {
			response = (new String(res.readAllBytes()));
		}
		return response;
	}
}
