package org.brylex.xmlgen;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.util.Stack;

class StackXMLEventReader implements XMLEventReader {

    private final XMLEventReader delegate;
    private final Stack<StackEvent> stack = new Stack<StackEvent>();

    StackXMLEventReader(final XMLEventReader delegate) {
        this.delegate = delegate;
    }

    public XMLEvent nextEvent() throws XMLStreamException {

        if (stack.isEmpty()) {
            return delegate.nextEvent();
        }

        return stack.pop().getEvent();
    }

    public boolean hasNext() {

        if (stack.isEmpty()) {
            return delegate.hasNext();
        }

        return !stack.isEmpty();
    }

    public Object next() {
        try {
            return nextEvent();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    public XMLEvent peek() throws XMLStreamException {

        if (stack.isEmpty()) {
            return delegate.peek();
        }

        return stack.peek().getEvent();
    }

    public String getElementText() throws XMLStreamException {
        return XMLEventReaders.getElementText(this);
    }

    public XMLEvent nextTag() throws XMLStreamException {
        return XMLEventReaders.nextTag(this);
    }

    public Object getProperty(String s) throws IllegalArgumentException {
        return delegate.getProperty(s);
    }

    public void close() throws XMLStreamException {
        delegate.close();
    }

    public Stack<StackEvent> getStack() {
        return stack;
    }
}
