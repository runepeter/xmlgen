package org.brylex.xmlgen;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

class _XMLEvent implements StartElement {

    private final StartElement delegate;

    private final Map<QName, Attribute> attributes = new HashMap<QName, Attribute>();
    private final AtomicBoolean template = new AtomicBoolean(false);
    public static final QName REPEAT = new QName("urn:xml:gen", "repeat");
    public static final QName INCREMENT = new QName("urn:xml:gen", "increment");
    private int increment;
    private final Random random;

    public _XMLEvent(final StartElement delegate, final Random random) {
        this.random = random;

        for (Iterator<Attribute> it = delegate.getAttributes(); it.hasNext(); ) {
            Attribute attribute = it.next();
            QName qName = attribute.getName();

            if ("urn:xml:gen".equals(qName.getNamespaceURI())) {

                if (REPEAT.equals(qName)) {
                    int resolved = resolveRepeat(attribute.getValue());
                    if (resolved > 1) {
                        this.template.set(true);
                    }
                    attributes.put(qName, new _Attribute(attribute, Integer.toString(resolved)));
                } else {
                    attributes.put(qName, attribute);
                }

                if (INCREMENT.equals(qName)) {
                    this.increment = Integer.parseInt(attribute.getValue());
                }

            } else {
                attributes.put(qName, attribute);
            }
        }

        // Unwrap nested _XMLEvent chains so subsequent method calls
        // (getName, getEventType, etc.) are O(1) instead of O(N) where
        // N is the chain depth. Without this, each gen:repeat replay
        // adds two wrapping layers, turning template fill-in into O(N^2)
        // work over the document.
        StartElement raw = delegate;
        while (raw instanceof _XMLEvent inner) {
            raw = inner.delegate;
        }
        this.delegate = raw;
    }

    private int resolveRepeat(String value) {
        int sep = value.indexOf("..");
        if (sep < 0) {
            return Integer.parseInt(value);
        }
        if (sep == 0 || sep >= value.length() - 2) {
            throw new IllegalArgumentException(
                    "gen:repeat value must be 'min..max', got '" + value + "'");
        }
        int min, max;
        try {
            min = Integer.parseInt(value.substring(0, sep).trim());
            max = Integer.parseInt(value.substring(sep + 2).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "gen:repeat value must be 'min..max', got '" + value + "'", e);
        }
        if (min > max) {
            throw new IllegalArgumentException(
                    "gen:repeat min must be <= max, got '" + value + "'");
        }
        return min == max ? min : min + random.nextInt(max - min + 1);
    }

    public QName getName() {
        return delegate.getName();
    }

    public Iterator getAttributes() {
        return attributes.values().iterator();
    }

    public Iterator getNamespaces() {
        return delegate.getNamespaces();
    }

    public Attribute getAttributeByName(QName qname) {
        return attributes.get(qname);
    }

    public NamespaceContext getNamespaceContext() {
        return delegate.getNamespaceContext();
    }

    public String getNamespaceURI(String s) {
        return delegate.getNamespaceURI(s);
    }

    public int getEventType() {
        return delegate.getEventType();
    }

    public Location getLocation() {
        return delegate.getLocation();
    }

    public boolean isStartElement() {
        return delegate.isStartElement();
    }

    public boolean isAttribute() {
        return delegate.isAttribute();
    }

    public boolean isNamespace() {
        return delegate.isNamespace();
    }

    public boolean isEndElement() {
        return delegate.isEndElement();
    }

    public boolean isEntityReference() {
        return delegate.isEntityReference();
    }

    public boolean isProcessingInstruction() {
        return delegate.isProcessingInstruction();
    }

    public boolean isCharacters() {
        return delegate.isCharacters();
    }

    public boolean isStartDocument() {
        return delegate.isStartDocument();
    }

    public boolean isEndDocument() {
        return delegate.isEndDocument();
    }

    public StartElement asStartElement() {
        return this;
    }

    public EndElement asEndElement() {
        return delegate.asEndElement();
    }

    public Characters asCharacters() {
        return delegate.asCharacters();
    }

    public QName getSchemaType() {
        return delegate.getSchemaType();
    }

    public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {
        delegate.writeAsEncodedUnicode(writer);
    }

    public int decrementRepeat() {

        Attribute removed = attributes.remove(REPEAT);
        if (removed != null) {

            int repeat = Integer.parseInt(removed.getValue());
            int next = repeat - 1;
            if (next >= 1) {
                attributes.put(removed.getName(), new _Attribute(removed, Integer.toString(next)));
            }

            Attribute remaining = attributes.get(REPEAT);
            boolean isTemplate = remaining != null
                    && Integer.parseInt(remaining.getValue()) > 1;
            this.template.set(isTemplate);
        }

        return 1;
    }

    public boolean isTemplate() {
        return template.get();
    }

    public boolean isTextProcessor() {

        if (delegate.isStartElement()) {
            return attributes.containsKey(INCREMENT);
        }

        return false;
    }

    public int getIncrement() {
        return increment;
    }

    @Override
    public String toString() {

        if (delegate.isStartElement()) {
            return "<" + delegate.asStartElement().getName() + ">";
        }

        return super.toString();
    }
}
