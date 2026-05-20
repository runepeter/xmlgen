package org.brylex.xmlgen.infer;

import org.dom4j.Document;
import org.dom4j.Node;
import org.dom4j.io.STAXEventReader;
import org.junit.jupiter.api.Test;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateInferrerAcceptanceTest {

    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    @Test
    void typicalInvoicePropertyAssertions() throws Exception {
        List<XMLEventReader> samples = loadSamples("infer/tier-a/typical-invoice", 5);
        InferredTemplate t = TemplateInferrer.infer(samples);

        // (a) XPath union coverage
        Set<String> sampleXPaths = unionXPaths("infer/tier-a/typical-invoice", 5);
        Set<String> templateXPaths = extractXPaths(t.templateXml());
        assertThat(templateXPaths).containsAll(sampleXPaths);

        // (b) Directive-specific position assertions
        assertThat(t.templateXml()).containsPattern("<line[^>]*gen:repeat");
        assertThat(t.templateXml()).containsPattern("<qty[^>]*gen:random-int");
        assertThat(t.templateXml()).containsPattern("<price[^>]*gen:random-amount");
        assertThat(t.templateXml()).containsPattern("<issued[^>]*gen:random-date");

        // (c) Value-range bound preservation across 50 expansions
        Set<Long> observedQtys = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            Document expanded = expand(t, 42 + i);
            for (Node n : (List<Node>) expanded.selectNodes("//qty")) {
                observedQtys.add(Long.parseLong(n.getText()));
            }
        }
        // All observed qty values must be within [1, 15] (the sample-observed range)
        assertThat(observedQtys).allMatch(q -> q >= 1 && q <= 15);
    }

    @Test
    void homogeneousFixturePropertyAssertions() throws Exception {
        List<XMLEventReader> samples = loadSamples("infer/tier-a/homogeneous", 5);
        InferredTemplate t = TemplateInferrer.infer(samples);

        // Property: template parses and expands without error
        for (int i = 0; i < 10; i++) {
            Document expanded = expand(t, 100 + i);
            // Every emitted <kind> must be one of the observed values
            for (Node n : (List<Node>) expanded.selectNodes("//kind")) {
                assertThat(n.getText()).isIn("click", "view", "purchase");
            }
        }
    }

    static Document expand(InferredTemplate t, long seed) throws Exception {
        return new STAXEventReader().readDocument(t.expand(new Random(seed)));
    }

    static List<XMLEventReader> loadSamples(String dir, int count) throws Exception {
        List<XMLEventReader> rs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            InputStream is = TemplateInferrerAcceptanceTest.class.getClassLoader()
                    .getResourceAsStream(dir + "/sample-" + i + ".xml");
            rs.add(FACTORY.createXMLEventReader(is));
        }
        return rs;
    }

    static Set<String> unionXPaths(String dir, int count) throws Exception {
        Set<String> all = new LinkedHashSet<>();
        for (int i = 1; i <= count; i++) {
            InputStream is = TemplateInferrerAcceptanceTest.class.getClassLoader()
                    .getResourceAsStream(dir + "/sample-" + i + ".xml");
            Document doc = new STAXEventReader().readDocument(FACTORY.createXMLEventReader(is));
            collectXPaths(doc.getRootElement(), "/" + doc.getRootElement().getName(), all);
        }
        return all;
    }

    static void collectXPaths(org.dom4j.Element el, String path, Set<String> out) {
        out.add(path);
        for (Object c : el.elements()) {
            org.dom4j.Element child = (org.dom4j.Element) c;
            collectXPaths(child, path + "/" + child.getName(), out);
        }
    }

    static Set<String> extractXPaths(String xml) throws Exception {
        Document doc = new STAXEventReader().readDocument(
                FACTORY.createXMLEventReader(new StringReader(xml)));
        Set<String> out = new LinkedHashSet<>();
        collectXPaths(doc.getRootElement(), "/" + doc.getRootElement().getName(), out);
        return out;
    }
}
