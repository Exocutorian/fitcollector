package com.fitcollector.web;

import com.fitcollector.ingest.CatalogIngestionService;
import com.fitcollector.model.Product;
import com.fitcollector.service.CatalogService;
import com.fitcollector.service.RationOptimizer;
import com.fitcollector.service.RationRequest;
import com.fitcollector.service.RationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final CatalogService catalog;
    private final RationOptimizer optimizer;
    private final CatalogIngestionService ingestionService;

    public ApiController(CatalogService catalog,
                         RationOptimizer optimizer,
                         CatalogIngestionService ingestionService) {
        this.catalog = catalog;
        this.optimizer = optimizer;
        this.ingestionService = ingestionService;
    }

    @GetMapping("/stores")
    public Set<String> stores() {
        return catalog.stores();
    }

    @GetMapping("/categories")
    public Set<String> categories() {
        return catalog.categories();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "productsTotal", catalog.findAll().size(),
                "realPrices", catalog.findAll().stream().filter(p -> "open-prices".equals(p.priceSource())).count(),
                "ingest", ingestionService.status());
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        boolean started = ingestionService.refreshAsync();
        return Map.of("started", started, "ingest", ingestionService.status());
    }

    @GetMapping("/products")
    public List<Product> products(
            @RequestParam(required = false) List<String> store,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "pricePer100gProtein") String sort,
            @RequestParam(defaultValue = "asc") String order) {

        Set<String> stores = store == null ? Set.of() : new HashSet<>(store);
        List<Product> result = catalog.findByStores(stores);

        if (category != null && !category.isBlank()) {
            result = result.stream().filter(p -> category.equals(p.category())).toList();
        }
        if (q != null && !q.isBlank()) {
            String needle = fold(q);
            result = result.stream()
                    .filter(p -> fold(p.name()).contains(needle)
                            || (p.brand() != null && fold(p.brand()).contains(needle)))
                    .toList();
        }
        boolean descending = "desc".equalsIgnoreCase(order);
        return result.stream().sorted(comparatorFor(sort, descending)).toList();
    }

    @PostMapping("/ration")
    public RationResult ration(@RequestBody RationRequest request) {
        Set<String> stores = request.stores() == null ? Set.of() : new HashSet<>(request.stores());
        List<Product> candidates = catalog.findByStores(stores);
        return optimizer.optimize(candidates, request);
    }

    /** Пошук без діакритики й регістру: "platki" знаходить "Płatki". */
    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'l')
                .toLowerCase(Locale.ROOT);
    }

    private static Comparator<Product> comparatorFor(String sort, boolean descending) {
        Comparator<Double> direction = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        // Продукти без білка/калорій (metric == null) завжди в кінці, незалежно від напрямку.
        Function<Function<Product, Double>, Comparator<Product>> byMetric =
                metric -> Comparator.comparing(metric, Comparator.nullsLast(direction));

        return switch (sort) {
            case "pricePer1000Kcal" -> byMetric.apply(Product::pricePer1000Kcal);
            case "proteinPerZloty" -> byMetric.apply(Product::proteinPerZloty);
            case "kcalPerZloty" -> byMetric.apply(Product::kcalPerZloty);
            case "pricePer100g" -> byMetric.apply(Product::pricePer100g);
            case "price" -> byMetric.apply(Product::packagePriceZl);
            case "name" -> {
                Comparator<Product> byName = Comparator.comparing(Product::name, String.CASE_INSENSITIVE_ORDER);
                yield descending ? byName.reversed() : byName;
            }
            default -> byMetric.apply(Product::pricePer100gProtein);
        };
    }
}
