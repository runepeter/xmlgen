package org.brylex.xmlgen;

import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import java.io.Writer;

public class _Attribute implements Attribute {

    private final Attribute delegate;

    private String value;

    public _Attribute(final Attribute delegate, final String newValue) {
        this.delegate = delegate;
        this.value = newValue;
    }

    @Override
    public QName getName() {
        return delegate.getName();
    }

    @Override
    public String getValue() {
        return this.value;
    }

    @Override
    public String getDTDType() {
        return delegate.getDTDType();
    }

    @Override
    public boolean isSpecified() {
        return delegate.isSpecified();
    }

    @Override
    public int getEventType() {
        return delegate.getEventType();
    }

    @Override
    public Location getLocation() {
        return delegate.getLocation();
    }

    @Override
    public boolean isStartElement() {
        return delegate.isStartElement();
    }

    @Override
    public boolean isAttribute() {
        return delegate.isAttribute();
    }

    @Override
    public boolean isNamespace() {
        return delegate.isNamespace();
    }

    @Override
    public boolean isEndElement() {
        return delegate.isEndElement();
    }

    @Override
    public boolean isEntityReference() {
        return delegate.isEntityReference();
    }

    @Override
    public boolean isProcessingInstruction() {
        return delegate.isProcessingInstruction();
    }

    @Override
    public boolean isCharacters() {
        return delegate.isCharacters();
    }

    @Override
    public boolean isStartDocument() {
        return delegate.isStartDocument();
    }

    @Override
    public boolean isEndDocument() {
        return delegate.isEndDocument();
    }

    @Override
    public StartElement asStartElement() {
        return delegate.asStartElement();
    }

    @Override
    public EndElement asEndElement() {
        return delegate.asEndElement();
    }

    @Override
    public Characters asCharacters() {
        return delegate.asCharacters();
    }

    @Override
    public QName getSchemaType() {
        return delegate.getSchemaType();
    }

    @Override
    public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {
        delegate.writeAsEncodedUnicode(writer);
    }
}
