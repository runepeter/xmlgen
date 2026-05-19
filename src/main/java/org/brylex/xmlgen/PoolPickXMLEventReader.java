package org.brylex.xmlgen;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.HashMap;
import java.util.Map;

class PoolPickXMLEventReader implements XMLEventReader {

    static final QName PICK = new QName("urn:xml:gen", "pick");
    static final QName REPEAT = new QName("urn:xml:gen", "repeat");

    private final XMLEventReader delegate;
    private final Pools pools;

    private final Map<String, Integer> pinnedRow = new HashMap<>();
    private int depth;
    private int scopeDepth = -1;

    private String pendingPool;
    private String pendingColumn;

    PoolPickXMLEventReader(XMLEventReader delegate, Pools pools) {
        this.delegate = delegate;
        this.pools = pools;
    }

    @Override
    public XMLEvent nextEvent() throws XMLStreamException {
        XMLEvent event = delegate.nextEvent();

        if (event.isCharacters() && pendingPool != null) {
            Pool pool = pools.get(pendingPool);
            int row = pinnedRow.computeIfAbsent(pendingPool, k -> pool.nextRow());
            String value = pool.value(row, pendingColumn);
            pendingPool = null;
            pendingColumn = null;
            return new OverriddenCharactersXMLEvent(event.asCharacters(), value);
        }

        if (event.isStartElement()) {
            StartElement se = event.asStartElement();
            if (se.getAttributeByName(REPEAT) != null) {
                pinnedRow.clear();
                scopeDepth = depth + 1;
            }
            Attribute pick = se.getAttributeByName(PICK);
            if (pick != null) {
                String spec = pick.getValue();
                int slash = spec.indexOf('/');
                if (slash <= 0 || slash >= spec.length() - 1) {
                    throw new XMLStreamException(
                            "gen:pick value must be 'pool/column', got '" + spec + "'",
                            se.getLocation());
                }
                pendingPool = spec.substring(0, slash);
                pendingColumn = spec.substring(slash + 1);
            }
            depth++;
        } else if (event.isEndElement()) {
            depth--;
            if (scopeDepth >= 0 && depth < scopeDepth) {
                pinnedRow.clear();
                scopeDepth = -1;
            }
        }

        return event;
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
