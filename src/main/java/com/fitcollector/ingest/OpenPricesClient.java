package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клієнт Open Prices (prices.openfoodfacts.org) — краудсорсні ціни з чеків.
 * Тягне всі ціни в PLN посторінково; кожен запис уже містить вбудований
 * OFF-продукт (назва, картинка, вага упаковки, бренд, категорії) та локацію
 * (мережа магазину, місто).
 */
@Component
public class OpenPricesClient {

    private static final Logger log = LoggerFactory.getLogger(OpenPricesClient.class);
    private static final int PAGE_SIZE = 100;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String userAgent;
    private final int maxPages;

    public OpenPricesClient(
            ObjectMapper objectMapper,
            @Value("${fitcollector.open-prices.base-url:https://prices.openfoodfacts.org}") String baseUrl,
            @Value("${fitcollector.user-agent:FitCollector/0.2 (https://github.com/Exocutorian/fitcollector)}") String userAgent,
            @Value("${fitcollector.open-prices.max-pages:30}") int maxPages) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.maxPages = maxPages;
    }

    public List<PriceEntry> fetchAllPlnPrices() throws IOException, InterruptedException {
        List<PriceEntry> all = new ArrayList<>();
        int page = 1;
        while (page <= maxPages) {
            String url = baseUrl + "/api/v1/prices?currency=PLN&size=" + PAGE_SIZE + "&page=" + page;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Open Prices відповів " + response.statusCode() + " на " + url);
            }
            PricesPage parsed = objectMapper.readValue(response.body(), PricesPage.class);
            all.addAll(parsed.items());
            log.info("Open Prices: сторінка {}/{} — {} записів", page, parsed.pages(), parsed.items().size());
            if (page >= parsed.pages() || parsed.items().isEmpty()) {
                break;
            }
            page++;
        }
        return all;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricesPage(List<PriceEntry> items, int page, int pages, long total) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceEntry(
            @JsonProperty("product_code") String productCode,
            Double price,
            @JsonProperty("price_per") String pricePer,
            String currency,
            String date,
            PriceProduct product,
            PriceLocation location) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceProduct(
            String code,
            @JsonProperty("product_name") String productName,
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("product_quantity") Double productQuantity,
            @JsonProperty("product_quantity_unit") String productQuantityUnit,
            String brands,
            @JsonProperty("categories_tags") List<String> categoriesTags) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceLocation(
            @JsonProperty("osm_brand") String osmBrand,
            @JsonProperty("osm_name") String osmName,
            @JsonProperty("osm_address_city") String city) {}
}
