package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A cleanup handler cannot itself throw the thing it is cleaning up after.
 *
 * <p>{@code Context.runOnContext} throws {@link java.util.concurrent.RejectedExecutionException}
 * once Vert.x has closed. {@code SseRoutes} called it from inside three catch blocks, where nothing
 * catches anything, so the throw escaped the producer's virtual thread and the JVM's default
 * handler printed it. Every green {@code Maven verify} run carried a failure-level annotation —
 * confirmed through the checks API, not inferred from the log — for a stream that had already
 * ended. {@code HealthRoutes} had the same shape with no {@code try} at all.
 *
 * <p><b>The predicate is the union of two shapes, and it took two tries to get there.</b> Asking
 * only "is it reachable from a catch handler" catches {@code SseRoutes} and is <em>green on the
 * unfixed {@code HealthRoutes}</em>, whose virtual-thread body has no {@code try} at all — measured,
 * not reasoned: the first version of this guard passed on the defect it was written for. Asking
 * only "is it outside every try" catches {@code HealthRoutes} and misses {@code SseRoutes}, whose
 * calls sit in handlers the table does not cover. Together they say the honest thing: <em>if this
 * throws, nothing catches it</em>.
 *
 * <p>Red on five sites across three class files before the fix — three in {@code SseRoutes}, one in
 * its frame writer, one in {@code HealthRoutes} — and green after.
 *
 * <p>Scoped to the two classes that had the defect. A repository-wide sweep would be red on
 * handlers that are correct because something above them catches, and a guard that is red for an
 * honest reason is one somebody eventually deletes. A third class that grows this shape is a
 * review question, and this test's failure message is where it gets asked.
 *
 * <p>This reads compiled classes with {@code java.lang.classfile}, standard since JDK 24 and so
 * available on this repository's Java 25 baseline: no dependency, no build-file change.
 */
class CleanupEscapeLedgerTest {

    private static final Path REPO = Path.of("..");

    /** Where the defect was, and the only places this predicate is honest. */
    private static final Map<String, String> SCANNED = Map.of(
            "tesseraql-runtime/target/classes/io/tesseraql/runtime/SseRoutes.class",
            "the SSE producer's cleanup, which ran after the runtime closed",
            "tesseraql-runtime/target/classes/io/tesseraql/runtime/SseRoutes$1.class",
            "the frame writer, whose delivery path must stop the producer rather than drop a write",
            "tesseraql-runtime/target/classes/io/tesseraql/runtime/HealthRoutes.class",
            "the first readiness roll-up, whose virtual thread has no catch above it");

    @Test
    void noCleanupHandlerCanThrowFromADeadEventLoop() throws IOException {
        List<String> escapes = new ArrayList<>();
        int scanned = 0;
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(SCANNED).entrySet()) {
            Path classFile = REPO.resolve(entry.getKey());
            assertThat(Files.isRegularFile(classFile))
                    .as(entry.getKey() + " is missing; build the module before this guard rather "
                            + "than passing on an empty scan")
                    .isTrue();
            scanned++;
            for (MethodModel method : ClassFile.of().parse(classFile).methods()) {
                method.code().ifPresent(code -> escapes.addAll(
                        escapingCalls(entry.getKey(), method, code)));
            }
        }

        assertThat(scanned).as("no classes scanned").isEqualTo(SCANNED.size());
        assertThat(escapes)
                .as("Context.runOnContext reachable from a catch handler, where nothing catches "
                        + "the RejectedExecutionException it throws once Vert.x has closed; route "
                        + "the call through a helper that drops the mutation, as SseRoutes' "
                        + "onConnection does — except on a delivery path, where dropping it leaves "
                        + "the producer looping against a dead stream")
                .isEmpty();
    }

    /**
     * Every {@code runOnContext} whose {@link java.util.concurrent.RejectedExecutionException}
     * nothing would catch.
     *
     * <p>Two shapes, and the union is the honest predicate — "if this throws, nothing catches it":
     *
     * <ul>
     * <li><b>Reachable from a catch handler.</b> {@code SseRoutes} called it from inside three
     * handlers, which no exception table covers.</li>
     * <li><b>Covered by no handler at all.</b> {@code HealthRoutes} had no {@code try} in its
     * virtual-thread body, so the first shape alone was blind to it — measured, not assumed: the
     * first version of this guard was green on the unfixed {@code HealthRoutes}.</li>
     * </ul>
     *
     * <p>Either half alone would be a guard that half-works. The narrow scope is what makes the
     * second half honest: repository-wide, "covered by no handler" is red on sites that are correct
     * because a caller catches.
     *
     * <p>Positions come from the {@code LabelTarget} pseudo-elements the element list already
     * carries, so no bytecode-index arithmetic is needed and none can be got wrong.
     */
    private static List<String> escapingCalls(String owner, MethodModel method, CodeModel code) {
        List<java.lang.classfile.CodeElement> elements = code.elementList();

        Map<java.lang.classfile.Label, Integer> positions = new java.util.HashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof LabelTarget target) {
                positions.put(target.label(), index);
            }
        }

        java.util.Set<Integer> handlerStarts = new TreeSet<>();
        List<int[]> covered = new ArrayList<>();
        for (java.lang.classfile.CodeElement element : elements) {
            if (element instanceof ExceptionCatch caught) {
                Integer handler = positions.get(caught.handler());
                Integer from = positions.get(caught.tryStart());
                Integer to = positions.get(caught.tryEnd());
                if (handler != null) {
                    handlerStarts.add(handler);
                }
                if (from != null && to != null) {
                    covered.add(new int[]{from, to});
                }
            }
        }

        java.util.Set<Integer> fromAHandler = new TreeSet<>();
        for (int start : handlerStarts) {
            for (int at = start; at < elements.size(); at++) {
                java.lang.classfile.CodeElement element = elements.get(at);
                if (element instanceof InvokeInstruction invoke
                        && "runOnContext".equals(invoke.name().stringValue())) {
                    fromAHandler.add(at);
                }
                if (element instanceof ReturnInstruction || element instanceof ThrowInstruction) {
                    break;
                }
            }
        }

        List<String> found = new ArrayList<>();
        for (int at = 0; at < elements.size(); at++) {
            if (!(elements.get(at) instanceof InvokeInstruction invoke)
                    || !"runOnContext".equals(invoke.name().stringValue())) {
                continue;
            }
            boolean inATry = false;
            for (int[] range : covered) {
                if (at >= range[0] && at < range[1]) {
                    inATry = true;
                    break;
                }
            }
            String where = owner.substring(owner.lastIndexOf('/') + 1) + " "
                    + method.methodName().stringValue() + " (element " + at + ")";
            if (fromAHandler.contains(at)) {
                found.add(where + ": reachable from a catch handler, which nothing covers");
            } else if (!inATry) {
                found.add(where + ": covered by no handler at all");
            }
        }
        return found;
    }
}
