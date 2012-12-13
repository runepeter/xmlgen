package org.brylex.xmlgen;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.*;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

class GeneratorStrippingStartEvent implements StartElement {

    private final StartElement delegate;

    private final Map<QName, Attribute> attributes = new HashMap<QName, Attribute>();
    private final Map<String, Namespace> namespaces = new HashMap<String, Namespace>();

    private final AtomicBoolean template = new AtomicBoolean(false);
    public static final QName REPEAT = new QName("urn:xml:gen", "repeat");

    public GeneratorStrippingStartEvent(final StartElement delegate) {
        this.delegate = delegate;

        for (Iterator<Attribute> it = delegate.getAttributes(); it.hasNext(); ) {
            Attribute attribute = it.next();
            QName qName = attribute.getName();

            if (!"urn:xml:gen".equals(qName.getNamespaceURI())) {
                attributes.put(qName, attribute);
            }
        }

        for (Iterator<Namespace> it = delegate.getNamespaces(); it.hasNext(); ) {
            Namespace namespace = it.next();
            String uri = namespace.getNamespaceURI();

            if (!"urn:xml:gen".equals(uri)) {
                namespaces.put(namespace.getPrefix(), namespace);
            }
        }
    }

    public QName getName() {
        return delegate.getName();
    }

    public Iterator getAttributes() {
        return attributes.values().iterator();
    }

    public Iterator getNamespaces() {
        return namespaces.values().iterator();
    }

    public Attribute getAttributeByName(QName qname) {
        return attributes.get(qname);
    }

    public NamespaceContext getNamespaceContext() {
        return delegate.getNamespaceContext();
    }

    public String getNamespaceURI(String prefix) {
        return namespaces.get(prefix).getNamespaceURI();
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

}
