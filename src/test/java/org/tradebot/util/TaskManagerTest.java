package org.tradebot.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {

    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        taskManager = new TaskManager();
    }

    @Test
    void create() {
        taskManager.create("task_id", () -> {}, TaskManager.Type.PERIOD, 1, 1, TimeUnit.HOURS);
        assertTrue(taskManager.executors.containsKey("task_id"));
        assertNotNull(taskManager.executors.get("task_id"));
    }

    @Test
    void update() {
        taskManager.create("task_id", () -> {}, TaskManager.Type.PERIOD, 1, 1, TimeUnit.HOURS);
        taskManager.create("task_id", () -> {}, TaskManager.Type.PERIOD, 2, 2, TimeUnit.HOURS);
        assertTrue(taskManager.executors.containsKey("task_id"));
        assertNotNull(taskManager.executors.get("task_id"));
    }

    @Test
    void stop() {
        taskManager.create("task_id", () -> {}, TaskManager.Type.PERIOD, 1, 1, TimeUnit.HOURS);
        taskManager.stop("task_id");
        assertFalse(taskManager.executors.containsKey("task_id"));
    }

    @Test
    void stopAll() {
        taskManager.create("task_1", () -> {}, TaskManager.Type.PERIOD, 1, 1, TimeUnit.HOURS);
        taskManager.create("task_2", () -> {}, TaskManager.Type.PERIOD, 1, 1, TimeUnit.HOURS);
        taskManager.stopAll();
        assertEquals(0, taskManager.executors.size());
    }
}