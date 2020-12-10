import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import javax.net.ssl.*;

import org.json.*;

import com.sun.net.httpserver.*;

/**
 * 
 * @author Mitchell Levy
 * 
 * @see - While not a copypaste, writing the SSL/HTTPS handling code would've
 *      been nearly impossible without <a target="_top" href=
 *      "https://stackoverflow.com/a/34483734/6759322">this stack overflow
 *      answer.</a>
 *
 */
public class Server {
	/**
	 * The port the server will listen to for insecure connections (80/http).
	 */
	private static final int INSECURE_PORT = 80;
	/**
	 * The port the server will listen to for secure connections (443/https).
	 */
	private static final int SECURE_PORT = 443;
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
	 * Initialized in the main method to be the contents of the {@code si.png}
	 * resource.
	 */
	private static byte[] siLogo;

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
	private static final String SPOTIFY_OAUTH_CALLBACK = "https://localhost/callback";
	/**
	 * The scopes that the application needs to have access to on the Spotify API.
	 */
	private static final String SPOTIFY_SCOPE = "user-read-email user-library-read user-read-playback-state";
	/**
	 * The name of the state cookie given to the user-agent to help prevent CSRF.
	 */
	private static final String STATE_COOKIE_NAME = "spotify_oauth_state";

	/**
	 * The hostname (used for http -> https redirects).
	 */
	private static final String HOSTNAME = "localhost";
	/**
	 * An {@code InputStream} pointing at the serialized {@code KeyStore} that
	 * should be used for HTTPS. Should be of type JKS.
	 */
	private static InputStream keyStore;
	/**
	 * The password for the {@code KeyStore} at {@link Server#keyStore}. Initialized
	 * to be the value of the {@code key_store_password} resource at startup.
	 */
	private static char[] keyStorePassword;

	/**
	 * The map of session IDs to sessions.
	 */
	private static final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
	/**
	 * The secure RNG used to generate session IDs.
	 */
	private static final SecureRandom secureRandom = new SecureRandom();
	/**
	 * The length of session IDs, in bytes. (Note: this is not the length of the
	 * actual session IDs, rather it's the number of bytes used to generate them.
	 * These bytes are then converted to hex and used as the session ID. As such,
	 * the actual length of the session ID String is 2x this number.)
	 */
	private static final int SESSION_ID_LENGTH_BYTES = 64;
	/**
	 * The name of the session cookie.
	 */
	private static final String SESSION_COOKIE_NAME = "session";
	/**
	 * The length of a session, in seconds.
	 */
	private static final long SESSION_LENGTH_SECONDS = Duration.of(30L, ChronoUnit.DAYS).getSeconds();

