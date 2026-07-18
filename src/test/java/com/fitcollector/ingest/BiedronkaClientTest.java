package com.fitcollector.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.BiedronkaClient.Tile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Парсинг плиток лістингу zakupy.biedronka.pl.
 * Фікстура віддзеркалює реальну структуру SFCC-сторінки: GTM-атрибут висить
 * на формі в глибині плитки, картинки — в <picture> поруч, плюс заглушка plp_stub.
 */
class BiedronkaClientTest {

    private final BiedronkaClient client = new BiedronkaClient(
            new ObjectMapper(), "https://unused.example", "test", 0);

    private static final String CATEGORY_HTML = """
        <html><body>
        <ul class="product-grid js-infinite-scroll-grid" data-size="32.0" data-has-next="true">
          <li class="product-grid__item">
            <div class="product-tile js-product-tile">
              <a href="https://zakupy.biedronka.pl/krasnystaw-kefir-420-g-0000004146.html"
                 class="product-tile-clickable js-product-link"></a>
              <div class="tile-image">
                <picture class="tile-image__container">
                  <source media="(min-width: 1200px)" data-srcset="https://zakupy.biedronka.pl/dw/image/v2/BKFJ_PRD/on/demandware.static/-/Sites-PL_Master_Catalog/default/dw1/images/hi-res/KEFIR.jpg?sw=270&amp;sh=270 1x"/>
                  <img class="lazy" data-srcset="https://zakupy.biedronka.pl/dw/image/v2/BKFJ_PRD/on/demandware.static/-/Sites-PL_Master_Catalog/default/dw1/images/hi-res/KEFIR.jpg?sw=270&amp;sh=270"/>
                </picture>
                <picture class="tile-image__stub">
                  <img src="https://zakupy.biedronka.pl/dw/image/v2/BKFJ_PRD/on/demandware.static/-/Library/default/dw2/images/utils/plp_stub.png?sw=270"/>
                </picture>
              </div>
              <div class="product-tile__button">
                <form class="pdpForm"
                  data-product-gtm='{"item_name":"Krasnystaw Kefir 420 g","item_id":"0000004146","price":"2.29","item_brand":"Krasnystaw","quantity":1,"item_category":"Nabiał","item_category2":"Kefiry"}'>
                </form>
              </div>
            </div>
          </li>
          <li class="product-grid__item">
            <div class="product-tile js-product-tile">
              <a href="https://zakupy.biedronka.pl/piatnica-serek-0000003518.html" class="product-tile-clickable"></a>
              <div class="product-tile__button">
                <form class="pdpForm"
                  data-product-gtm='{"item_name":"Piątnica Serek wiejski naturalny 200 g","item_id":"0000003518","price":"3.15","item_brand":"Piątnica","quantity":1,"item_category":"Nabiał","item_category2":"Serki wiejskie"}'>
                </form>
              </div>
            </div>
          </li>
          <li><form data-product-gtm='not a json'></form></li>
        </ul>
        </body></html>
        """;

    @Test
    void parsesTilesFromCategoryHtml() {
        List<Tile> tiles = client.parseTiles(CATEGORY_HTML);

        assertThat(tiles).hasSize(2);
        Tile kefir = tiles.get(0);
        assertThat(kefir.pid()).isEqualTo("0000004146");
        assertThat(kefir.name()).isEqualTo("Krasnystaw Kefir 420 g");
        assertThat(kefir.brand()).isEqualTo("Krasnystaw");
        assertThat(kefir.priceZl()).isEqualTo(2.29);
        assertThat(kefir.category()).isEqualTo("Nabiał");
        assertThat(kefir.category2()).isEqualTo("Kefiry");
        assertThat(kefir.productUrl()).contains("0000004146.html");
    }

    @Test
    void picksRealImageAndSkipsStub() {
        Tile kefir = client.parseTiles(CATEGORY_HTML).get(0);
        assertThat(kefir.imageUrl()).contains("hi-res/KEFIR.jpg");
        assertThat(kefir.imageUrl()).doesNotContain("plp_stub").doesNotContain(" ");
        // друга плитка без картинки — null, не заглушка
        assertThat(client.parseTiles(CATEGORY_HTML).get(1).imageUrl()).isNull();
    }

    @Test
    void skipsBrokenTilesAndDeduplicates() {
        assertThat(client.parseTiles(CATEGORY_HTML + CATEGORY_HTML)).hasSize(2);
    }

    @Test
    void readsHasNextFlagFromGrid() {
        assertThat(client.hasNextPage(CATEGORY_HTML)).isTrue();
        assertThat(client.hasNextPage(CATEGORY_HTML.replace("data-has-next=\"true\"", "data-has-next=\"false\""))).isFalse();
        assertThat(client.hasNextPage("<html><body>пусто</body></html>")).isFalse();
    }
}
