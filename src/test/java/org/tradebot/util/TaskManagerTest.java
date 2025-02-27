//package org.tradebot.util;
//
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.mockito.MockitoAnnotations;
//import org.tradebot.service.TaskManager;
//
//import java.util.concurrent.TimeUnit;
//
//import static org.junit.jupiter.api.Assertions.*;
//
////TODO
//class TaskManagerTest {
//
//    private TaskManager taskManager;
//
//    @BeforeEach
//    void setUp() {
//        MockitoAnnotations.openMocks(this);
//        taskManager = TaskManager.getInstance();
//    }
//
//    @Test
//    void create() {
//        taskManager.scheduleAtFixedRate("task_id", () -> {}, 1, 1, TimeUnit.HOURS);
//    }
//
//    @Test
//    void update() {
//        taskManager.scheduleAtFixedRate("task_id", () -> {}, 1, 1, TimeUnit.HOURS);
//        taskManager.scheduleAtFixedRate("task_id", () -> {}, 2, 2, TimeUnit.HOURS);
//    }
//
//    @Test
//    void stop() {
//        taskManager.scheduleAtFixedRate("task_id", () -> {}, 1, 1, TimeUnit.HOURS);
//        taskManager.cancel("task_id");
//    }
//
//    @Test
//    void stopAll() {
//        taskManager.scheduleAtFixedRate("task_1", () -> {}, 1, 1, TimeUnit.HOURS);
//        taskManager.scheduleAtFixedRate("task_2", () -> {}, 1, 1, TimeUnit.HOURS);
//        taskManager.cancelAll();
//    }
//}