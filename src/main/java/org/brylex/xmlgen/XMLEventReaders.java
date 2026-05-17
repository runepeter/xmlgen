package org.brylex.xmlgen;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

final class XMLEventReaders {

    private XMLEventReaders() {
    }

    static XMLEvent nextTag(XMLEventReader reader) throws XMLStreamException {
        XMLEvent event = reader.nextEvent();
        while ((event.isCharacters() && event.asCharacters().isWhiteSpace())
                || event.getEventType() == XMLStreamConstants.COMMENT
                || event.getEventType() == XMLStreamConstants.PROCESSING_INSTRUCTION) {
            event = reader.nextEvent();
        }
        if (!event.isStartElement() && !event.isEndElement()) {
            throw new XMLStreamException(
                    "Expected start or end tag, got event type " + event.getEventType(),
                    event.getLocation());
        }
        return event;
    }

    static String getElementText(XMLEventReader reader) throws XMLStreamException {
        StringBuilder buf = new StringBuilder();
        XMLEvent event = reader.nextEvent();
        while (!event.isEndElement()) {
            if (event.isCharacters()) {
                buf.append(event.asCharacters().getData());
            } else if (event.getEventType() != XMLStreamConstants.COMMENT
                    && event.getEventType() != XMLStreamConstants.PROCESSING_INSTRUCTION) {
                throw new XMLStreamException(
                        "Unexpected event type " + event.getEventType()
                                + " while reading element text",
                        event.getLocation());
            }
            event = reader.nextEvent();
        }
        return buf.toString();
    }
}
