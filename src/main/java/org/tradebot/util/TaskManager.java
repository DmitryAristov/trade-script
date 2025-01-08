package org.tradebot.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskManager {

    public enum Type {
        DELAYED,
        PERIOD
    }

    protected final Map<String, ScheduledExecutorService> executors = new HashMap<>();

    public void create(String taskId, Runnable command, Type type, long initialDelay, long period, TimeUnit timeUnit) {
        if (executors.containsKey(taskId) &&
                executors.get(taskId) != null &&
                !executors.get(taskId).isShutdown()
        ) {
            executors.get(taskId).shutdownNow();
            executors.remove(taskId);
        }

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        executors.put(taskId, executor);

        switch (type) {
            case PERIOD -> {
                executors.get(taskId).scheduleAtFixedRate(command, initialDelay, period, timeUnit);
                Log.info(String.format("task '%s' will run every %d (ms) after :: ", taskId,
                                timeUnit.toMillis(period)), System.currentTimeMillis() + timeUnit.toMillis(initialDelay));
            }
            case DELAYED -> {
                executors.get(taskId).schedule(() -> {
                    Log.info(String.format("task '%s' started", taskId));
                    command.run();
                    Log.info(String.format("task '%s' finished", taskId));
                    executors.get(taskId).shutdownNow();
                    executors.remove(taskId);
                    Log.info(String.format("task '%s' terminated and removed", taskId));
                }, initialDelay, timeUnit);
                Log.info(String.format("task '%s' will run ", taskId), System.currentTimeMillis() + timeUnit.toMillis(initialDelay));
            }
        }
    }

    public void stop(String taskId) {
        if (executors.containsKey(taskId) &&
                executors.get(taskId) != null &&
                !executors.get(taskId).isShutdown()) {
            executors.get(taskId).shutdownNow();
            executors.remove(taskId);
            Log.info(String.format("task '%s' stopped", taskId));
        }
    }

    public void stopAll() {
        executors.forEach((id, executor) -> {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdownNow();
                Log.info(String.format("task '%s' stopped", id));
            }
        });
        executors.clear();
    }

    public void logAll() {
        executors.forEach((id, executor) -> {
            Log.debug(String.format("""
                            '%s' ::
                               isShutdown :: %s
                               isTerminated :: %s
                            """,
                    id,
                    executor.isShutdown(),
                    executor.isTerminated()
            ));
        });
    }
}
