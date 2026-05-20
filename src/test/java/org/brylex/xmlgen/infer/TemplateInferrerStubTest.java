package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateInferrerStubTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void emptySamplesFailLoud() {
        assertThatThrownBy(() -> TemplateInferrer.infer(List.of()))
                .isInstanceOf(InferenceException.class)
                .hasMessageContaining("at least one sample");
    }

    @Test
    void singleSampleReturnsInferredTemplate() throws Exception {
        XMLEventReader sample = FACTORY.createXMLEventReader(
                new StringReader("<order><line><sku>A</sku></line></order>"));
        InferredTemplate t = TemplateInferrer.infer(List.of(sample));
        assertThat(t.templateXml()).isNotBlank();
        assertThat(t.warnings()).isNotNull();
    }
}
