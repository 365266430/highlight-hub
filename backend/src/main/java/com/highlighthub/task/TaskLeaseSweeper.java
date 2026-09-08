package com.highlighthub.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskLeaseSweeper {
    private final TaskService taskService;

    public TaskLeaseSweeper(TaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(fixedDelayString = "${highlight-hub.task.lease-sweep-interval-seconds:30}000",
               initialDelayString = "15000")
    public void sweep() {
        taskService.sweepExpiredLeases();
    }
}
