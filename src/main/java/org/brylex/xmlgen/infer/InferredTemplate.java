package org.brylex.xmlgen.infer;

import org.brylex.xmlgen.GeneratingXMLEventReader;
import org.brylex.xmlgen.Pools;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import java.io.StringReader;
import java.util.List;
import java.util.Random;

public record InferredTemplate(
        String templateXml,
        Pools pools,
        List<InferenceWarning> warnings
) {
    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();

    public InferredTemplate {
        warnings = List.copyOf(warnings);
    }

    public XMLEventReader expand(Random random) {
        try {
            XMLEventReader source = FACTORY.createXMLEventReader(new StringReader(templateXml));
            return new GeneratingXMLEventReader(source, pools, random);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("inferred template failed to parse: " + e.getMessage(), e);
        }
    }

    public XMLEventReader expand() {
        return expand(new Random());
    }
}
