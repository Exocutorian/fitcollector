package com.fitcollector.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Скрапер онлайн-магазину Biedronka (zakupy.biedronka.pl, Salesforce Commerce Cloud).
 *
 * Обходить харчові категорії з пагінацією (?start=N&sz=32 працює з заголовком
 * X-Requested-With: XMLHttpRequest) і збирає плитки продуктів: назва, бренд,
 * актуальна ціна, фото, категорія — все з боку магазину.
 * КБЖВ на сайті немає, її домапує {@link OffMatchIndex}.
 */
@Component
public class BiedronkaClient {

    private static final Logger log = LoggerFactory.getLogger(BiedronkaClient.class);
    private static final int MAX_PAGES_PER_CATEGORY = 40;

    /** Топ-категорії з їжею; решта (drogeria, dla domu…) нам не потрібна. */
    private static final Set<String> FOOD_TOP_CATEGORIES = Set.of(
            "artykuly-spozywcze", "dania-gotowe", "mieso", "mrozone",
            "nabial", "owoce", "piekarnia", "warzywa", "napoje");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String userAgent;
    private final long throttleMillis;

    public BiedronkaClient(
            ObjectMapper objectMapper,
            @Value("${fitcollector.biedronka.base-url:https://zakupy.biedronka.pl}") String baseUrl,
            @Value("${fitcollector.user-agent:FitCollector/0.3 (https://github.com/Exocutorian/fitcollector)}") String userAgent,
            @Value("${fitcollector.biedronka.throttle-millis:400}") long throttleMillis) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.throttleMillis = throttleMillis;
    }

    /** Обходить усі харчові категорії посторінково; повертає унікальні продукти (по pid). */
    public List<Tile> fetchAllProducts() throws IOException, InterruptedException {
        Set<String> categories = discoverCategoryPaths();
        log.info("Biedronka: {} категорій до обходу", categories.size());

        Map<String, Tile> byPid = new LinkedHashMap<>();
        for (String path : categories) {
            for (int page = 1; page <= MAX_PAGES_PER_CATEGORY; page++) {
                // так будує URL їхній infinite scroll (search.js: createRequestUrl)
                String url = baseUrl + path + "?page=" + page + "&format=page-element";
                List<Tile> tiles;
                boolean hasNext;
                try {
                    String html = fetchHtml(url);
                    tiles = parseTiles(html);
                    hasNext = hasNextPage(html);
                } catch (IOException e) {
                    log.warn("Biedronka: {} пропущено: {}", url, e.getMessage());
                    break;
                }
                int before = byPid.size();
                tiles.forEach(t -> byPid.putIfAbsent(t.pid(), t));
                log.info("Biedronka {} стор.{} → {} плиток (разом {})",
                        path, page, tiles.size(), byPid.size());
                Thread.sleep(throttleMillis);
                if (tiles.isEmpty() || !hasNext || byPid.size() == before) {
                    break;
                }
            }
        }
        return new ArrayList<>(byPid.values());
    }

    /** Головна сторінка → лінки харчових топ-категорій. */
    Set<String> discoverCategoryPaths() throws IOException, InterruptedException {
        Set<String> paths = new LinkedHashSet<>();
        try {
            Document home = Jsoup.parse(fetchHtml(baseUrl + "/"));
            for (Element a : home.select("a[href]")) {
                String href = a.attr("href");
                if (href.matches("^/[a-z0-9-]+/$")
                        && FOOD_TOP_CATEGORIES.contains(href.replace("/", ""))) {
                    paths.add(href);
                }
            }
        } catch (IOException e) {
            log.warn("Biedronka: головна не відповіла ({}), беру фіксований список", e.getMessage());
        }
        if (paths.isEmpty()) {
            FOOD_TOP_CATEGORIES.forEach(c -> paths.add("/" + c + "/"));
        }
        return paths;
    }

    boolean hasNextPage(String html) {
        Element grid = Jsoup.parse(html).selectFirst(".js-infinite-scroll-grid");
        return grid != null && "true".equalsIgnoreCase(grid.attr("data-has-next").strip());
    }

    List<Tile> parseTiles(String html) {
        Document doc = Jsoup.parse(html);
        Map<String, Tile> byPid = new LinkedHashMap<>();
        for (Element el : doc.select("[data-product-gtm]")) {
            try {
                Gtm gtm = objectMapper.readValue(el.attr("data-product-gtm"), Gtm.class);
                if (gtm.itemId() == null || gtm.itemName() == null || gtm.price() == null) {
                    continue;
                }
                Element tile = el.closest(".product-tile");
                Element root = tile != null ? tile : (el.parent() != null ? el.parent() : el);
                byPid.putIfAbsent(gtm.itemId(), new Tile(
                        gtm.itemId(),
                        gtm.itemName().strip(),
                        blankToNull(gtm.itemBrand()),
                        Double.parseDouble(gtm.price()),
                        blankToNull(gtm.itemCategory()),
                        blankToNull(gtm.itemCategory2()),
                        firstImageUrl(root),
                        firstProductUrl(root)));
            } catch (IOException | NumberFormatException e) {
                // зламана плитка — пропускаємо
            }
        }
        return new ArrayList<>(byPid.values());
    }

    private static String firstImageUrl(Element scope) {
        for (Element img : scope.select("img[data-srcset], source[data-srcset], img[data-src], img[src]")) {
            for (String attr : new String[]{"data-srcset", "data-src", "src"}) {
                String v = img.attr(attr);
                if (v.contains("demandware.static") && v.contains("hi-res") && !v.contains("plp_stub")) {
                    return v.split("[ ,]")[0];
                }
            }
        }
        return null;
    }

    private static String firstProductUrl(Element scope) {
        Element a = scope.selectFirst("a[href$=.html]");
        if (a == null) {
            return null;
        }
        String abs = a.absUrl("href");
        return abs.isEmpty() ? a.attr("href") : abs;
    }

    private String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept-Language", "pl-PL,pl;q=0.9")
                .header("X-Requested-With", "XMLHttpRequest")
                .timeout(Duration.ofSeconds(40))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " для " + url);
        }
        return response.body();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Плитка продукту з лістингу магазину. */
    public record Tile(
            String pid,
            String name,
            String brand,
            double priceZl,
            String category,
            String category2,
            String imageUrl,
            String productUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Gtm(
            @JsonProperty("item_id") String itemId,
            @JsonProperty("item_name") String itemName,
            @JsonProperty("item_brand") String itemBrand,
            @JsonProperty("price") String price,
            @JsonProperty("item_category") String itemCategory,
            @JsonProperty("item_category2") String itemCategory2) {}
}
