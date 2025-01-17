package org.tradebot.service;

import org.tradebot.util.Log;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TaskManager {
    private final Log log = new Log();

    protected final Map<String, ScheduledExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Long> periods = new ConcurrentHashMap<>();

    public void scheduleAtFixedRate(String taskId, Runnable task, long delay, long period, TimeUnit timeUnit) {
        cancel(taskId);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        State state = new State();

        executor.scheduleAtFixedRate(() -> {
            if (state.startExecution()) {
                task.run();
                state.complete();
            } else {
                log.debug(String.format("Task '%s' state is '%s', skipping", taskId, state));
            }
        }, delay, period, timeUnit);

        log.info(String.format("Task '%s' scheduled to run every %d %s and start after %d %s", taskId, period, timeUnit, delay, timeUnit),
                System.currentTimeMillis() + timeUnit.toMillis(delay));
        states.put(taskId, state);
        periods.put(taskId, period);
        executors.put(taskId, executor);
    }

    public void schedule(String taskId, Runnable task, long delay, TimeUnit timeUnit) {
        cancel(taskId);

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        State state = new State();

        executor.schedule(() -> {
            if (state.startExecution()) {
                try {
                    log.debug(String.format("Task '%s' started", taskId));
                    task.run();
                    log.debug(String.format("Task '%s' completed", taskId));
                } finally {
                    state.complete();
                    states.remove(taskId);
                    try (ScheduledExecutorService removedExecutor = executors.remove(taskId)) {
                        if (removedExecutor != null && !removedExecutor.isShutdown()) {
                            removedExecutor.shutdown();
                            log.debug(String.format("Task '%s' removed", taskId));
                        }
                    }
                }
            } else {
                log.debug(String.format("Task '%s' state is '%s', skipping", taskId, state));
            }
        }, delay, timeUnit);

        log.info(String.format("Task '%s' scheduled to run once after %d %s", taskId, delay, timeUnit));
        states.put(taskId, state);
        executors.put(taskId, executor);
    }

    public void cancel(String taskId) {
        try (ScheduledExecutorService executor = executors.remove(taskId)) {
            State taskState = states.remove(taskId);
            if (taskState != null) {
                if (taskState.isRunning()) {
                    log.debug(String.format("Task '%s' is running, waiting for completion before cancelling", taskId));
                    if (executor != null && !executor.isShutdown()) {
                        executor.shutdown();
                        log.info(String.format("Task '%s' has been cancelled", taskId));
                    }
                } else {
                    log.debug(String.format("Task '%s' is not running, set cancel flag and shutdown", taskId));
                    taskState.cancel();
                    if (executor != null && !executor.isShutdown()) {
                        executor.shutdownNow();
                        log.info(String.format("Task '%s' has been cancelled", taskId));
                    }
                }
            }
        }
    }

    public void cancelAll() {
        log.info("Cancelling all tasks...");
        executors.forEach((task, _) -> cancel(task));
        log.info("All tasks have been cancelled");
    }

    public long getTaskPeriod(String taskKey) {
        return periods.getOrDefault(taskKey, -1L);
    }

    private static class State {
        private final AtomicBoolean isRunning = new AtomicBoolean(false);
        private final AtomicBoolean isCancelled = new AtomicBoolean(false);

        public boolean startExecution() {
            return !isCancelled.get() && isRunning.compareAndSet(false, true);
        }

        public void complete() {
            isRunning.set(false);
        }

        public void cancel() {
            isCancelled.set(true);
        }

        public boolean isRunning() {
            return isRunning.get();
        }

        @Override
        public String toString() {
            return String.format("""
                            isRunning :: %s
                            isCancelled :: %s
                            """,
                    isRunning.get(),
                    isCancelled.get());
        }
    }

    public void logAll() {
        log.debug("TaskManager executors size: " + executors.size());
        log.debug("TaskManager states size: " + states.size());
        log.debug(String.format("""
                        TaskManager:
                            executors: %s
                            states: %s
                        """, executors, states));

    }
}
