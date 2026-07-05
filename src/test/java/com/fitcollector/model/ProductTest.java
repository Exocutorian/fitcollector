package com.fitcollector.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ProductTest {

    // 1 kg філе за 20 zł: 21.5 г білка / 100 г, 112 ккал / 100 г
    private final Product chicken = new Product(
            "test-chicken", null, "Filet z kurczaka", null, "Biedronka", "m'ясо",
            20.00, 1000, 112, 21.5, 2.6, 0, null, "seed", null);

    private final Product oil = new Product(
            "test-oil", null, "Olej", null, "Biedronka", "жири",
            8.00, 1000, 884, 0, 100, 0, null, "seed", null);

    @Test
    void pricePer100gIsPackagePriceScaledToGrams() {
        assertThat(chicken.pricePer100g()).isCloseTo(2.00, within(0.001));
    }

    @Test
    void pricePer100gProteinDividesByTotalProteinInPackage() {
        // 215 г білка в упаковці за 20 zł → 100 г білка коштує 9.30 zł
        assertThat(chicken.pricePer100gProtein()).isCloseTo(20.0 / 215.0 * 100.0, within(0.001));
    }

    @Test
    void pricePer1000KcalDividesByTotalKcalInPackage() {
        // 1120 ккал в упаковці за 20 zł → 1000 ккал коштує ~17.86 zł
        assertThat(chicken.pricePer1000Kcal()).isCloseTo(20.0 / 1120.0 * 1000.0, within(0.001));
    }

    @Test
    void proteinPerZlotyIsTotalProteinDividedByPrice() {
        assertThat(chicken.proteinPerZloty()).isCloseTo(215.0 / 20.0, within(0.001));
    }

    @Test
    void zeroProteinProductHasNullProteinPriceInsteadOfInfinity() {
        assertThat(oil.pricePer100gProtein()).isNull();
        assertThat(oil.proteinPerZloty()).isZero();
    }

    @Test
    void missingPriceSourceDefaultsToSeed() {
        Product p = new Product("x", null, "Test", null, "Lidl", null,
                1, 100, 100, 1, 1, 1, null, null, null);
        assertThat(p.priceSource()).isEqualTo("seed");
    }
}
