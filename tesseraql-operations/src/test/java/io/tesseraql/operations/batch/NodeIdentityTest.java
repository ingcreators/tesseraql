package io.tesseraql.operations.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The node's name: the configuration wins, then the environment's {@code TESSERAQL_NODE_ID}
 * (the chart sets it from the pod's name, docs/kubernetes.md), then the host and the pid.
 */
class NodeIdentityTest {

    @Test
    void theConfigurationWinsOverTheEnvironment() {
        assertThat(NodeIdentity.resolve(" slot-3 ", "orders-7c9d-x1")).isEqualTo("slot-3");
    }

    @Test
    void thePodsNameIsTheNodesNameWhenNothingIsConfigured() {
        assertThat(NodeIdentity.resolve(null, "orders-7c9d-x1")).isEqualTo("orders-7c9d-x1");
        assertThat(NodeIdentity.resolve("", " orders-7c9d-x1 ")).isEqualTo("orders-7c9d-x1");
    }

    @Test
    void theHostAndThePidRemainTheDefault() {
        String derived = NodeIdentity.resolve(null, " ");
        assertThat(derived).endsWith("-" + ProcessHandle.current().pid());
        assertThat(derived.length()).isLessThanOrEqualTo(200);
    }
}
