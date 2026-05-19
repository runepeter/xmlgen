package org.brylex.xmlgen;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

public class GeneratingXMLEventReader implements XMLEventReader {

    static final QName CHOOSE = new QName("urn:xml:gen", "choose");
    static final QName WHEN = new QName("urn:xml:gen", "when");
    private static final QName WEIGHT = new QName("weight");

    private final StackXMLEventReader stackReader;
    private final DelegatingXMLEventReader delegate;
    private final Random random;
    private final Deque<XMLEvent> pending = new ArrayDeque<>();
    private XMLEvent head;

    public GeneratingXMLEventReader(final XMLEventReader delegate) {
        this(delegate, Pools.empty(), new Random());
    }

    public GeneratingXMLEventReader(final XMLEventReader delegate, final Pools pools) {
        this(delegate, pools, new Random());
    }

    public GeneratingXMLEventReader(final XMLEventReader delegate, final Pools pools, final Random random) {
        this.stackReader = new StackXMLEventReader(delegate);
        XMLEventReader chain = stackReader;
        if (!pools.isEmpty()) {
            chain = new PoolPickXMLEventReader(chain, pools);
        }
        chain = new RandomXMLEventReader(chain, random);
        this.delegate = new DelegatingXMLEventReader(new TextProcessingXMLEventReader(chain));
        this.random = random;
    }

    public XMLEvent nextEvent() throws XMLStreamException {

        materialize();
        XMLEvent raw = head;
        head = null;

        StackEvent event = new StackEvent(raw);

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

    private void materialize() throws XMLStreamException {
        while (head == null) {
            if (!pending.isEmpty()) {
                head = pending.poll();
            } else {
                XMLEvent raw = delegate.nextEvent();
                if (isChooseStart(raw)) {
                    handleChoose();
                    continue;
                }
                head = raw;
            }
        }
    }

    private void handleChoose() throws XMLStreamException {

        List<List<XMLEvent>> branches = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        List<XMLEvent> current = null;
        int depth = 1;

        while (depth > 0) {
            XMLEvent event = delegate.nextEvent();

            if (event.isStartElement()) {
                StartElement se = event.asStartElement();
                if (depth == 1 && WHEN.equals(se.getName())) {
                    current = new ArrayList<>();
                    branches.add(current);
                    weights.add(parseWeight(se));
                    depth++;
                    continue;
                }
                depth++;
            } else if (event.isEndElement()) {
                QName endName = event.asEndElement().getName();
                depth--;
                if (depth == 1 && WHEN.equals(endName)) {
                    current = null;
                    continue;
                }
                if (depth == 0 && CHOOSE.equals(endName)) {
                    break;
                }
            }

            if (current != null) {
                current.add(event);
            }
        }

        if (branches.isEmpty()) {
            return;
        }

        List<XMLEvent> chosen = weightedPick(branches, weights);
        pending.addAll(chosen);
    }

    private int parseWeight(StartElement when) {
        Attribute w = when.getAttributeByName(WEIGHT);
        if (w == null) {
            return 1;
        }
        try {
            int v = Integer.parseInt(w.getValue());
            if (v < 0) {
                throw new IllegalArgumentException(
                        "gen:when weight must be non-negative, got " + v);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "gen:when weight must be an integer, got '" + w.getValue() + "'", e);
        }
    }

    private List<XMLEvent> weightedPick(List<List<XMLEvent>> branches, List<Integer> weights) {
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        if (total == 0) {
            return branches.get(random.nextInt(branches.size()));
        }
        int r = random.nextInt(total);
        int acc = 0;
        for (int i = 0; i < weights.size(); i++) {
            acc += weights.get(i);
            if (r < acc) {
                return branches.get(i);
            }
        }
        return branches.get(branches.size() - 1);
    }

    private static boolean isChooseStart(XMLEvent event) {
        return event.isStartElement() && CHOOSE.equals(event.asStartElement().getName());
    }

    private XMLEvent returnableEvent(StackEvent event) {
        if (event.getEvent().isStartElement()) {
            return new GeneratorStrippingStartEvent(event.getEvent().asStartElement());
        } else {
            return event.getEvent();
        }
    }

    public boolean hasNext() {
        if (head != null || !pending.isEmpty()) {
            return true;
        }
        return delegate.hasNext();
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
        materialize();
        if (head.isStartElement()) {
            return new GeneratorStrippingStartEvent(head.asStartElement());
        }
        return head;
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
}
