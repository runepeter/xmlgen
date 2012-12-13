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
        throw new UnsupportedOperationException();
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
        return "JALLA";
    }

    public XMLEvent nextTag() throws XMLStreamException {
        throw new UnsupportedOperationException();
    }

    public Object getProperty(String s) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    public void close() throws XMLStreamException {
        throw new UnsupportedOperationException();
    }

    public Stack<StackEvent> getStack() {
        return stack;
    }
}
