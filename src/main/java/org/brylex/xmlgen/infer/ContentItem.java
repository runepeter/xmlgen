package org.brylex.xmlgen.infer;

public sealed interface ContentItem {
    record ChildSlot(ShapeNode node) implements ContentItem {}
    record Text(String value) implements ContentItem {}
    record Cdata(String value) implements ContentItem {}
    record ProcessingInstruction(String target, String data) implements ContentItem {}
    record Comment(String value) implements ContentItem {}
}
