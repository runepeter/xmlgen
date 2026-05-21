package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.Pools;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class InferredTemplateTest {

    private static final String SIMPLE = """
            <order xmlns:gen="urn:xml:gen"><line><sku>A</sku></line></order>
            """;

    @Test
    void recordHoldsValues() {
        InferredTemplate t = new InferredTemplate(SIMPLE, Pools.empty(), List.of());
        assertThat(t.templateXml()).isEqualTo(SIMPLE);
        assertThat(t.pools().isEmpty()).isTrue();
        assertThat(t.warnings()).isEmpty();
    }

    @Test
    void expandReturnsWorkingReader() throws Exception {
        InferredTemplate t = new InferredTemplate(SIMPLE, Pools.empty(), List.of());
        XMLEventReader reader = t.expand(new Random(0));
        int events = 0;
        while (reader.hasNext()) { reader.next(); events++; }
        assertThat(events).isGreaterThan(5);
    }
}
