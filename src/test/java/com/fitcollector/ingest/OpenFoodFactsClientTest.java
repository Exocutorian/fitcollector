package com.fitcollector.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OpenFoodFactsClientTest {

    @TempDir
    Path tempDir;

    private OpenFoodFactsClient client() {
        return new OpenFoodFactsClient(new ObjectMapper(),
                "https://unused.example", "test", tempDir.toString(), 7, 0);
    }

    @Test
    void parsesKcalDirectly() {
        String json = """
            {"product":{"nutriments":{"energy-kcal_100g":97,"proteins_100g":11,"fat_100g":4,"carbohydrates_100g":2.5}},"status":1}
            """;
        Optional<Macros> m = client().parseMacros(json);
        assertThat(m).isPresent();
        assertThat(m.get().kcalPer100g()).isEqualTo(97);
        assertThat(m.get().proteinPer100g()).isEqualTo(11);
    }

    @Test
    void convertsKilojoulesWhenKcalMissing() {
        String json = """
            {"product":{"nutriments":{"energy_100g":418.4,"proteins_100g":5,"fat_100g":1,"carbohydrates_100g":10}},"status":1}
            """;
        Optional<Macros> m = client().parseMacros(json);
        assertThat(m).isPresent();
        assertThat(m.get().kcalPer100g()).isCloseTo(100, within(0.5));
    }

    @Test
    void missingFatAndCarbsDefaultToZero() {
        String json = """
            {"product":{"nutriments":{"energy-kcal_100g":112,"proteins_100g":21.5}},"status":1}
            """;
        Optional<Macros> m = client().parseMacros(json);
        assertThat(m).isPresent();
        assertThat(m.get().fatPer100g()).isZero();
        assertThat(m.get().carbsPer100g()).isZero();
    }

    @Test
    void returnsEmptyWhenNoNutrimentsOrNotFound() {
        assertThat(client().parseMacros("{\"status\":0}")).isEmpty();
        assertThat(client().parseMacros("{\"product\":{\"nutriments\":{\"proteins_100g\":5}},\"status\":1}")).isEmpty();
        assertThat(client().parseMacros("не json")).isEmpty();
    }
}
