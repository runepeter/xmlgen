package org.brylex.xmlgen;

import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import java.io.Writer;

class OverriddenCharactersXMLEvent implements Characters {

    private final Characters characters;
    private final String text;

    OverriddenCharactersXMLEvent(final Characters characters, final String text) {
        this.characters = characters;
        this.text = text;
    }

    @Override
    public String getData() {
        return text;
    }

    @Override
    public boolean isWhiteSpace() {
        return characters.isWhiteSpace();
    }

    @Override
    public boolean isCData() {
        return characters.isCData();
    }

    @Override
    public boolean isIgnorableWhiteSpace() {
        return characters.isIgnorableWhiteSpace();
    }

    @Override
    public int getEventType() {
        return characters.getEventType();
    }

    @Override
    public Location getLocation() {
        return characters.getLocation();
    }

    @Override
    public boolean isStartElement() {
        return characters.isStartElement();
    }

    @Override
    public boolean isAttribute() {
        return characters.isAttribute();
    }

    @Override
    public boolean isNamespace() {
        return characters.isNamespace();
    }

    @Override
    public boolean isEndElement() {
        return characters.isEndElement();
    }

    @Override
    public boolean isEntityReference() {
        return characters.isEntityReference();
    }

    @Override
    public boolean isProcessingInstruction() {
        return characters.isProcessingInstruction();
    }

    @Override
    public boolean isCharacters() {
        return characters.isCharacters();
    }

    @Override
    public boolean isStartDocument() {
        return characters.isStartDocument();
    }

    @Override
    public boolean isEndDocument() {
        return characters.isEndDocument();
    }

    @Override
    public StartElement asStartElement() {
        return characters.asStartElement();
    }

    @Override
    public EndElement asEndElement() {
        return characters.asEndElement();
    }

    @Override
    public Characters asCharacters() {
        return this;
    }

    @Override
    public QName getSchemaType() {
        return characters.getSchemaType();
    }

    @Override
    public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {
        characters.writeAsEncodedUnicode(writer);
    }
}
