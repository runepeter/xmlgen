package org.brylex.xmlgen;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.XMLEvent;
import java.util.Stack;

class TextProcessingXMLEventReader implements XMLEventReader {

    public static interface TextProcessor {
        public Characters process(Characters original);
    }

    private class IncrementTextProcessor implements TextProcessor {

        private final int increment;

        private IncrementTextProcessor(final int increment) {
            this.increment = increment;
        }

        @Override
        public Characters process(Characters original) {

            int current = Integer.parseInt(original.getData());

            return new OverriddenCharactersXMLEvent(original, "" + (current + increment));
        }
    }

    private final XMLEventReader delegate;

    private final Stack<TextProcessor> processorStack = new Stack<TextProcessor>();

    TextProcessingXMLEventReader(final XMLEventReader delegate) {
        this.delegate = delegate;
    }

    @Override
    public XMLEvent nextEvent() throws XMLStreamException {

        XMLEvent raw = delegate.nextEvent();
        if (raw.isCharacters() && !processorStack.isEmpty()) {

            return processorStack.pop().process(raw.asCharacters());

        } else {

            StackEvent event = new StackEvent(raw);
            if (event.isTextProcessor()) {
                processorStack.push(new IncrementTextProcessor(event.getIncrement()));
            }

            return event.getEvent();
        }
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public XMLEvent peek() throws XMLStreamException {
        return delegate.peek();
    }

    @Override
    public String getElementText() throws XMLStreamException {
        return delegate.getElementText();
    }

    @Override
    public XMLEvent nextTag() throws XMLStreamException {
        return delegate.nextTag();
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws XMLStreamException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object next() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
