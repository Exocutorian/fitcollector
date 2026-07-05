package com.fitcollector.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.model.Product;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRepositoryTest {

    private final ProductRepository repository = new ProductRepository(new ObjectMapper());

    @Test
    void loadsProductsWithValidData() {
        assertThat(repository.findAll()).isNotEmpty();
        for (Product p : repository.findAll()) {
            assertThat(p.id()).isNotBlank();
            assertThat(p.name()).isNotBlank();
            assertThat(p.packagePriceZl()).as("%s: ціна", p.id()).isPositive();
            assertThat(p.packageGrams()).as("%s: вага", p.id()).isPositive();
            assertThat(p.kcalPer100g()).as("%s: ккал", p.id()).isPositive();
            assertThat(p.proteinPer100g()).as("%s: білок", p.id()).isNotNegative();
            assertThat(p.fatPer100g()).as("%s: жири", p.id()).isNotNegative();
            assertThat(p.carbsPer100g()).as("%s: вуглеводи", p.id()).isNotNegative();
        }
    }

    @Test
    void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (Product p : repository.findAll()) {
            assertThat(ids.add(p.id())).as("дубльований id: %s", p.id()).isTrue();
        }
    }

    @Test
    void containsAllThreeStores() {
        assertThat(repository.stores()).containsExactlyInAnyOrder("Biedronka", "Lidl", "Stokrotka");
    }

    @Test
    void filtersByStore() {
        assertThat(repository.findByStores(Set.of("Lidl")))
                .isNotEmpty()
                .allMatch(p -> p.store().equals("Lidl"));
        assertThat(repository.findByStores(Set.of())).hasSameSizeAs(repository.findAll());
    }
}
