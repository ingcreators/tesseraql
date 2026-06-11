package io.tesseraql.coverage.plan;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Obtains estimated query plans from SQL Server via {@code SET SHOWPLAN_XML ON} (design ch. 46.5) and
 * normalizes the Showplan XML into the dialect-independent {@link QueryPlan}. The estimated plan is
 * produced without executing the statement. Full-scan operators ({@code Table Scan}, {@code Index
 * Scan}, {@code Clustered Index Scan}) map to the common {@code Seq Scan} the guard recognizes, while
 * seeks map to {@code Index Scan}.
 *
 * <p>The XML normalization is unit-tested against captured Showplan output; the live integration
 * test runs against a real SQL Server container behind {@code -Dtesseraql.dialect.its=true}
 * (the image is large and license-gated, so it is opt-in).
 */
public final class SqlServerPlanInspector implements PlanInspector {

    private static final TqlErrorCode EXPLAIN_ERROR = new TqlErrorCode(TqlDomain.PLAN, 1504);
    private static final String NS = "http://schemas.microsoft.com/sqlserver/2004/07/showplan";

    @Override
    public String dialect() {
        return "sqlserver";
    }

    @Override
    public QueryPlan explain(Connection connection, String sql, List<Object> params)
            throws SQLException {
        // SHOWPLAN_XML only yields a plan result set for direct batches, not for the RPC path
        // parameterized prepared statements take - so the representative parameter values inline
        // as escaped literals (plan inspection runs on fixture values, design ch. 46).
        setShowplan(connection, true);
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(inline(sql, params))) {
            if (!resultSet.next()) {
                throw new TqlException(EXPLAIN_ERROR, "SHOWPLAN_XML returned no rows");
            }
            return parse(resultSet.getString(1));
        } finally {
            setShowplan(connection, false);
        }
    }

    /** Replaces each positional marker with the escaped literal of its sample value. */
    static String inline(String sql, List<Object> params) {
        StringBuilder inlined = new StringBuilder();
        int param = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?' && param < params.size()) {
                inlined.append(literal(params.get(param++)));
            } else {
                inlined.append(ch);
            }
        }
        return inlined.toString();
    }

    private static String literal(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static void setShowplan(Connection connection, boolean on) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SHOWPLAN_XML " + (on ? "ON" : "OFF"));
        }
    }

    /** Normalizes a SQL Server Showplan XML document into a {@link QueryPlan}. */
    static QueryPlan parse(String xml) {
        try {
            Document document = secureDocument(xml);
            NodeList relOps = document.getElementsByTagNameNS(NS, "RelOp");
            if (relOps.getLength() == 0) {
                return new QueryPlan("Query Plan", null, null, 0, 0, List.of());
            }
            Map<Element, Element> objectByRelOp = mapObjects(document);
            List<QueryPlan> all = new ArrayList<>();
            for (int i = 0; i < relOps.getLength(); i++) {
                all.add(node((Element) relOps.item(i), objectByRelOp));
            }
            return new QueryPlan(all.get(0).nodeType(), all.get(0).relationName(),
                    all.get(0).indexName(), all.get(0).totalCost(), all.get(0).estimatedRows(),
                    all.subList(1, all.size()));
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(EXPLAIN_ERROR,
                    "Failed to parse Showplan XML: " + ex.getMessage());
        }
    }

    private static QueryPlan node(Element relOp, Map<Element, Element> objectByRelOp) {
        Element object = objectByRelOp.get(relOp);
        return new QueryPlan(
                mapOperator(relOp.getAttribute("PhysicalOp")),
                object == null ? null : unbracket(object.getAttribute("Table")),
                object == null ? null : unbracket(object.getAttribute("Index")),
                parseDouble(relOp.getAttribute("EstimatedTotalSubtreeCost")),
                (long) parseDouble(relOp.getAttribute("EstimateRows")),
                List.of());
    }

    /** Associates each {@code Object} with its nearest enclosing {@code RelOp}. */
    private static Map<Element, Element> mapObjects(Document document) {
        Map<Element, Element> byRelOp = new IdentityHashMap<>();
        NodeList objects = document.getElementsByTagNameNS(NS, "Object");
        for (int i = 0; i < objects.getLength(); i++) {
            Element object = (Element) objects.item(i);
            Element relOp = closestRelOp(object);
            if (relOp != null && object.hasAttribute("Table")) {
                byRelOp.putIfAbsent(relOp, object);
            }
        }
        return byRelOp;
    }

    private static Element closestRelOp(Node node) {
        for (Node current = node.getParentNode(); current != null; current = current
                .getParentNode()) {
            if (current instanceof Element element
                    && "RelOp".equals(element.getLocalName())
                    && NS.equals(element.getNamespaceURI())) {
                return element;
            }
        }
        return null;
    }

    private static String mapOperator(String physicalOp) {
        if (physicalOp == null || physicalOp.isEmpty()) {
            return "Operator";
        }
        if (physicalOp.contains("Seek")) {
            return "Index Scan";
        }
        return physicalOp.contains("Scan") ? "Seq Scan" : physicalOp;
    }

    private static String unbracket(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static double parseDouble(String value) {
        try {
            return value == null || value.isEmpty() ? 0 : Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Document secureDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
