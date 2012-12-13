package org.brylex.xmlgen;

import javax.xml.stream.Location;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.util.*;

class RecordingXMLEventReader implements XMLEventReader {

    private final XMLEventReader parent;

    private final Stack<StackEvent> record = new Stack<StackEvent>();
    private final Set<Location> locations = new HashSet<Location>();

    private int count = 1;

    RecordingXMLEventReader(final XMLEventReader parent, final StackEvent stackEvent) {
        this.parent = parent;
        record.push(stackEvent);

        locations.add(stackEvent.getEvent().getLocation());
    }

    public XMLEvent nextEvent() throws XMLStreamException {

        XMLEvent event = parent.nextEvent();
        if (!locations.contains(event.getLocation())) {

            locations.add(event.getLocation());

            if (event.isStartElement()) {
                count++;
            }
            if (event.isEndElement()) {
                count--;
            }

            record.push(new StackEvent(event));
        }

        return event;
    }

    public boolean hasNext() {
        return parent.hasNext();
    }

    public XMLEvent peek() throws XMLStreamException {
        return parent.peek();
    }

    public String getElementText() throws XMLStreamException {
        return parent.getElementText();
    }

    public XMLEvent nextTag() throws XMLStreamException {
        return parent.nextTag();
    }

    public Object getProperty(String s) throws IllegalArgumentException {
        return parent.getProperty(s);
    }

    public void close() throws XMLStreamException {
        parent.close();
    }

    public Object next() {
        return parent.next();
    }

    public void remove() {
        parent.remove();
    }

    public void replay(Stack<StackEvent> targetStack) {

        ListIterator<StackEvent> it = new LinkedList<StackEvent>(record).listIterator(record.size());

        while (it.hasPrevious()) {
            StackEvent event = it.previous();
            targetStack.push(event);
            if (!it.hasPrevious()) {
                event.decrementRepeat();
            }
        }
    }

    public boolean isDone() {
        return count == 0;
    }

}
