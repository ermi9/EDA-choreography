package com.eda.choreography.infra.host;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.StepAction;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DEMO, not real work: what a hosted service does for a step, so drawn flows can be run and
 * watched without writing services. Its result is the instance's input plus the service and the
 * step, so conditions in a drawn flow can test input fields ({@code #result['amount'] > 1000}).
 *
 * <p>Two input fields steer it: {@code failAt}, a step or service that throws every time, to
 * watch an instance being undone; and {@code delayMs}, a pause per step (up to 5 s), to watch a
 * join wait or time out.
 */
final class DemoAction implements StepAction {

    private static final long LONGEST_DELAY_MS = 5_000;

    private final String service;

    DemoAction(String service) {
        this.service = service;
    }

    @Override
    public Map<String, ?> execute(ChoreographyMessage message) {
        var step = message.step() != null ? message.step() : service;
        var failAt = message.input().get("failAt");
        if (step.equals(failAt) || service.equals(failAt)) {
            throw new IllegalStateException("demo: " + step + " was asked to fail");
        }
        if (message.input().get("delayMs") instanceof Number delay) {
            pause(Math.min(delay.longValue(), LONGEST_DELAY_MS));
        }
        var result = new LinkedHashMap<String, Object>(message.input());
        result.remove("failAt");
        result.remove("delayMs");
        result.put("service", service);
        result.put("step", step);
        return result;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during a demo pause", e);
        }
    }
}
