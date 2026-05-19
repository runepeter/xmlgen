package org.brylex.xmlgen.perf;

import org.brylex.xmlgen.GeneratingXMLEventReader;
import org.brylex.xmlgen.Pools;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.stream.events.XMLEvent;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Throughput benchmark. Not a JUnit test — has no @Test annotation, so
 * surefire ignores it. Run manually with:
 * <pre>
 *   mvn -q test-compile
 *   java -cp "$(mvn -q dependency:build-classpath \
 *                  -DincludeScope=test -Dmdep.outputFile=/dev/stdout):\
 *             target/classes:target/test-classes" \
 *        org.brylex.xmlgen.perf.GeneratorBenchmark
 * </pre>
 *
 * The benchmark generates a CAMT.053-shaped document with N entries
 * exercising gen:repeat, gen:choose, gen:pick, gen:increment, and
 * gen:random-amount together, writing it through a real StAX writer
 * to /dev/null. Reports events/sec and per-entry latency.
 */
public class GeneratorBenchmark {

    private static final String TEMPLATE = """
            <Stmt xmlns:gen="urn:xml:gen">
                <Ntry gen:repeat="%d">
                    <gen:choose>
                        <gen:when weight="60">
                            <CdtDbtInd>CRDT</CdtDbtInd>
                            <Amt Ccy="NOK" gen:random-amount="10.00..50000.00">_</Amt>
                            <Party><Nm gen:pick="customers/name">_</Nm></Party>
                            <Acct><Id><IBAN gen:pick="customers/iban">_</IBAN></Id></Acct>
                        </gen:when>
                        <gen:when weight="40">
                            <CdtDbtInd>DBIT</CdtDbtInd>
                            <Amt Ccy="NOK" gen:random-amount="100.00..200000.00">_</Amt>
                            <Party><Nm gen:pick="suppliers/name">_</Nm></Party>
                            <Acct><Id><IBAN gen:pick="suppliers/iban">_</IBAN></Id></Acct>
                        </gen:when>
                    </gen:choose>
                    <Refs><EndToEndId gen:increment="1">1000000</EndToEndId></Refs>
                </Ntry>
            </Stmt>
            """;

    public static void main(String[] args) throws Exception {
        int entries = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        int warmupRuns = 3;
        int measuredRuns = 5;

        Pools pools = buildPools();

        // Warmup.
        for (int i = 0; i < warmupRuns; i++) {
            runOnce(entries, pools);
        }

        long[] elapsedNanos = new long[measuredRuns];
        long[] eventCounts = new long[measuredRuns];
        for (int i = 0; i < measuredRuns; i++) {
            long[] r = runOnce(entries, pools);
            elapsedNanos[i] = r[0];
            eventCounts[i] = r[1];
        }

        long bestNanos = Long.MAX_VALUE;
        long bestEvents = 0;
        long totalNanos = 0;
        for (int i = 0; i < measuredRuns; i++) {
            totalNanos += elapsedNanos[i];
            if (elapsedNanos[i] < bestNanos) {
                bestNanos = elapsedNanos[i];
                bestEvents = eventCounts[i];
            }
        }
        long avgNanos = totalNanos / measuredRuns;

        System.out.printf("entries:    %d%n", entries);
        System.out.printf("events:     %d%n", bestEvents);
        System.out.printf("runs:       %d (best/avg of %d)%n", measuredRuns, measuredRuns);
        System.out.printf("best:       %.2f ms  (%.0f events/sec, %.2f us/entry)%n",
                bestNanos / 1_000_000.0,
                bestEvents * 1_000_000_000.0 / bestNanos,
                bestNanos / 1_000.0 / entries);
        System.out.printf("avg:        %.2f ms%n", avgNanos / 1_000_000.0);
    }

    private static long[] runOnce(int entries, Pools pools) throws XMLStreamException {
        String xml = String.format(TEMPLATE, entries);
        XMLEventReader src = XMLInputFactory.newFactory()
                .createXMLEventReader(new StringReader(xml));
        XMLEventReader reader = new GeneratingXMLEventReader(src, pools, new Random(42L));

        Writer writer = new OutputStreamWriter(OutputStream.nullOutputStream(), StandardCharsets.UTF_8);
        XMLStreamWriter out = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);

        long count = 0;
        long start = System.nanoTime();
        out.writeStartDocument();
        while (reader.hasNext()) {
            XMLEvent e = reader.nextEvent();
            writeEvent(out, e);
            count++;
        }
        out.writeEndDocument();
        out.flush();
        long elapsed = System.nanoTime() - start;
        return new long[]{elapsed, count};
    }

    private static void writeEvent(XMLStreamWriter out, XMLEvent e) throws XMLStreamException {
        switch (e.getEventType()) {
            case XMLEvent.START_DOCUMENT, XMLEvent.END_DOCUMENT ->
                    { /* already written outside the loop */ }
            case XMLEvent.START_ELEMENT ->
                    out.writeStartElement(e.asStartElement().getName().getLocalPart());
            case XMLEvent.END_ELEMENT -> out.writeEndElement();
            case XMLEvent.CHARACTERS -> out.writeCharacters(e.asCharacters().getData());
            default -> { /* ignore */ }
        }
    }

    private static Pools buildPools() {
        return Pools.builder()
                .inline("customers", List.of(
                        Map.of("name", "Acme AS", "iban", "NO9300000000001"),
                        Map.of("name", "Beta AS", "iban", "NO9300000000002"),
                        Map.of("name", "Gamma AS", "iban", "NO9300000000003"),
                        Map.of("name", "Delta AS", "iban", "NO9300000000004"),
                        Map.of("name", "Epsilon AS", "iban", "NO9300000000005")))
                .inline("suppliers", List.of(
                        Map.of("name", "Foo Ltd", "iban", "NO9300000000101"),
                        Map.of("name", "Bar Ltd", "iban", "NO9300000000102"),
                        Map.of("name", "Baz Ltd", "iban", "NO9300000000103")))
                .build();
    }
}
