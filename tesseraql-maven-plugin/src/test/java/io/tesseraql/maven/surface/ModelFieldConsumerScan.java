package io.tesseraql.maven.surface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;

/**
 * Method-level attribution over every reactor sibling's compiled classes
 * (docs/yaml-surface-consumers.md, third correction): a constant pool is per class, so a
 * per-class scan cannot separate "read by the canonical accessor" (always true, proves
 * nothing) from "read by a derived accessor another class calls" (the model's defaulting
 * idiom). Walking each method's instructions can, and method references riding as
 * {@code invokedynamic} bootstrap arguments are followed too — {@code
 * ColumnSpec::toMapping} is a read.
 *
 * <p>A component is <em>wired</em> when some class outside its record reads it directly,
 * or reaches it through a chain of methods on the record itself (derived accessors,
 * {@code toReadSpec()}-style converters — any length, since internal composition is the
 * record's own business). The record's constructor and its {@code equals}/{@code
 * hashCode}/{@code toString} never count: they touch every component by construction.
 */
final class ModelFieldConsumerScan {

    static final String MODEL_PREFIX = "io/tesseraql/yaml/model/";

    /** One component's verdict: its external consumers, however they reach it. */
    record Consumers(Set<String> direct, Set<String> viaRecordMethods) {
        boolean wired() {
            return !direct.isEmpty() || !viaRecordMethods.isEmpty();
        }

        Set<String> all() {
            Set<String> all = new LinkedHashSet<>(direct);
            viaRecordMethods.forEach(via -> all.add(via.substring(0, via.indexOf("->"))));
            return all;
        }
    }

    private record Site(String owner, String method) {
    }

    private record Ref(String targetOwner, String member) {
    }

    private final Map<Site, Set<Ref>> refs = new HashMap<>();
    private final Set<String> components = new LinkedHashSet<>();

    /** Scans every {@code target/classes} under the given module roots. */
    static ModelFieldConsumerScan over(List<Path> classesDirs) {
        ModelFieldConsumerScan scan = new ModelFieldConsumerScan();
        for (Path classes : classesDirs) {
            try (Stream<Path> walk = Files.walk(classes)) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(scan::read);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return scan;
    }

    /** Every record component of the model package, as {@code SimpleName#component}. */
    Set<String> components() {
        Set<String> names = new LinkedHashSet<>();
        components.forEach(key -> names.add(display(key)));
        return names;
    }

    /** The verdicts, keyed like {@link #components()}. */
    Map<String, Consumers> classify() {
        Map<String, Consumers> verdicts = new TreeMap<>();
        for (String component : components) {
            String owner = component.substring(0, component.indexOf('#'));
            String name = component.substring(component.indexOf('#') + 1);

            Set<String> direct = new LinkedHashSet<>();
            Set<String> seeds = new LinkedHashSet<>();
            for (Map.Entry<Site, Set<Ref>> entry : refs.entrySet()) {
                Site site = entry.getKey();
                if (!entry.getValue().contains(new Ref(owner, name))) {
                    continue;
                }
                if (site.owner().equals(owner)) {
                    if (!boilerplate(site.method(), name)) {
                        seeds.add(site.method());
                    }
                } else {
                    direct.add(site.owner());
                }
            }

            // Transitive closure inside the record: any same-class method that calls a
            // reachable one joins, then external callers of the closure count.
            Set<String> reachable = new LinkedHashSet<>(seeds);
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Map.Entry<Site, Set<Ref>> entry : refs.entrySet()) {
                    Site site = entry.getKey();
                    if (!site.owner().equals(owner) || reachable.contains(site.method())
                            || boilerplate(site.method(), name)) {
                        continue;
                    }
                    for (String target : reachable) {
                        if (entry.getValue().contains(new Ref(owner, target))) {
                            reachable.add(site.method());
                            grew = true;
                            break;
                        }
                    }
                }
            }
            Set<String> via = new LinkedHashSet<>();
            for (String method : reachable) {
                for (Map.Entry<Site, Set<Ref>> entry : refs.entrySet()) {
                    if (!entry.getKey().owner().equals(owner)
                            && entry.getValue().contains(new Ref(owner, method))) {
                        via.add(entry.getKey().owner() + "->" + method + "()");
                    }
                }
            }
            verdicts.put(display(component), new Consumers(direct, via));
        }
        return verdicts;
    }

    /** Canonical accessor, constructor, and record boilerplate touch every component. */
    private static boolean boilerplate(String method, String componentName) {
        return method.equals(componentName) || method.equals("<init>")
                || method.equals("equals") || method.equals("hashCode")
                || method.equals("toString");
    }

    private static String display(String internal) {
        return internal.substring(MODEL_PREFIX.length()).replace('$', '.');
    }

    private void read(Path file) {
        ClassReader reader;
        try {
            reader = new ClassReader(Files.readAllBytes(file));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String owner = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                    String signature) {
                if (owner.startsWith(MODEL_PREFIX)) {
                    components.add(owner + "#" + name);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                    String signature, String[] exceptions) {
                Site site = new Site(owner, methodName);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String fieldOwner, String name,
                            String desc) {
                        if (fieldOwner.startsWith(MODEL_PREFIX) && opcode == Opcodes.GETFIELD) {
                            add(site, new Ref(fieldOwner, name));
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String methodOwner, String name,
                            String desc, boolean isInterface) {
                        if (methodOwner.startsWith(MODEL_PREFIX)) {
                            add(site, new Ref(methodOwner, name));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc,
                            Handle bootstrap, Object... bsmArgs) {
                        // Method references (ColumnSpec::toMapping) ride as Handle
                        // bootstrap arguments, not direct method instructions.
                        for (Object arg : bsmArgs) {
                            if (arg instanceof Handle handle
                                    && handle.getOwner().startsWith(MODEL_PREFIX)) {
                                add(site, new Ref(handle.getOwner(), handle.getName()));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private void add(Site site, Ref ref) {
        refs.computeIfAbsent(site, s -> new HashSet<>()).add(ref);
    }

    private ModelFieldConsumerScan() {
    }
}
