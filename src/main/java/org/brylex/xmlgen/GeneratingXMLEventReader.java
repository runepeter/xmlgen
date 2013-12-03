package org.brylex.xmlgen;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;

public class GeneratingXMLEventReader implements XMLEventReader {

    private final StackXMLEventReader stackReader;
    private final DelegatingXMLEventReader delegate;

    public GeneratingXMLEventReader(final XMLEventReader delegate) {
        this.stackReader = new StackXMLEventReader(delegate);
        this.delegate = new DelegatingXMLEventReader(new TextProcessingXMLEventReader(stackReader));
    }

    public XMLEvent nextEvent() throws XMLStreamException {

        StackEvent event = new StackEvent(delegate.nextEvent());

        if (event.isTemplate()) {

            RecordingXMLEventReader recordingReader = new RecordingXMLEventReader(delegate.current(), event);
            delegate.newRecorder(recordingReader);

            return returnableEvent(event);
        }

        if (delegate.isRecording() && delegate.peekRecorder().isDone()) {
            delegate.popRecorder().replay(stackReader.getStack());
        }

        return returnableEvent(event);
    }

    private XMLEvent returnableEvent(StackEvent event) {
        if (event.getEvent().isStartElement()) {
            return new GeneratorStrippingStartEvent(event.getEvent().asStartElement());
        } else {
            return event.getEvent();
        }
    }

    public boolean hasNext() {
        return delegate.hasNext();
    }

    public Object next() {
        throw new UnsupportedOperationException();
    }

    public void remove() {
        throw new UnsupportedOperationException();
    }

    public XMLEvent peek() throws XMLStreamException {
        return delegate.peek();
    }

    public String getElementText() throws XMLStreamException {
        return delegate.getElementText();
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
}
