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
import java.util.concurrent.atomic.AtomicBoolean;

class _XMLEvent implements StartElement {

    private final StartElement delegate;

    private final Map<QName, Attribute> attributes = new HashMap<QName, Attribute>();
    private final AtomicBoolean template = new AtomicBoolean(false);
    public static final QName REPEAT = new QName("urn:xml:gen", "repeat");
    public static final QName INCREMENT = new QName("urn:xml:gen", "increment");
    private int increment;

    public _XMLEvent(final StartElement delegate) {
        this.delegate = delegate;

        for (Iterator<Attribute> it = delegate.getAttributes(); it.hasNext(); ) {
            Attribute attribute = it.next();
            QName qName = attribute.getName();

            if ("urn:xml:gen".equals(qName.getNamespaceURI())) {

                if (REPEAT.equals(qName) && Integer.parseInt(attribute.getValue()) > 1) {
                    attributes.put(qName, attribute);
                    this.template.set(true);
                }

                if (INCREMENT.equals(qName)) {
                    attributes.put(qName, attribute);
                    this.increment = Integer.parseInt(attribute.getValue());
                }

            } else {
                attributes.put(qName, attribute);
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

            if (REPEAT.equals(removed.getName())) {

                int repeat = Integer.parseInt(removed.getValue());
                if (repeat > 2) {
                    attributes.put(removed.getName(), new _Attribute(removed, "" + --repeat));
                }
            }

            boolean isTemplate = false;
            for (QName qName : attributes.keySet()) {
                if ("urn:xml:gen".equals(qName.getNamespaceURI())) {
                    isTemplate = true;
                }
            }
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
}
