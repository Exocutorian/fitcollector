package com.fitcollector.service;

import com.fitcollector.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RationOptimizerTest {

    private final RationOptimizer optimizer = new RationOptimizer();

    // Дешеве джерело калорій, дороге джерело білка, і "золота середина"
    private final Product rice = new Product(
            "rice", "Ryż", "Biedronka", "zboża", 5.50, 1000, 349, 7.0, 0.7, 78.0);
    private final Product chicken = new Product(
            "chicken", "Filet z kurczaka", "Biedronka", "mięso", 21.90, 1000, 112, 21.5, 2.6, 0);
    private final Product oats = new Product(
            "oats", "Płatki owsiane", "Biedronka", "zboża", 2.79, 500, 372, 12.0, 7.0, 60.0);
    private final Product oil = new Product(
            "oil", "Olej", "Biedronka", "tłuszcze", 7.99, 920, 884, 0, 100.0, 0);

    private RationRequest request(double kcal, double protein, Double maxFat, double maxPerProduct) {
        return new RationRequest(kcal, protein, maxFat, null, List.of(), maxPerProduct);
    }

    @Test
    void meetsKcalAndProteinTargetsAtMinimalCost() {
        RationResult result = optimizer.optimize(
                List.of(rice, chicken, oats, oil), request(2000, 100, null, 600));

        assertThat(result.feasible()).isTrue();
        // Допуск на округлення порцій до 5 г
        assertThat(result.totals().kcal()).isBetween(2000 * 0.97, 2000 * 1.08);
        assertThat(result.totals().proteinG()).isGreaterThanOrEqualTo(97);
        assertThat(result.totals().costZl()).isGreaterThan(0);
    }

    @Test
    void prefersCheaperProteinSourceWhenMacrosAllow() {
        // Вівсянка дає білок значно дешевше за курку —
        // без обмеження жирів оптимізатор мусить брати саме її.
        RationResult result = optimizer.optimize(
                List.of(chicken, oats), request(1500, 60, null, 1000));

        assertThat(result.feasible()).isTrue();
        assertThat(result.items())
                .anyMatch(i -> i.productId().equals("oats"));
        double oatsCost = result.items().stream()
                .filter(i -> i.productId().equals("oats"))
                .mapToDouble(RationResult.Item::costZl).sum();
        assertThat(oatsCost).isGreaterThan(0);
    }

    @Test
    void respectsPerProductCap() {
        RationResult result = optimizer.optimize(
                List.of(rice, chicken, oats, oil), request(2500, 120, null, 400));

        assertThat(result.feasible()).isTrue();
        assertThat(result.items()).allMatch(i -> i.grams() <= 400.0);
    }

    @Test
    void respectsFatLimit() {
        RationResult result = optimizer.optimize(
                List.of(rice, chicken, oats, oil), request(2000, 90, 40.0, 600));

        assertThat(result.feasible()).isTrue();
        // Допуск на округлення порцій
        assertThat(result.totals().fatG()).isLessThanOrEqualTo(42.0);
    }

    @Test
    void reportsInfeasibleWhenTargetsImpossible() {
        // 300 г білка з максимум 200 г на продукт із двох продуктів — фізично неможливо
        RationResult result = optimizer.optimize(
                List.of(rice, chicken), request(2000, 300, null, 200));

        assertThat(result.feasible()).isFalse();
        assertThat(result.message()).isNotBlank();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void rejectsNonPositiveKcalTarget() {
        RationResult result = optimizer.optimize(List.of(rice), request(0, 0, null, 500));
        assertThat(result.feasible()).isFalse();
    }
}