	public static void main(String[] args) {
		try {
			Server.index = Server.class.getResourceAsStream("index.html").readAllBytes();
			Server.notFound = Server.class.getResourceAsStream("notfound.html").readAllBytes();
			Server.siLogo = Server.class.getResourceAsStream("si.png").readAllBytes();
			Server.spotifyClientId = new String(Server.class.getResourceAsStream("spotify_client_id").readAllBytes());
			Server.spotifyClientSecret = new String(
					Server.class.getResourceAsStream("spotify_client_secret").readAllBytes());
			Server.keyStore = Server.class.getResourceAsStream("keystore.jks");
			Server.keyStorePassword = new String(Server.class.getResourceAsStream("key_store_password").readAllBytes())
					.toCharArray();
		} catch (IOException ioe) {
			System.err.println("Could not read a required resource: " + ioe);
			System.exit(1);
		}

		// Creates and starts a HTTP server to deal with upgrading HTTP requests to
		// HTTPS ones.
		HttpServer insecureServer = null;
		try {
			insecureServer = HttpServer.create(new InetSocketAddress(INSECURE_PORT), 0);
		} catch (IOException ioe) {
			System.err.println("Failed to create HTTP server: " + ioe);
			System.exit(1);
		}
		insecureServer.createContext("/", (HttpExchange t) -> {
			URI uri = t.getRequestURI();
			URI httpsUri;
			try {
				httpsUri = new URI("https", uri.getUserInfo(), HOSTNAME, 443, uri.getPath(), uri.getQuery(),
						uri.getFragment());
			} catch (URISyntaxException urise) {
				throw new IllegalArgumentException(urise);
			}
			Server.redirect(t, httpsUri.toString());
		});
		insecureServer.start();

		// Creates and starts the HTTPS server that does the bulk of the work.
		HttpsServer server = null;
		try {
			server = HttpsServer.create(new InetSocketAddress(SECURE_PORT), 0);

			SSLContext sslCtx = SSLContext.getInstance("TLS");
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(keyStore, keyStorePassword);

			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(ks, keyStorePassword);

			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(ks);

			sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

			server.setHttpsConfigurator(new HttpsConfigurator(sslCtx));
		} catch (Exception e) {
			System.err.println("Failed to create HTTPS server: " + e);
			System.exit(1);
		}

		Server.addPath(server, "/", List.of("/", "/index.html"), (HttpExchange t) -> {
			Server.send(t, "text/html", index);
		});
		Server.addPath(server, "/si.png", (HttpExchange t) -> {
			Server.send(t, "image/png", siLogo);
		});

		Server.addPath(server, "/login", (HttpExchange t) -> {
			// Random hex value from 0x000000 -> 0xFFFFFF
			int stateNumber = (int) (Math.random() * (0xFFFFFF + 1));
			String state = String.format("%06x", stateNumber);

			Server.addCookie(t, STATE_COOKIE_NAME, state);

			// @formatter:off
			Server.redirect(t, "https://accounts.spotify.com/authorize?"
							+ Server.generateURLEscapedKVPs(
									new KVP<>("response_type", "code"),
									new KVP<>("client_id", spotifyClientId), 
									new KVP<>("scope", SPOTIFY_SCOPE),
									new KVP<>("redirect_uri", SPOTIFY_OAUTH_CALLBACK), 
									new KVP<>("state", state))); 
			// @formatter:on
		});
		Server.addPath(server, "/callback", (HttpExchange t) -> {
			String rawCookies = t.getRequestHeaders().getFirst("Cookie");
			Map<String, String> cookies = rawCookies == null ? new HashMap<>() : parseCookieHeader(rawCookies);

			if (cookies.containsKey(SESSION_COOKIE_NAME) && sessions.containsKey(cookies.get(SESSION_COOKIE_NAME))) {
				Server.redirect(t, "https://" + HOSTNAME + "/results");
			} else {
				String cookieState = null, queryState = null;
				cookieState = cookies.get(STATE_COOKIE_NAME);

				Map<String, List<String>> queryPairs = parseQueryString(t.getRequestURI().getQuery());
				if (queryPairs.containsKey("state")) {
					queryState = queryPairs.get("state").get(0);
				}

				if (cookieState == null || queryState == null || !cookieState.equals(queryState)) {
					Server.send(t, "text/plain", "State mismatch.", HttpURLConnection.HTTP_FORBIDDEN);
				} else {
					if (queryPairs.containsKey("error")) {
						Server.send(t, "text/plain", "Error: " + queryPairs.get("error"));
					} else {
						try {
							Session current = new Session(queryPairs.get("code").get(0));
							byte[] sessionIdBytes = new byte[SESSION_ID_LENGTH_BYTES];
							secureRandom.nextBytes(sessionIdBytes);
							StringBuilder idBuilder = new StringBuilder(SESSION_ID_LENGTH_BYTES * 2);
							for (byte b : sessionIdBytes) {
								idBuilder.append(String.format("%02x", b));
							}
							String sessionId = idBuilder.toString();

							sessions.put(sessionId, current);
							Server.addCookie(t, SESSION_COOKIE_NAME, sessionId, true, true,
									Instant.now().plusSeconds(SESSION_LENGTH_SECONDS).getEpochSecond());
						} catch (IOException ioe) {
							System.err.println("Error creating the session: " + ioe);
							Server.send(t, "text/plain", "Server error.", HttpURLConnection.HTTP_INTERNAL_ERROR);
						}
						Server.redirect(t, "/results");
					}
				}
			}
		});
		Server.addPath(server, "/results", (HttpExchange t) -> {
			String rawCookies = t.getRequestHeaders().getFirst("Cookie");
			Map<String, String> cookies = rawCookies == null ? new HashMap<>() : parseCookieHeader(rawCookies);

			if (!cookies.containsKey(SESSION_COOKIE_NAME) || !sessions.containsKey(cookies.get(SESSION_COOKIE_NAME))) {
				Server.redirect(t, "/login");
			} else {
				Session session = sessions.get(cookies.get(SESSION_COOKIE_NAME));
				String email = null;
				try {
					session.softRefresh();
					URL spotifyApi = new URL("https://api.spotify.com/v1/me");
					String response = Server.makeGetRequest(spotifyApi, "application/json",
							"Bearer " + session.currentToken);
					email = new JSONObject(response).getString("email");
					Server.send(t, "text/plain", "OAuth success. Email: " + email);
				} catch (IOException ioe) {
					System.err.println("Error accessing the Spotify api: " + ioe);
					Server.send(t, "text/plain", "Server error.", HttpURLConnection.HTTP_INTERNAL_ERROR);
				}
			}
		});
		Server.addPath(server, "/logout", (HttpExchange t) -> {
			Server.clearCookie(t, SESSION_COOKIE_NAME, true, true);
			Server.redirect(t, "/");
		});
		ScheduledExecutorService sessionUpdateScheduler = Executors.newScheduledThreadPool(1);
		long currentEpochTime = Instant.now().getEpochSecond();
		sessionUpdateScheduler.scheduleAtFixedRate(() -> {
			Set<String> keys = Server.sessions.keySet();
			for (String sessionId : keys) {
				if (Server.sessions.get(sessionId).sessionExpiresAt > currentEpochTime) {
					Server.sessions.remove(sessionId);
				}
			}
		}, 0L, 1L, TimeUnit.MINUTES);
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
	 * Parses a query string into a {@code Map}. The values of the Map are
	 * {@code List}s of all of the values that appeared with a given key, e.g. the
	 * query string {@code exampleKey=val1&exampleKey=val2} would become
	 * {@code [exampleKey, [val1, val2]]}.
	 * 
	 * @param query - The query string to decode. Should not include the
	 *              {@literal ?} character that starts the query string. If null, an
	 *              empty list is returned.
	 * @return A map where each key is mapped to the list of values that appear with
	 *         that key. All {@code List<String>}s will contain at least one value
	 *         (i.e., no empty lists will be returned).
	 */
	private static Map<String, List<String>> parseQueryString(String query) {
		Map<String, List<String>> output = new HashMap<>();
		if (query != null) {
			String[] keyValuePairs = query.split("&");
			for (String queryKvp : keyValuePairs) {
				String[] keyAndValue = queryKvp.split("=", 2);
				String key = URLDecoder.decode(keyAndValue[0], StandardCharsets.UTF_8);
				String value = URLDecoder.decode(keyAndValue[1], StandardCharsets.UTF_8);
				if (!output.containsKey(key)) {
					output.put(key, new ArrayList<>());
				}
				output.get(key).add(value);
			}
		}
		return output;
	}

	/**
	 * Parses the value of an HTTP {@code Cookie} header into a Map where the keys
	 * are cookie names and the values are cookie values.
	 * 
	 * @param cookieHeaderValue - The value of an HTTP {@code Cookie} header.
	 * @return A Map where the keys are cookie names and the values are cookie
	 *         values.
	 * 
	 * @see <a href=
	 *      "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cookie">The
	 *      MDN docs for the Cookie header.</a>
	 */
	private static Map<String, String> parseCookieHeader(String cookieHeaderValue) {
		Map<String, String> output = new HashMap<>();
		String[] cookies = cookieHeaderValue.split(";");
		for (String rawCookie : cookies) {
			String[] nameAndVal = rawCookie.split("=");
			output.put(nameAndVal[0].trim(), nameAndVal[1].trim());
		}
		return output;

	}

	/**
	 * Returns a {@code String} based on the given {@link Server.KVP KVP&lt;String,
	 * String&gt;}s. The returned String is in the form
	 * {@code Kvp0Key + "=" Kvp0Val + "&" Kvp1Key + "=" Kvp2Val ...} where the keys
	 * and values have been URL encoded.
	 * 
	 * @param kvps - The key-value pairs to base the String on.
	 * 
	 * @return - A String where all of the keys and values have been URL encoded
	 *         with a separator of {@literal =} between the keys and values and a
	 *         separator of {@literal &} between the pairs.
	 */
	@SafeVarargs
	private static String generateURLEscapedKVPs(KVP<String, String>... kvps) {
		if (kvps.length == 0) {
			return "";
		}
		StringBuilder output = new StringBuilder();
		output.append(URLEncoder.encode(kvps[0].key, StandardCharsets.UTF_8));
		output.append("=");
		output.append(URLEncoder.encode(kvps[0].val, StandardCharsets.UTF_8));
		for (int i = 1; i < kvps.length; i++) {
			output.append("&");
			output.append(URLEncoder.encode(kvps[i].key, StandardCharsets.UTF_8));
			output.append("=");
			output.append(URLEncoder.encode(kvps[i].val, StandardCharsets.UTF_8));
		}
		return output.toString();
	}

	/**
	 * Very simple wrapper class for a Key-Value pair because Java doesn't have
	 * built in tuples.
	 * 
	 * @author Mitchell Levy
	 *
	 * @param <K> - Type of the key
	 * @param <V> - Type of the value.
	 */
	private static class KVP<K, V> {
		private K key;
		private V val;

		private KVP(K key, V val) {
			this.key = key;
			this.val = val;
		}
	}

	/**
	 * Using the given {@code HttpExchange}, sends a response with the given
	 * {@code statusCode}, {@code contentType} (as the {@code Content-Type} header),
	 * and {@code content} (as the response body). HSTS is enabled for 1 year.
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
			t.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
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
	 * and {@code content} (as the response body). HSTS is enabled for 1 year.
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
	 * (as the response body). A status code of 200 is implied. HSTS is enabled for
	 * 1 year.
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
	 * (as the response body). A status code of 200 is implied. HSTS is enabled for
	 * 1 year.
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
	 * Adds a session cookie with the given name ({@code key}) and {@code value})
	 * with no other attributes.
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
		addCookie(t, key, value, false, false, -1);
	}

	/**
	 * A {@code DateTimeFormatter} for generating HTTP dates.
	 * 
	 * @see <a href="https://stackoverflow.com/a/26367834/6759322">This stack
	 *      overflow answer</a><br />
	 *      <a href=
	 *      "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Date"> HTTP
	 *      Date on MDN</a>
	 */
	private static final DateTimeFormatter HTTP_DATE_FORMATTER = DateTimeFormatter
			.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));

	/**
	 * Adds a cookie with the given name ({@code key}) and {@code value}), as well
	 * as the given attributes.
	 * 
	 * @param t                 - The {@code HttpExchange} that the cookie should be
	 *                          added to.
	 * @param key               - The name of the cookie (must be a valid cookie
	 *                          name).
	 * @param value             - The value of the cookie (must be a valid cookie
	 *                          value).
	 * @param secure            - Whether the {@code Secure} attribute should be
	 *                          set.
	 * @param httpOnly          - Whether the {@code HttpOnly} attribute should be
	 *                          set.
	 * @param epochSecondExpire - The UNIX epoch second this cookie should expire
	 *                          at. Set to -1 for a session cookie.
	 * 
	 * @see <a href=
	 *      "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Set-Cookie">Set-Cookie
	 *      on MDN</a>
	 */
	private static void addCookie(HttpExchange t, String key, String value, boolean secure, boolean httpOnly,
			long epochSecondExpire) {

		StringBuilder cookieVal = new StringBuilder(key + "=" + value);
		if (secure) {
			cookieVal.append("; Secure");
		}
		if (httpOnly) {
			cookieVal.append("; HttpOnly");
		}
		if (epochSecondExpire != -1) {
			cookieVal.append("; Expires=" + HTTP_DATE_FORMATTER.format(Instant.ofEpochSecond(epochSecondExpire)));
		}
		t.getResponseHeaders().add("Set-Cookie", cookieVal.toString());
	}

	/**
	 * Clears a given cookie by setting the Expires attribute to 1970-1-1. The
	 * Secure and HttpOnly attributes must exactly match the cookie to be cleared.
	 * 
	 * @param t        - The {@code HttpExchange} that the cookie should be added
	 *                 to.
	 * @param key      - The name of the cookie (must be a valid cookie name).
	 * @param secure   - Whether the {@code Secure} attribute should be set.
	 * @param httpOnly - Whether the {@code HttpOnly} attribute should be set.
	 */
	private static void clearCookie(HttpExchange t, String key, boolean secure, boolean httpOnly) {
		StringBuilder valueBuilder = new StringBuilder(key + "=deleted; Expires=Thu, 01 Jan 1970 00:00:01 GMT");
		if (secure) {
			valueBuilder.append("; Secure");
		}
		if (httpOnly) {
			valueBuilder.append("; HttpOnly");
		}
		t.getResponseHeaders().add("Set-Cookie", valueBuilder.toString());
	}

	/**
	 * Makes a HTTP POST request to the specified {@code url}. The body of the
	 * response is returned. {@code Content-Type} is assumed to be
	 * {@code application/x-www-form-urlencoded}, {@code Accept} is assumed to be
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

	/**
	 * Represents a session with this Server.
	 * 
	 * @author Mitchell Levy
	 *
	 */
	private static class Session {
		/**
		 * The most recently acquired Spotify session token.
		 */
		private String currentToken;
		/**
		 * The Unix time stamp that {@code this.currentToken} will expire at.
		 */
		private long tokenExpiresAt;
		/**
		 * The Spotify refresh token.
		 */
		private String refreshToken;
		/**
		 * The Unix time stamp that this session expires at (i.e., when it should be
		 * removed from {@link Server#sessions}.
		 */
		private long sessionExpiresAt;
		/**
		 * A URL object that points to the Spotify acount service where access tokens
		 * can be generated/refreshed.
		 */
		private static final URL SPOTIFY_ACCOUNT_SERVICE;
		// Initializer block for SPOTIFY_ACCOUNT_SERVICE because you need to be able to
		// deal with MalformedURLException.
		static {
			try {
				SPOTIFY_ACCOUNT_SERVICE = new URL("https://accounts.spotify.com/api/token");
			} catch (MalformedURLException e) {
				throw new Error(e);
			}
		}

		/**
		 * Constructs a new {@code Session} by connecting to the Spotify account service
		 * with the given access code.
		 * 
		 * @param accessCode - The access code that should be used to generate a Spotify
		 *                   access token.
		 * @throws IOException Thrown if there's an IOException while connecting to the
		 *                     Spotify account service.
		 */
		private Session(String accessCode) throws IOException {
			//@formatter:off
			String requestBody = Server.generateURLEscapedKVPs(
					new KVP<>("grant_type", "authorization_code"),
					new KVP<>("code", accessCode),
					new KVP<>("redirect_uri", SPOTIFY_OAUTH_CALLBACK),
					new KVP<>("client_id", spotifyClientId),
					new KVP<>("client_secret", spotifyClientSecret));
			String responseRaw = Server.makePostRequest(SPOTIFY_ACCOUNT_SERVICE, requestBody);
			//@formatter:on
			JSONObject response = new JSONObject(responseRaw);
			this.currentToken = response.getString("access_token");
			// Subtracts one just to make sure there aren't any edge cases where a token
			// might *just barely* expire while still being shown as valid here.
			this.tokenExpiresAt = response.getInt("expires_in") + Instant.now().getEpochSecond() - 1;
			this.refreshToken = response.getString("refresh_token");
			this.sessionExpiresAt = Server.SESSION_LENGTH_SECONDS + Instant.now().getEpochSecond() - 1;
		}

		/**
		 * Refreshes the Spotify access token held at {@code currentToken} *if* it has
		 * expired.
		 * 
		 * @throws IOException If the token is expired and there's an IOException
		 *                     connecting to the Spotify account service while
		 *                     refreshing it.
		 */
		private void softRefresh() throws IOException {
			if (Instant.now().getEpochSecond() >= tokenExpiresAt) {
				this.refresh();
			}
		}

		/**
		 * Refreshes the Spotify access token held at {@code currentToken}.
		 * 
		 * @throws IOException If there is an error making the request to the Spotify
		 *                     account service.
		 */
		private void refresh() throws IOException {
			//@formatter:off
			String responseRaw = Server.makePostRequest(SPOTIFY_ACCOUNT_SERVICE, 
					Server.generateURLEscapedKVPs(
							new KVP<>("grant_type", "refresh_token"),
							new KVP<>("refresh_token", this.refreshToken),
							new KVP<>("client_id", spotifyClientId)
							));
			//@formatter:on
			JSONObject response = new JSONObject(responseRaw);
			this.currentToken = response.getString("access_token");
			// Subtracts one just to make sure there aren't any edge cases where a token
			// might *just barely* expire while still being shown as valid here.
			this.tokenExpiresAt = response.getInt("expires_in") + Instant.now().getEpochSecond() - 1;
			this.refreshToken = response.getString("refresh_token");
		}
	}
}
