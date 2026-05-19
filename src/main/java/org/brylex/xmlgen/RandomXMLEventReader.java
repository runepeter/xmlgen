package org.brylex.xmlgen;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Random;

class RandomXMLEventReader implements XMLEventReader {

    static final QName RANDOM_INT = new QName("urn:xml:gen", "random-int");
    static final QName RANDOM_AMOUNT = new QName("urn:xml:gen", "random-amount");
    static final QName RANDOM_DATE = new QName("urn:xml:gen", "random-date");

    private final XMLEventReader delegate;
    private final Random random;
    private Generator pending;

    RandomXMLEventReader(XMLEventReader delegate, Random random) {
        this.delegate = delegate;
        this.random = random;
    }

    @Override
    public XMLEvent nextEvent() throws XMLStreamException {
        XMLEvent event = delegate.nextEvent();

        if (event.isCharacters() && pending != null) {
            String value = pending.generate(random);
            pending = null;
            return new OverriddenCharactersXMLEvent(event.asCharacters(), value);
        }

        if (event.isStartElement()) {
            pending = parseGenerator(event.asStartElement());
        }

        return event;
    }

    private static Generator parseGenerator(StartElement se) {
        Attribute attr = se.getAttributeByName(RANDOM_INT);
        if (attr != null) {
            return parseIntRange(attr.getValue());
        }
        attr = se.getAttributeByName(RANDOM_AMOUNT);
        if (attr != null) {
            return parseAmountRange(attr.getValue());
        }
        attr = se.getAttributeByName(RANDOM_DATE);
        if (attr != null) {
            return parseDateRange(attr.getValue());
        }
        return null;
    }

    private static Generator parseIntRange(String spec) {
        String[] parts = parseBounds(spec, "gen:random-int");
        long min = Long.parseLong(parts[0]);
        long max = Long.parseLong(parts[1]);
        requireOrdered(min <= max, "gen:random-int", spec);
        return rng -> Long.toString(min + (long) (rng.nextDouble() * (max - min + 1)));
    }

    private static Generator parseAmountRange(String spec) {
        String[] parts = parseBounds(spec, "gen:random-amount");
        BigDecimal min = new BigDecimal(parts[0]);
        BigDecimal max = new BigDecimal(parts[1]);
        requireOrdered(min.compareTo(max) <= 0, "gen:random-amount", spec);
        int scale = Math.max(min.scale(), max.scale());
        BigDecimal range = max.subtract(min);
        return rng -> min.add(range.multiply(BigDecimal.valueOf(rng.nextDouble())))
                .setScale(scale, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static Generator parseDateRange(String spec) {
        String[] parts = parseBounds(spec, "gen:random-date");
        LocalDate start = LocalDate.parse(parts[0]);
        LocalDate end = LocalDate.parse(parts[1]);
        requireOrdered(!start.isAfter(end), "gen:random-date", spec);
        long days = ChronoUnit.DAYS.between(start, end);
        return rng -> start.plusDays(rng.nextLong(days + 1)).toString();
    }

    private static String[] parseBounds(String spec, String directive) {
        int sep = spec.indexOf("..");
        if (sep <= 0 || sep >= spec.length() - 2) {
            throw new IllegalArgumentException(
                    directive + " value must be 'min..max', got '" + spec + "'");
        }
        return new String[]{spec.substring(0, sep).trim(), spec.substring(sep + 2).trim()};
    }

    private static void requireOrdered(boolean ok, String directive, String spec) {
        if (!ok) {
            throw new IllegalArgumentException(
                    directive + " min must be <= max, got '" + spec + "'");
        }
    }

    @FunctionalInterface
    private interface Generator {
        String generate(Random random);
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public XMLEvent peek() throws XMLStreamException {
        return delegate.peek();
    }

    @Override
    public String getElementText() throws XMLStreamException {
        return XMLEventReaders.getElementText(this);
    }

    @Override
    public XMLEvent nextTag() throws XMLStreamException {
        return XMLEventReaders.nextTag(this);
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return delegate.getProperty(name);
    }

    @Override
    public void close() throws XMLStreamException {
        delegate.close();
    }

    @Override
    public Object next() {
        try {
            return nextEvent();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
