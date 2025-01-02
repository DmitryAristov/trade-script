package org.tradebot.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskManager {

    public enum Type {
        ONCE,
        PERIOD
    }

    protected final Map<String, ScheduledExecutorService> executors = new HashMap<>();

    public void create(String taskId, Runnable command, Type type, long initialDelay, long period, TimeUnit timeUnit) {
        if (!executors.containsKey(taskId)) {
            if (executors.get(taskId) == null || executors.get(taskId).isShutdown()) {
                ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
                executors.put(taskId, executor);
            }
        } else {
            ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
            executors.replace(taskId, executor);
        }

        switch (type) {
            case PERIOD -> executors.get(taskId).scheduleAtFixedRate(command, initialDelay, period, timeUnit);
            case ONCE -> executors.get(taskId).schedule(command, initialDelay, timeUnit);
        }

        Log.info(String.format("task '%s' scheduled", taskId));
    }

    public void stop(String taskId) {
        if (executors.containsKey(taskId)) {
            if (executors.get(taskId) != null && !executors.get(taskId).isShutdown()) {
                executors.get(taskId).shutdownNow();
                executors.remove(taskId);
                Log.info(String.format("task '%s' stopped", taskId));
            }
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
            Log.debug(String.format("executor %s isShutdown: %s", id, executor.isShutdown()));
            Log.debug(String.format("executor %s isTerminated: %s", id, executor.isTerminated()));
        });
    }
}
