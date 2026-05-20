package org.brylex.xmlgen.infer;

import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class DeterminismTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void shuffledInputProducesIdenticalTemplate() throws Exception {
        List<String> resources = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            resources.add("infer/tier-a/typical-invoice/sample-" + i + ".xml");
        }

        InferredTemplate forward = TemplateInferrer.infer(load(resources));
        List<String> shuffled = new ArrayList<>(resources);
        Collections.shuffle(shuffled, new Random(123));
        InferredTemplate backward = TemplateInferrer.infer(load(shuffled));

        assertThat(forward.templateXml()).isEqualTo(backward.templateXml());
    }

    private List<XMLEventReader> load(List<String> names) throws Exception {
        List<XMLEventReader> readers = new ArrayList<>();
        for (String n : names) {
            InputStream is = getClass().getClassLoader().getResourceAsStream(n);
            readers.add(FACTORY.createXMLEventReader(is));
        }
        return readers;
    }
}
