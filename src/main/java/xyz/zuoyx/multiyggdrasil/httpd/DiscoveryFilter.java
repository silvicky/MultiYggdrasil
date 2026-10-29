/*
 * Copyright (C) 2026  Haowei Wen <yushijinhun@gmail.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package xyz.zuoyx.multiyggdrasil.httpd;

import static xyz.zuoyx.multiyggdrasil.util.IOUtils.CONTENT_TYPE_JSON;
import static xyz.zuoyx.multiyggdrasil.util.IOUtils.asBytes;
import static xyz.zuoyx.multiyggdrasil.util.IOUtils.asString;
import static xyz.zuoyx.multiyggdrasil.util.IOUtils.sendResponse;
import static xyz.zuoyx.multiyggdrasil.util.JsonUtils.parseJson;
import static xyz.zuoyx.multiyggdrasil.util.JsonUtils.toJsonString;
import static xyz.zuoyx.multiyggdrasil.util.Logging.Level.DEBUG;
import static xyz.zuoyx.multiyggdrasil.util.Logging.log;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import xyz.zuoyx.multiyggdrasil.util.UnsupportedURLException;

public class DiscoveryFilter implements URLFilter {

	public static final JsonObject MOJANG_DISCOVERY_JSON;

	private final ConcurrentHashMap<URLProcessor, JsonObject> transformedDiscoveryJSONs = new ConcurrentHashMap<>();

	static {
		// Hardcode current response from discovery endpoint in case Mojang
		// moves an endpoint to somewhere the authlib-injector filters don't
		// recognize
		try (InputStream in = DiscoveryFilter.class.getResourceAsStream("/discovery_minecraftservices_com_minecraft_client.json")) {
			MOJANG_DISCOVERY_JSON = parseJson(asString(asBytes(in))).getAsJsonObject();
		} catch (IOException | UncheckedIOException e) {
			throw new RuntimeException("Failed to load hardcoded Mojang discovery response", e);
		}
	}

	@Override
	public boolean canHandle(String domain) {
		return domain.equals("discovery.minecraftservices.com");
	}

	@Override
	public void handle(URLProcessor urlProcessor, String domain, String path, HttpExchange exchange) throws UnsupportedURLException, IOException {
		if (domain.equals("discovery.minecraftservices.com") && path.equals("/minecraft/client") && exchange.getRequestMethod().equals("GET")) {
			JsonObject response = transformedDiscoveryJSONs.computeIfAbsent(urlProcessor, p -> {
				JsonObject transformed = transformDiscoveryJSON(MOJANG_DISCOVERY_JSON, p).getAsJsonObject();
				log(DEBUG, "Transformed discovery JSON: " + toJsonString(transformed));
				return transformed;
			});
			sendResponse(exchange, 200, CONTENT_TYPE_JSON, toJsonString(response).getBytes());
		} else {
			throw new UnsupportedURLException();
		}
	}

	// Returns a deep copy of the given JSON with every string value run through
	// urlProcessor.transformURL, so any endpoint URIs are pointed at the local server.
	private static JsonElement transformDiscoveryJSON(JsonElement json, URLProcessor urlProcessor) {
		if (json.isJsonObject()) {
			JsonObject copy = new JsonObject();
			json.getAsJsonObject().entrySet()
					.forEach(entry -> copy.add(entry.getKey(), transformDiscoveryJSON(entry.getValue(), urlProcessor)));
			return copy;
		} else if (json.isJsonArray()) {
			JsonArray copy = new JsonArray();
			for (JsonElement element : json.getAsJsonArray()) {
				copy.add(transformDiscoveryJSON(element, urlProcessor));
			}
			return copy;
		} else if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			String value = json.getAsString();
			return new JsonPrimitive(urlProcessor.transformURL(value).orElse(value));
		} else {
			return json;
		}
	}
}
