package org.brylex.xmlgen;

import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;
import java.util.Iterator;

class StackEvent {

    private final XMLEvent event;
    private int increment;

    StackEvent(final XMLEvent event) {
        if (event.isStartElement()) {
            this.event = new _XMLEvent(event.asStartElement());
        } else {
            this.event = event;
        }
    }

    public XMLEvent getEvent() {
        return event;
    }

    public boolean isTemplate() {

        if (event instanceof _XMLEvent) {
            return ((_XMLEvent) event).isTemplate();
        }

        return false;
    }

    public int decrementRepeat() {

        if (event instanceof _XMLEvent) {
            return ((_XMLEvent) event).decrementRepeat();
        }

        return -1;
    }

    @Override
    public String toString() {

        if (event.isStartDocument()) {
            return "START_DOCUMENT";
        }

        if (event.isEndDocument()) {
            return "END_DOCUMENT";
        }

        if (event.isStartElement()) {

            String attributes = "";
            for (Iterator<Attribute> it = event.asStartElement().getAttributes(); it.hasNext(); ) {
                Attribute attribute = it.next();
                attributes += " " + attribute.getName() + "='" + attribute.getValue() + "'";
            }

            return "<" + event.asStartElement().getName() + attributes + ">";
        }

        if (event.isEndElement()) {
            return "</" + event.asEndElement().getName() + ">";
        }

        return "[" + event + "]";
    }

    public boolean isTextProcessor() {

        if (event instanceof _XMLEvent) {
            return ((_XMLEvent) event).isTextProcessor();
        }

        return false;
    }

    public int getIncrement() {
        return ((_XMLEvent) event).getIncrement();
    }
}
