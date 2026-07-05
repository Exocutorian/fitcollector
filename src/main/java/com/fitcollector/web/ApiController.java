package com.fitcollector.web;

import com.fitcollector.model.Product;
import com.fitcollector.repository.ProductRepository;
import com.fitcollector.service.RationOptimizer;
import com.fitcollector.service.RationRequest;
import com.fitcollector.service.RationResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ProductRepository repository;
    private final RationOptimizer optimizer;

    public ApiController(ProductRepository repository, RationOptimizer optimizer) {
        this.repository = repository;
        this.optimizer = optimizer;
    }

    @GetMapping("/stores")
    public Set<String> stores() {
        return repository.stores();
    }

    @GetMapping("/products")
    public List<Product> products(
            @RequestParam(required = false) List<String> store,
            @RequestParam(defaultValue = "pricePer100gProtein") String sort,
            @RequestParam(defaultValue = "asc") String order) {

        Set<String> stores = store == null ? Set.of() : new HashSet<>(store);
        List<Product> result = repository.findByStores(stores);
        boolean descending = "desc".equalsIgnoreCase(order);
        return result.stream().sorted(comparatorFor(sort, descending)).toList();
    }

    @PostMapping("/ration")
    public RationResult ration(@RequestBody RationRequest request) {
        Set<String> stores = request.stores() == null ? Set.of() : new HashSet<>(request.stores());
        List<Product> candidates = repository.findByStores(stores);
        return optimizer.optimize(candidates, request);
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
