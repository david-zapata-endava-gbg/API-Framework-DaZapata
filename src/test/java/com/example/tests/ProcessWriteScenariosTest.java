package com.example.tests;

import com.example.framework.TestBase;
import com.example.framework.TestConfig;
import com.example.framework.TestResponseValidator;
import com.example.model.Movie;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProcessWriteScenariosTest
 *
 * These tests read data from TheMovieDB and write/update/delete it against a mocked JSONPlaceholder
 * endpoint (WireMock). Using WireMock makes tests deterministic and independent from external
 * third-party availability.
 */
public class ProcessWriteScenariosTest extends TestBase {

    private static WireMockServer wireMockServer;

    @BeforeAll
    public static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        // stub POST /posts to return 201 with a generated id
        WireMock.stubFor(WireMock.post(WireMock.urlEqualTo("/posts"))
                .willReturn(WireMock.aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":101}")));

        // stub PATCH/PUT to return 200 and echo back a title field for verification
        WireMock.stubFor(WireMock.patch(WireMock.urlPathMatching("/posts/\\d+"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"title\":\"updated\"}")));

        // stub DELETE to return 200
        WireMock.stubFor(WireMock.delete(WireMock.urlPathMatching("/posts/\\d+"))
                .willReturn(WireMock.aResponse().withStatus(200)));
    }

    @AfterAll
    public static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    public void fetchFromTmdb_and_postPutDelete_toJsonPlaceholder() {
        TestConfig cfg = new TestConfig();
        String apiKey = cfg.getApiKey();
        assertNotNull(apiKey, "API key required in src/test/resources/credentials.properties");

        // First, fetch a movie from TMDB
        Response nowPlaying = apiClient.request().queryParam("api_key", apiKey).get("/3/movie/now_playing");
        TestResponseValidator.assertStatusCode(nowPlaying, 200);
        Movie first = nowPlaying.jsonPath().getObject("results[0]", Movie.class);
        assertNotNull(first, "Expect at least one now_playing movie");

        // Next, POST the movie JSON to the local WireMock instance (mocking JSONPlaceholder)
        String postUrl = wireMockServer.baseUrl() + "/posts";
        Response postResp = apiClient.request().body(first).post(postUrl);
        assertEquals(201, postResp.getStatusCode(), "POST to mocked JSONPlaceholder should return 201");
        int createdId = postResp.jsonPath().getInt("id");
        assertTrue(createdId > 0, "Mocked JSONPlaceholder should return an id for the created resource");

        // PATCH (partially update) the created resource
        first.setTitle(first.getTitle() + " - updated");
        String patchUrl = String.format("%s/posts/%d", wireMockServer.baseUrl(), createdId);
        Response patchResp = apiClient.request().body(first).patch(patchUrl);
        assertEquals(200, patchResp.getStatusCode(), "PATCH should return 200 on mocked JSONPlaceholder");
        // our stub returns a fixed title value — check it's present
        assertNotNull(patchResp.jsonPath().getString("title"));

        // Finally, DELETE the created resource
        Response delResp = apiClient.request().delete(patchUrl);
        assertTrue(delResp.getStatusCode() == 200 || delResp.getStatusCode() == 204,
                "DELETE should return 200 or 204");
    }

    @Test
    public void searchTmdb_and_postResult_toJsonPlaceholder() {
        TestConfig cfg = new TestConfig();
        String apiKey = cfg.getApiKey();
        assertNotNull(apiKey);

        Response search = apiClient.request().queryParam("api_key", apiKey).queryParam("query", "matrix").get("/3/search/movie");
        TestResponseValidator.assertStatusCode(search, 200);
        Movie found = search.jsonPath().getObject("results[0]", Movie.class);
        assertNotNull(found, "Expected search to return a movie");

        String postUrl = wireMockServer.baseUrl() + "/posts";
        Response p = apiClient.request().body(found).post(postUrl);
        assertEquals(201, p.getStatusCode());
        assertTrue(p.jsonPath().getInt("id") > 0);
    }
}
