package com.fitcollector.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
import com.fitcollector.ingest.OpenPricesClient.PriceEntry;
import com.fitcollector.ingest.OpenPricesClient.PricesPage;
import com.fitcollector.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Мапінг реальних відповідей Open Prices (фікстура нижче — скорочений живий JSON API)
 * у продукти каталогу.
 */
class CatalogMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Macros macros = new Macros(97, 11, 4, 2.5);

    private static final String OPEN_PRICES_PAGE = """
        {
          "items": [
            {
              "product_code": "5900531000041",
              "price": 7.79,
              "price_per": "UNIT",
              "currency": "PLN",
              "date": "2026-06-10",
              "product": {
                "code": "5900531000041",
                "product_name": "Serek wiejski",
                "image_url": "https://images.openfoodfacts.org/images/products/590/053/100/0041/front_en.15.400.jpg",
                "product_quantity": 500,
                "product_quantity_unit": "g",
                "brands": "Piątnica",
                "categories_tags": ["en:dairies", "en:cottage-cheeses"]
              },
              "location": {"osm_brand": "Biedronka", "osm_name": "Biedronka", "osm_address_city": "Lublin"}
            },
            {
              "product_code": null,
              "price": 5.99,
              "price_per": "KILOGRAM",
              "currency": "PLN",
              "date": "2026-06-08",
              "product": null,
              "location": {"osm_brand": "Lidl", "osm_name": "Lidl", "osm_address_city": "Kraków"}
            }
          ],
          "page": 1, "pages": 1, "total": 2
        }
        """;

    private List<PriceEntry> parseFixture() throws Exception {
        return objectMapper.readValue(OPEN_PRICES_PAGE, PricesPage.class).items();
    }

    @Test
    void mapsRealApiEntryToProduct() throws Exception {
        PriceEntry entry = parseFixture().get(0);
        Optional<Product> mapped = CatalogMapper.toProduct(entry, macros, "Biedronka");

        assertThat(mapped).isPresent();
        Product p = mapped.get();
        assertThat(p.id()).isEqualTo("op-biedronka-5900531000041");
        assertThat(p.barcode()).isEqualTo("5900531000041");
        assertThat(p.name()).isEqualTo("Serek wiejski");
        assertThat(p.brand()).isEqualTo("Piątnica");
        assertThat(p.store()).isEqualTo("Biedronka");
        assertThat(p.category()).isEqualTo("молочка");
        assertThat(p.packagePriceZl()).isEqualTo(7.79);
        assertThat(p.packageGrams()).isEqualTo(500);
        assertThat(p.proteinPer100g()).isEqualTo(11);
        assertThat(p.imageUrl()).contains("openfoodfacts.org");
        assertThat(p.priceSource()).isEqualTo("open-prices");
        assertThat(p.priceDate()).isEqualTo("2026-06-10");
    }

    @Test
    void skipsEntryWithoutProduct() throws Exception {
        PriceEntry entry = parseFixture().get(1);
        assertThat(CatalogMapper.toProduct(entry, macros, "Lidl")).isEmpty();
    }

    @Test
    void convertsPerKilogramPriceToPackagePrice() throws Exception {
        PriceEntry unit = parseFixture().get(0);
        PriceEntry perKg = new PriceEntry(
                unit.productCode(), 20.0, "KILOGRAM", "PLN", unit.date(), unit.product(), unit.location());

        Product p = CatalogMapper.toProduct(perKg, macros, "Biedronka").orElseThrow();
        // 20 zł/кг × 500 г = 10 zł за упаковку
        assertThat(p.packagePriceZl()).isEqualTo(10.0);
    }

    @Test
    void rejectsImplausibleValues() throws Exception {
        PriceEntry unit = parseFixture().get(0);
        // 0.05 zł за упаковку — явно помилковий запис
        PriceEntry tooCheap = new PriceEntry(
                unit.productCode(), 0.05, "UNIT", "PLN", unit.date(), unit.product(), unit.location());
        assertThat(CatalogMapper.toProduct(tooCheap, macros, "Biedronka")).isEmpty();

        // 2000 ккал/100г не буває
        assertThat(CatalogMapper.toProduct(unit, new Macros(2000, 10, 5, 5), "Biedronka")).isEmpty();
    }

    @Test
    void biedronkaCategoryMapping() {
        assertThat(CatalogMapper.categoryFromBiedronka("Nabiał", "Kefiry")).isEqualTo("йогурти");
        assertThat(CatalogMapper.categoryFromBiedronka("Nabiał", "Sery żółte")).isEqualTo("сир");
        assertThat(CatalogMapper.categoryFromBiedronka("Nabiał", "Twarogi")).isEqualTo("молочка");
        assertThat(CatalogMapper.categoryFromBiedronka("Nabiał", "Jaja")).isEqualTo("яйця");
        assertThat(CatalogMapper.categoryFromBiedronka("Mięso", "Drób")).isEqualTo("м'ясо");
        assertThat(CatalogMapper.categoryFromBiedronka("Mięso", "Ryby")).isEqualTo("риба");
        // "Konserwy" не повинні ставати сиром через підрядок "serw"
        assertThat(CatalogMapper.categoryFromBiedronka("Artykuły spożywcze", "Produkty konserwowe"))
                .isEqualTo("інше");
        assertThat(CatalogMapper.categoryFromBiedronka("Artykuły spożywcze", "Produkty sypkie"))
                .isEqualTo("крупи");
        assertThat(CatalogMapper.categoryFromBiedronka(null, null)).isEqualTo("інше");
    }

    @Test
    void categoryMappingPrefersSpecificTags() {
        assertThat(CatalogMapper.category(List.of("en:dairies", "en:cheeses"))).isEqualTo("сир");
        assertThat(CatalogMapper.category(List.of("en:meats"))).isEqualTo("м'ясо");
        assertThat(CatalogMapper.category(List.of("en:something-unknown"))).isEqualTo("інше");
        assertThat(CatalogMapper.category(null)).isEqualTo("інше");
    }
}
