package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The YAML keys a shape accepts, and the declared type behind each, read from the shape itself
 * (docs/lint-restructure.md decision 3).
 *
 * <p>One derivation, three readers: the unknown-key lint walks a document with it, the domains
 * and catalogs loaders refuse a key with it, and the view loader refuses one with it. They used
 * to answer the same question from three hand-kept lists, so whether a typo was a load error, a
 * warning, or nothing at all was an accident of which campaign last touched the document family.
 * A hand-kept list also has to be remembered: this one cannot fall behind the model, because it
 * <em>is</em> the model.
 *
 * <p>The {@code @JsonCreator} comes first because the authoring form is what an author writes,
 * and the two part company exactly where a record is a folded shape: {@link PipelineStep} holds
 * a {@link Binding} but is authored with the arms spread across the step, so its components list
 * neither {@code when} nor {@code http} nor {@code enrich}. Reading components alone would call
 * three legal keys unknown.
 */
public final class AcceptedKeys {

    private AcceptedKeys() {
    }

    /** One shape's derivation: the authored properties, and their names sorted for a message. */
    private record Accepted(Map<String, Type> properties, Set<String> keys) {
    }

    private static final Map<Class<?>, Accepted> CACHE = new ConcurrentHashMap<>();

    /**
     * The keys {@code shape} accepts, in sorted order — the order a diagnostic lists them in, so
     * two runs name the accepted keys the same way.
     */
    public static Set<String> of(Class<?> shape) {
        return accepted(shape).keys();
    }

    /**
     * Each accepted key against the type declared behind it, in declaration order — how the lint
     * knows a value is itself a shape worth walking into.
     */
    public static Map<String, Type> properties(Class<?> shape) {
        return accepted(shape).properties();
    }

    private static Accepted accepted(Class<?> shape) {
        return CACHE.computeIfAbsent(shape, cls -> {
            Map<String, Type> properties = derive(cls);
            return new Accepted(properties,
                    Collections.unmodifiableSet(new TreeSet<>(properties.keySet())));
        });
    }

    /** The authored properties: the properties-mode creator's parameters, else the components. */
    private static Map<String, Type> derive(Class<?> shape) {
        Map<String, Type> authored = creatorProperties(shape);
        if (authored != null) {
            return authored;
        }
        Map<String, Type> components = new LinkedHashMap<>();
        if (shape.getRecordComponents() != null) {
            for (RecordComponent component : shape.getRecordComponents()) {
                components.put(yamlName(shape, component), component.getGenericType());
            }
        }
        return Collections.unmodifiableMap(components);
    }

    /**
     * The properties of a class's properties-mode {@code @JsonCreator}, or null when it has
     * none. A delegating creator (a scalar shorthand such as a bare column name, or the
     * string-or-map form of a guard) names no properties, so it is not one of these — and the
     * map form of every such shorthand carries the record's own component names anyway.
     */
    private static Map<String, Type> creatorProperties(Class<?> shape) {
        for (Method method : shape.getDeclaredMethods()) {
            if (method.getAnnotation(JsonCreator.class) == null
                    || method.getParameterCount() == 0) {
                continue;
            }
            Map<String, Type> properties = new LinkedHashMap<>();
            for (Parameter parameter : method.getParameters()) {
                JsonProperty property = parameter.getAnnotation(JsonProperty.class);
                if (property == null) {
                    properties = null;
                    break;
                }
                properties.put(property.value(), parameter.getParameterizedType());
            }
            if (properties != null) {
                return Collections.unmodifiableMap(properties);
            }
        }
        return null;
    }

    /**
     * A record component's YAML name, honoring a {@code @JsonProperty} rename.
     *
     * <p>Read from the backing <em>field</em>, not the component mirror: {@code @JsonProperty}
     * cannot target a record component, so javac puts it on the field and
     * {@code RecordComponent.getAnnotation} answers null. Asking the mirror listed the three
     * renamed components under their Java names — so the lint told an author that
     * {@code export:}, {@code import:} and {@code notify:} were unknown keys "silently ignored",
     * naming {@code fileExport}, {@code fileImport} and {@code notifications} as the accepted
     * spellings, none of which the loader accepts.
     */
    private static String yamlName(Class<?> shape, RecordComponent component) {
        try {
            JsonProperty renamed = shape.getDeclaredField(component.getName())
                    .getAnnotation(JsonProperty.class);
            if (renamed != null && !renamed.value().isEmpty()) {
                return renamed.value();
            }
        } catch (NoSuchFieldException impossibleForARecord) {
            throw new IllegalStateException(impossibleForARecord);
        }
        return component.getName();
    }
}
