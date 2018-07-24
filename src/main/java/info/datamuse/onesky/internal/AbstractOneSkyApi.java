package info.datamuse.onesky.internal;

import info.datamuse.onesky.OneSkyApiException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static info.datamuse.onesky.internal.HttpUtils.CONTENT_TYPE_HEADER;
import static info.datamuse.onesky.internal.HttpUtils.HTTP_GET;
import static info.datamuse.onesky.internal.HttpUtils.HTTP_POST;
import static info.datamuse.onesky.internal.HttpUtils.HTTP_STATUS_CREATED;
import static info.datamuse.onesky.internal.HttpUtils.HTTP_STATUS_OK;
import static java.net.http.HttpRequest.BodyPublishers.noBody;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.apache.commons.codec.digest.DigestUtils.md5Hex;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Abstract base class for OneSky API Wrappers.
 */
public abstract class AbstractOneSkyApi {

    /**
     * OneSky API base URL.
     */
    public static final String API_BASE_URL = "https://platform.api.onesky.io/1";

    private static final Logger logger = getLogger(AbstractOneSkyApi.class);

    private final String apiKey;
    private final String apiSecret;
    private final HttpClient httpClient;

    /**
     * Protected constructor.
     *
     * @param apiKey OneSky API public key
     * @param apiSecret OneSky API secret key
     * @param httpClient HTTP Client
     */
    protected AbstractOneSkyApi(final String apiKey, final String apiSecret, final HttpClient httpClient) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.httpClient = httpClient;
    }

    // TODO: implement and self-review the below

    /**
     * Executes an API GET-request which is expected to return a list and returns a {@link CompletableFuture promise} for the result list.
     *
     * @param <T> target list item type
     * @param apiUrl API URL excluding query parameters
     * @param dataItemConverter converter of the result-data-list-item-JSONs into target types.
     * @return retrieved list (promise)
     */
    protected final <T> CompletableFuture<List<T>> apiGetListOfObjectsRequest(
        final String apiUrl,
        final Function<JSONObject, T> dataItemConverter
    ) {
        return apiGetListOfObjectsRequest(apiUrl, emptyMap(), dataItemConverter);
    }

    /**
     * Executes an API GET-request which is expected to return a list and returns a {@link CompletableFuture promise} for the result list.
     *
     * @param <T> target list item type
     * @param apiUrl API URL excluding query parameters
     * @param parameters URL query parameters excluding auth-parameters
     * @param dataItemConverter converter of the result-data-list-item-JSONs into target types.
     * @return retrieved list (promise)
     */
    protected final <T> CompletableFuture<List<T>> apiGetListOfObjectsRequest(
        final String apiUrl,
        final Map<String, String> parameters,
        final Function<JSONObject, T> dataItemConverter
    ) {
        return
            apiDataRequest(HTTP_GET, noBody(), apiUrl, parameters, HTTP_STATUS_OK)
                .thenApply(/* @Nullable */ data -> {
                    if (!(data instanceof JSONArray)) {
                        throw unexpectedResponseDataTypeException(data, JSONArray.class, apiUrl);
                    }
                    return StreamSupport.stream(((JSONArray) data).spliterator(), false)
                        .map(dataItem -> {
                            if (!(dataItem instanceof JSONObject)) {
                                throw new IllegalArgumentException(String.format(
                                    Locale.ROOT,
                                    "OneSky API response data was expected to be an array of objects, but an item of type `%s` was received. API URL: `%s`",
                                    getTypeName(data), apiUrl
                                ));
                            }
                            return dataItemConverter.apply((JSONObject) dataItem);
                        })
                        .collect(toUnmodifiableList());
                    }
                );
    }

    protected final <T> CompletableFuture<T> apiCreateRequest(
        final String apiUrl,
        final Map<String, String> parameters,
        final Function<JSONObject, T> dataConverter
    ) {
        return
            apiDataRequest(HTTP_POST, noBody(), apiUrl, parameters, HTTP_STATUS_CREATED)
                .thenApply(/* @Nullable */ data -> {
                        if (!(data instanceof JSONObject)) {
                            throw unexpectedResponseDataTypeException(data, JSONObject.class, apiUrl);
                        }
                        return dataConverter.apply((JSONObject) data);
                    }
                );
    }

    /**
     * Executes an API request and returns a {@link CompletableFuture promise} for the result's {@code data} part.
     *
     * @param apiUrl API URL excluding query parameters
     * @param parameters URL query parameters excluding auth-parameters
     * @return retrieved data (promise)
     */
    protected final CompletableFuture<?> apiDataRequest(
        final String httpMethod,
        final HttpRequest.BodyPublisher httpRequestBodyPublisher,
        final String apiUrl,
        final Map<String, String> parameters,
        final long expectedStatus
    ) {
        logger.info("OneSky API call"); // TODO: include url with parameters, excluding auth data
        // TODO: log from within the Future, when finished
        final String apiUrlWithParameters =
            apiUrl
            + '?'
            + Stream.concat(
                parameters.entrySet().stream(),
                generateAuthParameters().entrySet().stream()
            ).map(
                paramEntry -> paramEntry.getKey() + '=' + paramEntry.getValue()
            ).collect(joining("&"));
        final HttpRequest apiHttpRequest =
            HttpRequest.newBuilder(URI.create(apiUrlWithParameters))
                .method(httpMethod, httpRequestBodyPublisher)
                .header(CONTENT_TYPE_HEADER, "application/json")
                .build(); // TODO: cache this
        return
            httpClient
                .sendAsync(apiHttpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> getResponseData(httpResponse, expectedStatus));
    }

    private Map<String, String> generateAuthParameters() {
        final String timestamp = Long.toString(Instant.now().getEpochSecond());
        final String devHash = md5Hex(timestamp + apiSecret);
        return Map.of(
            "api_key", apiKey,
            "timestamp", timestamp,
            "dev_hash", devHash
        );
    }

    private static Object getResponseData(final HttpResponse<String> httpResponse, final long expectedStatus) { // TODO: test this method and the methods below it
        try {
            final JSONObject responseJson = new JSONObject(httpResponse.body());
            checkSuccessResponse(responseJson, expectedStatus);
            return responseJson.get("data");
        } catch (final JSONException e) {
            throw new OneSkyApiException(e);
        }
    }

    private static void checkSuccessResponse(final JSONObject responseJson, final long expectedStatus) {
        final JSONObject metaJson = responseJson.getJSONObject("meta");
        final long status = metaJson.getLong("status");
        if (status != expectedStatus) {
            final @Nullable String message = metaJson.has("message") ? metaJson.getString("message") : null;
            throw new OneSkyApiException(String.format(
                Locale.ROOT,
                "Expected status=%d, but API responded with status=%d, message=%s",
                expectedStatus, status, message
            ));
        }
    }

    private static IllegalArgumentException unexpectedResponseDataTypeException(final @Nullable Object data, final Class<?> expectedClass, final String apiUrl) {
        return new IllegalArgumentException(String.format(
            Locale.ROOT,
            "OneSky API response data was expected to be of type `%s`, but was of type `%s`. API URL: `%s`",
            expectedClass.getSimpleName(), getTypeName(data), apiUrl
        ));
    }

    private static String getTypeName(final @Nullable Object obj) {
        return obj != null ? obj.getClass().getSimpleName() : "null";
    }

}
