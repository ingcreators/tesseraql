package io.tesseraql.operations.batch;

/**
 * Names the process a batch run belongs to (docs/audit-hardening.md Decision 6).
 *
 * <p>Deliberately not a node registry with a process lifecycle of its own. The defect this answers
 * is a {@code RUNNING} row nobody will ever finish, and the smallest thing that answers it is a
 * label plus a pulse — a registry is stronger and a much larger piece of work than the defect
 * warrants.
 *
 * <p>The default is derived rather than configured, because an operator who has not thought about
 * node identity still needs runs to be distinguishable: two replicas of the same image on the same
 * host must not collide. Hostname plus process id gives that, and an explicit
 * {@code tesseraql.batch.nodeId} takes over wherever the derivation is wrong — a scheduler that
 * reuses hostnames, or an operator who wants the run rows to name a deployment slot.
 */
public final class NodeIdentity {

    private NodeIdentity() {
    }

    /** The configured node id, the environment's, or one derived from the host and process. */
    public static String resolve(String configured) {
        return resolve(configured, System.getenv("TESSERAQL_NODE_ID"));
    }

    /**
     * The same with the environment's name given explicitly. An orchestrator that names the pod
     * hands the name over as {@code TESSERAQL_NODE_ID} (the chart sets it from the pod's name,
     * docs/kubernetes.md), so a heartbeat, a reaper verdict and an alert payload name something
     * {@code kubectl} can find, and the name survives a container restart inside the pod, where
     * the pid does not. The configuration still wins, for the operator who names slots.
     */
    public static String resolve(String configured, String fromEnvironment) {
        if (configured != null && !configured.isBlank()) {
            return trim(configured.trim());
        }
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return trim(fromEnvironment.trim());
        }
        return trim(hostname() + "-" + ProcessHandle.current().pid());
    }

    private static String hostname() {
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            // A host that cannot name itself is not a reason to refuse to run; the pid still
            // distinguishes replicas on this machine, which is what the column is for.
            return "node";
        }
    }

    /** The column is 200 characters; a long derived name is truncated rather than failing a run. */
    private static String trim(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
