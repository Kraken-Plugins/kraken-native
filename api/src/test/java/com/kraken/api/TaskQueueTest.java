package com.kraken.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client thread's {@link TaskQueue}, driven by hand the way the poll loop drives it.
 */
class TaskQueueTest {

    private TaskQueue tasks;
    private List<String> ran;

    @BeforeEach
    void setUp() {
        tasks = new TaskQueue();
        ran = new ArrayList<>();
    }

    @Test
    void invokeOffTheClientThreadWaitsForThePoll() {
        assertFalse(tasks.isClientThread());
        tasks.invoke(() -> ran.add("a"));
        assertEquals(List.of(), ran);
        tasks.runPending();
        assertEquals(List.of("a"), ran);
        tasks.runPending();
        assertEquals(List.of("a"), ran, "once");
    }

    @Test
    void invokeOnTheClientThreadRunsAtOnceButInvokeLaterWaits() {
        tasks.bind(Thread.currentThread());
        assertTrue(tasks.isClientThread());
        tasks.invoke(() -> ran.add("now"));
        tasks.invokeLater(() -> ran.add("later"));
        assertEquals(List.of("now"), ran);
        tasks.runPending();
        assertEquals(List.of("now", "later"), ran);
    }

    @Test
    void aTaskReturningFalseRunsAgainEachPollUntilTrue() {
        int[] runs = {0};
        tasks.invokeLater(() -> ++runs[0] == 3);
        tasks.runPending();
        tasks.runPending();
        assertEquals(2, runs[0]);
        tasks.runPending();
        tasks.runPending();
        assertEquals(3, runs[0]);
    }

    @Test
    void tasksQueuedWhileRunningWaitForTheNextPoll() {
        tasks.invokeLater(() -> {
            ran.add("first");
            tasks.invokeLater(() -> ran.add("queued by first"));
            return true;
        });
        tasks.runPending();
        assertEquals(List.of("first"), ran);
        tasks.runPending();
        assertEquals(List.of("first", "queued by first"), ran);
    }

    @Test
    void tickEndTasksOnlyRunWhenAsked() {
        tasks.invokeAtTickEnd(() -> ran.add("tick end"));
        tasks.runPending();
        assertEquals(List.of(), ran);
        tasks.runTickEnd();
        tasks.runTickEnd();
        assertEquals(List.of("tick end"), ran);
    }

    @Test
    void aThrowingTaskIsDroppedAndTheOthersRun() {
        tasks.invokeLater(() -> {
            throw new IllegalStateException("boom");
        });
        tasks.invokeLater(() -> ran.add("after"));
        tasks.runPending();
        tasks.runPending();
        assertEquals(List.of("after"), ran);
    }
}
