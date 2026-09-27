package com.claimflow.common.kafka;

import com.claimflow.common.error.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.nio.channels.ClosedChannelException;

import static org.assertj.core.api.Assertions.assertThat;

class RootCauseTest {

    @Test
    void usesRootWhenItHasAMessage() {
        Exception ex = new ListenerExecutionFailedException("Listener method threw exception",
                new IllegalStateException("wrapper", new IllegalArgumentException("bad claim id")));

        assertThat(KafkaInfrastructureConfig.rootCause(ex)).isEqualTo("IllegalArgumentException: bad claim id");
    }

    @Test
    void fallsBackToDeepestMessageWhenRootHasNone() {
        // what a refused connection to Policy Service actually looks like
        Exception ex = new ListenerExecutionFailedException("Listener method threw exception",
                new DependencyUnavailableException("Policy Service unreachable: I/O error on GET",
                        new ClosedChannelException()));

        assertThat(KafkaInfrastructureConfig.rootCause(ex)).isEqualTo(
                "DependencyUnavailableException: Policy Service unreachable: I/O error on GET (root: ClosedChannelException)");
    }
}
