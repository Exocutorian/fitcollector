package com.fitcollector.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuantityParserTest {

    @Test
    void parsesGrams() {
        assertThat(QuantityParser.parseGrams("Krasnystaw Kefir 420 g")).hasValue(420);
        assertThat(QuantityParser.parseGrams("Serek wiejski 200g")).hasValue(200);
    }

    @Test
    void parsesKilogramsAndLiters() {
        assertThat(QuantityParser.parseGrams("Filet z kurczaka 1 kg")).hasValue(1000);
        assertThat(QuantityParser.parseGrams("Mleko UHT 1,5 l")).hasValue(1500);
        assertThat(QuantityParser.parseGrams("Napój owsiany 500 ml")).hasValue(500);
    }

    @Test
    void parsesMultipacks() {
        assertThat(QuantityParser.parseGrams("Kasza gryczana 4x100 g")).hasValue(400);
        assertThat(QuantityParser.parseGrams("Ryż 4×100 g")).hasValue(400);
    }

    @Test
    void parsesEggsByPieces() {
        assertThat(QuantityParser.parseGrams("Jaja z chowu na wolnym wybiegu 10 szt")).hasValue(600);
        // штуки без яєць — не вгадуємо вагу
        assertThat(QuantityParser.parseGrams("Bułka 5 szt")).isEmpty();
    }

    @Test
    void emptyWhenNoQuantity() {
        assertThat(QuantityParser.parseGrams("Banany")).isEmpty();
        assertThat(QuantityParser.parseGrams(null)).isEmpty();
    }
}
