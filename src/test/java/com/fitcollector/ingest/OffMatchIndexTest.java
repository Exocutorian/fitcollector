package com.fitcollector.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitcollector.ingest.OffMatchIndex.Entry;
import com.fitcollector.ingest.OpenFoodFactsClient.Macros;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class OffMatchIndexTest {

    @TempDir
    Path tempDir;

    private OffMatchIndex index;

    @BeforeEach
    void setUp() {
        index = new OffMatchIndex(new ObjectMapper(), "https://unused.example", "test",
                tempDir.toString(), 7, 1, 1);
        index.loadEntries(List.of(
                Entry.of("5900531000041", "Kefir", "Krasnystaw", 420,
                        "https://img/kefir.jpg", new Macros(51, 3.4, 2.0, 4.7)),
                Entry.of("5900531007019", "Serek wiejski naturalny", "Piątnica", 200,
                        "https://img/serek.jpg", new Macros(97, 11, 4, 2.5)),
                Entry.of("5901234567890", "Jogurt naturalny", "Zott", 370,
                        null, new Macros(61, 4.4, 2, 6.2))));
    }

    @Test
    void matchesByBrandQuantityAndNameTokens() {
        Optional<Entry> match = index.match("Krasnystaw Kefir 420 g", "Krasnystaw", 420);
        assertThat(match).isPresent();
        assertThat(match.get().code()).isEqualTo("5900531000041");
    }

    @Test
    void matchesDespiteDiacritics() {
        Optional<Entry> match = index.match("Piatnica Serek wiejski naturalny 200 g", "Piatnica", 200);
        assertThat(match).isPresent();
        assertThat(match.get().code()).isEqualTo("5900531007019");
    }

    @Test
    void noMatchForDifferentQuantity() {
        assertThat(index.match("Krasnystaw Kefir 1 l", "Krasnystaw", 1000)).isEmpty();
    }

    @Test
    void noMatchForUnrelatedName() {
        assertThat(index.match("Chleb żytni 420 g", "Złotoklos", 420)).isEmpty();
    }

    @Test
    void tokensStripDiacriticsAndStopWords() {
        assertThat(OffMatchIndex.tokens("Płatki owsiane górskie 500 g"))
                .containsExactlyInAnyOrder("platki", "owsiane", "gorskie");
    }
}
