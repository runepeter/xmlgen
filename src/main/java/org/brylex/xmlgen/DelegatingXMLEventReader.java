package org.brylex.xmlgen;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import java.util.Stack;

class DelegatingXMLEventReader implements XMLEventReader {

    private final Stack<XMLEventReader> delegate = new Stack<XMLEventReader>();

    DelegatingXMLEventReader(XMLEventReader delegate) {
        this.delegate.push(delegate);
    }

    public XMLEvent nextEvent() throws XMLStreamException {
        return delegate.peek().nextEvent();
    }

    public boolean hasNext() {
        return delegate.peek().hasNext();
    }

    public XMLEvent peek() throws XMLStreamException {
        return delegate.peek().peek();
    }

    public String getElementText() throws XMLStreamException {
        return delegate.peek().getElementText();
    }

    public XMLEvent nextTag() throws XMLStreamException {
        return delegate.peek().nextTag();
    }

    public Object getProperty(String s) throws IllegalArgumentException {
        return delegate.peek().getProperty(s);
    }

    public void close() throws XMLStreamException {
        delegate.peek().close();
    }

    public Object next() {
        return delegate.peek().next();
    }

    public void remove() {
        delegate.peek().remove();
    }

    public XMLEventReader current() {
        return delegate.peek();
    }

    public void newRecorder(final XMLEventReader current) {
        this.delegate.push(current);
    }

    public RecordingXMLEventReader peekRecorder() {
        return (RecordingXMLEventReader) this.delegate.peek();
    }

    public RecordingXMLEventReader popRecorder() {
        return (RecordingXMLEventReader) this.delegate.pop();
    }

    public boolean isRecording() {
        return delegate.size() > 1;
    }
}
