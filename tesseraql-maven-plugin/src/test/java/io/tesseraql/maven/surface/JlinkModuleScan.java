package io.tesseraql.maven.surface;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Which JDK modules the framework's own compiled classes reach for, beyond the {@code java.se}
 * closure — the set the jlinked images in {@code .github/workflows/jpackage.yml} must name.
 *
 * <p>It reads <b>bytecode</b>, not source. An import-only scan is falsified by a file already in
 * this repository: {@code tesseraql-test-core/.../CaptureServer.java} writes
 * {@code com.sun.net.httpserver.HttpServer} fully qualified with no import line, and 354 of the
 * 1021 main sources write some system class that way. A constant pool has no such blind spot,
 * and this module already scans the reactor's compiled classes for the same reason
 * ({@link ModelFieldConsumerScan}, docs/yaml-surface-consumers.md).
 *
 * <p>Two deliberate limits, both stated so a green run is not over-read:
 *
 * <ul>
 * <li><b>Erasure.</b> A type that survives only in a generic signature is invisible here. That is
 * correct rather than a gap: an erased type is never loaded, so a jlinked image does not need its
 * module.</li>
 * <li><b>Reflection and service lookup.</b> A class named by a string, or a provider found through
 * an algorithm name or a locale, leaves no constant-pool trace. Most of the modules the workflow
 * lists are of exactly that kind, which is why this scan justifies only some of them — see the
 * ledger test's failure message.</li>
 * </ul>
 */
final class JlinkModuleScan {

    /** Every system package, mapped to the module that exports or contains it. */
    private static final Map<String, String> PACKAGE_TO_MODULE = systemPackages();

    /** The modules {@code java.se} pulls in — the ones the workflow's first root already covers. */
    private static final Set<String> JAVA_SE_CLOSURE = javaSeClosure();

    private JlinkModuleScan() {
    }

    /**
     * The modules outside the {@code java.se} closure that {@code classesDirs} reach for, each
     * mapped to the packages that reached for them, so a failure names what to look at.
     */
    static Map<String, Set<String>> modulesBeyondJavaSe(Iterable<Path> classesDirs) {
        Map<String, Set<String>> reached = new TreeMap<>();
        for (String type : referencedTypes(classesDirs)) {
            int dot = type.lastIndexOf('.');
            if (dot < 0) {
                continue;
            }
            String module = PACKAGE_TO_MODULE.get(type.substring(0, dot));
            if (module != null && !JAVA_SE_CLOSURE.contains(module)) {
                reached.computeIfAbsent(module, unused -> new TreeSet<>())
                        .add(type.substring(0, dot));
            }
        }
        return reached;
    }

    /** Every binary type name any class under {@code classesDirs} refers to. */
    static Set<String> referencedTypes(Iterable<Path> classesDirs) {
        Set<String> types = new TreeSet<>();
        for (Path classes : classesDirs) {
            try (Stream<Path> files = Files.walk(classes)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    new ClassReader(Files.readAllBytes(file))
                            .accept(new TypeCollector(types), ClassReader.SKIP_FRAMES);
                }
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Could not scan " + classes, unreadable);
            }
        }
        return types;
    }

    /** The module owning {@code packageName}, or null. Exposed so the ledger can canary it. */
    static String moduleOf(String packageName) {
        return PACKAGE_TO_MODULE.get(packageName);
    }

    /** Whether {@code module} is inside the {@code java.se} closure. */
    static boolean insideJavaSe(String module) {
        return JAVA_SE_CLOSURE.contains(module);
    }

    private static Map<String, String> systemPackages() {
        Map<String, String> packages = new LinkedHashMap<>();
        ModuleFinder.ofSystem().findAll()
                .forEach(module -> module.descriptor().packages()
                        .forEach(name -> packages.put(name, module.descriptor().name())));
        return Map.copyOf(packages);
    }

    /**
     * Resolved, not derived from the {@code java.} prefix. Exactly one {@code java.*} module sits
     * outside this closure on JDK 25 — {@code java.smartcardio} — so a prefix test would drop an
     * import of {@code javax.smartcardio} silently, and this repository ships the SAML, OIDC and
     * OAuth signing surfaces where an HSM path would land.
     */
    private static Set<String> javaSeClosure() {
        return Configuration.empty()
                .resolve(ModuleFinder.ofSystem(), ModuleFinder.of(), Set.of("java.se"))
                .modules().stream()
                .map(resolved -> resolved.reference().descriptor().name())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Collects every type the class file names, from the places a constant pool records them. */
    private static final class TypeCollector extends ClassVisitor {

        private final Set<String> types;

        private TypeCollector(Set<String> types) {
            super(Opcodes.ASM9);
            this.types = types;
        }

        private void internal(String name) {
            if (name != null && !name.startsWith("[")) {
                types.add(Type.getObjectType(name).getClassName());
            } else if (name != null) {
                descriptor(name);
            }
        }

        private void descriptor(String desc) {
            if (desc == null) {
                return;
            }
            if (desc.startsWith("(")) {
                for (Type argument : Type.getArgumentTypes(desc)) {
                    add(argument);
                }
                add(Type.getReturnType(desc));
            } else {
                add(Type.getType(desc));
            }
        }

        private void add(Type type) {
            Type element = type;
            while (element.getSort() == Type.ARRAY) {
                element = element.getElementType();
            }
            if (element.getSort() == Type.OBJECT) {
                types.add(element.getClassName());
            }
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            internal(superName);
            if (interfaces != null) {
                for (String each : interfaces) {
                    internal(each);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            descriptor(desc);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature,
                Object value) {
            descriptor(desc);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                String[] exceptions) {
            descriptor(desc);
            if (exceptions != null) {
                for (String each : exceptions) {
                    internal(each);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    internal(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    internal(owner);
                    descriptor(desc);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc,
                        boolean isInterface) {
                    internal(owner);
                    descriptor(desc);
                }

                @Override
                public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap,
                        Object... arguments) {
                    descriptor(desc);
                    internal(bootstrap.getOwner());
                    for (Object argument : arguments) {
                        if (argument instanceof Type type) {
                            add(type);
                        } else if (argument instanceof Handle handle) {
                            internal(handle.getOwner());
                            descriptor(handle.getDesc());
                        }
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type) {
                        add(type);
                    }
                }

                @Override
                public void visitLocalVariable(String name, String desc, String signature,
                        org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {
                    descriptor(desc);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler,
                        String type) {
                    internal(type);
                }
            };
        }
    }
}
