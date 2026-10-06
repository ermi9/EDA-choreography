package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JoinDeadlineSweeperTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final InMemoryJoinDeadlines deadlines = new InMemoryJoinDeadlines();
    private final List<JoinKey> notified = new ArrayList<>();
    private final JoinDeadlineSweeper sweeper =
            new JoinDeadlineSweeper(deadlines, notified::add, Clock.fixed(NOW, ZoneOffset.UTC), 2);

    @Test
    void notifiesEveryDueJoinOnceAndLeavesTheOthersWaiting() {
        var due = List.of(new JoinKey("corr-1", "J"), new JoinKey("corr-2", "J"), new JoinKey("corr-3", "K"));
        due.forEach(key -> deadlines.setIfAbsent(key, NOW.minusSeconds(1)));
        var waiting = new JoinKey("corr-4", "J");
        deadlines.setIfAbsent(waiting, NOW.plusSeconds(1));

        assertThat(sweeper.sweep()).isEqualTo(3);
        assertThat(sweeper.sweep()).isZero();

        assertThat(notified).containsExactlyInAnyOrderElementsOf(due);
        assertThat(deadlines.dueBy(NOW.plusSeconds(1), 10)).containsExactly(waiting);
    }

    @Test
    void keepsTheDeadlineWhenTheNoticeCouldNotBeSent() {
        var key = new JoinKey("corr-1", "J");
        deadlines.setIfAbsent(key, NOW);
        var failing = new JoinDeadlineSweeper(deadlines, ignored -> {
            throw new IllegalStateException("broker unreachable");
        }, Clock.fixed(NOW, ZoneOffset.UTC), 2);

        assertThatThrownBy(failing::sweep).hasMessage("broker unreachable");
        assertThat(deadlines.dueBy(NOW, 10)).containsExactly(key);
    }
}
